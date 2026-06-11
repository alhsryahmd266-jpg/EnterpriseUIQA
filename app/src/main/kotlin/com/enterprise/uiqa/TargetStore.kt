package com.enterprise.uiqa

/**
 * TargetStore v2 — مخزن الإعدادات الاحترافي
 *
 * يضم إعدادات كل لعبة بذكاء:
 *  - PUBG Mobile / BGMI
 *  - Call of Duty Mobile
 *  - Free Fire
 *  - Custom
 */
object TargetStore {

    @Volatile var manualX: Float = -1f
    @Volatile var manualY: Float = -1f
    val hasManualTarget: Boolean get() = manualX >= 0f && manualY >= 0f
    fun set(x: Float, y: Float) { manualX = x; manualY = y }
    fun clear() { manualX = -1f; manualY = -1f }

    // ── إعدادات أساسية ────────────────────────────────────────────────────
    @Volatile var tapIntervalMs: Long   = 100L
    @Volatile var centerMargin: Float   = 0.45f
    @Volatile var targetPart: TargetPart = TargetPart.CHEST
    @Volatile var trackingMode: Boolean  = true
    @Volatile var isEnabled: Boolean     = true
    @Volatile var burstMode: Boolean     = true    // طلقة مزدوجة للضمان

    enum class TargetPart(val label: String) {
        HEAD("الرأس"),
        CHEST("الصدر"),
        CENTER("المركز"),
        HANDS("اليد")
    }

    // ── ملفات الألعاب ─────────────────────────────────────────────────────
    enum class GameProfile(
        val aimSens: Float,
        val fireXR: Float, val fireYR: Float,
        val aimXR: Float,  val aimYR: Float,
        val scanMs: Long,
        val label: String
    ) {
        PUBG(
            aimSens = 5.5f,
            fireXR = 0.830f, fireYR = 0.670f,
            aimXR  = 0.750f, aimYR  = 0.500f,
            scanMs = 50L,
            label  = "PUBG Mobile"
        ),
        COD(
            aimSens = 6.5f,
            fireXR = 0.845f, fireYR = 0.685f,
            aimXR  = 0.760f, aimYR  = 0.510f,
            scanMs = 45L,
            label  = "Call of Duty Mobile"
        ),
        FREE_FIRE(
            aimSens = 7.0f,
            fireXR = 0.820f, fireYR = 0.660f,
            aimXR  = 0.740f, aimYR  = 0.490f,
            scanMs = 55L,
            label  = "Free Fire"
        ),
        CUSTOM(
            aimSens = 6.0f,
            fireXR = 0.820f, fireYR = 0.680f,
            aimXR  = 0.750f, aimYR  = 0.500f,
            scanMs = 50L,
            label  = "Custom"
        )
    }

    @Volatile var activeProfile: GameProfile = GameProfile.PUBG

    fun applyProfile(profile: GameProfile) {
        activeProfile = profile
        ScreenCaptureService.aimSensitivity   = profile.aimSens
        ScreenCaptureService.fireButtonXRatio = profile.fireXR
        ScreenCaptureService.fireButtonYRatio = profile.fireYR
        ScreenCaptureService.aimOriginXRatio  = profile.aimXR
        ScreenCaptureService.aimOriginYRatio  = profile.aimYR
    }

    // ── إحصائيات ──────────────────────────────────────────────────────────
    @Volatile var tapCount: Long      = 0L
    @Volatile var detectCount: Long   = 0L
    @Volatile var sessionStartMs: Long = System.currentTimeMillis()

    fun resetStats() {
        tapCount = 0L; detectCount = 0L
        sessionStartMs = System.currentTimeMillis()
    }

    fun sessionSeconds(): Long = (System.currentTimeMillis() - sessionStartMs) / 1000L
    fun accuracy(): Float = if (detectCount == 0L) 0f else tapCount * 100f / detectCount
}