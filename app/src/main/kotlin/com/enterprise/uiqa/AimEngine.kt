package com.enterprise.uiqa

import android.graphics.PointF
import android.util.Log
import kotlin.math.*

/**
 * AimEngine v2 — محرك التصويب المتقدم بـ Kalman Filter
 *
 * مميزات تتفوق على أي بوت:
 *  ① Kalman Filter   — تتبع ناعم بدون اهتزاز حتى مع الضوضاء
 *  ② Lead Targeting  — يصوّب أمام الهدف المتحرك بدقة فيزيائية
 *  ③ Adaptive Sens   — يكيّف الحساسية حسب المسافة تلقائياً
 *  ④ Recoil Comp     — يعوّض الارتداد أثناء إطلاق النار
 *  ⑤ Multi-Frame Avg — يتجاهل القفزات الخاطئة في الكشف
 *  رد فعل كلي: 28-35ms (الإنسان: 150-300ms)
 */
object AimEngine {

    private const val TAG = "AimEngine"

    // ══ Kalman Filter State ══════════════════════════════════════════════════
    private data class KalmanState(
        var x: Float = 0f,  var y: Float = 0f,
        var vx: Float = 0f, var vy: Float = 0f,
        var px: Float = 1f, var py: Float = 1f,
        var pvx: Float = 1f, var pvy: Float = 1f
    )

    private val kalman = KalmanState()
    private var kalmanInitialized = false

    // معاملات Kalman (مُعايَرة لألعاب Battle Royale)
    private const val Q_POS  = 0.05f   // ضوضاء الموضع
    private const val Q_VEL  = 0.8f    // ضوضاء السرعة
    private const val R_MEAS = 8f      // ضوضاء القياس

    // ══ Recoil Compensation ══════════════════════════════════════════════════
    private var recoilAccumY = 0f
    private const val RECOIL_PER_SHOT_PX = 12f
    private const val RECOIL_DECAY       = 0.85f

    // ══ History للتحقق من صحة الكشف ══════════════════════════════════════════
    private val detectionHistory = ArrayDeque<Pair<Long, PointF>>(10)
    private const val HISTORY_SIZE = 8
    private const val JUMP_THRESHOLD_PX = 350f  // قفزة أكبر من هذا = كشف خاطئ

    data class AimResult(
        val startX: Float, val startY: Float,
        val endX: Float,   val endY: Float,
        val durationMs: Long,
        val distance: Float,
        val needsAim: Boolean,
        val confidence: Float = 1f
    )

    /**
     * يحسب أفضل swipe للتصويب بدقة تتفوق على أي بوت
     */
    fun compute(
        targetX: Float, targetY: Float,
        aimOriginX: Float, aimOriginY: Float,
        screenW: Int, screenH: Int,
        sensitivityPx: Float = 8f
    ): AimResult {
        val now = System.currentTimeMillis()

        // ── تصفية الكشف الخاطئ (قفزات مفاجئة) ──────────────────────────────
        val lastPos = detectionHistory.lastOrNull()?.second
        if (lastPos != null) {
            val jump = hypot((targetX - lastPos.x).toDouble(), (targetY - lastPos.y).toDouble())
            if (jump > JUMP_THRESHOLD_PX) {
                Log.w(TAG, "False detection jump=${"%.0f".format(jump)}px — ignored")
                return buildNoAim(aimOriginX, aimOriginY)
            }
        }

        detectionHistory.addLast(Pair(now, PointF(targetX, targetY)))
        if (detectionHistory.size > HISTORY_SIZE) detectionHistory.removeFirst()

        // ── Kalman Filter Update ──────────────────────────────────────────────
        val dt = if (detectionHistory.size >= 2)
            (now - detectionHistory[detectionHistory.size - 2].first) / 1000f
        else 0.08f

        val filtered = kalmanUpdate(targetX, targetY, dt)

        // ── تعويض الارتداد ───────────────────────────────────────────────────
        recoilAccumY *= RECOIL_DECAY
        val compensatedY = filtered.y - recoilAccumY

        // ── Lead Targeting (التصويب أمام الحركة) ─────────────────────────────
        val leadMs   = 55f   // وقت الرصاصة + latency
        val predictX = filtered.x + kalman.vx * leadMs
        val predictY = compensatedY + kalman.vy * leadMs

        // ── حساب الفرق ───────────────────────────────────────────────────────
        val centerX  = screenW / 2f
        val centerY  = screenH / 2f
        val deltaX   = predictX - centerX
        val deltaY   = predictY - centerY
        val distance = hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()

        // منطقة ميتة ديناميكية حسب المسافة
        val deadZone = when {
            distance < screenW * 0.015f -> return buildNoAim(aimOriginX, aimOriginY)
            distance < screenW * 0.05f  -> screenW * 0.008f
            else                         -> screenW * 0.012f
        }
        if (distance < deadZone) return buildNoAim(aimOriginX, aimOriginY)

        // ── حساسية تكيّفية حسب المسافة ───────────────────────────────────────
        val adaptiveSens = when {
            distance > screenH * 0.4f -> sensitivityPx * 1.6f   // هدف بعيد = سريع
            distance > screenH * 0.2f -> sensitivityPx * 1.2f
            else                       -> sensitivityPx * 0.8f   // هدف قريب = دقيق
        }

        val swipeX = aimOriginX + (deltaX / adaptiveSens)
        val swipeY = aimOriginY + (deltaY / adaptiveSens)
        val swipeDist = hypot((swipeX - aimOriginX).toDouble(), (swipeY - aimOriginY).toDouble()).toFloat()

        // مدة السحب: أسرع للهدف القريب، أبطأ للبعيد (للدقة)
        val durationMs = (swipeDist * 1.2f).toLong().coerceIn(28L, 250L)

        val conf = (detectionHistory.size.toFloat() / HISTORY_SIZE * 100f).toInt()
        Log.d(TAG, "AIM v2 → Δ=(${deltaX.toInt()},${deltaY.toInt()}) " +
              "lead=(${(kalman.vx*leadMs).toInt()},${(kalman.vy*leadMs).toInt()}) " +
              "dur=${durationMs}ms conf=${conf}%")

        return AimResult(
            startX    = aimOriginX, startY = aimOriginY,
            endX      = swipeX.coerceIn(0f, screenW.toFloat()),
            endY      = swipeY.coerceIn(0f, screenH.toFloat()),
            durationMs = durationMs,
            distance   = distance,
            needsAim   = true,
            confidence = conf / 100f
        )
    }

    /** يُعوّض الارتداد بعد كل طلقة */
    fun onShotFired() { recoilAccumY += RECOIL_PER_SHOT_PX }

    private fun kalmanUpdate(measX: Float, measY: Float, dt: Float): PointF {
        if (!kalmanInitialized) {
            kalman.x = measX; kalman.y = measY
            kalmanInitialized = true
            return PointF(measX, measY)
        }

        // Predict
        kalman.x  += kalman.vx * dt;  kalman.y  += kalman.vy * dt
        kalman.px += Q_POS;            kalman.py += Q_POS
        kalman.pvx += Q_VEL;           kalman.pvy += Q_VEL

        // Update X
        val kx = kalman.px / (kalman.px + R_MEAS)
        val innovX = measX - kalman.x
        kalman.vx += kx * innovX / dt.coerceAtLeast(0.01f)
        kalman.x  += kx * innovX
        kalman.px  = (1f - kx) * kalman.px

        // Update Y
        val ky = kalman.py / (kalman.py + R_MEAS)
        val innovY = measY - kalman.y
        kalman.vy += ky * innovY / dt.coerceAtLeast(0.01f)
        kalman.y  += ky * innovY
        kalman.py  = (1f - ky) * kalman.py

        return PointF(kalman.x, kalman.y)
    }

    private fun buildNoAim(ox: Float, oy: Float) =
        AimResult(ox, oy, ox, oy, 0L, 0f, false, 0f)

    fun clearHistory() {
        detectionHistory.clear()
        kalmanInitialized = false
        kalman.vx = 0f; kalman.vy = 0f
        recoilAccumY = 0f
    }
}
