package com.enterprise.uiqa

/**
 * TargetStore — يخزن نقطة الضغط اليدوية المحددة من المستخدم
 * يُشارَك بين MainActivity و ScreenCaptureService و FloatingTapIndicator
 */
object TargetStore {
    @Volatile var manualX: Float = -1f
    @Volatile var manualY: Float = -1f

    val hasManualTarget: Boolean get() = manualX >= 0f && manualY >= 0f

    fun set(x: Float, y: Float) { manualX = x; manualY = y }
    fun clear() { manualX = -1f; manualY = -1f }
}
