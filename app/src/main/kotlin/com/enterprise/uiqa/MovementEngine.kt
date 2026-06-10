package com.enterprise.uiqa

import android.graphics.PointF
import android.util.Log
import kotlin.math.*
import kotlin.random.Random

/**
 * MovementEngine — محرك الحركة التلقائي
 * يتحكم في الـ joystick الأيسر بأنماط بشرية واقعية
 */
object MovementEngine {

    private const val TAG = "MovementEngine"

    enum class MoveState { IDLE, PATROL, ENGAGE, STRAFE, RETREAT }

    data class MoveAction(
        val joyStartX: Float, val joyStartY: Float,
        val joyEndX: Float,   val joyEndY: Float,
        val durationMs: Long,
        val state: MoveState
    )

    private var currentState = MoveState.PATROL
    private var strafeDir    = 1f  // +1 يمين، -1 يسار
    private var lastStateMs  = System.currentTimeMillis()

    /**
     * يحسب حركة الـ joystick التالية
     *
     * @param hasTarget     هل في هدف مكتشف؟
     * @param targetDist    المسافة للهدف بالبكسل
     * @param screenW/H     أبعاد الشاشة
     * @param joyCenterX/Y  مركز منطقة الـ joystick
     */
    fun compute(
        hasTarget: Boolean,
        targetDist: Float = 0f,
        screenW: Int, screenH: Int,
        joyCenterX: Float = screenW * 0.15f,
        joyCenterY: Float = screenH * 0.78f,
        joyRadius: Float  = screenW * 0.12f
    ): MoveAction {

        val now = System.currentTimeMillis()
        val stateAge = now - lastStateMs

        // ── اختيار الحالة ───────────────────────────────────────────────────
        currentState = when {
            hasTarget && targetDist < screenH * 0.3f -> MoveState.STRAFE   // قريب → تحرك جانبي
            hasTarget && targetDist < screenH * 0.6f -> MoveState.ENGAGE   // متوسط → تقدم
            hasTarget                                 -> MoveState.ENGAGE   // بعيد → تقدم
            else                                      -> MoveState.PATROL   // لا هدف → دورية
        }

        // ── تغيير اتجاه الـ strafe كل 600-1200ms ────────────────────────────
        if (currentState == MoveState.STRAFE && stateAge > Random.nextLong(600L, 1200L)) {
            strafeDir = -strafeDir
            lastStateMs = now
        }

        // ── حساب اتجاه الحركة ────────────────────────────────────────────────
        val (dx, dy, dur) = when (currentState) {
            MoveState.PATROL -> {
                // دورية عشوائية — يتغير كل 1-3 ثانية
                val angle = Random.nextDouble(0.0, 2 * PI).toFloat()
                val speed = Random.nextFloat() * 0.6f + 0.4f
                Triple(cos(angle) * speed, sin(angle) * speed,
                    Random.nextLong(800L, 2000L))
            }
            MoveState.ENGAGE -> {
                // تقدم للأمام مع انحراف بسيط
                val jitter = Random.nextFloat() * 0.2f - 0.1f
                Triple(jitter, -0.85f, Random.nextLong(300L, 600L))
            }
            MoveState.STRAFE -> {
                // حركة جانبية مع تقدم خفيف
                Triple(strafeDir * 0.9f, -0.3f, Random.nextLong(400L, 700L))
            }
            MoveState.RETREAT -> {
                Triple(0f, 0.9f, Random.nextLong(200L, 400L))
            }
            else -> Triple(0f, 0f, 0L)
        }

        val endX = (joyCenterX + dx * joyRadius).coerceIn(0f, screenW.toFloat())
        val endY = (joyCenterY + dy * joyRadius).coerceIn(0f, screenH.toFloat())

        Log.d(TAG, "move [${currentState.name}] → (${endX.toInt()},${endY.toInt()}) ${dur}ms")

        return MoveAction(joyCenterX, joyCenterY, endX, endY, dur, currentState)
    }

    fun reset() { currentState = MoveState.PATROL; lastStateMs = System.currentTimeMillis() }
}
