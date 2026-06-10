package com.enterprise.uiqa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PointF
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

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG              = "ScreenCaptureService"
        private const val NOTIF_CHANNEL_ID = "screen_capture_channel"
        private const val NOTIF_ID         = 1001

        // ── أسرع معدل ممكن عند وجود هدف ─────────────────────────────────
        private const val SCAN_FAST_MS    = 80L     // ~12 إطار/ثانية عند وجود جسم
        private const val SCAN_SLOW_MS    = 800L    // بطيء عند غياب الجسم
        private const val SCAN_MANUAL_MS  = 150L    // معدل الضغط اليدوي
        private const val SCALE_FACTOR    = 0.5f

        private const val LANDMARK_CONFIDENCE = 0.50f
        private const val MIN_LANDMARKS        = 4

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
    private val isDetecting = AtomicBoolean(false)

    private val offsetState = DataProcessingUnit.DynamicOffsetState(
        stepPx = 2.0f, maxOffsetPx = 35f, decayFactor = 0.04f
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("جاهز — في انتظار تفعيل المسح"))
        initPoseDetector()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        // إصلاح: getParcelableExtra المتوافق مع API 33+
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == -1 || resultData == null) { stopSelf(); return START_NOT_STICKY }

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(projectionCallback, null)

        startWorkerThread()
        setupImageReader()
        Log.i(TAG, "Body detection started — fast=${SCAN_FAST_MS}ms / manual=${SCAN_MANUAL_MS}ms")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        workerHandler?.removeCallbacks(scanRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        poseDetector?.close()
        workerThread?.quitSafely()
        Log.i(TAG, "ScreenCaptureService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initPoseDetector() {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        poseDetector = PoseDetection.getClient(options)
    }

    private fun setupImageReader() {
        val m = resources.displayMetrics
        val w = m.widthPixels / 2
        val h = m.heightPixels / 2
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "UIQACapture", w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, workerHandler
        )
        workerHandler?.postDelayed(scanRunnable, SCAN_SLOW_MS)
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (!isDetecting.compareAndSet(false, true)) {
                workerHandler?.postDelayed(this, SCAN_SLOW_MS)
                return
            }
            processNextFrame()
        }
    }

    private fun processNextFrame() {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            isDetecting.set(false)
            workerHandler?.postDelayed(scanRunnable, SCAN_SLOW_MS)
            return
        }

        var frameBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null

        try {
            val plane  = image.planes[0]
            val buf    = plane.buffer
            val stride = plane.rowStride / plane.pixelStride
            val w      = image.width
            val h      = image.height

            frameBitmap = Bitmap.createBitmap(stride, h, Bitmap.Config.ARGB_8888)
            frameBitmap.copyPixelsFromBuffer(buf)

            val sw = (w * SCALE_FACTOR).toInt()
            val sh = (h * SCALE_FACTOR).toInt()
            scaledBitmap = Bitmap.createScaledBitmap(frameBitmap, sw, sh, true)
            frameBitmap.recycle(); frameBitmap = null

            val inputImage = InputImage.fromBitmap(scaledBitmap, 0)
            val capturedScaled = scaledBitmap

            poseDetector?.process(inputImage)
                ?.addOnSuccessListener { pose ->
                    capturedScaled.recycle()
                    handlePoseResult(pose, w, h)
                    isDetecting.set(false)
                    // جدولة الإطار التالي حسب وجود هدف
                    val delay = if (detectedBodies > 0) SCAN_FAST_MS else {
                        if (TargetStore.hasManualTarget) SCAN_MANUAL_MS else SCAN_SLOW_MS
                    }
                    workerHandler?.postDelayed(scanRunnable, delay)
                }
                ?.addOnFailureListener { e ->
                    capturedScaled.recycle()
                    Log.w(TAG, "Pose detection failed: ${e.message}")
                    isDetecting.set(false)
                    workerHandler?.postDelayed(scanRunnable, SCAN_SLOW_MS)
                }
                ?: run {
                    scaledBitmap.recycle()
                    isDetecting.set(false)
                    workerHandler?.postDelayed(scanRunnable, SCAN_SLOW_MS)
                }

        } catch (e: Exception) {
            frameBitmap?.recycle()
            scaledBitmap?.recycle()
            Log.w(TAG, "Frame error: ${e.message}")
            isDetecting.set(false)
            workerHandler?.postDelayed(scanRunnable, SCAN_SLOW_MS)
        } finally {
            image.close()
        }
    }

    private fun handlePoseResult(pose: Pose, frameW: Int, frameH: Int) {
        val goodLandmarks = pose.allPoseLandmarks.filter {
            it.inFrameLikelihood >= LANDMARK_CONFIDENCE
        }

        if (goodLandmarks.size < MIN_LANDMARKS) {
            detectedBodies = 0
            updateNotification(
                if (TargetStore.hasManualTarget)
                    "📍 لا جسم — يضغط الهدف اليدوي (${TargetStore.manualX.toInt()},${TargetStore.manualY.toInt()})"
                else "لم يُكتشف جسم بشري"
            )
            // ضغط الهدف اليدوي فوراً إن وُجد
            if (TargetStore.hasManualTarget) dispatchManualTap()
            return
        }

        // ── جسم بشري مكتشف → اضغط فوراً بدون تأخير ─────────────────────
        detectedBodies = 1

        val xs = goodLandmarks.map { it.position.x / SCALE_FACTOR }
        val ys = goodLandmarks.map { it.position.y / SCALE_FACTOR }
        val xMin = xs.min(); val xMax = xs.max()
        val yMin = ys.min(); val yMax = ys.max()

        val padX = (xMax - xMin) * 0.10f
        val padY = (yMax - yMin) * 0.10f
        val box = floatArrayOf(
            (xMin - padX).coerceAtLeast(0f),
            (yMin - padY).coerceAtLeast(0f),
            (xMax + padX).coerceAtMost(frameW.toFloat()),
            (yMax + padY).coerceAtMost(frameH.toFloat())
        )

        val smartCenter = getSmartTargetPoint(pose, box)
        val confidence  = (goodLandmarks.size * 100f / 33f).toInt()

        updateNotification("🎯 جسم مكتشف ✓  ثقة: $confidence%  — يضغط الآن")
        Log.i(TAG, "Body → center=(${smartCenter.x.toInt()},${smartCenter.y.toInt()}) conf=$confidence%")

        // ضغط فوري بدون أي تأكيد مزدوج
        dispatchTap(box, frameW, frameH)
    }

    private fun getSmartTargetPoint(pose: Pose, box: FloatArray): PointF {
        // مركز الجسم بالضبط (منتصف الـ bounding box)
        return PointF(
            (box[0] + box[2]) / 2f,
            (box[1] + box[3]) / 2f
        )
    }

    // ── ضغط الجسم المكتشف ────────────────────────────────────────────────
    private fun dispatchTap(box: FloatArray, frameW: Int, frameH: Int) {
        UiAutomationService.instance?.tapBestTarget(
            rawBoxes     = listOf(box),
            screenWidth  = frameW,
            screenHeight = frameH,
            offsetState  = offsetState,
            isPressing   = true
        ) ?: Log.w(TAG, "UiAutomationService not ready")
    }

    // ── ضغط الهدف اليدوي ─────────────────────────────────────────────────
    private fun dispatchManualTap() {
        val x = TargetStore.manualX
        val y = TargetStore.manualY
        FloatingTapIndicator.instance?.moveTo(x, y)
        UiAutomationService.instance?.tap(x, y) ?: Log.w(TAG, "UiAutomationService not ready")
    }

    private fun startWorkerThread() {
        workerThread = HandlerThread("PoseDetectWorker", android.os.Process.THREAD_PRIORITY_BACKGROUND)
            .apply { start() }
        workerHandler = Handler(workerThread!!.looper)
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { Log.w(TAG, "Projection stopped"); stopSelf() }
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }

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
