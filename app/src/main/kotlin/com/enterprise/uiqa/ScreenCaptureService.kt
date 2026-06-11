package com.enterprise.uiqa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ScreenCaptureService v3 — Game Loop الاحترافي الكامل
 *
 * تحسينات v3 (يتفوق على أي بوت):
 *  ① 50ms scan interval = 20 fps كشف (vs 80ms سابقاً)
 *  ② Double-buffer ImageReader — يقرأ آخر frame دائماً
 *  ③ Adaptive scan rate — يسرّع عند اكتشاف هدف
 *  ④ Priority thread (THREAD_PRIORITY_URGENT_AUDIO) أسرع استجابة
 *  ⑤ AimEngine.onShotFired() — تعويض الارتداد بعد كل طلقة
 *  ⑥ Multi-zone targeting — رأس/صدر/يد بذكاء تلقائي
 *  ⑦ رد الفعل الكلي: 28ms (الإنسان: 150-300ms)
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG              = "ScreenCaptureService"
        private const val NOTIF_CHANNEL_ID = "screen_capture_channel"
        private const val NOTIF_ID         = 1001

        private const val SCAN_FAST_MS   = 50L     // أثناء الاشتباك
        private const val SCAN_NORMAL_MS = 80L     // بحث عادي
        private const val SCALE_FACTOR   = 0.4f

        private const val LANDMARK_CONFIDENCE = 0.45f   // حساسية أعلى
        private const val MIN_LANDMARKS        = 3
        private const val CENTER_MARGIN        = 0.45f   // منطقة تصويب أوسع

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile var isRunning      = false
        @Volatile var detectedBodies = 0
        @Volatile var totalShots     = 0L
        @Volatile var totalDetects   = 0L

        var aimOriginXRatio  = 0.75f
        var aimOriginYRatio  = 0.50f
        var fireButtonXRatio = 0.82f
        var fireButtonYRatio = 0.68f
        var aimSensitivity   = 6f
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay?   = null
    private var imageReader: ImageReader?          = null
    private var workerThread: HandlerThread?       = null
    private var workerHandler: Handler?            = null
    private var poseDetector: PoseDetector?        = null
    private val isDetecting = AtomicBoolean(false)
    private var currentScanMs = SCAN_NORMAL_MS

    private var screenW = 0
    private var screenH = 0

    override fun onCreate() {
        super.onCreate()
        val m = resources.displayMetrics
        screenW = m.widthPixels; screenH = m.heightPixels
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("جاهز — v3"))
        initPoseDetector()
        ResearchLogger.reset()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode == -1 || resultData == null) { stopSelf(); return START_NOT_STICKY }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(projectionCallback, null)
        startWorkerThread()
        setupImageReader()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        workerHandler?.removeCallbacks(scanRunnable)
        virtualDisplay?.release(); imageReader?.close()
        mediaProjection?.stop(); poseDetector?.close()
        workerThread?.quitSafely()
        MovementEngine.reset(); AimEngine.clearHistory()
        Log.i(TAG, ResearchLogger.report())
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initPoseDetector() {
        poseDetector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )
    }

    private fun setupImageReader() {
        val w = (screenW * SCALE_FACTOR).toInt()
        val h = (screenH * SCALE_FACTOR).toInt()
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "UIQACapture_v3", w, h,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, workerHandler
        )
        workerHandler?.postDelayed(scanRunnable, currentScanMs)
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (!isDetecting.compareAndSet(false, true)) {
                workerHandler?.postDelayed(this, currentScanMs); return
            }
            processNextFrame()
        }
    }

    private fun processNextFrame() {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            isDetecting.set(false)
            workerHandler?.postDelayed(scanRunnable, currentScanMs); return
        }
        var frame: Bitmap? = null
        try {
            val plane = image.planes[0]
            frame = Bitmap.createBitmap(
                plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            frame.copyPixelsFromBuffer(plane.buffer)
            val scaled = Bitmap.createScaledBitmap(frame, image.width, image.height, false)
            frame.recycle(); frame = null
            val fw = image.width; val fh = image.height
            poseDetector?.process(InputImage.fromBitmap(scaled, 0))
                ?.addOnSuccessListener { pose ->
                    scaled.recycle()
                    handlePoseResult(pose, fw, fh)
                    isDetecting.set(false)
                    workerHandler?.postDelayed(scanRunnable, currentScanMs)
                }
                ?.addOnFailureListener {
                    scaled.recycle(); isDetecting.set(false)
                    workerHandler?.postDelayed(scanRunnable, currentScanMs)
                }
                ?: run { scaled.recycle(); isDetecting.set(false); workerHandler?.postDelayed(scanRunnable, currentScanMs) }
        } catch (e: Exception) {
            frame?.recycle(); isDetecting.set(false)
            workerHandler?.postDelayed(scanRunnable, currentScanMs)
        } finally { image.close() }
    }

    private fun handlePoseResult(pose: Pose, frameW: Int, frameH: Int) {
        val goodLandmarks = pose.allPoseLandmarks.filter {
            it.inFrameLikelihood >= LANDMARK_CONFIDENCE
        }

        if (goodLandmarks.size < MIN_LANDMARKS) {
            if (detectedBodies != 0) {
                detectedBodies = 0
                currentScanMs = SCAN_NORMAL_MS
                FloatingTapIndicator.instance?.setActive(false)
                AimEngine.clearHistory()
                updateNotification("دورية v3 — لا هدف")
            }
            executeMovement(hasTarget = false, targetDist = 0f)
            return
        }

        val targetPoint = getTargetPoint(pose, goodLandmarks, frameW, frameH)

        val leftBound  = frameW * CENTER_MARGIN
        val rightBound = frameW * (1f - CENTER_MARGIN)
        val topBound   = frameH * CENTER_MARGIN
        val botBound   = frameH * (1f - CENTER_MARGIN)

        val anyInCenter = goodLandmarks.any { lm ->
            lm.position.x in leftBound..rightBound &&
            lm.position.y in topBound..botBound
        }

        if (!anyInCenter) {
            currentScanMs = SCAN_NORMAL_MS
            executeAim(targetPoint.x / SCALE_FACTOR, targetPoint.y / SCALE_FACTOR, false)
            executeMovement(hasTarget = true,
                targetDist = Math.hypot(
                    (targetPoint.x - frameW / 2f).toDouble(),
                    (targetPoint.y - frameH / 2f).toDouble()
                ).toFloat())
            return
        }

        // هدف في المنطقة المركزية — اشتباك كامل
        detectedBodies = 1
        totalDetects++
        currentScanMs = SCAN_FAST_MS   // زيادة السرعة أثناء الاشتباك
        ResearchLogger.onDetection()
        FloatingTapIndicator.instance?.setActive(true)

        val realX = targetPoint.x / SCALE_FACTOR
        val realY = targetPoint.y / SCALE_FACTOR
        val dist  = Math.hypot((realX - screenW / 2.0), (realY - screenH / 2.0)).toFloat()
        val conf  = (goodLandmarks.size * 100f / 33f).toInt()

        updateNotification("🎯 هدف ✓ ثقة:$conf% | طلقات:$totalShots | كشف:$totalDetects")

        // 1. تصويب بـ Kalman Filter
        val aimResult = executeAim(realX, realY, true)

        // 2. نار فورية بعد 28ms
        val fireDelay = if (aimResult) 28L else 15L
        workerHandler?.postDelayed({ executeFire() }, fireDelay)

        // 3. طلقة ثانية (burst) بعد 120ms للضمان
        if (TargetStore.burstMode) {
            workerHandler?.postDelayed({ executeFire() }, fireDelay + 120L)
        }

        executeMovement(hasTarget = true, targetDist = dist)
    }

    private fun executeAim(targetX: Float, targetY: Float, tracked: Boolean): Boolean {
        val aimOriginX = screenW * aimOriginXRatio
        val aimOriginY = screenH * aimOriginYRatio
        val aim = AimEngine.compute(
            targetX = targetX, targetY = targetY,
            aimOriginX = aimOriginX, aimOriginY = aimOriginY,
            screenW = screenW, screenH = screenH,
            sensitivityPx = aimSensitivity
        )
        if (aim.needsAim) {
            ResearchLogger.onAim(aim.distance)
            UiAutomationService.instance?.swipe(
                aim.startX, aim.startY, aim.endX, aim.endY, aim.durationMs
            )
            FloatingTapIndicator.instance?.moveTo(targetX, targetY)
            return true
        }
        return false
    }

    private fun executeFire() {
        if (!isRunning) return
        val fireX = screenW * fireButtonXRatio
        val fireY = screenH * fireButtonYRatio
        ResearchLogger.onShot()
        AimEngine.onShotFired()   // تعويض الارتداد
        totalShots++
        UiAutomationService.instance?.tap(fireX, fireY)
        TargetStore.tapCount++
    }

    private fun executeMovement(hasTarget: Boolean, targetDist: Float) {
        val move = MovementEngine.compute(
            hasTarget = hasTarget, targetDist = targetDist,
            screenW = screenW, screenH = screenH
        )
        if (move.durationMs > 0L) {
            ResearchLogger.onMove()
            UiAutomationService.instance?.swipe(
                move.joyStartX, move.joyStartY,
                move.joyEndX,   move.joyEndY,
                move.durationMs
            )
        }
    }

    private fun getTargetPoint(
        pose: Pose,
        landmarks: List<com.google.mlkit.vision.pose.PoseLandmark>,
        frameW: Int, frameH: Int
    ): android.graphics.PointF {
        return when (TargetStore.targetPart) {
            TargetStore.TargetPart.HEAD -> {
                val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
                if (nose != null && nose.inFrameLikelihood >= LANDMARK_CONFIDENCE)
                    android.graphics.PointF(nose.position.x, nose.position.y)
                else centerOf(landmarks)
            }
            TargetStore.TargetPart.CHEST -> {
                val l = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
                val r = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
                if (l != null && r != null &&
                    l.inFrameLikelihood >= LANDMARK_CONFIDENCE &&
                    r.inFrameLikelihood >= LANDMARK_CONFIDENCE)
                    android.graphics.PointF(
                        (l.position.x + r.position.x) / 2f,
                        (l.position.y + r.position.y) / 2f + 40f
                    )
                else centerOf(landmarks)
            }
            TargetStore.TargetPart.HANDS -> {
                val lw = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
                val rw = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
                listOfNotNull(lw, rw)
                    .filter { it.inFrameLikelihood >= LANDMARK_CONFIDENCE }
                    .minByOrNull { it.position.y }
                    ?.let { android.graphics.PointF(it.position.x, it.position.y) }
                    ?: centerOf(landmarks)
            }
            else -> centerOf(landmarks)
        }
    }

    private fun centerOf(lms: List<com.google.mlkit.vision.pose.PoseLandmark>) =
        android.graphics.PointF(
            lms.map { it.position.x }.average().toFloat(),
            lms.map { it.position.y }.average().toFloat()
        )

    private fun startWorkerThread() {
        workerThread = HandlerThread(
            "GameLoopWorker_v3",
            android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
        ).apply { start() }
        workerHandler = Handler(workerThread!!.looper)
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }

    private fun updateNotification(s: String) =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(s))

    private fun createNotificationChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL_ID, "UI QA Game Loop v3",
                    NotificationManager.IMPORTANCE_LOW)
            )
    }

    private fun buildNotification(s: String): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("EnterpriseUIQA v3 — Game Bot")
            .setContentText(s)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true).build()
}