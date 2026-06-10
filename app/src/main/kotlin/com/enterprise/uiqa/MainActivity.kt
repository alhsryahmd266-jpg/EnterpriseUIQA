package com.enterprise.uiqa

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val PROJECTION_REQUEST_CODE = 100
    private lateinit var projMgr: MediaProjectionManager
    private val offsetState = DataProcessingUnit.DynamicOffsetState(2.5f, 40f, 0.05f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        projMgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        updateStatus()

        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // ── زر بدء التقاط الشاشة + Template Matching ──
        findViewById<Button>(R.id.btnStartCapture).setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                setStatus("⚠ فعّل خدمة الوصول أولاً", 0xFFC62828.toInt()); return@setOnClickListener
            }
            startActivityForResult(projMgr.createScreenCaptureIntent(), PROJECTION_REQUEST_CODE)
        }

        // ── زر إيقاف الخدمة ──
        findViewById<Button>(R.id.btnStopCapture).setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            setStatus("⏹ التقاط الشاشة متوقف", 0xFF8B949E.toInt())
            updateCaptureButtons(running = false)
        }

        // ── زر DPU تجريبي ──
        findViewById<Button>(R.id.btnRunDpu).setOnClickListener { runDpuDemo() }
    }

    override fun onResume() { super.onResume(); updateStatus() }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PROJECTION_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(intent)
            setStatus("📡 جاري مسح الشاشة بحثاً عن الهدف…", 0xFF2E7D32.toInt())
            updateCaptureButtons(running = true)
        } else {
            setStatus("✗ لم يُمنح إذن تسجيل الشاشة", 0xFFC62828.toInt())
        }
    }

    // ── DPU Demo ──────────────────────────────────────────────────────────

    private fun runDpuDemo() {
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val rawBoxes = listOf(
            floatArrayOf(50f,  100f, 250f, 300f),
            floatArrayOf(400f, 700f, 900f, 1100f),
            floatArrayOf(200f, 500f, 600f,  900f),
            floatArrayOf(sw * 0.3f, sh * 0.4f, sw * 0.7f, sh * 0.6f)
        )
        val boxes  = DataProcessingUnit.fromRawBoxes(rawBoxes)
        val sorted = DataProcessingUnit.sortByProximity(boxes, sw, sh)
        offsetState.reset()
        val sb = StringBuilder("الشاشة: ${sw}×${sh}px\n\n— ترتيب الأولويات:\n")
        sorted.forEach { s -> sb.append("#${s.rank} [${s.priority}] مسافة=${s.distancePx.toInt()}px مساحة=${s.box.area.toInt()}px²\n") }
        sb.append("\n— تعويض الانحراف (5 دورات):\n")
        repeat(5) {
            val pt = DataProcessingUnit.computeAdjustedTouchPoint(sorted.first().box, offsetState, true)
            sb.append("دورة ${pt.cycle}: rawY=${pt.raw.y.toInt()} adjY=${pt.adjusted.y.toInt()} Δ=${pt.offsetApplied.toInt()}\n")
        }
        findViewById<TextView>(R.id.tvDpu).text = sb.toString()
    }

    // ── مساعدات ───────────────────────────────────────────────────────────

    private fun updateStatus() {
        val enabled = isAccessibilityServiceEnabled()
        setStatus(
            if (enabled) "✓ خدمة الوصول مفعّلة وجاهزة" else "✗ فعّل خدمة الوصول أولاً",
            if (enabled) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
        )
        updateCaptureButtons(running = ScreenCaptureService.isRunning)
    }

    private fun setStatus(msg: String, color: Int) {
        val tv = findViewById<TextView>(R.id.tvStatus)
        tv.text = msg; tv.setTextColor(color)
    }

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
