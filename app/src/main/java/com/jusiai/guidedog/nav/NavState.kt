package com.jusiai.guidedog.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 步行导航的可观察状态（导航器写，UI 读）。语音播报本身走系统 TTS，不放这里；
 * 这里只放屏幕上要显示的摘要，方便明眼陪同者/调试查看。
 */
data class NavStatus(
    val navigating: Boolean = false,
    val busy: Boolean = false,         // 语音设目的地流程进行中（点开始后到进入导航前）
    val phase: String = "",            // "听取目的地中…" / "规划路线中…" / "导航中" 等可读阶段
    val destName: String = "",         // 目的地名称
    val remainingDist: Int = -1,       // 到目的地剩余距离（米）
    val remainingTime: Int = -1,       // 到目的地剩余时间（秒）
    val nextRoad: String = "",         // 下一段路名
    val nextTurnDist: Int = -1,        // 距下一个转向的距离（米）
    val error: String? = null,
)

/** 导航的共享状态容器，仿 [com.jusiai.guidedog.GuideState]。 */
class NavState {
    val status = MutableStateFlow(NavStatus())
    /** 正在用语音设目的地（开麦+提示）。为 true 时感知循环抑制视觉播报，避免干扰语音输入。 */
    val capturingVoice = MutableStateFlow(false)
    fun update(block: (NavStatus) -> NavStatus) = status.update(block)
}
