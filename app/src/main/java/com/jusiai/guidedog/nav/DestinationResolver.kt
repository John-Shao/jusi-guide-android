package com.jusiai.guidedog.nav

import android.util.Log
import com.jusiai.guidedog.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 解析出的目的地。 */
data class Place(
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
)

/**
 * 把用户说出的地名解析成坐标。用高德 Web 服务 REST（place/text 关键词搜索），不依赖原生 search SDK，
 * 从而避开 navi-3dmap 与 search 的 com.amap.apis.utils.core 重复类问题。
 *
 * 需要在 local.properties 配置 AMAP_WEB_KEY（高德"Web服务"类型 Key），经 BuildConfig 注入。
 */
class DestinationResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** 关键词搜索，取第一条结果；未配置 Key / 无结果 / 出错返回 null。 */
    suspend fun resolve(query: String, city: String?): Place? {
        if (query.isBlank()) return null
        val key = BuildConfig.AMAP_WEB_KEY
        if (key.isBlank()) { Log.e(TAG, "AMAP_WEB_KEY 未配置（local.properties）"); return null }

        val url = "https://restapi.amap.com/v3/place/text".toHttpUrl().newBuilder()
            .addQueryParameter("key", key)
            .addQueryParameter("keywords", query)
            .apply { if (!city.isNullOrBlank()) addQueryParameter("city", city) }
            .addQueryParameter("offset", "5")
            .addQueryParameter("page", "1")
            .addQueryParameter("extensions", "base")
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) { Log.w(TAG, "http ${resp.code}"); return@use null }
                    val body = resp.body?.string() ?: return@use null
                    val json = JSONObject(body)
                    if (json.optString("status") != "1") {
                        Log.w(TAG, "amap status=${json.optString("status")} info=${json.optString("info")}")
                        return@use null
                    }
                    val pois = json.optJSONArray("pois")
                    if (pois == null || pois.length() == 0) return@use null
                    val p = pois.getJSONObject(0)
                    val parts = p.optString("location").split(",")  // "lng,lat"
                    if (parts.size != 2) return@use null
                    val lng = parts[0].toDoubleOrNull() ?: return@use null
                    val lat = parts[1].toDoubleOrNull() ?: return@use null
                    Place(
                        name = p.optString("name", query),
                        address = p.optString("address", ""),
                        lat = lat,
                        lng = lng,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "poi rest failed: ${e.message}")
                null
            }
        }
    }

    private companion object { const val TAG = "guide.poi" }
}
