package com.enterprise.uiqa

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlin.math.pow

/**
 * BezierTouchEngine — محرك محاكاة اللمس بمنحنيات بيزيه
 * يحاكي حركة الإصبع الطبيعية عند السحب بين نقطتين
 */
object BezierTouchEngine {

    /**
     * عدد النقاط البينية المولَّدة على طول المنحنى
     */
    private const val INTERPOLATION_STEPS = 60

    /**
     * مدة الحركة الافتراضية بالمللي ثانية
     */
    private const val DEFAULT_DURATION_MS = 300L

    /**
     * يولّد نقاط بيزيه التكعيبية (Cubic Bezier) بين نقطتين
     * مع إضافة نقطتَي تحكم عشوائيتَين لمحاكاة الحركة البشرية
     *
     * @param x1 إحداثي X لنقطة البداية
     * @param y1 إحداثي Y لنقطة البداية
     * @param x2 إحداثي X لنقطة النهاية
     * @param y2 إحداثي Y لنقطة النهاية
     * @param steps عدد النقاط البينية (افتراضي 60)
     * @return قائمة من أزواج (x, y) تمثّل المسار المنحني
     */
    fun generateBezierPoints(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        steps: Int = INTERPOLATION_STEPS
    ): List<Pair<Float, Float>> {

        val dx = x2 - x1
        val dy = y2 - y1

        // نقاط تحكم تضيف انحناء طبيعياً يعكس حركة اليد
        val controlX1 = x1 + dx * 0.25f + (dy * 0.15f)
        val controlY1 = y1 + dy * 0.25f - (dx * 0.15f)
        val controlX2 = x1 + dx * 0.75f - (dy * 0.15f)
        val controlY2 = y1 + dy * 0.75f + (dx * 0.15f)

        val points = mutableListOf<Pair<Float, Float>>()

        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val mt = 1f - t

            // معادلة بيزيه التكعيبية:
            // B(t) = (1-t)³·P0 + 3(1-t)²t·P1 + 3(1-t)t²·P2 + t³·P3
            val px = mt.pow(3) * x1 +
                    3f * mt.pow(2) * t * controlX1 +
                    3f * mt * t.pow(2) * controlX2 +
                    t.pow(3) * x2

            val py = mt.pow(3) * y1 +
                    3f * mt.pow(2) * t * controlY1 +
                    3f * mt * t.pow(2) * controlY2 +
                    t.pow(3) * y2

            points.add(Pair(px, py))
        }

        return points
    }

    /**
     * يبني GestureDescription جاهز للتنفيذ عبر AccessibilityService
     * باستخدام مسار بيزيه بين النقطتين المحددتين
     *
     * @param x1 إحداثي X لنقطة البداية
     * @param y1 إحداثي Y لنقطة البداية
     * @param x2 إحداثي X لنقطة النهاية
     * @param y2 إحداثي Y لنقطة النهاية
     * @param durationMs مدة تنفيذ الإيماءة بالمللي ثانية
     * @return GestureDescription جاهز للتمرير إلى dispatchGesture
     */
    fun buildSwipeGesture(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = DEFAULT_DURATION_MS
    ): GestureDescription {

        val bezierPoints = generateBezierPoints(x1, y1, x2, y2)

        val path = Path().apply {
            moveTo(bezierPoints.first().first, bezierPoints.first().second)
            bezierPoints.drop(1).forEach { (px, py) ->
                lineTo(px, py)
            }
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)

        return GestureDescription.Builder()
            .addStroke(stroke)
            .build()
    }

    /**
     * يبني إيماءة نقر بسيطة (Tap) على نقطة محددة
     *
     * @param x إحداثي X للنقطة
     * @param y إحداثي Y للنقطة
     * @return GestureDescription للنقر
     */
    fun buildTapGesture(x: Float, y: Float): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    /**
     * يبني إيماءة ضغط مطوّل (Long Press)
     *
     * @param x إحداثي X للنقطة
     * @param y إحداثي Y للنقطة
     * @param durationMs مدة الضغط (افتراضي 800ms)
     */
    fun buildLongPressGesture(x: Float, y: Float, durationMs: Long = 800L): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return GestureDescription.Builder().addStroke(stroke).build()
    }
}
