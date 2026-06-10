package com.enterprise.uiqa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
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

/**
 * ScreenCaptureService — خدمة التقاط الشاشة ومطابقة القوالب البصرية
 *
 * تعمل كـ Foreground Service تستخدم MediaProjection لسحب إطارات الشاشة
 * كـ Bitmap حية، ثم تبحث عن head_target.png من assets عبر خوارزمية مقارنة
 * مباشرة للبكسل (SSD Normalized). عند تطابق > 0.85 تُمرّر الإحداثيات فوراً
 * إلى UiAutomationService.tapBestTarget().
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG              = "ScreenCaptureService"
        private const val NOTIF_CHANNEL_ID = "screen_capture_channel"
        private const val NOTIF_ID         = 1001
        private const val MATCH_THRESHOLD  = 0.85f   // حد التطابق
        private const val SCAN_INTERVAL_MS = 120L     // فاصل المسح بالمللي ثانية

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile var isRunning = false
    }

    // ── MediaProjection ──────────────────────────────────────────────────
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay?   = null
    private var imageReader: ImageReader?          = null

    // ── Background Thread ────────────────────────────────────────────────
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler?      = null

    // ── Template (القالب المحمّل من assets) ─────────────────────────────
    private var templateBitmap: Bitmap? = null

    // ── DPU offset state ────────────────────────────────────────────────
    private val offsetState = DataProcessingUnit.DynamicOffsetState(
        stepPx      = 2.5f,
        maxOffsetPx = 40f,
        decayFactor = 0.05f
    )
    @Volatile private var isPressing = false

    // =========================================================================
    // دورة حياة الخدمة
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        loadTemplate()
        isRunning = true
        Log.i(TAG, "ScreenCaptureService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == -1 || resultData == null) {
            Log.e(TAG, "Missing MediaProjection result — stopping")
            stopSelf(); return START_NOT_STICKY
        }

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(projectionCallback, null)

        startWorkerThread()
        setupImageReader()
        Log.i(TAG, "MediaProjection started — scanning every ${SCAN_INTERVAL_MS}ms")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopCapture()
        workerThread?.quitSafely()
        Log.i(TAG, "ScreenCaptureService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =========================================================================
    // إعداد ImageReader + VirtualDisplay
    // =========================================================================

    private fun setupImageReader() {
        val metrics = resources.displayMetrics
        val width   = metrics.widthPixels
        val height  = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "UIQACapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, workerHandler
        )

        // جدوَل أول مسح
        workerHandler?.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
    }

    // =========================================================================
    // Runnable المسح الدوري (يعمل على worker thread — لا يجمّد الـ UI)
    // =========================================================================

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            try {
                scanFrame()
            } catch (e: Exception) {
                Log.w(TAG, "Scan error: ${e.message}")
            }
            workerHandler?.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    private fun scanFrame() {
        val tmpl = templateBitmap ?: return
        val image = imageReader?.acquireLatestImage() ?: return

        try {
            val plane  = image.planes[0]
            val buffer = plane.buffer
            val width  = image.width
            val height = image.height
            val stride = plane.rowStride / plane.pixelStride

            val frameBitmap = Bitmap.createBitmap(stride, height, Bitmap.Config.ARGB_8888)
            frameBitmap.copyPixelsFromBuffer(buffer)

            // ── Template Matching (SSD Normalized) ──────────────────────
            val result = templateMatch(frameBitmap, tmpl)

            if (result != null && result.score >= MATCH_THRESHOLD) {
                Log.i(TAG, "MATCH FOUND! score=${result.score} at (${result.x},${result.y})")
                onMatchFound(result, width, height)
            }

            frameBitmap.recycle()
        } finally {
            image.close()
        }
    }

    // =========================================================================
    // خوارزمية مطابقة القوالب — SSD Normalized
    // مقارنة مباشرة للبكسلات بدون AI
    // =========================================================================

    data class MatchResult(val x: Int, val y: Int, val score: Float)

    private fun templateMatch(frame: Bitmap, tmpl: Bitmap): MatchResult? {
        val fw = frame.width;  val fh = frame.height
        val tw = tmpl.width;   val th = tmpl.height

        if (tw >= fw || th >= fh) return null

        // استخرج بكسلات القالب مرة واحدة
        val tmplPixels = IntArray(tw * th)
        tmpl.getPixels(tmplPixels, 0, tw, 0, 0, tw, th)

        var bestScore = -1f
        var bestX = 0; var bestY = 0

        // نمسح كل نافذة ممكنة بخطوة 4px لتسريع المسح
        val step = 4
        val framePatch = IntArray(tw * th)

        var y = 0
        while (y <= fh - th) {
            var x = 0
            while (x <= fw - tw) {
                frame.getPixels(framePatch, 0, tw, x, y, tw, th)
                val score = computeSsdScore(tmplPixels, framePatch)
                if (score > bestScore) {
                    bestScore = score; bestX = x; bestY = y
                }
                x += step
            }
            y += step
        }

        return if (bestScore > 0) MatchResult(bestX, bestY, bestScore) else null
    }

    /**
     * يحسب نقاط التطابق بين قالبين من البكسلات.
     * يعيد قيمة بين 0.0 (لا تطابق) و 1.0 (تطابق تام).
     * الصيغة: 1 − (SSD / MAX_SSD)
     */
    private fun computeSsdScore(tmpl: IntArray, patch: IntArray): Float {
        var ssd = 0.0
        val maxSsd = tmpl.size * 3.0 * 255.0 * 255.0   // أقصى SSD ممكن (3 قنوات)
        for (i in tmpl.indices) {
            val tr = (tmpl[i] shr 16) and 0xFF
            val tg = (tmpl[i] shr  8) and 0xFF
            val tb =  tmpl[i]         and 0xFF
            val pr = (patch[i] shr 16) and 0xFF
            val pg = (patch[i] shr  8) and 0xFF
            val pb =  patch[i]         and 0xFF
            val dr = (tr - pr).toDouble(); ssd += dr * dr
            val dg = (tg - pg).toDouble(); ssd += dg * dg
            val db = (tb - pb).toDouble(); ssd += db * db
        }
        return (1.0 - (ssd / maxSsd)).toFloat().coerceIn(0f, 1f)
    }

    // =========================================================================
    // عند العثور على تطابق → DPU → tapBestTarget
    // =========================================================================

    private fun onMatchFound(result: MatchResult, frameW: Int, frameH: Int) {
        val tmpl = templateBitmap ?: return
        // بناء صندوق محيط من إحداثيات التطابق
        val rawBox = floatArrayOf(
            result.x.toFloat(),
            result.y.toFloat(),
            (result.x + tmpl.width).toFloat(),
            (result.y + tmpl.height).toFloat()
        )

        isPressing = true
        UiAutomationService.instance?.tapBestTarget(
            rawBoxes      = listOf(rawBox),
            screenWidth   = frameW,
            screenHeight  = frameH,
            offsetState   = offsetState,
            isPressing    = isPressing
        ) ?: Log.w(TAG, "UiAutomationService not connected")
    }

    // =========================================================================
    // تحميل القالب من assets
    // =========================================================================

    private fun loadTemplate() {
        try {
            assets.open("head_target.png").use { stream ->
                templateBitmap = BitmapFactory.decodeStream(stream)
                Log.i(TAG, "Template loaded: ${templateBitmap?.width}×${templateBitmap?.height}px")
            }
        } catch (e: Exception) {
            Log.w(TAG, "head_target.png not found in assets — using fallback 20×20 red square")
            templateBitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(0xFFFF0000.toInt())
            }
        }
    }

    // =========================================================================
    // مساعدات
    // =========================================================================

    private fun startWorkerThread() {
        workerThread = HandlerThread("TemplateMatchWorker").apply { start() }
        workerHandler = Handler(workerThread!!.looper)
    }

    private fun stopCapture() {
        workerHandler?.removeCallbacks(scanRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay    = null
        imageReader       = null
        mediaProjection   = null
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped externally")
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "UI QA Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Template matching background scan" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("UI QA — Template Matching")
            .setContentText("جاري مسح الشاشة بحثاً عن الهدف…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
}
