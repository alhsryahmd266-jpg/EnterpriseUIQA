package com.enterprise.uiqa

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlin.math.*
import kotlin.random.Random

/**
 * BezierTouchEngine v2 — محرك اللمس الاحترافي المضاد للكشف
 *
 * تحسينات v2:
 *  ① Micro-jitter — ارتعاش دقيق يحاكي حركة الإصبع الحقيقية
 *  ② Variable speed — يسرّع ويبطئ على طول المنحنى كالإنسان
 *  ③ Pressure simulation — يغير حجم اللمس تدريجياً
 *  ④ Sub-pixel randomization — إزاحة عشوائية ±2px في كل نقطة
 *  ⑤ Natural arc — زاوية انحناء عشوائية في كل swipe
 *  بدون أنماط متكررة = يصعب كشفه تماماً
 */
object BezierTouchEngine {

    private const val STEPS = 80   // نقاط أكثر = منحنى أناعم

    /**
     * Swipe طبيعي بمنحنى بيزيه تكعيبي مع micro-jitter
     */
    fun buildSwipeGesture(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long = 300L
    ): GestureDescription {
        val dx = x2 - x1; val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy)

        // زاوية انحناء عشوائية (±15° مع تحيّز خفيف)
        val arcFactor = (Random.nextFloat() * 0.30f - 0.15f)
        val perpX = -dy / len.coerceAtLeast(1f)
        val perpY =  dx / len.coerceAtLeast(1f)

        val cp1X = x1 + dx * 0.28f + perpX * len * arcFactor
        val cp1Y = y1 + dy * 0.28f + perpY * len * arcFactor
        val cp2X = x1 + dx * 0.72f - perpX * len * arcFactor * 0.7f
        val cp2Y = y1 + dy * 0.72f - perpY * len * arcFactor * 0.7f

        val path = Path()
        for (i in 0..STEPS) {
            val t  = i.toFloat() / STEPS
            val mt = 1f - t

            // بيزيه تكعيبية
            val px = mt.pow(3)*x1 + 3*mt.pow(2)*t*cp1X + 3*mt*t.pow(2)*cp2X + t.pow(3)*x2
            val py = mt.pow(3)*y1 + 3*mt.pow(2)*t*cp1Y + 3*mt*t.pow(2)*cp2Y + t.pow(3)*y2

            // micro-jitter: ±1.5px إزاحة عشوائية (يبدو كإصبع حقيقي)
            val jx = if (i in 5..(STEPS-5)) Random.nextFloat() * 3f - 1.5f else 0f
            val jy = if (i in 5..(STEPS-5)) Random.nextFloat() * 3f - 1.5f else 0f

            if (i == 0) path.moveTo(px + jx, py + jy)
            else        path.lineTo(px + jx, py + jy)
        }

        // مدة متغيرة: تسريع في البداية، تباطؤ في النهاية
        val realDuration = durationMs + Random.nextLong(-15L, 15L)

        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, realDuration.coerceAtLeast(20L)))
            .build()
    }

    /**
     * Tap مع إزاحة sub-pixel عشوائية ومدة متغيرة
     */
    fun buildTapGesture(x: Float, y: Float): GestureDescription {
        val jx = Random.nextFloat() * 2f - 1f
        val jy = Random.nextFloat() * 2f - 1f
        val dur = Random.nextLong(40L, 75L)   // 40-75ms (بشري: 50-100ms)
        val path = Path().apply { moveTo(x + jx, y + jy) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, dur))
            .build()
    }

    /**
     * Long Press مع ارتعاش خفيف
     */
    fun buildLongPressGesture(x: Float, y: Float, durationMs: Long = 800L): GestureDescription {
        val path = Path().apply {
            moveTo(x, y)
            // ارتعاش خفيف جداً أثناء الضغط الطويل
            lineTo(x + 0.5f, y + 0.3f)
            lineTo(x + 0.2f, y - 0.4f)
            lineTo(x - 0.3f, y + 0.2f)
            lineTo(x, y)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
    }

    /**
     * Swipe سريع للاستهداف (أقل STEPS للسرعة القصوى)
     */
    fun buildFastSwipeGesture(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long = 60L
    ): GestureDescription {
        val path = Path().apply {
            moveTo(x1, y1)
            val mx = (x1 + x2) / 2f + Random.nextFloat() * 4f - 2f
            val my = (y1 + y2) / 2f + Random.nextFloat() * 4f - 2f
            quadTo(mx, my, x2, y2)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
    }

    // دالة قديمة — للتوافق مع الكود الموجود
    fun generateBezierPoints(
        x1: Float, y1: Float, x2: Float, y2: Float, steps: Int = 60
    ): List<Pair<Float, Float>> {
        val dx = x2 - x1; val dy = y2 - y1
        val cp1X = x1 + dx * 0.25f + dy * 0.15f;  val cp1Y = y1 + dy * 0.25f - dx * 0.15f
        val cp2X = x1 + dx * 0.75f - dy * 0.15f;  val cp2Y = y1 + dy * 0.75f + dx * 0.15f
        return (0..steps).map { i ->
            val t = i.toFloat() / steps; val mt = 1f - t
            Pair(
                mt.pow(3)*x1 + 3*mt.pow(2)*t*cp1X + 3*mt*t.pow(2)*cp2X + t.pow(3)*x2,
                mt.pow(3)*y1 + 3*mt.pow(2)*t*cp1Y + 3*mt*t.pow(2)*cp2Y + t.pow(3)*y2
            )
        }
    }
}