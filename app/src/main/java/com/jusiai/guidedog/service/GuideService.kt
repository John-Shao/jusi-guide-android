package com.jusiai.guidedog.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.jusiai.guidedog.GuideDogApp
import com.jusiai.guidedog.MainActivity
import com.jusiai.guidedog.R
import com.jusiai.guidedog.core.FrameDiff
import com.jusiai.guidedog.core.Yuv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Latest camera frame, copied out so the ImageProxy can be closed immediately. */
private class Frame(val nv21: ByteArray, val width: Int, val height: Int, val rotation: Int)

/**
 * Foreground service that runs the whole guide-dog loop so it keeps working with
 * the screen off: CameraX ImageAnalysis feeds the latest frame to a perception
 * loop (frame-diff → JPEG → relay → speak). The analysis stream also drives the
 * UI preview, so there is a single camera owner.
 */
class GuideService : LifecycleService() {

    private val app by lazy { application as GuideDogApp }
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latest = AtomicReference<Frame?>(null)
    private var loopJob: Job? = null
    private var started = false
    private var lastAnalyzeAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_STOP_NAV -> { app.navigator.stop(); return START_NOT_STICKY }
            ACTION_START_NAV -> {
                ensureStarted()
                // 导航需要后台 GPS：此刻定位权限已授予，重申前台通知把 location 类型补上。
                startForegroundNotification()
                val start = com.amap.api.navi.model.NaviLatLng(
                    intent.getDoubleExtra(EX_START_LAT, 0.0), intent.getDoubleExtra(EX_START_LNG, 0.0),
                )
                val end = com.amap.api.navi.model.NaviLatLng(
                    intent.getDoubleExtra(EX_DEST_LAT, 0.0), intent.getDoubleExtra(EX_DEST_LNG, 0.0),
                )
                val name = intent.getStringExtra(EX_NAME) ?: ""
                app.navigator.startWalk(start, end, name)
            }
            else -> ensureStarted()
        }
        return START_NOT_STICKY
    }

    /** Start the foreground service + camera + perception loop once. */
    private fun ensureStarted() {
        if (started) return
        started = true
        startForegroundNotification()
        app.guideState.update { it.copy(running = true, error = null) }
        startCamera()
        loopJob = startLoop()
    }

    // ---- camera ----------------------------------------------------------------
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::onFrame) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed: ${e.message}")
                app.guideState.update { it.copy(error = "相机启动失败") }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(image: androidx.camera.core.ImageProxy) {
        try {
            // CameraX delivers ~30 fps, but the (~1 s) loop and the preview only
            // need a fresh frame every ~200 ms. Converting every frame to NV21
            // churns CPU/GC and janks the UI, so throttle here; skipped frames are
            // just closed in finally.
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalyzeAt < ANALYZE_INTERVAL_MS) return
            lastAnalyzeAt = now

            val w = image.width
            val h = image.height
            val rot = image.imageInfo.rotationDegrees
            val nv21 = Yuv.toNv21(image)
            latest.set(Frame(nv21, w, h, rot))
            Yuv.toPreviewBitmap(nv21, w, h, rot, 360)?.let { app.guideState.preview.value = it }
        } catch (e: Exception) {
            Log.w(TAG, "frame error: ${e.message}")
        } finally {
            image.close()
        }
    }

    // ---- perception loop -------------------------------------------------------
    private fun startLoop(): Job = ioScope.launch {
        val settings = app.settings
        val relay = app.relayClient
        val audio = app.audioPlayer
        val diff = FrameDiff(settings.frameDiffThreshold, settings.frameDiffForceMs)
        Log.i(TAG, "guidance loop started")
        while (isActive) {
            val cycleStart = SystemClock.elapsedRealtime()
            val frame = latest.get()
            if (frame == null) { delay(100); continue }

            val (send, mad) = diff.shouldSend(frame.nv21, frame.width, frame.height, frame.width, cycleStart)
            if (!send) {
                app.guideState.update { it.copy(mad = mad) }
                pace(cycleStart, settings.intervalMs)
                continue
            }

            val jpeg = Yuv.nv21ToJpeg(
                frame.nv21, frame.width, frame.height, frame.rotation,
                settings.uploadMaxDim, settings.uploadQuality,
            )
            if (jpeg == null) { pace(cycleStart, settings.intervalMs); continue }

            val result = relay.guide(
                jpeg,
                onHeader = { r ->
                    Log.i(TAG, "guidance = \"${r.text}\" (repeat=${r.repeat})")
                    if (settings.wantAudio && !r.repeat) audio.ensure(r.sampleRate)
                },
                // 双语音协调：导航/提示语音播报时、或正在语音设目的地时，抑制视觉播报，避免互相干扰。
                onPcm = { data, len ->
                    if (settings.wantAudio && !app.speaker.speaking.value && !app.navState.capturingVoice.value) {
                        audio.write(data, len)
                    }
                },
            )
            val cycleMs = SystemClock.elapsedRealtime() - cycleStart
            if (result != null) {
                app.guideState.update {
                    it.copy(text = result.text, mad = mad, lastCycleMs = cycleMs, vlmMs = result.vlmMs, error = null)
                }
            } else {
                app.guideState.update { it.copy(mad = mad, error = "中转无响应") }
            }
            pace(cycleStart, settings.intervalMs)
        }
    }

    private suspend fun pace(startMs: Long, intervalMs: Long) {
        val wait = intervalMs - (SystemClock.elapsedRealtime() - startMs)
        if (wait > 0) delay(wait)
    }

    // ---- foreground notification ----------------------------------------------
    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "导盲犬运行中", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GuideService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("导盲犬运行中")
            .setContentText("正在为你观察前方并语音提示")
            .setSmallIcon(R.drawable.ic_guide)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        startForeground(NOTIF_ID, notif, foregroundType())
    }

    /**
     * Android 14+ 要求 startForeground 声明的每个类型都已持有对应权限，否则抛 SecurityException。
     * 相机/媒体在"开始"时已具备；定位权限是导航时才申请的，所以这里按当前已授予的权限动态拼装：
     * 没有定位权限就不带 location 类型，导航开始（已授权）时再调用本方法把 location 加上。
     */
    private fun foregroundType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (hasLocationPermission()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return type
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        loopJob?.cancel()
        ioScope.cancel()
        app.navigator.stop()   // 导航依赖前台服务，服务退出即停导航
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) {}
        cameraExecutor.shutdown()
        app.audioPlayer.release()
        app.guideState.preview.value = null
        app.guideState.update { it.copy(running = false) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "guide.service"
        private const val CHANNEL = "guide_running"
        private const val NOTIF_ID = 1
        private const val ANALYZE_INTERVAL_MS = 200L
        const val ACTION_STOP = "com.jusiai.guidedog.action.STOP"
        const val ACTION_START_NAV = "com.jusiai.guidedog.action.START_NAV"
        const val ACTION_STOP_NAV = "com.jusiai.guidedog.action.STOP_NAV"
        private const val EX_START_LAT = "start_lat"
        private const val EX_START_LNG = "start_lng"
        private const val EX_DEST_LAT = "dest_lat"
        private const val EX_DEST_LNG = "dest_lng"
        private const val EX_NAME = "dest_name"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, GuideService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GuideService::class.java))
        }

        /** 开始步行导航：会确保服务（含摄像头视觉引导）一并启动。 */
        fun startNav(
            ctx: Context,
            startLat: Double, startLng: Double,
            destLat: Double, destLng: Double,
            name: String,
        ) {
            val i = Intent(ctx, GuideService::class.java).setAction(ACTION_START_NAV)
                .putExtra(EX_START_LAT, startLat).putExtra(EX_START_LNG, startLng)
                .putExtra(EX_DEST_LAT, destLat).putExtra(EX_DEST_LNG, destLng)
                .putExtra(EX_NAME, name)
            ContextCompat.startForegroundService(ctx, i)
        }

        /** 仅停止导航，服务（视觉引导）继续运行。 */
        fun stopNav(ctx: Context) {
            ctx.startService(Intent(ctx, GuideService::class.java).setAction(ACTION_STOP_NAV))
        }
    }
}
