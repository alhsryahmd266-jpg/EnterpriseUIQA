package com.enterprise.uiqa

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * UiAutomationService — خدمة الوصول المخصصة لأتمتة فحص واجهات المستخدم
 * Enterprise UI QA Automation Framework
 */
class UiAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "UiAutomationService"

        @Volatile
        var instance: UiAutomationService? = null
            private set
    }

    // =====================================================================
    // دورة حياة الخدمة
    // =====================================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "UiAutomationService connected — جاهز للأتمتة")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.i(TAG, "UiAutomationService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                Log.d(TAG, "Window changed: ${event.packageName} / ${event.className}")
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                Log.d(TAG, "View clicked: ${event.className}")
            else -> { /* تجاهل باقي الأحداث */ }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    // =====================================================================
    // API الأتمتة الأساسي
    // =====================================================================

    /**
     * ينفّذ سحباً ناعماً بين نقطتين باستخدام محرك بيزيه
     */
    fun swipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 300L,
        callback: GestureResultCallback? = null
    ) {
        val gesture = BezierTouchEngine.buildSwipeGesture(x1, y1, x2, y2, durationMs)
        dispatchGesture(gesture, callback ?: defaultCallback("swipe"), null)
        Log.d(TAG, "Swipe dispatched: ($x1,$y1) → ($x2,$y2)")
    }

    /**
     * ينفّذ نقرة بسيطة على إحداثيات محددة
     */
    fun tap(x: Float, y: Float, callback: GestureResultCallback? = null) {
        val gesture = BezierTouchEngine.buildTapGesture(x, y)
        dispatchGesture(gesture, callback ?: defaultCallback("tap"), null)
        Log.d(TAG, "Tap dispatched: ($x,$y)")
    }

    /**
     * ينفّذ ضغطاً مطوّلاً على إحداثيات محددة
     */
    fun longPress(x: Float, y: Float, durationMs: Long = 800L, callback: GestureResultCallback? = null) {
        val gesture = BezierTouchEngine.buildLongPressGesture(x, y, durationMs)
        dispatchGesture(gesture, callback ?: defaultCallback("longPress"), null)
        Log.d(TAG, "LongPress dispatched: ($x,$y) for ${durationMs}ms")
    }

    // =====================================================================
    // ██  Advanced Multi-Touch — Concurrent Input Stress Testing  ██
    // =====================================================================

    /**
     * نتائج اختبار الضغط المتزامن
     *
     * @property dragSegmentsDispatched   عدد مقاطع السحب المُرسَلة
     * @property tapBatchesDispatched     عدد دفعات النقرات المُرسَلة
     * @property dragCancelled            هل أُلغي السحب؟
     * @property tapsCancelled            عدد دفعات النقر التي أُلغيت
     * @property totalElapsedMs           إجمالي الوقت المستغرق بالمللي ثانية
     */
    data class StressTestReport(
        val dragSegmentsDispatched: Int,
        val tapBatchesDispatched: Int,
        val dragCancelled: Boolean,
        val tapsCancelled: Int,
        val totalElapsedMs: Long
    ) {
        override fun toString(): String =
            "StressTestReport[drag=$dragSegmentsDispatched segs (cancelled=$dragCancelled) | " +
            "taps=$tapBatchesDispatched batches (cancelled=$tapsCancelled) | ${totalElapsedMs}ms]"
    }

    /**
     * تشغيل اختبار إدخال متعدد متزامن — Advanced Multi-Touch Stress Test.
     *
     * يُطلق حدثين لمس مستقلين في نفس الوقت:
     *
     *  ① Continuous Vector Drag — سحب مستمر من نقطة ارتكاز ثابتة (مركز D-Pad افتراضي)
     *    باتجاه متجهات ديناميكية تتحدث مع كل مقطع.
     *    يُبنى كسلسلة من StrokeDescription مع willContinue=true حتى المقطع الأخير.
     *
     *  ② High-Frequency Pulse Taps — نقرات نبضية عالية التردد على مصفوفة إحداثيات
     *    في النصف الآخر من الشاشة. كل دفعة تحتوي على الإحداثيات كلها في نفس اللحظة
     *    بمقاطع زمنية متداخلة ضمن GestureDescription واحدة.
     *
     * الحدثان يعملان بشكل كامل غير متزامن (Handler + callback chains) بدون أي
     * قفل أو blocking بين القناتين.
     *
     * @param dpadCenter         مركز D-Pad (pivot ثابت لبداية السحب)
     * @param dragVectors        متجهات السحب — كل عنصر (dx, dy) يُطبَّق على الـ pivot
     *                           تباعاً خلال جلسة الاختبار
     * @param tapTargets         إحداثيات النقرات النبضية (يجب أن تكون في النصف الآخر من الشاشة)
     * @param segmentDurationMs  مدة كل مقطع سحب بالمللي ثانية (افتراضي: 80)
     * @param tapIntervalMs      الفاصل الزمني بين كل دفعة نقرات بالمللي ثانية (افتراضي: 60)
     * @param tapHoldMs          مدة الضغط لكل نقرة نبضية بالمللي ثانية (افتراضي: 20)
     * @param onReport           callback يُستدعى عند انتهاء الاختبار بالكامل
     */
    fun runConcurrentInputStressTest(
        dpadCenter: PointF,
        dragVectors: List<PointF>,
        tapTargets: List<PointF>,
        segmentDurationMs: Long = 80L,
        tapIntervalMs: Long = 60L,
        tapHoldMs: Long = 20L,
        onReport: ((StressTestReport) -> Unit)? = null
    ) {
        require(dragVectors.isNotEmpty()) { "dragVectors must not be empty" }
        require(tapTargets.isNotEmpty()) { "tapTargets must not be empty" }
        require(segmentDurationMs in 1..5000) { "segmentDurationMs must be 1–5000" }
        require(tapIntervalMs in 1..1000) { "tapIntervalMs must be 1–1000" }
        require(tapHoldMs in 1..500 && tapHoldMs < tapIntervalMs) {
            "tapHoldMs must be 1–500 and less than tapIntervalMs"
        }

        val startTime = System.currentTimeMillis()
        val mainHandler = Handler(Looper.getMainLooper())

        // ── مؤشرات الحالة (thread-safe) ──
        val dragSegmentsSent   = AtomicInteger(0)
        val tapBatchesSent     = AtomicInteger(0)
        val tapsCancelledCount = AtomicInteger(0)
        val dragCancelled      = AtomicBoolean(false)
        val dragDone           = AtomicBoolean(false)
        val tapsDone           = AtomicBoolean(false)

        // ────────────────────────────────────────────────────────────────
        // دالة مساعدة: دمج نتائج القناتين وإرسال التقرير النهائي
        // ────────────────────────────────────────────────────────────────
        fun tryFinalize() {
            if (dragDone.get() && tapsDone.get()) {
                val elapsed = System.currentTimeMillis() - startTime
                val report  = StressTestReport(
                    dragSegmentsDispatched = dragSegmentsSent.get(),
                    tapBatchesDispatched   = tapBatchesSent.get(),
                    dragCancelled          = dragCancelled.get(),
                    tapsCancelled          = tapsCancelledCount.get(),
                    totalElapsedMs         = elapsed
                )
                Log.i(TAG, "StressTest DONE — $report")
                onReport?.invoke(report)
            }
        }

        // ────────────────────────────────────────────────────────────────
        // قناة ①: Continuous Vector Drag
        //   سلسلة callback تُرسل مقطعاً واحداً في كل مرة، وعند اكتماله
        //   تنتقل للمقطع التالي حتى استنفاد جميع المتجهات.
        // ────────────────────────────────────────────────────────────────
        fun dispatchDragSegment(index: Int) {
            if (index >= dragVectors.size) {
                dragDone.set(true)
                tryFinalize()
                return
            }

            val isLast  = (index == dragVectors.size - 1)
            val vector  = dragVectors[index]
            val target  = PointF(dpadCenter.x + vector.x, dpadCenter.y + vector.y)

            val path = Path().apply {
                moveTo(dpadCenter.x, dpadCenter.y)
                lineTo(target.x, target.y)
            }

            // willContinue=true لكل المقاطع ماعدا الأخير لإبقاء "إصبع" السحب نشطاً
            val stroke = GestureDescription.StrokeDescription(
                path,
                /* startTime  */ 0L,
                /* duration   */ segmentDurationMs,
                /* willContinue */ !isLast
            )

            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val cb = object : GestureResultCallback() {
                override fun onCompleted(gd: GestureDescription) {
                    dragSegmentsSent.incrementAndGet()
                    Log.v(TAG, "Drag[$index] → (${target.x},${target.y}) ✓")
                    mainHandler.post { dispatchDragSegment(index + 1) }
                }
                override fun onCancelled(gd: GestureDescription) {
                    dragCancelled.set(true)
                    dragSegmentsSent.incrementAndGet()
                    Log.w(TAG, "Drag[$index] cancelled ✗")
                    // نتابع حتى لو أُلغي مقطع
                    mainHandler.post { dispatchDragSegment(index + 1) }
                }
            }

            dispatchGesture(gesture, cb, null)
        }

        // ────────────────────────────────────────────────────────────────
        // قناة ②: High-Frequency Pulse Taps
        //   كل دفعة تُبنى كـ GestureDescription تحتوي على جميع إحداثيات
        //   tapTargets كمقاطع متداخلة زمنياً (تبدأ بفاصل tapHoldMs بينها)
        //   لمحاكاة الضغط المتوازي.
        //   الدفعات التالية تُجدوَل على الـ mainHandler بفاصل tapIntervalMs.
        // ────────────────────────────────────────────────────────────────
        val tapBatchCount = dragVectors.size    // نفس عدد دفعات السحب للتزامن
        var tapBatchIndex = 0

        fun dispatchTapBatch() {
            if (tapBatchIndex >= tapBatchCount) {
                tapsDone.set(true)
                tryFinalize()
                return
            }

            val currentBatch = tapBatchIndex
            tapBatchIndex++

            // ابنِ gesture واحدة تحتوي على جميع tap targets متداخلة زمنياً
            val builder = GestureDescription.Builder()
            tapTargets.forEachIndexed { i, pt ->
                val tapPath = Path().apply {
                    moveTo(pt.x, pt.y)
                    lineTo(pt.x + 0.1f, pt.y + 0.1f)   // حركة دقيقة جداً لتجاوز filter الـ zero-length
                }
                // كل tap يبدأ بـ i*2ms تأخير لتمييز المؤشرات
                val tapStroke = GestureDescription.StrokeDescription(
                    tapPath,
                    /* startTime */ i * 2L,
                    /* duration  */ tapHoldMs
                )
                builder.addStroke(tapStroke)
            }

            val cb = object : GestureResultCallback() {
                override fun onCompleted(gd: GestureDescription) {
                    tapBatchesSent.incrementAndGet()
                    Log.v(TAG, "TapBatch[$currentBatch] ×${tapTargets.size} ✓")
                    mainHandler.postDelayed({ dispatchTapBatch() }, tapIntervalMs)
                }
                override fun onCancelled(gd: GestureDescription) {
                    tapsCancelledCount.incrementAndGet()
                    tapBatchesSent.incrementAndGet()
                    Log.w(TAG, "TapBatch[$currentBatch] cancelled ✗")
                    mainHandler.postDelayed({ dispatchTapBatch() }, tapIntervalMs)
                }
            }

            dispatchGesture(builder.build(), cb, null)
        }

        // ────────────────────────────────────────────────────────────────
        // إطلاق القناتين معاً — لا waiting، لا blocking
        // ────────────────────────────────────────────────────────────────
        Log.i(TAG,
            "StressTest START — " +
            "drag: ${dragVectors.size} vectors @ ${segmentDurationMs}ms each | " +
            "taps: ${tapTargets.size} targets × $tapBatchCount batches @ ${tapIntervalMs}ms"
        )
        mainHandler.post { dispatchDragSegment(0)  }   // ① سحب
        mainHandler.post { dispatchTapBatch()       }   // ② نقرات
    }

    /**
     * دالة مساعدة لبناء متجهات D-Pad جاهزة بأربعة اتجاهات + مركز
     *
     * @param pivot      نقطة المركز
     * @param radius     نصف قطر الحركة بالـ px
     * @param steps      عدد الخطوات المتوسطة لكل اتجاه
     */
    fun buildDpadVectors(pivot: PointF, radius: Float, steps: Int = 4): List<PointF> {
        val result = mutableListOf<PointF>()
        // شمال
        for (i in 1..steps) result.add(PointF(0f, -radius * i / steps))
        // عودة للمركز
        for (i in steps downTo 1) result.add(PointF(0f, -radius * i / steps))
        // يمين
        for (i in 1..steps) result.add(PointF(radius * i / steps, 0f))
        // عودة للمركز
        for (i in steps downTo 1) result.add(PointF(radius * i / steps, 0f))
        // جنوب
        for (i in 1..steps) result.add(PointF(0f, radius * i / steps))
        // عودة للمركز
        for (i in steps downTo 1) result.add(PointF(0f, radius * i / steps))
        // غرب
        for (i in 1..steps) result.add(PointF(-radius * i / steps, 0f))
        // عودة للمركز
        for (i in steps downTo 1) result.add(PointF(-radius * i / steps, 0f))
        return result
    }

    // =====================================================================
    // أدوات فحص العناصر
    // =====================================================================

    fun findNodeByDescription(description: String): AccessibilityNodeInfo? =
        rootInActiveWindow?.findAccessibilityNodeInfosByText(description)?.firstOrNull()

    fun findNodeById(viewId: String): AccessibilityNodeInfo? =
        rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()

    fun dumpWindowTree() {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "No active window found")
            return
        }
        dumpNode(root, 0)
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        Log.d(TAG, "$indent[${node.className}] id=${node.viewIdResourceName} " +
                "text='${node.text}' desc='${node.contentDescription}' bounds=$bounds")
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNode(it, depth + 1) }
        }
    }

    // =====================================================================
    // مساعدات داخلية
    // =====================================================================

    private fun defaultCallback(action: String) = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription) {
            Log.i(TAG, "$action completed ✓")
        }
        override fun onCancelled(gestureDescription: GestureDescription) {
            Log.w(TAG, "$action cancelled ✗")
        }
    }
}
