package com.jusiai.guidedog.nav

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * 系统 TextToSpeech 的封装，转向播报与语音提示共用一个引擎。
 *
 * [speaking] 暴露"当前是否在播报导航/提示语音"。视觉引导音频（relay PCM → AudioPlayer）连续，
 * 导航播报稀疏但更重要，[com.jusiai.guidedog.service.GuideService] 的感知循环会在 speaking==true 时
 * 抑制视觉音频写入，让导航播报压过环境描述。
 *
 * 只用一个 UtteranceProgressListener：用 [speaking] 计数维护播报状态，用 [awaiters] 表让
 * [speakAndWait] 能等某一句念完（"请说出目的地"提示念完再开麦，避免回声）。
 * TTS 初始化异步，就绪前的 speak 会缓存补播。
 */
class Speaker(context: Context) {

    val speaking = MutableStateFlow(false)

    private var ready = false
    private val pending = ArrayList<Utt>()
    private val active = AtomicInteger(0)
    private val seq = AtomicInteger(0)
    private val awaiters = ConcurrentHashMap<String, () -> Unit>()

    private class Utt(val id: String, val text: String, val flush: Boolean)

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setLanguage(Locale.CHINESE)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    active.incrementAndGet()
                    speaking.value = true
                }
                override fun onDone(utteranceId: String?) = done(utteranceId)
                @Deprecated("deprecated in API level 21")
                override fun onError(utteranceId: String?) = done(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = done(utteranceId)
            })
            synchronized(pending) {
                pending.forEach { speakNow(it) }
                pending.clear()
            }
        } else {
            Log.w(TAG, "TextToSpeech init failed: $status")
        }
    }

    private fun done(id: String?) {
        if (id != null) awaiters.remove(id)?.invoke()
        if (active.decrementAndGet() <= 0) speaking.value = false
    }

    /** 即时播报。导航转向用默认 add 排队，不打断已有句子；提示语用 flush。 */
    fun speak(text: String, flush: Boolean = false): String? {
        if (text.isBlank()) return null
        val utt = Utt("u-${seq.incrementAndGet()}", text, flush)
        synchronized(pending) {
            if (!ready) { pending.add(utt); return utt.id }
        }
        speakNow(utt)
        return utt.id
    }

    /** 播报并等待这句念完。 */
    suspend fun speakAndWait(text: String) {
        if (text.isBlank()) return
        suspendCancellableCoroutine { cont ->
            val id = speak(text, flush = true)
            if (id == null) { if (cont.isActive) cont.resume(Unit); return@suspendCancellableCoroutine }
            awaiters[id] = { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { awaiters.remove(id) }
        }
    }

    private fun speakNow(utt: Utt) {
        val mode = if (utt.flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(utt.text, mode, Bundle(), utt.id)
    }

    fun stop() {
        try { tts.stop() } catch (_: Exception) {}
        synchronized(pending) { pending.clear() }
        // 唤醒所有等待者，避免协程悬挂
        awaiters.keys.toList().forEach { awaiters.remove(it)?.invoke() }
        active.set(0)
        speaking.value = false
    }

    fun shutdown() {
        stop()
        try { tts.shutdown() } catch (_: Exception) {}
    }

    private companion object { const val TAG = "guide.tts" }
}
