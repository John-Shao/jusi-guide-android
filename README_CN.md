# jusi-guide-android（导盲犬 · Android 客户端）

面向盲人的"导盲犬" Android 客户端,是 RV1126B 设备端 [jusi-guide-dog](../jusi-guide-dog) 的
手机版,对接**同一个中转服务** [jusi-guide-server](../jusi-guide-server)(`https://login.jusiai.com`)。
每秒抓一帧 → 帧差(静止跳过)→ 缩放转 JPEG → 流式 POST 中转 → 收"指导文字 + 语音 PCM" →
`AudioTrack` 边收边播。**云端密钥不在端上**,设备只持有一个可吊销的按设备 token。

```
CameraX 抓帧 ─▶ 帧差/缩放(JPEG) ─▶ 中转(HTTPS,Bearer token) ─▶ 豆包 VLM ─▶ 云端 TTS
                                                                              │
              扬声器 ◀── AudioTrack(24kHz s16) ◀── 流式 PCM ◀── JSON 头 + PCM ─┘
```

## 与设备端一致的设计

- **前台服务** `GuideService` 持有 CameraX `ImageAnalysis`(后摄),驱动感知循环,熄屏也跑。
- **帧差**:复用 CameraX 帧的 Y 平面算 16×16 块均 MAD,跟设备端 `frame_change.cc` 同套逻辑;
  静止跳过,`frame_diff_force_ms` 后强发一次。
- **双线程效果**:`AudioTrack(MODE_STREAM)` 约 3s 缓冲 = 后台播放,写入不阻塞循环,
  上一句的尾音在播,下一帧的 VLM 已在跑(等于设备端的后台播放线程)。
- **预览免费**:分析流的最新帧转 Bitmap 经 StateFlow 推给 UI,**单一相机占用者**。
- 相同指导的去重、复读安全阀都在**中转端**,客户端无需处理。

## 技术栈(对齐 we-meet-android)

Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.10.2 · JDK 17 · compileSdk 34 / minSdk 29 ·
Compose + Material3(单 Activity)· 协程 + Flow · CameraX 1.4 · OkHttp 4.12(流式) ·
EncryptedSharedPreferences 存 token。

## 构建运行

> 本工程未附带 `gradlew` 的二进制 wrapper jar。**用 Android Studio 打开**(会自动配好 Gradle),
> 或先在命令行跑一次 `gradle wrapper --gradle-version 8.10.2` 生成 wrapper,再 `./gradlew assembleDebug`。

1. Android Studio(Koala/Ladybug 或更新,自带 JDK 17)打开本目录,等待 Gradle Sync。
2. 连真机(建议真机,模拟器无后摄/朝向问题),Run。
3. 首次启动授予**相机**(必需)与**通知**(API 33+,用于前台服务)权限,点「开始」。

## 配置

应用内默认已填好可直接连开发中转:

| 项 | 默认 |
|---|---|
| `relay_url` | `https://login.jusiai.com` |
| `device_token` | `t_androiddev_K9pQ3zVx7mNbR4tLy0sWcf2`(开发用) |
| 语言 | 中文(zh) |

在右上角**设置**里可改 `relay_url` / `device_token` / 语言 / 是否语音(存 EncryptedSharedPreferences)。

> ⚠️ **要让默认 token 生效**,请在中转 `.env` 的 `DEVICE_TOKENS` 里加上这一台,然后重新部署:
> ```
> DEVICE_TOKENS=...,t_androiddev_K9pQ3zVx7mNbR4tLy0sWcf2:guide-android-01
> ```
> 生产环境给每台设备**单独签**一个 token(别和板子共用——中转的去重按 token 区分)。

## 目录结构

```
app/src/main/java/com/jusiai/guidedog/
  GuideDogApp.kt          Application = service locator(settings / relayClient / audioPlayer / guideState)
  GuideState.kt           共享可观测状态(服务写、UI 读)
  MainActivity.kt         单 Activity + Compose
  core/
    Settings.kt           EncryptedSharedPreferences 配置
    RelayClient.kt        OkHttp 流式 POST → onHeader/onPcm 回调
    AudioPlayer.kt        AudioTrack(MODE_STREAM)播 24kHz s16 PCM
    FrameDiff.kt          16×16 块均亮度 MAD,跳过静止帧
    Yuv.kt                YUV_420_888 → NV21 → 旋转/缩放 JPEG
  service/GuideService.kt 前台服务 + CameraX + 感知循环
  ui/                     GuideViewModel / GuideScreen / theme
```

## 说明

- 本仓库在生成它的机器上**不构建/不运行 Android**(无 Gradle/模拟器);代码以 Android Studio 可直接打开为准。
- 中转协议详见 [jusi-guide-server](../jusi-guide-server)、设备端实现见 [jusi-guide-dog](../jusi-guide-dog)。
