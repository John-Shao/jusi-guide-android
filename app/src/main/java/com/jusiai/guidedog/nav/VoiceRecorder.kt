package com.jusiai.guidedog.nav

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * 录一段语音用于云端识别（目的地）。16kHz 单声道 s16le，带简单能量 VAD：
 * 检测到说话后开始，尾部静音约 1.2s 自动停止；若一直没说话则在 ~5s 超时。返回原始 PCM，
 * 由 [com.jusiai.guidedog.core.RelayClient.asr] 包成 WAV 上传。需 RECORD_AUDIO 权限。
 */
class VoiceRecorder(private val context: Context) {

    /** 录一句；没录到有效语音返回 null。调用前应先念完提示语并抑制视觉播报。 */
    @SuppressLint("MissingPermission") // 调用方在 UI 已校验 RECORD_AUDIO
    suspend fun record(): ByteArray? = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) { Log.e(TAG, "getMinBufferSize=$minBuf"); return@withContext null }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, maxOf(minBuf, SAMPLE_RATE),
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord create failed: ${e.message}"); return@withContext null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized"); recorder.release(); return@withContext null
        }

        val out = ByteArrayOutputStream()
        val frame = ShortArray(FRAME_SAMPLES)
        var speechStarted = false
        var elapsedMs = 0
        var silenceMs = 0
        var peakRms = 0.0
        try {
            recorder.startRecording()
            while (elapsedMs < MAX_TOTAL_MS) {
                val n = recorder.read(frame, 0, frame.size)
                if (n <= 0) break
                var sum = 0.0
                for (i in 0 until n) { val s = frame[i].toDouble(); sum += s * s }
                val rms = sqrt(sum / n)
                if (rms > peakRms) peakRms = rms
                val isSpeech = rms > SPEECH_RMS

                if (speechStarted || isSpeech) {
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        val v = frame[i].toInt()
                        bytes[i * 2] = (v and 0xff).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
                    }
                    out.write(bytes)
                }
                if (isSpeech) {
                    speechStarted = true
                    silenceMs = 0
                } else if (speechStarted) {
                    silenceMs += FRAME_MS
                    if (silenceMs >= TRAILING_SILENCE_MS) break
                }
                elapsedMs += FRAME_MS
                if (!speechStarted && elapsedMs >= PRE_SPEECH_TIMEOUT_MS) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "record failed: ${e.message}")
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }

        val pcm = out.toByteArray()
        Log.i(TAG, "recorded ${pcm.size} bytes, speechStarted=$speechStarted, peakRms=${peakRms.toInt()}")
        // 需要至少 ~0.3s 的语音才算有效
        if (!speechStarted || pcm.size < SAMPLE_RATE * 2 * 3 / 10) null else pcm
    }

    private companion object {
        const val TAG = "guide.rec"
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS  // 320
        const val MAX_TOTAL_MS = 9000
        const val PRE_SPEECH_TIMEOUT_MS = 5000
        const val TRAILING_SILENCE_MS = 1200
        const val SPEECH_RMS = 700.0   // s16 能量阈值，按现场可微调
    }
}
