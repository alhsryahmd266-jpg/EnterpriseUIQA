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
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG              = "ScreenCaptureService"
        private const val NOTIF_CHANNEL_ID = "screen_capture_channel"
        private const val NOTIF_ID         = 1001

        private const val SCAN_INTERVAL_MS  = 400L   // ML Kit كل 400ms — لا تسخين
        private const val TAP_INTERVAL_MS   = 100L   // ضغط كل 100ms عند وجود جسم
        private const val SCALE_FACTOR      = 0.4f   // تصغير 40% لتوفير الباتري

        private const val LANDMARK_CONFIDENCE = 0.50f
        private const val MIN_LANDMARKS        = 4

        // منطقة الوسط: 30% من كل جانب (يعني الوسط 40% من الشاشة)
        private const val CENTER_MARGIN = 0.475f

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile var isRunning      = false
        @Volatile var detectedBodies = 0
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay?   = null
    private var imageReader: ImageReader?          = null
    private var workerThread: HandlerThread?       = null
    private var workerHandler: Handler?            = null
    private var poseDetector: PoseDetector?        = null

    private val isDetecting  = AtomicBoolean(false)
    private val isTapping    = AtomicBoolean(false)

    // ── loop الضغط السريع منفصل عن ML Kit ──────────────────────────────────
    private val tapRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || !isTapping.get()) return
            dispatchTap()
            workerHandler?.postDelayed(this, TAP_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("جاهز — في انتظار تفعيل المسح"))
        initPoseDetector()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == -1 || resultData == null) { stopSelf(); return START_NOT_STICKY }

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(projectionCallback, null)

        startWorkerThread()
        setupImageReader()
        Log.i(TAG, "Started — scan=${SCAN_INTERVAL_MS}ms tap=${TAP_INTERVAL_MS}ms")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopTapping()
        workerHandler?.removeCallbacks(scanRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        poseDetector?.close()
        workerThread?.quitSafely()
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
        val m = resources.displayMetrics
        val w = (m.widthPixels  * SCALE_FACTOR).toInt()
        val h = (m.heightPixels * SCALE_FACTOR).toInt()
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "UIQACapture", w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, workerHandler
        )
        workerHandler?.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
    }

    // ── ML Kit scan loop ────────────────────────────────────────────────────
    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (!isDetecting.compareAndSet(false, true)) {
                workerHandler?.postDelayed(this, SCAN_INTERVAL_MS)
                return
            }
            processNextFrame()
        }
    }

    private fun processNextFrame() {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            isDetecting.set(false)
            workerHandler?.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
            return
        }
        var frame: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val plane  = image.planes[0]
            val stride = plane.rowStride / plane.pixelStride
            frame  = Bitmap.createBitmap(stride, image.height, Bitmap.Config.ARGB_8888)
            frame.copyPixelsFromBuffer(plane.buffer)
            scaled = Bitmap.createScaledBitmap(frame, image.width, image.height, false)
            frame.recycle(); frame = null

            val inputImage   = InputImage.fromBitmap(scaled, 0)
            val capturedBitmap = scaled
            val fw = image.width; val fh = image.height

            poseDetector?.process(inputImage)
                ?.addOnSuccessListener { pose ->
                    capturedBitmap.recycle()
                    handlePoseResult(pose, fw, fh)
                    isDetecting.set(false)
                    workerHandler?.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
                }
                ?.addOnFailureListener {
                    capturedBitmap.recycle()
                    isDetecting.set(false)
                    workerHandler?.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
                }
                ?: run { scaled.recycle(); isDetecting.set(false); workerHandler?.postDelayed(scanRunnable, SCAN_INTERVAL_MS) }

        } catch (e: Exception) {
            frame?.recycle(); scaled?.recycle()
            isDetecting.set(false)
            workerHandler?.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
        } finally {
            image.close()
        }
    }

    // ── تحليل نتيجة Pose — مع شرط الوسط ────────────────────────────────────
    private fun handlePoseResult(pose: Pose, frameW: Int, frameH: Int) {
        val goodLandmarks = pose.allPoseLandmarks.filter {
            it.inFrameLikelihood >= LANDMARK_CONFIDENCE
        }

        if (goodLandmarks.size < MIN_LANDMARKS) {
            // لا جسم → أوقف الضغط فوراً
            if (detectedBodies != 0) {
                detectedBodies = 0
                stopTapping()
                updateNotification("لم يُكتشف جسم بشري في المنطقة الوسطى")
                Log.i(TAG, "Body gone → tapping stopped")
            }
            return
        }

        // ── شرط الوسط: أي نقطة من الجسم تدخل المنطقة الوسطى ────────────
        val leftBound  = frameW * CENTER_MARGIN
        val rightBound = frameW * (1f - CENTER_MARGIN)
        val topBound   = frameH * CENTER_MARGIN
        val botBound   = frameH * (1f - CENTER_MARGIN)

        val inCenter = goodLandmarks.any { lm ->
            lm.position.x in leftBound..rightBound &&
            lm.position.y in topBound..botBound
        }

        if (!inCenter) {
            // جسم موجود لكن على الأطراف → أوقف
            if (isTapping.get()) {
                stopTapping()
                detectedBodies = 0
                updateNotification("جسم على الأطراف — في انتظار المنتصف")
            }
            return
        }

        // ── جسم في الوسط ✓ → ابدأ الضغط ─────────────────────────────────
        detectedBodies = 1
        val conf = (goodLandmarks.size * 100f / 33f).toInt()
        if (!isTapping.get()) {
            startTapping()
            Log.i(TAG, "Body in CENTER → tapping started  conf=$conf%  pos=(${bodyX.toInt()},${bodyY.toInt()})")
        }
        updateNotification("🎯 جسم في الوسط ✓  ثقة: $conf%  — يضغط الآن")
    }

    // ── إدارة loop الضغط ───────────────────────────────────────────────────
    private fun startTapping() {
        if (isTapping.compareAndSet(false, true)) {
            FloatingTapIndicator.instance?.setActive(true)
            workerHandler?.post(tapRunnable)
        }
    }

    private fun stopTapping() {
        if (isTapping.compareAndSet(true, false)) {
            workerHandler?.removeCallbacks(tapRunnable)
            FloatingTapIndicator.instance?.setActive(false)
        }
    }

    private fun dispatchTap() {
        if (!TargetStore.hasManualTarget) return
        UiAutomationService.instance?.tap(TargetStore.manualX, TargetStore.manualY)
            ?: Log.w(TAG, "UiAutomationService not ready")
    }

    private fun startWorkerThread() {
        workerThread = HandlerThread("PoseDetectWorker",
            android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        workerHandler = Handler(workerThread!!.looper)
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }

    private fun updateNotification(status: String) =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(status))

    private fun createNotificationChannel() {
        val ch = NotificationChannel(NOTIF_CHANNEL_ID, "UI QA Body Detection",
            NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("UI QA — اكتشاف الجسم البشري")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true).build()
}
