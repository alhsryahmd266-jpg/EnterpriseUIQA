package com.enterprise.uiqa

import android.content.Intent
import android.graphics.PointF
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // حالة إزاحة مستمرة عبر جلسة الضغط الحالية
    private val offsetState = DataProcessingUnit.DynamicOffsetState(
        stepPx       = 2.5f,
        maxOffsetPx  = 40f,
        decayFactor  = 0.05f
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        updateStatus()

        // زر تفعيل خدمة الوصول
        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // زر تشغيل اختبار DPU التوضيحي
        findViewById<Button>(R.id.btnRunDpu).setOnClickListener {
            runDpuDemo()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    // =========================================================================
    // اختبار توضيحي للـ DataProcessingUnit
    // =========================================================================

    private fun runDpuDemo() {
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels

        // صناديق محيطة تجريبية [Xmin, Ymin, Xmax, Ymax]
        val rawBoxes = listOf(
            floatArrayOf(50f,  100f, 250f, 300f),   // صندوق صغير — بعيد
            floatArrayOf(400f, 700f, 900f, 1100f),  // صندوق كبير — قريب من المركز
            floatArrayOf(200f, 500f, 600f,  900f),  // صندوق متوسط
            floatArrayOf(sw * 0.3f, sh * 0.4f, sw * 0.7f, sh * 0.6f)  // صندوق مركزي
        )

        // ① Proximity Sort
        val boxes  = DataProcessingUnit.fromRawBoxes(rawBoxes)
        val sorted = DataProcessingUnit.sortByProximity(boxes, sw, sh)

        val sortLog = StringBuilder("— ترتيب الأولويات:\n")
        sorted.forEach { s ->
            sortLog.append(
                "#${s.rank} [${s.priority}]  " +
                "مركز=(${s.box.center.x.toInt()},${s.box.center.y.toInt()})  " +
                "مسافة=${s.distancePx.toInt()}px  " +
                "مساحة=${s.box.area.toInt()}px²\n"
            )
        }

        // ② Dynamic Offset — 5 دورات متتالية على أفضل صندوق
        offsetState.reset()
        val best = sorted.first().box
        val offsetLog = StringBuilder("— تعويض الانحراف (5 دورات):\n")
        repeat(5) {
            val pt = DataProcessingUnit.computeAdjustedTouchPoint(best, offsetState, isPressing = true)
            offsetLog.append(
                "دورة ${pt.cycle}: raw.Y=${pt.raw.y.toInt()}  " +
                "adj.Y=${pt.adjusted.y.toInt()}  " +
                "Δ=${pt.offsetApplied.toInt()}  " +
                "Σ=${pt.totalOffset.toInt()}\n"
            )
        }

        // عرض النتيجة في tvDpu
        val result = "الشاشة: ${sw}×${sh}px\n\n$sortLog\n$offsetLog"
        findViewById<TextView>(R.id.tvDpu).text = result
    }

    // =========================================================================
    // حالة الخدمة
    // =========================================================================

    private fun updateStatus() {
        val enabled = isAccessibilityServiceEnabled()
        val tv = findViewById<TextView>(R.id.tvStatus)
        tv.text = if (enabled) "✓ الخدمة مفعّلة وجاهزة" else "✗ الخدمة غير مفعّلة — اضغط الزر لتفعيلها"
        tv.setTextColor(if (enabled) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${UiAutomationService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':')
            .any { it.equals(serviceName, ignoreCase = true) }
    }
}
