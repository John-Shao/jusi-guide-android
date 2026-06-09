package com.jusiai.guidedog.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            startForegroundNotification()
            app.guideState.update { it.copy(running = true, error = null) }
            startCamera()
            loopJob = startLoop()
        }
        return START_NOT_STICKY
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
                onPcm = { data, len -> if (settings.wantAudio) audio.write(data, len) },
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

        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        startForeground(NOTIF_ID, notif, type)
    }

    override fun onDestroy() {
        loopJob?.cancel()
        ioScope.cancel()
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

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, GuideService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GuideService::class.java))
        }
    }
}
