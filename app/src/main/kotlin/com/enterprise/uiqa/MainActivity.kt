package com.enterprise.uiqa

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val PROJECTION_REQUEST_CODE = 100
    private lateinit var projMgr: MediaProjectionManager
    private val offsetState = DataProcessingUnit.DynamicOffsetState(2.0f, 35f, 0.04f)
    private val uiHandler   = Handler(Looper.getMainLooper())

    // يُحدّث الواجهة كل ثانية لعرض حالة الكشف
    private val statusUpdater = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                refreshLiveStatus()
                uiHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        projMgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        updateStatus()

        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnStartCapture).setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                setStatus("⚠ فعّل خدمة الوصول أولاً", 0xFFF0A500.toInt())
                return@setOnClickListener
            }
            startActivityForResult(projMgr.createScreenCaptureIntent(), PROJECTION_REQUEST_CODE)
        }

        findViewById<Button>(R.id.btnStopCapture).setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            setStatus("⏹ الكشف متوقف", 0xFF8B949E.toInt())
            updateCaptureButtons(false)
            uiHandler.removeCallbacks(statusUpdater)
            findViewById<TextView>(R.id.tvDpu).text = "—"
        }

        findViewById<Button>(R.id.btnRunDpu).setOnClickListener { runDpuDemo() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        if (ScreenCaptureService.isRunning) uiHandler.post(statusUpdater)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(statusUpdater)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PROJECTION_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(intent)
            setStatus("📡 جاري الكشف عن الجسم البشري…", 0xFF2E7D32.toInt())
            updateCaptureButtons(true)
            uiHandler.postDelayed(statusUpdater, 1000L)
        } else {
            setStatus("✗ لم يُمنح إذن تسجيل الشاشة", 0xFFC62828.toInt())
        }
    }

    private fun refreshLiveStatus() {
        val bodies = ScreenCaptureService.detectedBodies
        val tv = findViewById<TextView>(R.id.tvDpu)
        tv.text = if (bodies > 0)
            "✅ جسم بشري مكتشف — جاري الاستهداف والضغط"
        else
            "🔍 يبحث عن جسم بشري في الشاشة…"
    }

    private fun runDpuDemo() {
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val rawBoxes = listOf(
            floatArrayOf(50f, 100f, 250f, 800f),
            floatArrayOf(400f, 200f, 900f, 1600f),
            floatArrayOf(sw * 0.2f, sh * 0.1f, sw * 0.8f, sh * 0.9f)
        )
        val boxes  = DataProcessingUnit.fromRawBoxes(rawBoxes)
        val sorted = DataProcessingUnit.sortByProximity(boxes, sw, sh)
        offsetState.reset()
        val sb = StringBuilder("الشاشة: ${sw}×${sh}px\n\n— ترتيب الأهداف:\n")
        sorted.forEach { s ->
            sb.append("#${s.rank} [${s.priority}]  " +
                "مسافة=${s.distancePx.toInt()}px  " +
                "مساحة=${s.box.area.toInt()}px²\n")
        }
        sb.append("\n— تعويض الانحراف (5 دورات):\n")
        repeat(5) {
            val pt = DataProcessingUnit.computeAdjustedTouchPoint(sorted.first().box, offsetState, true)
            sb.append("دورة ${pt.cycle}: rawY=${pt.raw.y.toInt()}  adjY=${pt.adjusted.y.toInt()}  Δ=${pt.offsetApplied.toInt()}\n")
        }
        findViewById<TextView>(R.id.tvDpu).text = sb.toString()
    }

    private fun updateStatus() {
        val enabled = isAccessibilityServiceEnabled()
        setStatus(
            if (enabled) "✓ خدمة الوصول مفعّلة" else "✗ فعّل خدمة الوصول أولاً",
            if (enabled) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
        )
        updateCaptureButtons(ScreenCaptureService.isRunning)
    }

    private fun setStatus(msg: String, color: Int) =
        findViewById<TextView>(R.id.tvStatus).also { it.text = msg; it.setTextColor(color) }

    private fun updateCaptureButtons(running: Boolean) {
        findViewById<Button>(R.id.btnStartCapture).isEnabled = !running
        findViewById<Button>(R.id.btnStopCapture).isEnabled  =  running
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val name = "${packageName}/${UiAutomationService::class.java.canonicalName}"
        return (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').any { it.equals(name, ignoreCase = true) }
    }
}
