package com.enterprise.uiqa

import android.util.Log

/**
 * ResearchLogger — مسجّل البيانات للبحث الأكاديمي
 * يسجل كل حدث مع timestamp لتحليل الأداء ومقارنته بالإنسان
 */
object ResearchLogger {

    private const val TAG = "ResearchLogger"

    data class Session(
        val startMs: Long = System.currentTimeMillis(),
        var detections: Int  = 0,
        var aimActions: Int  = 0,
        var shots: Int       = 0,
        var moves: Int       = 0,
        var reactionTimes: MutableList<Long> = mutableListOf(),
        var aimErrors: MutableList<Float>    = mutableListOf()
    )

    private var session = Session()
    private var lastDetectMs = 0L

    fun onDetection() {
        session.detections++
        lastDetectMs = System.currentTimeMillis()
    }

    fun onAim(errorPx: Float) {
        session.aimActions++
        session.aimErrors.add(errorPx)
    }

    fun onShot() {
        session.shots++
        if (lastDetectMs > 0L) {
            val rt = System.currentTimeMillis() - lastDetectMs
            session.reactionTimes.add(rt)
            Log.i(TAG, "reaction_time=${rt}ms")
        }
    }

    fun onMove() { session.moves++ }

    fun report(): String {
        val elapsed = (System.currentTimeMillis() - session.startMs) / 1000L
        val avgRT   = if (session.reactionTimes.isEmpty()) 0L
                      else session.reactionTimes.average().toLong()
        val avgErr  = if (session.aimErrors.isEmpty()) 0f
                      else session.aimErrors.average().toFloat()
        val spm     = if (elapsed > 0) session.shots * 60f / elapsed else 0f

        return buildString {
            appendLine("══ Research Session Report ══")
            appendLine("مدة الجلسة     : ${elapsed}s")
            appendLine("كشف أهداف     : ${session.detections}")
            appendLine("تصويب          : ${session.aimActions}")
            appendLine("إطلاق نار      : ${session.shots} (${spm.toInt()}/min)")
            appendLine("حركة           : ${session.moves}")
            appendLine("متوسط رد الفعل : ${avgRT}ms  [إنسان: 150-300ms]")
            appendLine("متوسط خطأ التصويب: ${avgErr.toInt()}px")
            appendLine("═════════════════════════════")
        }
    }

    fun reset() { session = Session() }
}
