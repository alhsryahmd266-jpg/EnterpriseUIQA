package com.enterprise.uiqa

import android.graphics.PointF
import android.util.Log
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.*

/**
 * GazeEngine — محرك التحكم بالعين
 * يحول اتجاه نظرة المريض إلى حركة كاميرا اللعبة
 *
 * الاستخدام: المريض يحرك عيونه يمين/يسار/فوق/تحت
 *            → النظام يترجمها لـ swipe على الشاشة
 */
object GazeEngine {

    private const val TAG = "GazeEngine"

    data class GazeResult(
        val dx: Float,          // انحراف أفقي  (-1.0 يسار .. +1.0 يمين)
        val dy: Float,          // انحراف رأسي  (-1.0 فوق  .. +1.0 تحت)
        val confidence: Float,  // مستوى الثقة 0-1
        val isBlinking: Boolean // رمشة = إطلاق نار
    )

    // حساسية الحركة (قابلة للضبط لكل مريض)
    var sensitivityH = 1.4f
    var sensitivityV = 1.2f
    var blinkThresholdMs = 250L   // رمشة أقل من 250ms = إطلاق
    var dwellTimeMs     = 600L    // تثبيت النظرة 600ms = تأكيد

    private var leftEyeOpenHistory  = ArrayDeque<Pair<Long, Float>>(10)
    private var rightEyeOpenHistory = ArrayDeque<Pair<Long, Float>>(10)

    /**
     * يحلل وجه المريض ويستخرج اتجاه النظرة
     * @param face نتيجة ML Kit Face Detection
     * @param frameW/H أبعاد الإطار
     */
    fun analyze(face: Face, frameW: Int, frameH: Int): GazeResult {
        val now = System.currentTimeMillis()

        // ── 1. اتجاه الرأس (Euler angles) كبديل للـ gaze ─────────────────
        //    (ML Kit لا يعطي gaze vector مباشرة — نستخدم head pose)
        val yaw   = face.headEulerAngleY   // يمين/يسار  ±90°
        val pitch = face.headEulerAngleX   // فوق/تحت    ±45°

        val dx = (yaw   / 30f).coerceIn(-1f, 1f) * sensitivityH
        val dy = (pitch / 20f).coerceIn(-1f, 1f) * sensitivityV

        // ── 2. كشف الرمشة ─────────────────────────────────────────────────
        val leftOpen  = face.leftEyeOpenProbability  ?: 1f
        val rightOpen = face.rightEyeOpenProbability ?: 1f

        leftEyeOpenHistory.addLast(Pair(now, leftOpen))
        rightEyeOpenHistory.addLast(Pair(now, rightOpen))
        // احتفظ بآخر 500ms فقط
        while (leftEyeOpenHistory.isNotEmpty() &&
               now - leftEyeOpenHistory.first().first > 500L)
            leftEyeOpenHistory.removeFirst()

        val isBlink = detectBlink(leftEyeOpenHistory) || detectBlink(rightEyeOpenHistory)

        val conf = face.trackingId?.let { 1f } ?: 0.7f

        Log.d(TAG, "gaze dx=${"%.2f".format(dx)} dy=${"%.2f".format(dy)} blink=$isBlink")

        return GazeResult(dx, dy, conf, isBlink)
    }

    // رمشة = العين كانت مفتوحة ثم أغلقت بسرعة
    private fun detectBlink(history: ArrayDeque<Pair<Long, Float>>): Boolean {
        if (history.size < 3) return false
        val recent = history.takeLast(5)
        val minOpen = recent.minOf { it.second }
        val maxOpen = recent.maxOf { it.second }
        return maxOpen > 0.7f && minOpen < 0.3f
    }

    fun reset() {
        leftEyeOpenHistory.clear(); rightEyeOpenHistory.clear()
    }
}
