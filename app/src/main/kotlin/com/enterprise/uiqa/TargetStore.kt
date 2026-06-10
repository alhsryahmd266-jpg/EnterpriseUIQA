package com.enterprise.uiqa

/**
 * TargetStore — مخزن الإعدادات والإحصائيات المشترك
 */
object TargetStore {

    // ── موضع الزرار اليدوي ────────────────────────────────────────────────
    @Volatile var manualX: Float = -1f
    @Volatile var manualY: Float = -1f
    val hasManualTarget: Boolean get() = manualX >= 0f && manualY >= 0f
    fun set(x: Float, y: Float) { manualX = x; manualY = y }
    fun clear() { manualX = -1f; manualY = -1f }

    // ── إعدادات ───────────────────────────────────────────────────────────
    @Volatile var tapIntervalMs: Long  = 100L      // سرعة الضغط
    @Volatile var centerMargin: Float  = 0.475f    // حجم منطقة التصويب
    @Volatile var targetPart: TargetPart = TargetPart.CHEST
    @Volatile var trackingMode: Boolean  = true    // الزرار يتبع الجسم
    @Volatile var isEnabled: Boolean     = true    // تشغيل/إيقاف (زر الصوت)

    enum class TargetPart(val label: String) {
        HEAD("الرأس"),
        CHEST("الصدر"),
        CENTER("المركز"),
        HANDS("اليد")
    }

    // ── إحصائيات ──────────────────────────────────────────────────────────
    @Volatile var tapCount: Long     = 0L
    @Volatile var detectCount: Long  = 0L
    @Volatile var sessionStartMs: Long = System.currentTimeMillis()

    fun resetStats() {
        tapCount = 0L; detectCount = 0L
        sessionStartMs = System.currentTimeMillis()
    }

    fun sessionSeconds(): Long = (System.currentTimeMillis() - sessionStartMs) / 1000L
}
