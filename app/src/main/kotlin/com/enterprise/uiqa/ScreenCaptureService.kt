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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ScreenCaptureService — النسخة الآمنة (v1.0.4)
 *
 * إصلاحات الأمان:
 *  • تصغير الإطار 4× قبل المطابقة (يُقلّل العمليات 16 مرة)
 *  • حد أدنى للمسح 1500ms — لا يبدأ مسح جديد قبل انتهاء السابق
 *  • مفتاح AtomicBoolean يمنع تشغيل مسحين في نفس الوقت
 *  • تحرير فوري للـ Bitmap بعد كل إطار
 *  • توقف تلقائي إذا استغرق المسح أكثر من 800ms
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG              = "ScreenCaptureService"
        private const val NOTIF_CHANNEL_ID = "screen_capture_channel"
        private const val NOTIF_ID         = 1001
        private const val MATCH_THRESHOLD  = 0.85f

        /** أبطأ مسح — لا يُضغط الـ CPU */
        private const val SCAN_INTERVAL_MS = 1500L

        /** الحد الأقصى لوقت المسح — يُلغى إذا تجاوزه */
        private const val MAX_SCAN_MS = 800L

        /** نسبة التصغير — يُقلّل العمليات 16× */
        private const val SCALE_FACTOR = 0.25f

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile var isRunning = false
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay?   = null
    private var imageReader: ImageReader?          = null
    private var workerThread: HandlerThread?       = null
    private var workerHandler: Handler?            = null
    private var templateBitmap: Bitmap?            = null
    private val isScanning = AtomicBoolean(false)   // يمنع تشغيل مسحين معاً

    private val offsetState = DataProcessingUnit.DynamicOffsetState(
        stepPx = 2.5f, maxOffsetPx = 40f, decayFactor = 0.05f
    )

    // =========================================================================
    // دورة الحياة
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("جاهز — لم يبدأ المسح بعد"))
        loadTemplate()
        isRunning = true
        Log.i(TAG, "ScreenCaptureService (safe) created")
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
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        workerHandler?.removeCallbacks(scanRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        workerThread?.quitSafely()
        templateBitmap?.recycle()
        Log.i(TAG, "ScreenCaptureService destroyed safely")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =========================================================================
    // إعداد الالتقاط
    // =========================================================================

    private fun setupImageReader() {
        val m = resources.displayMetrics
        // نلتقط بدقة نصف الشاشة فقط — يقلل استهلاك الذاكرة
        val w = m.widthPixels  / 2
        val h = m.heightPixels / 2
        val density = m.densityDpi

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "UIQACapture", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, workerHandler
        )
        Log.i(TAG, "VirtualDisplay: ${w}×${h}  scan every ${SCAN_INTERVAL_MS}ms")
        workerHandler?.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
    }

    // =========================================================================
    // Runnable آمن — يمنع التشغيل المتزامن
    // =========================================================================

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            // إذا لا يزال المسح السابق يعمل، تخطّ هذه الدورة
            if (isScanning.compareAndSet(false, true)) {
                try {
                    val start = System.currentTimeMillis()
                    scanFrame()
                    val elapsed = System.currentTimeMillis() - start
                    Log.v(TAG, "Scan done in ${elapsed}ms")
                } catch (e: Exception) {
                    Log.w(TAG, "Scan error: ${e.message}")
                } finally {
                    isScanning.set(false)
                }
            } else {
                Log.v(TAG, "Scan skipped — previous still running")
            }

            if (isRunning) workerHandler?.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    // =========================================================================
    // معالجة الإطار — مصغّر آمن
    // =========================================================================

    private fun scanFrame() {
        val tmpl = templateBitmap ?: return
        val image = imageReader?.acquireLatestImage() ?: return
        var frame: Bitmap? = null
        var scaled: Bitmap? = null
        var tmplScaled: Bitmap? = null

        try {
            val plane  = image.planes[0]
            val buf    = plane.buffer
            val stride = plane.rowStride / plane.pixelStride
            val w      = image.width
            val h      = image.height

            frame = Bitmap.createBitmap(stride, h, Bitmap.Config.ARGB_8888)
            frame.copyPixelsFromBuffer(buf)

            // تصغير الإطار والقالب بنسبة SCALE_FACTOR
            val sw = (w * SCALE_FACTOR).toInt().coerceAtLeast(1)
            val sh = (h * SCALE_FACTOR).toInt().coerceAtLeast(1)
            scaled = Bitmap.createScaledBitmap(frame, sw, sh, false)

            val tw = (tmpl.width  * SCALE_FACTOR).toInt().coerceAtLeast(1)
            val th = (tmpl.height * SCALE_FACTOR).toInt().coerceAtLeast(1)
            tmplScaled = Bitmap.createScaledBitmap(tmpl, tw, th, false)

            if (tw >= sw || th >= sh) return

            val result = templateMatchSafe(scaled, tmplScaled)
            if (result != null && result.score >= MATCH_THRESHOLD) {
                // تحويل الإحداثيات للدقة الأصلية
                val origX = result.x / SCALE_FACTOR
                val origY = result.y / SCALE_FACTOR
                val origW = tmpl.width.toFloat()
                val origH = tmpl.height.toFloat()
                Log.i(TAG, "MATCH! score=${result.score} → orig=(${origX.toInt()},${origY.toInt()})")
                onMatchFound(floatArrayOf(origX, origY, origX + origW, origY + origH),
                             (w / SCALE_FACTOR).toInt(), (h / SCALE_FACTOR).toInt())
            }
        } finally {
            image.close()
            frame?.recycle()
            scaled?.recycle()
            tmplScaled?.recycle()
        }
    }

    // =========================================================================
    // SSD Normalized — على الإطار المصغّر
    // =========================================================================

    data class MatchResult(val x: Int, val y: Int, val score: Float)

    private fun templateMatchSafe(frame: Bitmap, tmpl: Bitmap): MatchResult? {
        val fw = frame.width; val fh = frame.height
        val tw = tmpl.width;  val th = tmpl.height
        if (tw >= fw || th >= fh) return null

        val deadline = System.currentTimeMillis() + MAX_SCAN_MS
        val tmplPx   = IntArray(tw * th)
        tmpl.getPixels(tmplPx, 0, tw, 0, 0, tw, th)

        var bestScore = -1f; var bestX = 0; var bestY = 0
        val patch = IntArray(tw * th)
        val step  = 2   // خطوة 2px على الإطار المصغّر (= 8px على الأصلي)

        var y = 0
        while (y <= fh - th) {
            // تحقق من الوقت في كل سطر
            if (System.currentTimeMillis() > deadline) {
                Log.w(TAG, "Scan timed out at y=$y — aborting safely")
                break
            }
            var x = 0
            while (x <= fw - tw) {
                frame.getPixels(patch, 0, tw, x, y, tw, th)
                val s = ssdScore(tmplPx, patch)
                if (s > bestScore) { bestScore = s; bestX = x; bestY = y }
                x += step
            }
            y += step
        }
        return if (bestScore > 0) MatchResult(bestX, bestY, bestScore) else null
    }

    private fun ssdScore(tmpl: IntArray, patch: IntArray): Float {
        var ssd = 0.0
        val max = tmpl.size * 3.0 * 255.0 * 255.0
        for (i in tmpl.indices) {
            val dr = ((tmpl[i] shr 16 and 0xFF) - (patch[i] shr 16 and 0xFF)).toDouble()
            val dg = ((tmpl[i] shr  8 and 0xFF) - (patch[i] shr  8 and 0xFF)).toDouble()
            val db = ((tmpl[i]        and 0xFF) - (patch[i]        and 0xFF)).toDouble()
            ssd += dr*dr + dg*dg + db*db
        }
        return (1.0 - ssd / max).toFloat().coerceIn(0f, 1f)
    }

    // =========================================================================
    // عند التطابق
    // =========================================================================

    private fun onMatchFound(box: FloatArray, frameW: Int, frameH: Int) {
        UiAutomationService.instance?.tapBestTarget(
            rawBoxes     = listOf(box),
            screenWidth  = frameW,
            screenHeight = frameH,
            offsetState  = offsetState,
            isPressing   = true
        ) ?: Log.w(TAG, "UiAutomationService not connected")
    }

    // =========================================================================
    // مساعدات
    // =========================================================================

    private fun loadTemplate() {
        try {
            assets.open("head_target.png").use {
                templateBitmap = BitmapFactory.decodeStream(it)
                Log.i(TAG, "Template: ${templateBitmap?.width}×${templateBitmap?.height}px")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Using fallback 20×20 template")
            templateBitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
                .also { it.eraseColor(0xFFFF0000.toInt()) }
        }
    }

    private fun startWorkerThread() {
        workerThread = HandlerThread("TemplateMatchWorker", android.os.Process.THREAD_PRIORITY_BACKGROUND)
            .apply { start() }
        workerHandler = Handler(workerThread!!.looper)
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { Log.w(TAG, "Projection stopped"); stopSelf() }
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(NOTIF_CHANNEL_ID, "UI QA Screen Capture",
            NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("UI QA — Template Matching")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true).build()
}
