# Minification is disabled (see build.gradle.kts), so these are belt-and-braces.
# OkHttp ships its own consumer rules; keep its optional platform classes quiet.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 高德 SDK（导航/定位/搜索）依赖 JNI 与反射，必须 keep。
-keep class com.amap.api.** { *; }
-keep class com.amap.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-dontwarn com.amap.**
-dontwarn com.autonavi.**
