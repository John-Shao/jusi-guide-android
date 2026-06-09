package com.jusiai.guidedog.ui

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

    /** 设好但还没开始导航的目的地（在「开始」前用语音设定，点「开始」时才真正起导航）。 */
    private var pendingDest: Place? = null
    private var voiceJob: Job? = null

    /** 「开始」：启动视觉引导；若已用语音设好目的地，则一并开始步行导航。 */
    fun start() {
        GuideService.start(app)
        val dest = pendingDest ?: return
        pendingDest = null
        viewModelScope.launch {
            val loc = app.locationClient.locate()
            if (loc == null) {
                app.navState.update { it.copy(navigating = false, phase = "", error = "定位失败") }
                app.speaker.speak("无法获取当前位置，导航未开始", flush = true)
                return@launch
            }
            GuideService.startNav(app, loc.latitude, loc.longitude, dest.lat, dest.lng, dest.name)
        }
    }

    fun stop() {
        pendingDest = null
        GuideService.stop(app)
    }

    fun stopNav() {
        voiceJob?.cancel()
        pendingDest = null
        app.navState.capturingVoice.value = false
        app.navState.update { NavStatus() }
        GuideService.stopNav(app)
    }

    /**
     * 语音设目的地：念提示 → 开麦识别 → 定位 → POI 搜索 → 确认。
     * - 若服务尚未运行（推荐：在「开始」之前设目的地）：仅"设定"目的地，点「开始」时再起导航——
     *   这样设目的地全程没有视觉播报/开麦冲突。
     * - 若服务已在运行：边设边起导航；期间用 capturingVoice 抑制视觉播报避免互相干扰。
     * 调用前需已授予 RECORD_AUDIO 与定位权限。
     */
    fun setDestinationByVoice() {
        val nav = app.navState
        voiceJob?.cancel()
        voiceJob = viewModelScope.launch {
            nav.capturingVoice.value = true
            try {
                nav.update { it.copy(navigating = false, phase = "听取目的地中…", error = null, destName = "") }
                app.speaker.speakAndWait("请说出目的地")

                nav.update { it.copy(phase = "聆听中…") }
                val pcm = app.voiceRecorder.record()
                if (pcm == null) {
                    nav.update { it.copy(phase = "", error = "没有听到声音") }
                    app.speaker.speak("没有听到声音，请重试", flush = true)
                    return@launch
                }

                nav.update { it.copy(phase = "识别中…") }
                val query = when (val asr = withContext(Dispatchers.IO) { app.relayClient.asr(pcm, 16000) }) {
                    is AsrResult.Ok -> asr.text
                    is AsrResult.Fail -> {
                        nav.update { it.copy(phase = "", error = asr.reason) }
                        app.speaker.speak(asr.reason, flush = true)
                        return@launch
                    }
                }

                nav.update { it.copy(phase = "定位中…", destName = query) }
                val loc = app.locationClient.locate()
                if (loc == null) {
                    nav.update { it.copy(phase = "", error = "定位失败") }
                    app.speaker.speak("无法获取当前位置，请检查定位", flush = true)
                    return@launch
                }

                nav.update { it.copy(phase = "搜索“$query”…") }
                val place = app.destinationResolver.resolve(query, loc.city)
                if (place == null) {
                    nav.update { it.copy(phase = "", error = "未找到 $query") }
                    app.speaker.speak("没有找到$query，请重试", flush = true)
                    return@launch
                }

                if (status.value.running) {
                    // 已在运行：直接起导航（用刚拿到的定位作起点）
                    app.speaker.speakAndWait("目的地，${place.name}，开始步行导航")
                    GuideService.startNav(app, loc.latitude, loc.longitude, place.lat, place.lng, place.name)
                } else {
                    // 推荐路径：先设好，等用户点「开始」
                    pendingDest = place
                    nav.update { it.copy(phase = "已设目的地", destName = place.name) }
                    app.speaker.speakAndWait("目的地已设为${place.name}，请点击开始")
                }
            } finally {
                nav.capturingVoice.value = false
            }
        }
    }

    class Factory(private val app: GuideDogApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = GuideViewModel(app) as T
    }
}
