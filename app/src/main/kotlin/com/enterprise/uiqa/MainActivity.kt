package com.enterprise.uiqa

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private val PROJECTION_REQUEST_CODE = 100
    private lateinit var projMgr: MediaProjectionManager
    private val offsetState = DataProcessingUnit.DynamicOffsetState(2.0f, 35f, 0.04f)
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvManualTarget: TextView
    private lateinit var tvDpu: TextView
    private lateinit var btnStartCapture: MaterialButton
    private lateinit var btnStopCapture: MaterialButton
    private lateinit var btnOpenAccessibility: MaterialButton
    private lateinit var btnPickTarget: MaterialButton
    private lateinit var btnClearTarget: MaterialButton
    private lateinit var btnRunDpu: MaterialButton

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

        projMgr     = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        tvStatus     = findViewById(R.id.tvStatus)
        tvManualTarget = findViewById(R.id.tvManualTarget)
        tvDpu        = findViewById(R.id.tvDpu)
        btnStartCapture   = findViewById(R.id.btnStartCapture)
        btnStopCapture    = findViewById(R.id.btnStopCapture)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        btnPickTarget     = findViewById(R.id.btnPickTarget)
        btnClearTarget    = findViewById(R.id.btnClearTarget)
        btnRunDpu         = findViewById(R.id.btnRunDpu)

        updateStatus()
        refreshManualTargetLabel()

        btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // ── تحديد نقطة الضغط اليدوية ──────────────────────────────────
        btnPickTarget.setOnClickListener { showTargetPicker() }

        btnClearTarget.setOnClickListener {
            TargetStore.clear()
            FloatingTapIndicator.instance?.dismiss()
            FloatingTapIndicator.instance?.show()
            refreshManualTargetLabel()
        }

        // ── المسح التلقائي ─────────────────────────────────────────────
        btnStartCapture.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                setStatus("⚠ فعّل خدمة الوصول أولاً", 0xFFF0A500.toInt())
                return@setOnClickListener
            }
            startActivityForResult(projMgr.createScreenCaptureIntent(), PROJECTION_REQUEST_CODE)
        }

        btnStopCapture.setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            setStatus("⏹ الكشف متوقف", 0xFF8B949E.toInt())
            updateCaptureButtons(false)
            uiHandler.removeCallbacks(statusUpdater)
            tvDpu.text = "—"
        }

        btnRunDpu.setOnClickListener { runDpuDemo() }
    }

    // ── overlay شفاف لاختيار نقطة الضغط ──────────────────────────────────
    private fun showTargetPicker() {
        val root = window.decorView as ViewGroup
        val hint = TextView(this).apply {
            text = "المس المكان الذي تريد الضغط عليه"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 120, 0, 0)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        val overlay = object : View(this) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                canvas.drawColor(Color.parseColor("#CC000000"))
            }
        }.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setWillNotDraw(false)
        }

        val container = android.widget.FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(overlay)
            addView(hint)
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val x = event.rawX
                    val y = event.rawY
                    TargetStore.set(x, y)
                    FloatingTapIndicator.instance?.moveTo(x, y)
                    refreshManualTargetLabel()
                    root.removeView(v)
                }
                true
            }
        }
        root.addView(container)
    }

    private fun refreshManualTargetLabel() {
        if (TargetStore.hasManualTarget) {
            tvManualTarget.text =
                "✅ الهدف اليدوي: X=${TargetStore.manualX.toInt()}  Y=${TargetStore.manualY.toInt()}\n" +
                "سيضغط هنا عند عدم اكتشاف جسم بشري"
            tvManualTarget.setTextColor(Color.parseColor("#3FB950"))
        } else {
            tvManualTarget.text = "لم يُحدَّد هدف بعد"
            tvManualTarget.setTextColor(Color.parseColor("#8B949E"))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        refreshManualTargetLabel()
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
        val hasTarget = TargetStore.hasManualTarget
        tvDpu.text = when {
            bodies > 0 -> "✅ جسم بشري مكتشف — جاري الاستهداف والضغط"
            hasTarget  -> "📍 لا يوجد جسم — يضغط على النقطة اليدوية (${TargetStore.manualX.toInt()}, ${TargetStore.manualY.toInt()})"
            else       -> "🔍 يبحث عن جسم بشري في الشاشة…"
        }
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
        tvDpu.text = sb.toString()
    }

    private fun updateStatus() {
        val enabled = isAccessibilityServiceEnabled()
        setStatus(
            if (enabled) "✓ خدمة الوصول مفعّلة" else "✗ فعّل خدمة الوصول أولاً",
            if (enabled) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
        )
        updateCaptureButtons(ScreenCaptureService.isRunning)
    }

    private fun setStatus(msg: String, color: Int) {
        tvStatus.text = msg
        tvStatus.setTextColor(color)
    }

    private fun updateCaptureButtons(running: Boolean) {
        btnStartCapture.isEnabled = !running
        btnStopCapture.isEnabled  =  running
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val name = "${packageName}/${UiAutomationService::class.java.canonicalName}"
        return (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').any { it.equals(name, ignoreCase = true) }
    }
}
