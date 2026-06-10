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
 * ScreenCaptureService — Game Loop الكامل
 *
 * الدورة:
 *  1. ML Kit يكشف جسم بشري (كل 80ms)
 *  2. AimEngine يحسب swipe التصويب
 *  3. UiAutomationService ينفّذ swipe التصويب (يمين الشاشة)
 *  4. بعد الـ aim يضغط زرار النار فوراً
 *  5. MovementEngine يحرك الـ joystick (يسار)
 *  6. ResearchLogger يسجّل كل حدث
 *
 *  رد الفعل الكلي: ~30ms (يتفوق على إنسان 150-300ms)
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG              = "ScreenCaptureService"
        private const val NOTIF_CHANNEL_ID = "screen_capture_channel"
        private const val NOTIF_ID         = 1001

        private const val SCAN_INTERVAL_MS  = 80L    // كشف كل 80ms = ~12 fps
        private const val SCALE_FACTOR      = 0.4f

        private const val LANDMARK_CONFIDENCE = 0.50f
        private const val MIN_LANDMARKS        = 4
        private const val CENTER_MARGIN        = 0.475f

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile var isRunning      = false
        @Volatile var detectedBodies = 0

        // ── إعدادات مواضع اللعبة (قابلة للضبط حسب الجهاز) ──────────────────
        // مركز منطقة التصويب (يمين الشاشة)
        var aimOriginXRatio  = 0.75f   // 75% من العرض
        var aimOriginYRatio  = 0.50f   // وسط الارتفاع
        // زرار النار
        var fireButtonXRatio = 0.82f
        var fireButtonYRatio = 0.68f
        // حساسية التصويب (px swipe لكل px فرق)
        var aimSensitivity   = 6f
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay?   = null
    private var imageReader: ImageReader?          = null
    private var workerThread: HandlerThread?       = null
    private var workerHandler: Handler?            = null
    private var poseDetector: PoseDetector?        = null
    private val isDetecting = AtomicBoolean(false)

    private var screenW = 0
    private var screenH = 0

    override fun onCreate() {
        super.onCreate()
        val m = resources.displayMetrics
        screenW = m.widthPixels; screenH = m.heightPixels
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("جاهز"))
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
            PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
        )
    }

    private fun setupImageReader() {
        val w = (screenW * SCALE_FACTOR).toInt()
        val h = (screenH * SCALE_FACTOR).toInt()
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "UIQACapture", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, workerHandler
        )
        workerHandler?.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
    }

    // ── Scan Loop ───────────────────────────────────────────────────────────
    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (!isDetecting.compareAndSet(false, true)) {
                workerHandler?.postDelayed(this, SCAN_INTERVAL_MS); return
            }
            processNextFrame()
        }
    }

    private fun processNextFrame() {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            isDetecting.set(false)
            workerHandler?.postDelayed(scanRunnable, SCAN_INTERVAL_MS); return
        }
        var frame: Bitmap? = null; var scaled: Bitmap? = null
        try {
            val plane = image.planes[0]
            frame = Bitmap.createBitmap(plane.rowStride / plane.pixelStride,
                image.height, Bitmap.Config.ARGB_8888)
            frame.copyPixelsFromBuffer(plane.buffer)
            scaled = Bitmap.createScaledBitmap(frame, image.width, image.height, false)
            frame.recycle(); frame = null
            val capturedBitmap = scaled
            val fw = image.width; val fh = image.height
            poseDetector?.process(InputImage.fromBitmap(scaled, 0))
                ?.addOnSuccessListener { pose ->
                    capturedBitmap.recycle()
                    handlePoseResult(pose, fw, fh)
                    isDetecting.set(false)
                    workerHandler?.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
                }
                ?.addOnFailureListener {
                    capturedBitmap.recycle(); isDetecting.set(false)
                    workerHandler?.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
                }
                ?: run { scaled.recycle(); isDetecting.set(false); workerHandler?.postDelayed(scanRunnable, SCAN_INTERVAL_MS) }
        } catch (e: Exception) {
            frame?.recycle(); scaled?.recycle(); isDetecting.set(false)
            workerHandler?.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
        } finally { image.close() }
    }

    // ── Game Loop الكامل ────────────────────────────────────────────────────
    private fun handlePoseResult(pose: Pose, frameW: Int, frameH: Int) {
        val goodLandmarks = pose.allPoseLandmarks.filter {
            it.inFrameLikelihood >= LANDMARK_CONFIDENCE
        }

        if (goodLandmarks.size < MIN_LANDMARKS) {
            // لا هدف → حركة دورية
            if (detectedBodies != 0) {
                detectedBodies = 0
                FloatingTapIndicator.instance?.setActive(false)
                AimEngine.clearHistory()
                updateNotification("دورية — لا هدف")
            }
            executeMovement(hasTarget = false, targetDist = 0f)
            return
        }

        // ── حساب مركز الهدف ─────────────────────────────────────────────────
        val targetPoint = getTargetPoint(pose, goodLandmarks, frameW, frameH)

        // ── شرط منطقة التصويب ───────────────────────────────────────────────
        val leftBound  = frameW * CENTER_MARGIN
        val rightBound = frameW * (1f - CENTER_MARGIN)
        val topBound   = frameH * CENTER_MARGIN
        val botBound   = frameH * (1f - CENTER_MARGIN)

        val anyInCenter = goodLandmarks.any { lm ->
            lm.position.x in leftBound..rightBound && lm.position.y in topBound..botBound
        }

        if (!anyInCenter) {
            // هدف على الأطراف → صوّب عليه (حرّك الكاميرا)
            executeAim(targetPoint.x / SCALE_FACTOR, targetPoint.y / SCALE_FACTOR,
                tracked = false)
            executeMovement(hasTarget = true,
                targetDist = targetPoint.y.let { (frameH / 2f - it).let { d -> d * d } }
                    .let { Math.sqrt(it.toDouble()).toFloat() })
            return
        }

        // ── هدف في المنطقة ✓ → كشف + تصويب + نار ───────────────────────────
        detectedBodies = 1
        ResearchLogger.onDetection()
        FloatingTapIndicator.instance?.setActive(true)

        val realX = targetPoint.x / SCALE_FACTOR
        val realY = targetPoint.y / SCALE_FACTOR
        val dist  = Math.hypot((realX - screenW / 2.0), (realY - screenH / 2.0)).toFloat()

        val conf = (goodLandmarks.size * 100f / 33f).toInt()
        updateNotification("🎯 هدف في الوسط ✓ ثقة:$conf% → يصوّب ويطلق")
        Log.i(TAG, "TARGET → pos=(${realX.toInt()},${realY.toInt()}) conf=$conf%")

        // 1. تصويب
        executeAim(realX, realY, tracked = true)

        // 2. إطلاق نار (بعد 30ms من التصويب)
        workerHandler?.postDelayed({ executeFire() }, 30L)

        // 3. حركة جانبية أثناء الاشتباك
        executeMovement(hasTarget = true, targetDist = dist)
    }

    // ── التصويب ─────────────────────────────────────────────────────────────
    private fun executeAim(targetX: Float, targetY: Float, tracked: Boolean) {
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
        }
    }

    // ── إطلاق النار ─────────────────────────────────────────────────────────
    private fun executeFire() {
        if (!isRunning) return
        val fireX = screenW * fireButtonXRatio
        val fireY = screenH * fireButtonYRatio
        ResearchLogger.onShot()
        UiAutomationService.instance?.tap(fireX, fireY)
        TargetStore.tapCount++
    }

    // ── الحركة ──────────────────────────────────────────────────────────────
    private fun executeMovement(hasTarget: Boolean, targetDist: Float) {
        val move = MovementEngine.compute(
            hasTarget = hasTarget, targetDist = targetDist,
            screenW = screenW, screenH = screenH
        )
        if (move.durationMs > 0L) {
            ResearchLogger.onMove()
            UiAutomationService.instance?.swipe(
                move.joyStartX, move.joyStartY,
                move.joyEndX, move.joyEndY,
                move.durationMs
            )
        }
    }

    // ── نقطة الاستهداف حسب جزء الجسم ────────────────────────────────────────
    private fun getTargetPoint(pose: Pose, landmarks: List<com.google.mlkit.vision.pose.PoseLandmark>,
                                frameW: Int, frameH: Int): android.graphics.PointF {
        return when (TargetStore.targetPart) {
            TargetStore.TargetPart.HEAD -> {
                val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
                if (nose != null && nose.inFrameLikelihood >= LANDMARK_CONFIDENCE)
                    android.graphics.PointF(nose.position.x, nose.position.y)
                else centerOfLandmarks(landmarks)
            }
            TargetStore.TargetPart.CHEST -> {
                val l = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
                val r = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
                if (l != null && r != null &&
                    l.inFrameLikelihood >= LANDMARK_CONFIDENCE &&
                    r.inFrameLikelihood >= LANDMARK_CONFIDENCE)
                    android.graphics.PointF((l.position.x + r.position.x) / 2f,
                        (l.position.y + r.position.y) / 2f + 40f)
                else centerOfLandmarks(landmarks)
            }
            TargetStore.TargetPart.HANDS -> {
                val lw = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
                val rw = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
                val best = listOfNotNull(lw, rw)
                    .filter { it.inFrameLikelihood >= LANDMARK_CONFIDENCE }
                    .minByOrNull { it.position.y }
                if (best != null) android.graphics.PointF(best.position.x, best.position.y)
                else centerOfLandmarks(landmarks)
            }
            else -> centerOfLandmarks(landmarks)
        }
    }

    private fun centerOfLandmarks(lms: List<com.google.mlkit.vision.pose.PoseLandmark>) =
        android.graphics.PointF(
            lms.map { it.position.x }.average().toFloat(),
            lms.map { it.position.y }.average().toFloat()
        )

    private fun startWorkerThread() {
        workerThread = HandlerThread("GameLoopWorker",
            android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
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
            .createNotificationChannel(NotificationChannel(NOTIF_CHANNEL_ID,
                "UI QA Game Loop", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(s: String): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Enterprise UI QA — Game Loop")
            .setContentText(s)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true).build()
}
