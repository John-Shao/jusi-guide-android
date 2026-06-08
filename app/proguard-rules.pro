# Minification is disabled (see build.gradle.kts), so these are belt-and-braces.
# OkHttp ships its own consumer rules; keep its optional platform classes quiet.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
