package com.jusiai.guidedog.ui

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.amap.api.location.AMapLocation
import com.jusiai.guidedog.GuideDogApp
import com.jusiai.guidedog.core.AsrResult
import com.jusiai.guidedog.nav.NavStatus
import com.jusiai.guidedog.nav.Place
import com.jusiai.guidedog.service.GuideService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GuideViewModel(private val app: GuideDogApp) : AndroidViewModel(app) {
    val status = app.guideState.status
    val preview = app.guideState.preview
    val navStatus = app.navState.status
    val settings get() = app.settings

    private var voiceJob: Job? = null

    /**
     * 单按钮「开始」的全流程（面向盲人，纯语音）：
     *   念"请说出目的地" → 录音识别 → 定位 → POI 搜索 → 念"目的地 X，确认请说确认，重设请说重说"
     *   → 用户语音确认 → 启动视觉引导 + 步行导航。
     * 任一步失败/未确认会语音说明并结束（用户可再点一次重来）。最多 3 轮尝试。
     * 调用前需已授予 相机 / 麦克风 / 定位 权限。
     */
    fun startGuided() {
        if (status.value.running) return
        val nav = app.navState
        voiceJob?.cancel()
        voiceJob = viewModelScope.launch {
            nav.capturingVoice.value = true
            nav.update { it.copy(busy = true, navigating = false, error = null, destName = "", phase = "") }
            try {
                var attempt = 0
                while (attempt < MAX_ATTEMPTS) {
                    attempt++
                    val (place, loc) = resolveDestination() ?: continue  // 失败已语音说明
                    when (confirmDestination(place)) {
                        Confirm.YES -> {
                            nav.update { it.copy(busy = false) }
                            app.speaker.speak("开始导航，前往${place.name}")
                            // startNav 会一并拉起视觉引导（摄像头）
                            GuideService.startNav(
                                app, loc.latitude, loc.longitude, place.lat, place.lng, place.name,
                            )
                            return@launch
                        }
                        Confirm.NO -> app.speaker.speakAndWait("好的，重新设置")
                        Confirm.UNCLEAR -> app.speaker.speakAndWait("没听清，请重新说目的地")
                    }
                }
                nav.update { it.copy(busy = false, navigating = false, phase = "", error = "未设置目的地") }
                app.speaker.speak("已取消", flush = true)
            } finally {
                nav.capturingVoice.value = false
                nav.update { it.copy(busy = false) }
            }
        }
    }

    /** 取消正在进行的语音设置流程。 */
    fun cancelGuided() {
        voiceJob?.cancel()
        app.navState.capturingVoice.value = false
        app.navState.update { NavStatus() }
        app.speaker.stop()
    }

    /** 停止：结束导航 + 视觉引导（一个按钮全停）。 */
    fun stop() {
        voiceJob?.cancel()
        app.navState.capturingVoice.value = false
        GuideService.stop(app)
    }

    // ---- 内部：语音子步骤 -----------------------------------------------------

    /** 念提示 → 录音 → 识别 → 定位 → POI。成功返回 (目的地, 起点定位)，失败语音说明并返回 null。 */
    private suspend fun resolveDestination(): Pair<Place, AMapLocation>? {
        val nav = app.navState
        nav.update { it.copy(phase = "请说出目的地", destName = "", error = null) }
        app.speaker.speakAndWait("请说出目的地")

        nav.update { it.copy(phase = "聆听中…") }
        val pcm = app.voiceRecorder.record()
        if (pcm == null) { fail("没有听到声音，请重试"); return null }

        nav.update { it.copy(phase = "识别中…") }
        val query = when (val asr = withContext(Dispatchers.IO) { app.relayClient.asr(pcm, SAMPLE_RATE) }) {
            is AsrResult.Ok -> asr.text
            is AsrResult.Fail -> { fail(asr.reason); return null }
        }

        nav.update { it.copy(phase = "定位中…", destName = query) }
        val loc = app.locationClient.locate()
        if (loc == null) { fail("无法获取当前位置，请检查定位"); return null }

        nav.update { it.copy(phase = "搜索“$query”…") }
        val place = app.destinationResolver.resolve(query, loc.city)
        if (place == null) { fail("没有找到$query"); return null }

        return place to loc
    }

    /** 念出目的地请求确认，录一句解析"确认/重说"。 */
    private suspend fun confirmDestination(place: Place): Confirm {
        app.navState.update { it.copy(phase = "请确认目的地", destName = place.name) }
        app.speaker.speakAndWait("目的地，${place.name}。确认请说确认，重新设置请说重说")
        val pcm = app.voiceRecorder.record() ?: return Confirm.UNCLEAR
        val text = when (val asr = withContext(Dispatchers.IO) { app.relayClient.asr(pcm, SAMPLE_RATE) }) {
            is AsrResult.Ok -> asr.text
            is AsrResult.Fail -> return Confirm.UNCLEAR
        }
        return parseConfirm(text)
    }

    private suspend fun fail(reason: String) {
        app.navState.update { it.copy(phase = "", error = reason) }
        app.speaker.speakAndWait(reason)
    }

    private fun parseConfirm(text: String): Confirm {
        val s = text.replace(" ", "")
        // 先判否定：如"不对"含"对"，必须先匹配否定词
        if (NEGATIVES.any { s.contains(it) }) return Confirm.NO
        if (AFFIRMATIVES.any { s.contains(it) }) return Confirm.YES
        return Confirm.UNCLEAR
    }

    private enum class Confirm { YES, NO, UNCLEAR }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_ATTEMPTS = 3
        val AFFIRMATIVES = listOf("确认", "对", "是", "好", "没错", "可以", "正确", "开始", "嗯", "要")
        val NEGATIVES = listOf("重说", "重新", "取消", "不", "错", "换", "再说")
    }

    class Factory(private val app: GuideDogApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = GuideViewModel(app) as T
    }
}
