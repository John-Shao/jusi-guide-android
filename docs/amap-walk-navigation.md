# 集成高德步行导航 SDK（纯语音、无界面）

## 背景

`jusi-guide-android`（"导盲犬"）是面向视障用户的视觉引导 App：`GuideService` 前台服务里跑
摄像头 → 帧差 → JPEG → relay/VLM → 语音 的感知循环，靠 `AudioPlayer`（s16 PCM，
`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`）播报。原本**没有任何定位/地图/导航代码**。

本特性叠加一个能力：用户**用语音说出目的地**，App 用**高德步行导航**做**纯语音、无地图界面**的
逐步引导，转向播报走 **Android 系统 TTS**。导航与现有视觉避障引导**并行运行**（视觉负责"前方有台阶"，
导航负责"前方路口左转"），让视障用户既能避障又能到达目的地。

需求范围：
- 模式：**步行导航** `calculateWalkRoute`
- 形态：**纯语音无界面**（用 `AMapNavi` 核心 SDK，不引入 `AmapNaviPage`/地图视图）
- 目的地输入：**语音说出**（Android `SpeechRecognizer` → 高德 POI 搜索定位坐标）
- 转向播报引擎：**Android 系统 TTS**（不用高德内置语音，消费 `onGetNavigationText` 回调文本）

## 前置准备（否则跑不通）

需要在高德开放平台同一个应用下创建**两个 Key**（导航走原生 SDK，POI 搜索走 Web REST）：

1. **Android 平台 Key**（导航/定位）：[控制台](https://console.amap.com/dev/key/app) 创建 "Android 平台" Key，
   绑定 `applicationId = com.jusiai.guidedog` + 签名 **SHA1**。
   - debug SHA1：`keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android`
   - 发布另需 release keystore 的 SHA1（同 Key 可配多个 SHA1）。
2. **Web 服务 Key**（POI 关键词搜索 REST）：同一应用下再建一个 "Web 服务" 类型 Key。
3. 两个 Key 写入 `local.properties`（已被 `.gitignore` 忽略，不入库）：
   ```
   AMAP_KEY=你的Android平台Key
   AMAP_WEB_KEY=你的Web服务Key
   ```
   构建时 `AMAP_KEY` 注入 manifest `com.amap.api.v2.apikey`，`AMAP_WEB_KEY` 注入 `BuildConfig`。
4. 导航走真实 GPS（`startNavi(NaviType.GPS)`），真机测试需开启定位/GPS。

> 为什么 POI 用 REST 而非原生 search SDK：`com.amap.api:navi-3dmap`（含地图+导航+定位）与
> `com.amap.api:search` 都内置了 `com.amap.apis.utils.core`，同时引入会重复类编译失败。改用 Web REST
> 既避开冲突、又省一份 .so，且复用已有的 OkHttp。代价是需要一个 Web 服务 Key。

## 数据流

```
用户点"语音设目的地"
  → VoiceRecorder 录音(16k 单声道) → relay /v1/asr 云端识别出文本
  → LocationClient(AMapLocationClient 单次) 取当前定位 + 城市
  → DestinationResolver(高德 Web REST place/text, 限当前城市) 解析出坐标 + 名称
  → Speaker(系统 TTS) 确认"目的地：X，开始步行导航"
  → GuideService(ACTION_START_NAV) → Navigator.startWalk(start, end)
       → AMapNavi.calculateWalkRoute(start,end)
       → onCalculateRouteSuccess → startNavi(GPS)
       → onGetNavigationText(转向文本) → Speaker(系统 TextToSpeech) 播报
       → onNaviInfoUpdate(剩余距离/时间/下一路口) → NavState(UI 显示)
       → onArriveDestination → TTS"已到达" → 停止
```

## 设计要点

- 新增包 `com.jusiai.guidedog.nav`，与现有 `core`/`service`/`ui` 解耦。
- `AMapNavi` 单例（`AMapNavi.getInstance(ctx)`），导航生命周期挂在 **`GuideService`** 上：它已是前台服务、
  支持锁屏运行，给后台 GPS 定位提供前台上下文。其 `foregroundServiceType` 增加 `location`。
- **导航在 GuideService 运行期内启动**（先点"开始"＝摄像头+导航同时在线，符合视障用户"既避障又导航"的组合场景）。
  "只导航不开摄像头"的省电模式本期不做。
- **隐私合规（强制）**：高德 navi 8.1.0+ 必须在触碰任何高德类之前调用
  `updatePrivacyShow`/`updatePrivacyAgree`，否则 SDK 拒绝工作。统一放 `GuideDogApp.onCreate` 最前面。

### 双语音协调（视障体验关键点）

视觉引导音频（relay PCM → `AudioPlayer`）连续，导航转向播报（系统 TTS）稀疏但重要，同时响很难听。轻量方案：
`Speaker` 用 `UtteranceProgressListener` 维护 `speaking` 标志（开始播报置 true、播完置 false）；
`GuideService` 感知循环里当 `speaking==true` 时**抑制** `audio.write`，让导航播报压过环境描述（正确优先级）。

## 涉及文件

**新建**（`app/src/main/java/com/jusiai/guidedog/nav/`）：`NavState.kt`、`Speaker.kt`、`Navigator.kt`、
`DestinationResolver.kt`、`SpeechInput.kt`、`LocationClient.kt`

**修改**：
- `gradle/libs.versions.toml` — 高德 navi-3dmap 依赖
- `app/build.gradle.kts` — 依赖 / abiFilters / `AMAP_KEY`(manifestPlaceholder) / `AMAP_WEB_KEY`(BuildConfig)
- `app/src/main/AndroidManifest.xml` — 权限 / Key meta-data / 前台服务类型 location
- `GuideDogApp.kt` — 隐私合规(导航+定位) + 单例
- `service/GuideService.kt` — location 前台类型 / nav action / 音频协调
- `ui/GuideViewModel.kt` + `ui/GuideScreen.kt` — 导航入口/状态/权限
- `app/proguard-rules.pro` — 高德 keep 规则
- `local.properties`（本地，不提交）— `AMAP_KEY=...` 与 `AMAP_WEB_KEY=...`

> `DestinationResolver` 用 Web REST，不依赖原生 search SDK（避开重复类）。`LocationClient` 用的
> `AMapLocationClient` 由 navi-3dmap 内置提供，无需单独引定位包。

## 验证

### 0. 一次性配置（高德控制台 + local.properties）
- 控制台同一应用下建两个 Key：
  - **Android 平台** Key → `AMAP_KEY`；其「设置」里 **包名=`com.jusiai.guidedog`**、**调试 SHA1=`60:AD:B8:88:94:A9:1A:E6:8A:47:3B:68:2A:4C:00:15:67:D0:2C:CE`**（绑定不符会运行时鉴权失败）。
  - **Web 服务**（不是"Web端"）Key → `AMAP_WEB_KEY`（"Web端"用于 REST 会报 `USERKEY_PLAT_NOMATCH`）。
- 取本机 debug SHA1：
  `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android`
- `local.properties`（不入库）：
  ```
  AMAP_KEY=<Android平台Key>
  AMAP_WEB_KEY=<Web服务Key>
  ```

### 1. 构建（已通过）
`:app:assembleDebug` —— APK 产出、`.so` 仅 arm64-v8a/armeabi-v7a、合并 manifest 含 apikey/`location` 前台类型/
定位+麦克风权限；`BuildConfig.AMAP_WEB_KEY` 注入正确。
> 本工程未提交 Gradle wrapper jar，可借同版本(8.10.2)工程的 wrapper：
> `d:\workspace\we-meet\we-meet-android\gradlew.bat -p d:\workspace\camera\jusi-guide-android :app:assembleDebug`
> 或直接用 Android Studio 打开本工程构建。

### 2. 安装到真机
接上手机（开 USB 调试），`adb devices` 能看到设备后：
```
<wrapper>\gradlew.bat -p d:\workspace\camera\jusi-guide-android :app:installDebug
```
首启授予 **相机 / 通知 / 麦克风 / 定位** 权限。

### 3. 冒烟（建议先设目的地再开始）
点「语音设置目的地」→ 说一个本地地名 → 听到"目的地已设为 X，请点击开始" → 点「开始」→
验证：算路成功、转向经系统 TTS 播报、界面剩余距离/时间递减、到达播"已到达"。
排障看 logcat：`adb logcat -s guide.navi guide.poi guide.loc guide.rec guide.tts guide.relay guide.amap`
（POI 报 `USERKEY_PLAT_NOMATCH`=web key 类型不对；导航报鉴权失败=Android key 包名/SHA1 不符）。

### 4. 真机实走（GPS）
户外步行：验证起点定位、实时转向播报、双语音协调（导航播报时视觉播报被压住）。

### 5. 回归
未设目的地时，原视觉引导（摄像头→relay→音频）完全不受影响。

## 实现注意

- 当前解析到 `navi-3dmap:10.0.800`。`AMapNaviListener` 各版本接口有增删：10.0.800 已移除 `onNaviInfoUpdated(AMapNaviInfo)`，
  其余按现接口空实现。升级版本若报"overrides nothing / 未实现"，以该版本接口为准增删即可。
- 隐私合规只需导航 + 定位两套（`NaviSetting` / `AMapLocationClient` 的 updatePrivacyShow/Agree）；搜索走 REST 不涉及。
- 关闭高德内置语音无需显式调用：navi 默认只回调 `onGetNavigationText` 文本、不出声，由系统 TTS 播报；
  部分版本会同时触发单参/双参文本回调，`Navigator` 内做了 4 秒去重避免双声。
- 语音识别**不用系统 `SpeechRecognizer`**：国行荣耀/华为的 `voice_recognition_service` 是 Google 的
  `GoogleTTSRecognitionService`，无 GMS 连不上谷歌，必报"需要联网"。改为客户端录音 → relay `/v1/asr` 云端识别。
- APK 较大（debug ~146MB，navi-3dmap 自带完整 3D 地图引擎）；上线可考虑开启 R8/资源压缩或评估更轻的 SDK 组合。

## relay 接口：`/v1/asr`（语音识别）—— 已在 jusi-guide-server 实现

客户端把录音包成 WAV 上传，relay 调火山大模型 ASR（`sauc bigmodel`，协议照搬
`jusi_meet_suite .../plugins/doubao/stt.py`）转文字返回。鉴权与 `/v1/guide` 一致，识别密钥只在 relay。
实现见 `jusi-guide-server/app/asr.py` + `app/main.py` 的 `/v1/asr` 路由；ASR 默认复用 TTS 的
`app_id/access_token`，需在火山控制台开通 ASR 服务（resource `volc.seedasr.sauc.duration`），然后重新部署 relay。

- **请求**：`POST {relayUrl}/v1/asr?lang=zh`
  - Header：`Authorization: Bearer <device_token>`、`Content-Type: audio/wav`
  - Body：WAV（RIFF/WAVE），**PCM 16-bit 单声道 16000Hz**（客户端 `VoiceRecorder` 已按此录制并加 44 字节 WAV 头）
- **响应**：`200`，JSON
  - 成功：`{"text":"北京南站"}`
  - 失败：`{"text":"","error":"原因"}`（error 会被 App 直接语音播报）；非 2xx 时 App 提示"识别服务错误（code）"
- 后端实现：把上传的 WAV 直接喂给火山引擎/豆包 ASR 的一句话识别接口，取首条结果填 `text` 返回即可。
