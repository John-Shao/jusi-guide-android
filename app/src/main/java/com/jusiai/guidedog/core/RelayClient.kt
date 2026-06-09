package com.jusiai.guidedog.core

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Parsed from the relay's streamed response header (audio arrives via the on-pcm callback). */
data class GuideResult(
    val text: String,
    val lang: String,
    val repeat: Boolean,
    val sampleRate: Int,
    val vlmMs: Int,
)

/** 云端语音识别结果。Fail.reason 可直接播报给用户。 */
sealed interface AsrResult {
    data class Ok(val text: String) : AsrResult
    data class Fail(val reason: String) : AsrResult
}

/**
 * Posts one JPEG frame to the relay and reads its streamed reply: a one-line JSON
 * header `{text,lang,repeat,sample_rate,vlm_ms}\n` then raw s16le PCM. Mirrors the
 * device firmware's relay_client.cc. The device carries only its bearer token —
 * the Ark/Doubao secrets stay on the relay.
 *
 * Blocking; call from a background dispatcher. Keep-alive is on by default, so the
 * TCP+TLS connection is reused across cycles.
 */
class RelayClient(private val settings: Settings) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * @param onHeader fires once (before any audio) with the parsed result —
     *        open the speaker here.
     * @param onPcm fires for each PCM chunk (data, len) as it streams in.
     * @return the GuideResult, or null on any error.
     */
    fun guide(
        jpeg: ByteArray,
        onHeader: (GuideResult) -> Unit,
        onPcm: (ByteArray, Int) -> Unit,
    ): GuideResult? {
        val base = settings.relayUrl.trim().trimEnd('/')
        val token = settings.deviceToken.trim()
        if (base.isEmpty() || token.isEmpty()) {
            Log.e(TAG, "relay_url/device_token not set")
            return null
        }
        val audio = if (settings.wantAudio) "1" else "0"
        val url = "$base/v1/guide?lang=${settings.lang}&audio=$audio"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "relay HTTP ${resp.code}")
                    return null
                }
                val source = resp.body?.source() ?: run { Log.w(TAG, "empty body"); return null }

                val headerLine = source.readUtf8Line()
                if (headerLine == null) { Log.w(TAG, "missing stream header"); return null }
                val result = parseHeader(headerLine) ?: return null
                onHeader(result)

                // Remaining bytes are raw PCM (none on repeat / no-audio).
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n == -1) break
                    if (n > 0) onPcm(buf, n)
                }
                return result
            }
        } catch (e: Exception) {
            Log.w(TAG, "relay request failed: ${e.message}")
            return null
        }
    }

    /**
     * 上传一段录音做云端语音识别（目的地）。把 s16le 单声道 PCM 包成 WAV，POST 到 relay 的
     * /v1/asr，relay 用豆包/火山 ASR 转文字返回 `{"text":"..."}`（失败可带 `{"error":"..."}`）。
     * 阻塞，调用方放到 IO 线程。识别密钥只在 relay，App 不持有。
     */
    fun asr(pcm: ByteArray, sampleRate: Int): AsrResult {
        val base = settings.relayUrl.trim().trimEnd('/')
        val token = settings.deviceToken.trim()
        if (base.isEmpty() || token.isEmpty()) return AsrResult.Fail("中转地址或 token 未设置")

        val wav = pcmToWav(pcm, sampleRate)
        val url = "$base/v1/asr?lang=${settings.lang}"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "asr HTTP ${resp.code}")
                    return AsrResult.Fail("识别服务错误（${resp.code}）")
                }
                val body = resp.body?.string() ?: return AsrResult.Fail("识别无响应")
                val j = JSONObject(body)
                val text = j.optString("text", "").trim()
                if (text.isEmpty()) {
                    val err = j.optString("error", "")
                    AsrResult.Fail(if (err.isNotEmpty()) err else "没有听清，请重试")
                } else {
                    AsrResult.Ok(text)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "asr request failed: ${e.message}")
            AsrResult.Fail("网络异常，请重试")
        }
    }

    /** 给原始 PCM 加 44 字节 WAV 头（16-bit 单声道）。 */
    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val dataLen = pcm.size
        val header = ByteArray(44)
        fun wInt(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
            header[off + 2] = ((v shr 16) and 0xff).toByte()
            header[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun wShort(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
        }
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        wInt(4, 36 + dataLen)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(header, 12)
        wInt(16, 16)            // PCM fmt chunk size
        wShort(20, 1)           // audio format = PCM
        wShort(22, channels)
        wInt(24, sampleRate)
        wInt(28, byteRate)
        wShort(32, channels * bits / 8)  // block align
        wShort(34, bits)
        "data".toByteArray(Charsets.US_ASCII).copyInto(header, 36)
        wInt(40, dataLen)
        return header + pcm
    }

    private fun parseHeader(line: String): GuideResult? = try {
        val j = JSONObject(line)
        val text = j.optString("text", "")
        if (text.isEmpty()) {
            Log.w(TAG, "stream header has empty text")
            null
        } else {
            GuideResult(
                text = text,
                lang = j.optString("lang", "zh"),
                repeat = j.optBoolean("repeat", false),
                sampleRate = j.optInt("sample_rate", 24000),
                vlmMs = j.optInt("vlm_ms", -1),
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "header parse error: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "guide.relay"
    }
}
