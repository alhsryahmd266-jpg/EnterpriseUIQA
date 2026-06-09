package com.enterprise.uiqa

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        updateStatus()
        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val enabled = isAccessibilityServiceEnabled()
        val tv = findViewById<TextView>(R.id.tvStatus)
        tv.text = if (enabled) "✓ الخدمة مفعّلة وجاهزة" else "✗ الخدمة غير مفعّلة — اضغط الزر لتفعيلها"
        tv.setTextColor(if (enabled) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/${UiAutomationService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return TextUtils.SimpleStringSplitter(':').also { it.setString(enabled) }
            .asSequence().any { it.equals(service, ignoreCase = true) }
    }
}
