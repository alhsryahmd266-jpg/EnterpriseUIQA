package com.enterprise.uiqa

import android.util.Log
import kotlin.math.*

/**
 * GazeEngine — محرك التحكم بالعين والرأس
 * يحول حركة الرأس إلى حركة كاميرا اللعبة
 *
 * يعمل مع ML Kit Face Detection — يُمرَّر له القيم الخام
 * eulerY (yaw) و eulerX (pitch) و eyeOpenProb مباشرةً
 * بدون اعتماد على كلاس Face مباشرة.
 */
object GazeEngine {

    private const val TAG = "GazeEngine"

    data class GazeResult(
        val dx: Float,          // أفقي  -1.0 يسار .. +1.0 يمين
        val dy: Float,          // رأسي  -1.0 فوق  .. +1.0 تحت
        val confidence: Float,
        val isBlinking: Boolean
    )

    var sensitivityH    = 1.4f
    var sensitivityV    = 1.2f

    private val eyeOpenHistory = ArrayDeque<Pair<Long, Float>>(20)

    /**
     * @param eulerY  زاوية الرأس يمين/يسار  (degrees, من Face.headEulerAngleY)
     * @param eulerX  زاوية الرأس فوق/تحت    (degrees, من Face.headEulerAngleX)
     * @param leftEyeOpen   احتمال أن العين اليسرى مفتوحة  0..1
     * @param rightEyeOpen  احتمال أن العين اليمنى مفتوحة 0..1
     * @param trackingId    معرّف الوجه من ML Kit (null = ثقة أقل)
     */
    fun analyze(
        eulerY: Float, eulerX: Float,
        leftEyeOpen: Float = 1f, rightEyeOpen: Float = 1f,
        trackingId: Int? = null
    ): GazeResult {
        val now = System.currentTimeMillis()

        val dx = (eulerY / 30f).coerceIn(-1f, 1f) * sensitivityH
        val dy = (eulerX / 20f).coerceIn(-1f, 1f) * sensitivityV

        val avgOpen = (leftEyeOpen + rightEyeOpen) / 2f
        eyeOpenHistory.addLast(Pair(now, avgOpen))
        while (eyeOpenHistory.isNotEmpty() && now - eyeOpenHistory.first().first > 500L)
            eyeOpenHistory.removeFirst()

        val isBlink = detectBlink()
        val conf = if (trackingId != null) 1f else 0.7f

        Log.d(TAG, "gaze dx=${"%.2f".format(dx)} dy=${"%.2f".format(dy)} blink=$isBlink")
        return GazeResult(dx, dy, conf, isBlink)
    }

    private fun detectBlink(): Boolean {
        if (eyeOpenHistory.size < 3) return false
        val recent  = eyeOpenHistory.takeLast(5)
        val minOpen = recent.minOf { it.second }
        val maxOpen = recent.maxOf { it.second }
        return maxOpen > 0.7f && minOpen < 0.3f
    }

    fun reset() { eyeOpenHistory.clear() }
}
