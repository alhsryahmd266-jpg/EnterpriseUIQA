package com.enterprise.uiqa

import android.graphics.PointF
import android.util.Log
import kotlin.math.*

/**
 * AimEngine — محرك التصويب التلقائي
 *
 * يحسب:
 * 1. swipe من مركز الشاشة لموقع الهدف
 * 2. سرعة السحب حسب المسافة
 * 3. تعويض الحركة (lead targeting) لو الهدف يتحرك
 *
 * بدون إنترنت — خوارزميات كلاسيكية فقط
 */
object AimEngine {

    private const val TAG = "AimEngine"

    // ── تاريخ مواضع الهدف لحساب السرعة ────────────────────────────────────
    private val positionHistory = ArrayDeque<Pair<Long, PointF>>(8)
    private const val HISTORY_SIZE = 6

    data class AimResult(
        val startX: Float, val startY: Float,   // نقطة البداية (مركز إبهام التصويب)
        val endX: Float,   val endY: Float,     // نقطة الهدف بعد تعويض الحركة
        val durationMs: Long,                    // مدة السحب
        val distance: Float,                     // المسافة بالبكسل
        val needsAim: Boolean                    // هل محتاج swipe أصلاً؟
    )

    /**
     * يحسب حركة التصويب المطلوبة
     *
     * @param targetX       موقع الهدف على الشاشة
     * @param targetY       موقع الهدف على الشاشة
     * @param aimOriginX    نقطة التصويب (مركز منطقة السحب في اللعبة)
     * @param aimOriginY    نقطة التصويب
     * @param screenW       عرض الشاشة
     * @param screenH       ارتفاع الشاشة
     * @param sensitivityPx كم بكسل swipe = كم درجة دوران (حسب حساسية اللعبة)
     */
    fun compute(
        targetX: Float, targetY: Float,
        aimOriginX: Float, aimOriginY: Float,
        screenW: Int, screenH: Int,
        sensitivityPx: Float = 8f
    ): AimResult {

        // ── تسجيل الموضع في التاريخ ────────────────────────────────────────
        val now = System.currentTimeMillis()
        positionHistory.addLast(Pair(now, PointF(targetX, targetY)))
        if (positionHistory.size > HISTORY_SIZE) positionHistory.removeFirst()

        // ── حساب سرعة الهدف (lead targeting) ────────────────────────────────
        val velocity = computeVelocity()

        // توقع موضع الهدف بعد 80ms (رد الفعل + swipe duration)
        val predictMs = 80f
        val predictedX = targetX + velocity.x * predictMs
        val predictedY = targetY + velocity.y * predictMs

        // ── حساب الفرق بين مركز الشاشة والهدف المتوقع ───────────────────────
        val centerX = screenW / 2f
        val centerY = screenH / 2f

        val deltaX = predictedX - centerX
        val deltaY = predictedY - centerY
        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)

        // لو الهدف قريب جداً من المركز مش محتاج swipe
        val threshold = screenW * 0.03f  // 3% من عرض الشاشة
        if (distance < threshold) {
            return AimResult(aimOriginX, aimOriginY, aimOriginX, aimOriginY, 0L, distance, false)
        }

        // ── تحويل الفرق لـ swipe بالبكسل ────────────────────────────────────
        // sensitivityPx = عدد البكسل swipe لكل بكسل فرق على الشاشة
        val swipeX = aimOriginX + (deltaX / sensitivityPx)
        val swipeY = aimOriginY + (deltaY / sensitivityPx)

        // ── حساب مدة السحب حسب المسافة (أسرع للمسافات القريبة) ──────────────
        val swipeDist = sqrt((swipeX - aimOriginX).pow(2) + (swipeY - aimOriginY).pow(2))
        val durationMs = (swipeDist * 1.5f).toLong().coerceIn(50L, 300L)

        Log.d(TAG, "aim → delta=(${deltaX.toInt()},${deltaY.toInt()}) " +
                "swipe=(${swipeX.toInt()},${swipeY.toInt()}) dur=${durationMs}ms " +
                "vel=(${velocity.x.toInt()},${velocity.y.toInt()})")

        return AimResult(
            startX = aimOriginX, startY = aimOriginY,
            endX   = swipeX.coerceIn(0f, screenW.toFloat()),
            endY   = swipeY.coerceIn(0f, screenH.toFloat()),
            durationMs = durationMs,
            distance   = distance,
            needsAim   = true
        )
    }

    /** حساب سرعة الهدف (px/ms) من آخر N مواضع */
    private fun computeVelocity(): PointF {
        if (positionHistory.size < 2) return PointF(0f, 0f)
        val oldest = positionHistory.first()
        val newest = positionHistory.last()
        val dt = (newest.first - oldest.first).toFloat()
        if (dt <= 0f) return PointF(0f, 0f)
        return PointF(
            (newest.second.x - oldest.second.x) / dt,
            (newest.second.y - oldest.second.y) / dt
        )
    }

    fun clearHistory() = positionHistory.clear()
}
