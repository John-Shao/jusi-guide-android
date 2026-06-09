package com.jusiai.guidedog.nav

import android.content.Context
import android.util.Log
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 高德单次高精度定位：给步行路线起点 + POI 搜索的城市范围用。
 * 隐私合规（AMapLocationClient.updatePrivacyShow/Agree）在 GuideDogApp.onCreate 已调用。
 */
class LocationClient(private val context: Context) {

    /** 取一次当前位置；失败返回 null。需已授予定位权限。 */
    suspend fun locate(): AMapLocation? = suspendCancellableCoroutine { cont ->
        val client = try {
            AMapLocationClient(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "create AMapLocationClient failed: ${e.message}")
            if (cont.isActive) cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = true
            isNeedAddress = true   // 需要 city 用于 POI 搜索限定
            httpTimeOut = 8000
        }
        client.setLocationOption(option)
        client.setLocationListener { loc ->
            val result = if (loc != null && loc.errorCode == 0) loc else {
                Log.w(TAG, "locate error: code=${loc?.errorCode} ${loc?.errorInfo}")
                null
            }
            try { client.stopLocation() } catch (_: Exception) {}
            try { client.onDestroy() } catch (_: Exception) {}
            if (cont.isActive) cont.resume(result)
        }
        cont.invokeOnCancellation {
            try { client.stopLocation() } catch (_: Exception) {}
            try { client.onDestroy() } catch (_: Exception) {}
        }
        client.startLocation()
    }

    private companion object { const val TAG = "guide.loc" }
}
