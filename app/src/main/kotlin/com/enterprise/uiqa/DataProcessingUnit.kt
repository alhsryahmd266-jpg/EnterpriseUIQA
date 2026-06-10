package com.enterprise.uiqa

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * DataProcessingUnit — وحدة المنطق الرياضي وفلترة البيانات
 *
 * تُعالج مصفوفات الإحداثيات المستقبَلة من UiAutomationService وتُجهّزها
 * للتنفيذ الدقيق عبر دالتين رئيسيتين:
 *
 *  ① ProximitySorter  — ترتيب الصناديق المحيطة بالقرب من مركز الشاشة
 *  ② DynamicOffset    — تعويض انحراف المحور Y أثناء الضغط المستمر
 */
object DataProcessingUnit {

    // =========================================================================
    // ① دالة ترتيب الأولويات — Proximity Sorting
    // =========================================================================

    /**
     * صندوق محيط مُعنوَن بمعلومات مشتقة مسبقاً
     *
     * @property rect       إحداثيات الصندوق [Xmin, Ymin, Xmax, Ymax]
     * @property center     المركز الهندسي للصندوق
     * @property area       المساحة بالـ px²
     * @property distancePx المسافة الإقليدية من مركز الشاشة (تُحسب عند الفرز)
     */
    data class BoundingBox(
        val rect: RectF,
        val center: PointF  = PointF((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f),
        val area: Float     = rect.width() * rect.height(),
        var distancePx: Float = 0f
    )

    /**
     * يُحوّل مصفوفة خام [[Xmin,Ymin,Xmax,Ymax], …] إلى قائمة [BoundingBox]
     */
    fun fromRawBoxes(rawBoxes: List<FloatArray>): List<BoundingBox> =
        rawBoxes.map { arr ->
            require(arr.size == 4) { "كل صندوق يجب أن يحتوي على 4 قيم: [Xmin,Ymin,Xmax,Ymax]" }
            BoundingBox(RectF(arr[0], arr[1], arr[2], arr[3]))
        }

    /**
     * ترتّب الصناديق تصاعدياً بالقرب من مركز الشاشة، مع تفضيل الصناديق
     * ذات المساحة الأكبر عند تساوي المسافة (ضمن هامش [areaTieBreakPx]).
     *
     * منطق الأولوية:
     *   1. الصناديق الأقرب للمركز أولاً (مسافة إقليدية أصغر).
     *   2. عند تقارب المسافة (< [distanceTiePx]) يُفضَّل الصندوق ذو المساحة الأكبر.
     *
     * @param boxes           قائمة الصناديق المُدخلة
     * @param screenWidth     عرض الشاشة بالـ px
     * @param screenHeight    ارتفاع الشاشة بالـ px
     * @param distanceTiePx   هامش المسافة الذي يُعدّ فيه صندوقان "متساويَين" (افتراضي: 30 px)
     * @return قائمة مرتّبة من [SortedBox]
     */
    data class SortedBox(
        val box: BoundingBox,
        val rank: Int,
        val distancePx: Float,
        val priority: String        // "NEAREST" | "LARGEST_NEARBY" | "SECONDARY"
    )

    fun sortByProximity(
        boxes: List<BoundingBox>,
        screenWidth: Int,
        screenHeight: Int,
        distanceTiePx: Float = 30f
    ): List<SortedBox> {
        if (boxes.isEmpty()) return emptyList()

        val screenCenter = PointF(screenWidth / 2f, screenHeight / 2f)

        // احسب المسافة الإقليدية لكل صندوق
        val annotated = boxes.map { box ->
            val dx = box.center.x - screenCenter.x
            val dy = box.center.y - screenCenter.y
            box.copy(distancePx = sqrt(dx * dx + dy * dy))
        }

        // فرز مركّب: أولاً بالمسافة، ثم بالمساحة (عكسي) عند التعادل
        val sorted = annotated.sortedWith(
            compareBy<BoundingBox> { it.distancePx }
                .thenByDescending { it.area }
        )

        // تعيين الأولوية النصية لكل صندوق
        val minDist = sorted.first().distancePx
        return sorted.mapIndexed { idx, box ->
            val priority = when {
                idx == 0                                    -> "NEAREST"
                abs(box.distancePx - minDist) < distanceTiePx -> "LARGEST_NEARBY"
                else                                         -> "SECONDARY"
            }
            SortedBox(box, rank = idx + 1, distancePx = box.distancePx, priority = priority)
        }
    }

    // =========================================================================
    // ② دالة تعويض الانحراف الديناميكي — Dynamic Offset Algorithm
    // =========================================================================

    /**
     * حالة الإزاحة لجلسة ضغط واحدة — احتفظ بمثيل واحد طوال فترة الضغط
     * وأعِد تهيئته عند بدء ضغطة جديدة.
     *
     * @property stepPx        مقدار الإزاحة السالبة لكل دورة بالـ px (موجبة الإشارة، تُطبَّق سالبة)
     * @property maxOffsetPx   الحد الأقصى للإزاحة التراكمية بالـ px
     * @property decayFactor   معامل تخفيف يُقلّل الخطوة مع الوقت (0.0 = بلا تخفيف، 1.0 = توقف)
     */
    class DynamicOffsetState(
        val stepPx: Float   = 2.5f,
        val maxOffsetPx: Float = 40f,
        val decayFactor: Float = 0.05f
    ) {
        private var _accumulatedOffset: Float = 0f
        private var _cycleCount: Int = 0
        val accumulatedOffsetPx: Float get() = _accumulatedOffset
        val cycleCount: Int             get() = _cycleCount

        /** هل وصلت الإزاحة للحد الأقصى؟ */
        val isSaturated: Boolean get() = abs(_accumulatedOffset) >= maxOffsetPx

        /** إعادة التهيئة لضغطة جديدة */
        fun reset() { _accumulatedOffset = 0f; _cycleCount = 0 }

        internal fun advance(): Float {
            if (isSaturated) return _accumulatedOffset
            val effectiveStep = stepPx * (1f - decayFactor * _cycleCount).coerceAtLeast(0.1f)
            _accumulatedOffset = (_accumulatedOffset - effectiveStep)
                .coerceAtLeast(-maxOffsetPx)
            _cycleCount++
            return _accumulatedOffset
        }
    }

    /**
     * نقطة تفاعل مُعوَّضة جاهزة للإرسال إلى [UiAutomationService]
     *
     * @property raw         المركز الهندسي الخام للصندوق
     * @property adjusted    النقطة بعد تطبيق تعويض Y
     * @property offsetApplied الإزاحة المطبّقة على Y هذه الدورة (سالبة أو صفر)
     * @property totalOffset  إجمالي الإزاحة التراكمية
     * @property cycle        رقم الدورة (يبدأ من 1)
     */
    data class AdjustedTouchPoint(
        val raw: PointF,
        val adjusted: PointF,
        val offsetApplied: Float,
        val totalOffset: Float,
        val cycle: Int
    )

    /**
     * يحسب نقطة التفاعل المركزية للصندوق المستهدف ويُطبّق عليها إزاحة
     * سالبة تدريجية على المحور Y لموازنة الانحراف التلقائي للأعلى.
     *
     * استخدم [DynamicOffsetState] واحدة لكل جلسة ضغط واستدعِ هذه الدالة
     * في كل دورة (كل مرة تُرسل فيها حدث لمس).
     *
     * @param targetBox   الصندوق المستهدف (يُفضَّل أول عنصر من [sortByProximity])
     * @param state       حالة الإزاحة الخاصة بهذه الجلسة
     * @param isPressing  هل الضغط لا يزال مستمراً؟ (false = لا تُطبَّق إزاحة إضافية)
     * @return [AdjustedTouchPoint] تحتوي على الإحداثي الجاهز للإرسال
     */
    fun computeAdjustedTouchPoint(
        targetBox: BoundingBox,
        state: DynamicOffsetState,
        isPressing: Boolean
    ): AdjustedTouchPoint {
        val rawCenter = targetBox.center
        val prevOffset = state.accumulatedOffsetPx

        val newOffset = if (isPressing) state.advance() else prevOffset

        val adjusted = PointF(rawCenter.x, rawCenter.y + newOffset)
        return AdjustedTouchPoint(
            raw          = rawCenter,
            adjusted     = adjusted,
            offsetApplied = newOffset - prevOffset,
            totalOffset  = newOffset,
            cycle        = state.cycleCount
        )
    }

    // =========================================================================
    // أداة مساعدة — Pipeline كامل من البيانات الخام للنقطة الجاهزة
    // =========================================================================

    /**
     * Pipeline مدمج: يأخذ صناديق خام + حالة الضغط ويُعيد نقطة التفاعل
     * المُعوَّضة الجاهزة مباشرةً.
     *
     * @param rawBoxes      مصفوفات [[Xmin,Ymin,Xmax,Ymax], …]
     * @param screenWidth   عرض الشاشة
     * @param screenHeight  ارتفاع الشاشة
     * @param offsetState   حالة الإزاحة للجلسة الحالية
     * @param isPressing    هل الضغط مستمر؟
     * @return [AdjustedTouchPoint] أو null إذا كانت القائمة فارغة
     */
    fun processAndGetTouchPoint(
        rawBoxes: List<FloatArray>,
        screenWidth: Int,
        screenHeight: Int,
        offsetState: DynamicOffsetState,
        isPressing: Boolean
    ): AdjustedTouchPoint? {
        val boxes  = fromRawBoxes(rawBoxes)
        val sorted = sortByProximity(boxes, screenWidth, screenHeight)
        val best   = sorted.firstOrNull()?.box ?: return null
        return computeAdjustedTouchPoint(best, offsetState, isPressing)
    }
}
