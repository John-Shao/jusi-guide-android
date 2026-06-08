# jusi-guide-android (guide-dog Android client)

An Android "guide-dog" client for blind users — the phone counterpart of the
RV1126B device [jusi-guide-dog](../jusi-guide-dog), talking to the **same relay**
[jusi-guide-server](../jusi-guide-server) (`https://login.jusiai.com`). Every
second it grabs a camera frame → frame-diff (skip static) → downscale to JPEG →
stream-POST to the relay → receive guidance text + speech PCM → play via
`AudioTrack`. No cloud secret lives on the device — only a revocable per-device token.

```
CameraX frame ─▶ frame-diff/downscale(JPEG) ─▶ relay (HTTPS, Bearer) ─▶ Doubao VLM ─▶ cloud TTS
                                                                                      │
              speaker ◀── AudioTrack (24kHz s16) ◀── streamed PCM ◀── JSON header + PCM
```

Mirrors the device firmware: a **foreground service** owns CameraX `ImageAnalysis`
and runs the perception loop (works with the screen off); the analysis stream also
drives the UI preview (single camera owner); `AudioTrack(MODE_STREAM)` with a ~3 s
buffer is the background player, so a sentence's tail plays while the next frame's
VLM runs. Relay-side dedup/re-announce is unchanged — the client does none of it.

**Stack** (mirrors we-meet-android): Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.10.2 ·
JDK 17 · compileSdk 34 / minSdk 29 · Compose + Material3 (single activity) ·
Coroutines + Flow · CameraX 1.4 · OkHttp 4.12 (streaming) · EncryptedSharedPreferences.

## Build & run

> The Gradle wrapper jar is not committed. **Open the folder in Android Studio**
> (it provisions Gradle), or run `gradle wrapper --gradle-version 8.10.2` once, then
> `./gradlew assembleDebug`.

Open in Android Studio (JDK 17), sync, run on a real device. Grant **Camera**
(required) and **Notifications** (API 33+, for the foreground service), tap **Start**.

## Configuration

Defaults are prefilled (`relay_url=https://login.jusiai.com`, a dev `device_token`,
lang `zh`); change them in the in-app Settings (stored in EncryptedSharedPreferences).

> Add the dev token to the relay's `DEVICE_TOKENS` and redeploy, e.g.
> `DEVICE_TOKENS=...,t_androiddev_K9pQ3zVx7mNbR4tLy0sWcf2:guide-android-01`. In
> production issue one token per device (don't share with the board — relay dedup
> is keyed by it).

See [README_CN.md](README_CN.md) for the Chinese version and layout details.
