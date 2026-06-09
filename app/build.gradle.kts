import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 高德 Key 从 local.properties 读取（不入库）。
// AMAP_KEY      —— Android 平台 Key，用于导航/定位（注入 manifest meta-data）。
// AMAP_WEB_KEY  —— Web 服务 Key，用于 POI 关键词搜索 REST（注入 BuildConfig）。
private val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val amapKey: String = localProps.getProperty("AMAP_KEY", "")
val amapWebKey: String = localProps.getProperty("AMAP_WEB_KEY", "")

android {
    namespace = "com.jusiai.guidedog"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jusiai.guidedog"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        manifestPlaceholders["AMAP_KEY"] = amapKey
        buildConfigField("String", "AMAP_WEB_KEY", "\"$amapWebKey\"")
        // 高德带原生 .so，只保留主流手机 ABI，避免 APK 过大 / x86 兼容问题。
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)

    implementation(libs.androidx.security.crypto)

    implementation(libs.amap.navi)
    // POI 关键词搜索改用高德 Web 服务 REST（OkHttp），不引 com.amap.api:search，
    // 避免它与 navi-3dmap 的 com.amap.apis.utils.core 重复类冲突。
}
