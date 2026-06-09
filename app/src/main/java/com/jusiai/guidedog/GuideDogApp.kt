package com.jusiai.guidedog

import android.app.Application
import android.util.Log
import com.amap.api.location.AMapLocationClient
import com.amap.api.navi.NaviSetting
import com.jusiai.guidedog.core.AudioPlayer
import com.jusiai.guidedog.core.RelayClient
import com.jusiai.guidedog.core.Settings
import com.jusiai.guidedog.nav.DestinationResolver
import com.jusiai.guidedog.nav.LocationClient
import com.jusiai.guidedog.nav.NavState
import com.jusiai.guidedog.nav.Navigator
import com.jusiai.guidedog.nav.Speaker
import com.jusiai.guidedog.nav.VoiceRecorder

/**
 * Application = hand-rolled service locator (matches the we-meet convention). Owns
 * the singletons: settings, the relay client, the audio player, and the shared
 * GuideState that the service and UI both observe.
 *
 * 高德步行导航的相关单例（navigator/speaker/定位/搜索/语音输入）也挂这里。所有高德对象都用
 * lazy 延迟创建，确保在 [agreeAmapPrivacy] 完成隐私合规之后才会被实例化。
 */
class GuideDogApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var relayClient: RelayClient
        private set

    val audioPlayer = AudioPlayer()
    val guideState = GuideState()

    // ---- 导航相关 ----
    val navState = NavState()
    // speaker 在 onCreate（主线程）初始化：TextToSpeech 须在主线程创建，回调才可靠；
    // 且感知循环会在 IO 线程读 speaker.speaking 做音频协调，不能由那次访问触发懒创建。
    lateinit var speaker: Speaker
        private set
    val navigator by lazy { Navigator(this, navState, speaker) }
    val locationClient by lazy { LocationClient(this) }
    val destinationResolver by lazy { DestinationResolver() }
    val voiceRecorder by lazy { VoiceRecorder(this) }

    override fun onCreate() {
        super.onCreate()
        agreeAmapPrivacy()   // 必须在任何高德对象创建之前
        settings = Settings(this)
        relayClient = RelayClient(settings)
        speaker = Speaker(this)
    }

    /**
     * 高德 SDK 隐私合规：navi 8.1.0+ 起，必须在调用任何 SDK 接口前声明"已弹隐私政策、用户已同意"，
     * 否则导航/定位都会失效。导航与定位各有一组静态方法，全部声明一遍（幂等）。
     * 真实产品里 isAgree 应来自用户实际勾选的隐私弹窗结果。（POI 搜索走 Web REST，无需此合规。）
     */
    private fun agreeAmapPrivacy() {
        try {
            NaviSetting.updatePrivacyShow(this, true, true)
            NaviSetting.updatePrivacyAgree(this, true)
            AMapLocationClient.updatePrivacyShow(this, true, true)
            AMapLocationClient.updatePrivacyAgree(this, true)
        } catch (e: Throwable) {
            Log.e("guide.amap", "updatePrivacy failed: ${e.message}")
        }
    }
}
