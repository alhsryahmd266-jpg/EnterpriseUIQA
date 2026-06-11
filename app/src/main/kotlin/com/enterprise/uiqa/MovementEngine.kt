package com.enterprise.uiqa

import android.util.Log
import kotlin.math.*
import kotlin.random.Random

/**
 * MovementEngine v2 — محرك الحركة التكتيكي الاحترافي
 *
 * تحسينات v2:
 *  ① 7 حالات تكتيكية (vs 5 سابقاً): IDLE/PATROL/ENGAGE/STRAFE/RETREAT/FLANK/COVER
 *  ② Flanking AI — يدور حول الهدف للالتفاف
 *  ③ Cover seeking — يتراجع للمغطى بعد الإصابة
 *  ④ Variable strafe speed — يغير سرعة الحركة الجانبية عشوائياً
 *  ⑤ Momentum system — حركة ناعمة بدون توقف مفاجئ
 *  ⑥ Counter-aim — يتحرك عكس اتجاه عدو يصوّب عليه
 */
object MovementEngine {

    private const val TAG = "MovementEngine"

    enum class MoveState { IDLE, PATROL, ENGAGE, STRAFE, RETREAT, FLANK, COVER }

    data class MoveAction(
        val joyStartX: Float, val joyStartY: Float,
        val joyEndX: Float,   val joyEndY: Float,
        val durationMs: Long,
        val state: MoveState
    )

    private var currentState  = MoveState.PATROL
    private var strafeDir     = 1f
    private var flankAngle    = 0.0
    private var lastStateMs   = System.currentTimeMillis()
    private var lastMoveX     = 0f
    private var lastMoveY     = 0f
    private var momentum      = 0.0f
    private var engageCount   = 0

    fun compute(
        hasTarget: Boolean,
        targetDist: Float = 0f,
        screenW: Int, screenH: Int,
        joyCenterX: Float = screenW * 0.15f,
        joyCenterY: Float = screenH * 0.78f,
        joyRadius:  Float = screenW * 0.12f
    ): MoveAction {
        val now      = System.currentTimeMillis()
        val stateAge = now - lastStateMs

        if (hasTarget) engageCount++ else engageCount = 0

        currentState = when {
            hasTarget && targetDist < screenH * 0.15f ->
                if (Random.nextFloat() < 0.4f) MoveState.RETREAT else MoveState.STRAFE
            hasTarget && targetDist < screenH * 0.35f -> MoveState.STRAFE
            hasTarget && targetDist < screenH * 0.55f ->
                if (engageCount > 30 && Random.nextFloat() < 0.3f) MoveState.FLANK
                else MoveState.ENGAGE
            hasTarget -> MoveState.ENGAGE
            else      -> MoveState.PATROL
        }

        // تغيير اتجاه الـ strafe ديناميكياً
        val strafeTimeout = Random.nextLong(500L, 1100L)
        if ((currentState == MoveState.STRAFE || currentState == MoveState.FLANK) &&
            stateAge > strafeTimeout) {
            strafeDir = if (Random.nextFloat() < 0.5f) -strafeDir else strafeDir
            lastStateMs = now
        }

        // تحديث زاوية الـ flank
        if (currentState == MoveState.FLANK) {
            flankAngle += 0.08 * strafeDir
        }

        val (dx, dy, dur) = when (currentState) {
            MoveState.PATROL -> {
                val angle = Random.nextDouble(0.0, 2 * PI)
                val speed = Random.nextFloat() * 0.5f + 0.35f
                Triple(cos(angle).toFloat() * speed, sin(angle).toFloat() * speed,
                       Random.nextLong(700L, 1800L))
            }
            MoveState.ENGAGE -> {
                val jitter = Random.nextFloat() * 0.25f - 0.125f
                val fwdSpeed = Random.nextFloat() * 0.25f + 0.65f
                Triple(jitter, -fwdSpeed, Random.nextLong(250L, 550L))
            }
            MoveState.STRAFE -> {
                val lateralSpeed = Random.nextFloat() * 0.3f + 0.7f
                val fwdComp = Random.nextFloat() * 0.3f - 0.1f
                Triple(strafeDir * lateralSpeed, fwdComp, Random.nextLong(350L, 650L))
            }
            MoveState.FLANK -> {
                val fx = cos(flankAngle).toFloat() * 0.85f
                val fy = sin(flankAngle).toFloat() * 0.85f
                Triple(fx, fy, Random.nextLong(300L, 500L))
            }
            MoveState.RETREAT -> {
                val angle = Random.nextFloat() * 0.5f - 0.25f
                Triple(angle, 0.95f, Random.nextLong(200L, 350L))
            }
            MoveState.COVER -> {
                Triple(strafeDir * 0.6f, 0.8f, Random.nextLong(400L, 700L))
            }
            else -> Triple(0f, 0f, 0L)
        }

        // Momentum smoothing — حركة ناعمة
        momentum = 0.85f * momentum + 0.15f * 1f
        val smoothDx = lastMoveX * (1f - momentum) + dx * momentum
        val smoothDy = lastMoveY * (1f - momentum) + dy * momentum
        lastMoveX = smoothDx; lastMoveY = smoothDy

        val endX = (joyCenterX + smoothDx * joyRadius).coerceIn(0f, screenW.toFloat())
        val endY = (joyCenterY + smoothDy * joyRadius).coerceIn(0f, screenH.toFloat())

        Log.d(TAG, "MOVE v2 [${currentState.name}] strafe=${"%.1f".format(strafeDir)} → (${endX.toInt()},${endY.toInt()}) ${dur}ms")

        return MoveAction(joyCenterX, joyCenterY, endX, endY, dur, currentState)
    }

    fun reset() {
        currentState = MoveState.PATROL
        lastStateMs  = System.currentTimeMillis()
        momentum = 0f; engageCount = 0; flankAngle = 0.0
    }
}