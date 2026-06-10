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
import kotlin.math.abs

/**
 * ScreenCaptureService v1.0.5 — اكتشاف الجسم البشري الكامل
 *
 * يستخدم ML Kit Pose Detection للتعرف على الجسم كاملاً (33 نقطة تشريحية)
 * بدون إنترنت، ثم يمرر الإحداثيات لـ DataProcessingUnit وUiAutomationService.
 *
 * ضمانات الأمان:
 *  • AtomicBoolean يمنع تشغيل كشفين في نفس الوقت
 *  • تصغير الإطار 50% قبل الكشف لتسريع ML Kit
 *  • تحرير Bitmap فوري بعد انتهاء كل عملية
 *  • الكشف على BACKGROUND thread لا يلمس الـ UI thread
 *  • معدل فحص تكيّفي: يسرع عند وجود هدف، يبطئ عند غيابه
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG              = "ScreenCaptureService"
        private const val NOTIF_CHANNEL_ID = "screen_capture_channel"
        private const val NOTIF_ID         = 1001

        // ── معدل الفحص التكيّفي ──────────────────────────────────────────
        private const val SCAN_FAST_MS    = 300L    // عند اكتشاف هدف
        private const val SCAN_SLOW_MS    = 1200L   // عند عدم وجود هدف
        private const val SCALE_FACTOR    = 0.5f    // تصغير 50% قبل ML Kit

        // ── حدود جودة الكشف ──────────────────────────────────────────────
        private const val LANDMARK_CONFIDENCE = 0.55f  // حد الثقة لكل نقطة
        private const val MIN_LANDMARKS        = 5      // حد أدنى للنقاط المكتشفة

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile var isRunning    = false
        @Volatile var detectedBodies = 0
    }

    // ── حالة الخدمة ───────────────────────────────────────────────────────
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay?   = null
    private var imageReader: ImageReader?          = null
    private var workerThread: HandlerThread?       = null
    private var workerHandler: Handler?            = null
    private var poseDetector: PoseDetector?        = null
    private val isDetecting   = AtomicBoolean(false)
    private var scanIntervalMs = SCAN_SLOW_MS

    private val offsetState = DataProcessingUnit.DynamicOffsetState(
        stepPx = 2.0f, maxOffsetPx = 35f, decayFactor = 0.04f
    )

    // ── متتبع الاستقرار: يُرسل ضغطة فقط إذا تأكد الهدف 2 إطارات متتالية ──
    private var lastBodyCenter: PointF? = null
    private var confirmCount   = 0
    private val CONFIRM_NEEDED = 2

    // =========================================================================
    // دورة الحياة
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("جاهز — في انتظار تفعيل المسح"))
        initPoseDetector()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == -1 || resultData == null) { stopSelf(); return START_NOT_STICKY }

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(projectionCallback, null)

        startWorkerThread()
        setupImageReader()
        Log.i(TAG, "Body detection started — fast=${SCAN_FAST_MS}ms / slow=${SCAN_SLOW_MS}ms")
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

    // =========================================================================
    // إعداد ML Kit Pose Detector
    // =========================================================================

    private fun initPoseDetector() {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        poseDetector = PoseDetection.getClient(options)
        Log.i(TAG, "ML Kit PoseDetector initialized (STREAM_MODE, on-device)")
    }

    // =========================================================================
    // إعداد VirtualDisplay
    // =========================================================================

    private fun setupImageReader() {
        val m = resources.displayMetrics
        val w = m.widthPixels / 2     // نصف الدقة — أسرع وأأمن
        val h = m.heightPixels / 2
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "UIQACapture", w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, workerHandler
        )
        workerHandler?.postDelayed(scanRunnable, SCAN_SLOW_MS)
    }

    // =========================================================================
    // Runnable — تكيّفي + آمن
    // =========================================================================

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (!isDetecting.compareAndSet(false, true)) {
                // الكشف السابق لم ينته — تخطّ هذه الدورة
                workerHandler?.postDelayed(this, SCAN_SLOW_MS)
                return
            }
            processNextFrame()
            // الجدولة التالية تتم داخل callback الكشف
        }
    }

    // =========================================================================
    // معالجة الإطار → ML Kit Pose Detection
    // =========================================================================

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

            // تصغير 50% لتسريع ML Kit
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
                    val delay = if (detectedBodies > 0) SCAN_FAST_MS else SCAN_SLOW_MS
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

    // =========================================================================
    // تحليل نتيجة Pose Detection
    // =========================================================================

    private fun handlePoseResult(pose: Pose, frameW: Int, frameH: Int) {
        // فلتر النقاط عالية الثقة فقط
        val goodLandmarks = pose.allPoseLandmarks.filter {
            it.inFrameLikelihood >= LANDMARK_CONFIDENCE
        }

        if (goodLandmarks.size < MIN_LANDMARKS) {
            detectedBodies = 0
            confirmCount   = 0
            lastBodyCenter = null
            updateNotification("لم يُكتشف جسم بشري")
            return
        }

        detectedBodies = 1

        // حساب Bounding Box من أقصى إحداثيات النقاط المكتشفة
        val xs = goodLandmarks.map { it.position.x / SCALE_FACTOR }
        val ys = goodLandmarks.map { it.position.y / SCALE_FACTOR }
        val xMin = xs.min(); val xMax = xs.max()
        val yMin = ys.min(); val yMax = ys.max()

        // توسيع الصندوق بهامش 10% لشمول حواف الجسم
        val padX = (xMax - xMin) * 0.10f
        val padY = (yMax - yMin) * 0.10f
        val box = floatArrayOf(
            (xMin - padX).coerceAtLeast(0f),
            (yMin - padY).coerceAtLeast(0f),
            (xMax + padX).coerceAtMost(frameW.toFloat()),
            (yMax + padY).coerceAtMost(frameH.toFloat())
        )

        // نقطة الاستهداف الذكية: مركز الجسم العلوي (منتصف الكتفين إن وُجدا، وإلا مركز الصندوق)
        val smartCenter = getSmartTargetPoint(pose, box)

        // نظام التأكيد المزدوج — يُرسل ضغطة بعد إطارين متتاليين للتأكد من الهدف
        val prev = lastBodyCenter
        if (prev != null && abs(smartCenter.x - prev.x) < 80f && abs(smartCenter.y - prev.y) < 80f) {
            confirmCount++
        } else {
            confirmCount = 1
        }
        lastBodyCenter = smartCenter

        val confidence = (goodLandmarks.size * 100f / 33f).toInt()
        updateNotification("جسم مكتشف ✓  ثقة: $confidence%  نقاط: ${goodLandmarks.size}/33")
        Log.i(TAG, "Body at box=(${xMin.toInt()},${yMin.toInt()},${xMax.toInt()},${yMax.toInt()}) " +
                   "center=(${smartCenter.x.toInt()},${smartCenter.y.toInt()}) " +
                   "confidence=$confidence% confirm=$confirmCount")

        if (confirmCount >= CONFIRM_NEEDED) {
            dispatchTap(box, frameW, frameH)
        }
    }

    /**
     * يختار نقطة الاستهداف الأذكى:
     * 1. منتصف الكتفين إن كانا مكتشفَين بثقة عالية
     * 2. وإلا مركز الصدر (منتصف الصندوق أفقياً، الثلث العلوي عمودياً)
     */
    private fun getSmartTargetPoint(pose: Pose, box: FloatArray): PointF {
        val lShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)

        return if (lShoulder != null && rShoulder != null &&
            lShoulder.inFrameLikelihood >= LANDMARK_CONFIDENCE &&
            rShoulder.inFrameLikelihood >= LANDMARK_CONFIDENCE) {
            PointF(
                ((lShoulder.position.x + rShoulder.position.x) / 2f) / SCALE_FACTOR,
                ((lShoulder.position.y + rShoulder.position.y) / 2f) / SCALE_FACTOR
            )
        } else {
            // مركز الثلث العلوي من الصندوق (منطقة الصدر/الرأس)
            PointF(
                (box[0] + box[2]) / 2f,
                box[1] + (box[3] - box[1]) * 0.30f
            )
        }
    }

    // =========================================================================
    // إرسال الضغطة عبر DPU + UiAutomationService
    // =========================================================================

    private fun dispatchTap(box: FloatArray, frameW: Int, frameH: Int) {
        UiAutomationService.instance?.tapBestTarget(
            rawBoxes     = listOf(box),
            screenWidth  = frameW,
            screenHeight = frameH,
            offsetState  = offsetState,
            isPressing   = true
        ) ?: Log.w(TAG, "UiAutomationService not ready")
    }

    // =========================================================================
    // مساعدات
    // =========================================================================

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
