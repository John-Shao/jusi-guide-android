package com.jusiai.guidedog.nav

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviListener
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng

/**
 * 高德步行导航的无界面封装：持有 AMapNavi 单例、实现 AMapNaviListener，把转向播报文本
 * （onGetNavigationText）转给系统 TTS（[Speaker]），把诱导信息（onNaviInfoUpdate）写进 [NavState]。
 *
 * 不开启高德内置语音：导航 SDK 默认只回调播报文本、不出声，正好让我们用系统 TTS 播报，
 * 与视障用户的现有语音架构统一，也方便和视觉引导音频做"双语音协调"（见 Speaker.speaking）。
 *
 * 注意：AMapNaviListener 方法较多且各版本有增删，未用到的均空实现；若编译报"未实现/多余覆盖"，
 * 以实际 SDK 版本接口为准增删即可（Android Studio "Implement members"）。
 * 接口里有若干已废弃回调（不同版本不一），统一用类级 @Suppress 关掉"覆盖废弃成员"的告警。
 */
@Suppress("OVERRIDE_DEPRECATION")
class Navigator(
    context: Context,
    private val navState: NavState,
    private val speaker: Speaker,
) : AMapNaviListener {

    private val appCtx = context.applicationContext
    private var navi: AMapNavi? = null

    private var destName: String = ""
    @Volatile private var simulate = false
    @Volatile private var calcPending = false   // 路线规划进行中（去重成功/失败的重复回调）
    @Volatile private var running = false        // 导航进行中

    // onGetNavigationText 在部分版本会同时触发单参/双参两个回调，去重避免双声。
    @Volatile private var lastText = ""
    @Volatile private var lastTextAt = 0L

    private fun ensureNavi(): AMapNavi? {
        if (navi == null) {
            navi = try {
                AMapNavi.getInstance(appCtx)
            } catch (e: Exception) {
                Log.e(TAG, "AMapNavi.getInstance failed: ${e.message}")
                null
            }
            navi?.addAMapNaviListener(this)
        }
        return navi
    }

    /** 发起步行导航：算路成功后自动 startNavi。simulate=true 走模拟导航（室内验证用）。 */
    fun startWalk(start: NaviLatLng, end: NaviLatLng, name: String, simulate: Boolean) {
        destName = name
        this.simulate = simulate
        navState.update {
            it.copy(
                navigating = true, phase = "规划路线中…", destName = name, error = null,
                remainingDist = -1, remainingTime = -1, nextRoad = "", nextTurnDist = -1,
            )
        }
        calcPending = true
        val n = ensureNavi()
        if (n == null) { calcPending = false; fail("导航初始化失败"); return }
        val ok = try { n.calculateWalkRoute(start, end) } catch (e: Exception) {
            Log.e(TAG, "calculateWalkRoute failed: ${e.message}"); false
        }
        if (!ok) { calcPending = false; fail("无法发起路线规划") }
    }

    /** 停止导航并清空状态；服务继续运行（视觉引导不受影响）。 */
    fun stop() {
        running = false
        calcPending = false
        try { navi?.stopNavi() } catch (_: Exception) {}
        speaker.stop()
        navState.update { NavStatus() }
    }

    /** 进程退出时彻底释放。 */
    fun destroy() {
        stop()
        try { navi?.removeAMapNaviListener(this) } catch (_: Exception) {}
        try { AMapNavi.destroy() } catch (_: Exception) {}
        navi = null
    }

    private fun beginNavi() {
        if (!calcPending) return
        calcPending = false
        val n = navi ?: return
        running = true
        navState.update { it.copy(navigating = true, phase = "导航中") }
        try {
            n.startNavi(if (simulate) NaviType.EMULATOR else NaviType.GPS)
        } catch (e: Exception) {
            Log.e(TAG, "startNavi failed: ${e.message}")
        }
        speaker.speak("开始步行导航，目的地$destName")
    }

    private fun fail(msg: String) {
        navState.update { it.copy(navigating = false, phase = "", error = msg) }
        speaker.speak(msg, flush = true)
    }

    private fun speakNav(text: String?) {
        if (text.isNullOrBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (text == lastText && now - lastTextAt < 4000) return  // 去重双回调
        lastText = text
        lastTextAt = now
        speaker.speak(text)
    }

    // ---- AMapNaviListener：用到的回调 -----------------------------------------
    override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) = beginNavi()
    override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
        if (!calcPending) return
        calcPending = false
        fail("路线规划失败")
    }

    override fun onGetNavigationText(text: String?) = speakNav(text)

    override fun onNaviInfoUpdate(info: NaviInfo?) {
        if (info == null) return
        navState.update {
            it.copy(
                navigating = true,
                phase = "导航中",
                remainingDist = info.pathRetainDistance,
                remainingTime = info.pathRetainTime,
                nextRoad = info.nextRoadName ?: "",
                nextTurnDist = info.curStepRetainDistance,
            )
        }
    }

    override fun onArriveDestination() {
        running = false
        speaker.speak("已到达目的地", flush = true)
        navState.update { it.copy(navigating = false, phase = "已到达", remainingDist = 0, nextTurnDist = -1) }
    }

    override fun onInitNaviFailure() {
        if (calcPending) { calcPending = false; fail("导航初始化失败") }
    }

    override fun onEndEmulatorNavi() {
        running = false
        navState.update { it.copy(navigating = false, phase = "模拟导航结束") }
    }

    // ---- AMapNaviListener：旧版/未使用回调（空实现） --------------------------
    override fun onInitNaviSuccess() {}
    override fun onStartNavi(type: Int) {}
    override fun onTrafficStatusUpdate() {}
    override fun onLocationChange(location: AMapNaviLocation?) {}
    // 单参/双参文本回调随版本会触发其一或都触发，两路都转 speakNav（内部 4 秒去重）。
    override fun onGetNavigationText(type: Int, text: String?) = speakNav(text)
    override fun onCalculateRouteSuccess(ints: IntArray?) = beginNavi()
    override fun onCalculateRouteFailure(errorInfo: Int) {
        if (calcPending) { calcPending = false; fail("路线规划失败") }
    }
    override fun onReCalculateRouteForYaw() {}
    override fun onReCalculateRouteForTrafficJam() {}
    override fun onArrivedWayPoint(wayID: Int) {}
    override fun onGpsOpenStatus(enabled: Boolean) {}
    override fun updateCameraInfo(cameraInfos: Array<out com.amap.api.navi.model.AMapNaviCameraInfo>?) {}
    override fun updateIntervalCameraInfo(
        camera1: com.amap.api.navi.model.AMapNaviCameraInfo?,
        camera2: com.amap.api.navi.model.AMapNaviCameraInfo?,
        x: Int,
    ) {}
    override fun onServiceAreaUpdate(serviceAreaInfos: Array<out com.amap.api.navi.model.AMapServiceAreaInfo>?) {}
    override fun showCross(cross: com.amap.api.navi.model.AMapNaviCross?) {}
    override fun hideCross() {}
    override fun showModeCross(cross: com.amap.api.navi.model.AMapModelCross?) {}
    override fun hideModeCross() {}
    override fun showLaneInfo(
        laneInfos: Array<out com.amap.api.navi.model.AMapLaneInfo>?,
        laneBackgroundInfo: ByteArray?,
        laneRecommendedInfo: ByteArray?,
    ) {}
    override fun showLaneInfo(laneInfo: com.amap.api.navi.model.AMapLaneInfo?) {}
    override fun hideLaneInfo() {}
    override fun notifyParallelRoad(road: Int) {}
    override fun OnUpdateTrafficFacility(infos: Array<out com.amap.api.navi.model.AMapNaviTrafficFacilityInfo>?) {}
    override fun OnUpdateTrafficFacility(info: com.amap.api.navi.model.AMapNaviTrafficFacilityInfo?) {}
    override fun updateAimlessModeStatistics(stat: com.amap.api.navi.model.AimLessModeStat?) {}
    override fun updateAimlessModeCongestionInfo(info: com.amap.api.navi.model.AimLessModeCongestionInfo?) {}
    override fun onPlayRing(type: Int) {}
    override fun onNaviRouteNotify(data: com.amap.api.navi.model.AMapNaviRouteNotifyData?) {}
    override fun onGpsSignalWeak(weak: Boolean) {}

    private companion object { const val TAG = "guide.navi" }
}
