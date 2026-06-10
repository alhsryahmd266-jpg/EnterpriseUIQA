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

class UiAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "UiAutomationService"
        @Volatile var instance: UiAutomationService? = null
            private set
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this; Log.i(TAG, "connected") }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() { Log.w(TAG, "interrupted") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            Log.d(TAG, "Window: ${event.packageName}")
    }

    // =========================================================================
    // tapBestTarget — يدمج DPU مع الضغط مباشرة
    // =========================================================================

    /**
     * يستقبل صناديق خام من ScreenCaptureService، يختار الأفضل عبر ProximitySorter،
     * يعوّض الانحراف عبر DynamicOffset، ثم ينفّذ النقرة في أجزاء من الثانية.
     *
     * @param rawBoxes     مصفوفات [[Xmin,Ymin,Xmax,Ymax], …]
     * @param screenWidth  عرض الشاشة
     * @param screenHeight ارتفاع الشاشة
     * @param offsetState  حالة الإزاحة للجلسة الحالية
     * @param isPressing   هل الضغط مستمر؟
     * @param callback     نتيجة اختيارية
     */
    fun tapBestTarget(
        rawBoxes: List<FloatArray>,
        screenWidth: Int,
        screenHeight: Int,
        offsetState: DataProcessingUnit.DynamicOffsetState,
        isPressing: Boolean,
        callback: GestureResultCallback? = null
    ) {
        val point = DataProcessingUnit.processAndGetTouchPoint(
            rawBoxes, screenWidth, screenHeight, offsetState, isPressing
        ) ?: run { Log.w(TAG, "tapBestTarget: no boxes"); return }

        Log.d(TAG, "tapBestTarget → adj=(${point.adjusted.x.toInt()},${point.adjusted.y.toInt()}) " +
                   "Δy=${point.offsetApplied.toInt()} Σ=${point.totalOffset.toInt()}")
        tap(point.adjusted.x, point.adjusted.y, callback)
    }

    // =========================================================================
    // API الأتمتة الأساسي
    // =========================================================================

    fun tap(x: Float, y: Float, callback: GestureResultCallback? = null) {
        val gesture = BezierTouchEngine.buildTapGesture(x, y)
        dispatchGesture(gesture, callback ?: defaultCallback("tap"), null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float,
              durationMs: Long = 300L, callback: GestureResultCallback? = null) {
        val gesture = BezierTouchEngine.buildSwipeGesture(x1, y1, x2, y2, durationMs)
        dispatchGesture(gesture, callback ?: defaultCallback("swipe"), null)
    }

    fun longPress(x: Float, y: Float, durationMs: Long = 800L,
                  callback: GestureResultCallback? = null) {
        val gesture = BezierTouchEngine.buildLongPressGesture(x, y, durationMs)
        dispatchGesture(gesture, callback ?: defaultCallback("longPress"), null)
    }

    // =========================================================================
    // Advanced Multi-Touch Concurrent Input Stress Test
    // =========================================================================

    data class StressTestReport(
        val dragSegmentsDispatched: Int, val tapBatchesDispatched: Int,
        val dragCancelled: Boolean, val tapsCancelled: Int, val totalElapsedMs: Long
    )

    fun runConcurrentInputStressTest(
        dpadCenter: PointF, dragVectors: List<PointF>, tapTargets: List<PointF>,
        segmentDurationMs: Long = 80L, tapIntervalMs: Long = 60L, tapHoldMs: Long = 20L,
        onReport: ((StressTestReport) -> Unit)? = null
    ) {
        require(dragVectors.isNotEmpty()); require(tapTargets.isNotEmpty())
        val startTime = System.currentTimeMillis()
        val mainHandler = Handler(Looper.getMainLooper())
        val dragSegmentsSent = AtomicInteger(0); val tapBatchesSent = AtomicInteger(0)
        val tapsCancelledCount = AtomicInteger(0); val dragCancelled = AtomicBoolean(false)
        val dragDone = AtomicBoolean(false); val tapsDone = AtomicBoolean(false)

        fun tryFinalize() {
            if (dragDone.get() && tapsDone.get()) {
                val r = StressTestReport(dragSegmentsSent.get(), tapBatchesSent.get(),
                    dragCancelled.get(), tapsCancelledCount.get(),
                    System.currentTimeMillis() - startTime)
                Log.i(TAG, "StressTest DONE — $r"); onReport?.invoke(r)
            }
        }

        fun dispatchDragSegment(index: Int) {
            if (index >= dragVectors.size) { dragDone.set(true); tryFinalize(); return }
            val isLast = index == dragVectors.size - 1
            val target = PointF(dpadCenter.x + dragVectors[index].x, dpadCenter.y + dragVectors[index].y)
            val path = Path().apply { moveTo(dpadCenter.x, dpadCenter.y); lineTo(target.x, target.y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, segmentDurationMs, !isLast)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val cb = object : GestureResultCallback() {
                override fun onCompleted(gd: GestureDescription) { dragSegmentsSent.incrementAndGet(); mainHandler.post { dispatchDragSegment(index + 1) } }
                override fun onCancelled(gd: GestureDescription) { dragCancelled.set(true); dragSegmentsSent.incrementAndGet(); mainHandler.post { dispatchDragSegment(index + 1) } }
            }
            dispatchGesture(gesture, cb, null)
        }

        val tapBatchCount = dragVectors.size; var tapBatchIndex = 0
        fun dispatchTapBatch() {
            if (tapBatchIndex >= tapBatchCount) { tapsDone.set(true); tryFinalize(); return }
            val currentBatch = tapBatchIndex++
            val builder = GestureDescription.Builder()
            tapTargets.forEachIndexed { i, pt ->
                val tapPath = Path().apply { moveTo(pt.x, pt.y); lineTo(pt.x + 0.1f, pt.y + 0.1f) }
                builder.addStroke(GestureDescription.StrokeDescription(tapPath, i * 2L, tapHoldMs))
            }
            val cb = object : GestureResultCallback() {
                override fun onCompleted(gd: GestureDescription) { tapBatchesSent.incrementAndGet(); mainHandler.postDelayed({ dispatchTapBatch() }, tapIntervalMs) }
                override fun onCancelled(gd: GestureDescription) { tapsCancelledCount.incrementAndGet(); tapBatchesSent.incrementAndGet(); mainHandler.postDelayed({ dispatchTapBatch() }, tapIntervalMs) }
            }
            dispatchGesture(builder.build(), cb, null)
        }

        mainHandler.post { dispatchDragSegment(0) }
        mainHandler.post { dispatchTapBatch() }
    }

    fun buildDpadVectors(pivot: PointF, radius: Float, steps: Int = 4): List<PointF> {
        val r = mutableListOf<PointF>()
        for (i in 1..steps) r.add(PointF(0f, -radius * i / steps))
        for (i in steps downTo 1) r.add(PointF(0f, -radius * i / steps))
        for (i in 1..steps) r.add(PointF(radius * i / steps, 0f))
        for (i in steps downTo 1) r.add(PointF(radius * i / steps, 0f))
        for (i in 1..steps) r.add(PointF(0f, radius * i / steps))
        for (i in steps downTo 1) r.add(PointF(0f, radius * i / steps))
        for (i in 1..steps) r.add(PointF(-radius * i / steps, 0f))
        for (i in steps downTo 1) r.add(PointF(-radius * i / steps, 0f))
        return r
    }

    // =========================================================================
    // فحص العناصر
    // =========================================================================

    fun findNodeByDescription(d: String) = rootInActiveWindow?.findAccessibilityNodeInfosByText(d)?.firstOrNull()
    fun findNodeById(id: String) = rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
    fun dumpWindowTree() { val root = rootInActiveWindow ?: return; dumpNode(root, 0) }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val b = android.graphics.Rect(); node.getBoundsInScreen(b)
        Log.d(TAG, "$indent[${node.className}] id=${node.viewIdResourceName} text='${node.text}' bounds=$b")
        for (i in 0 until node.childCount) node.getChild(i)?.let { dumpNode(it, depth + 1) }
    }

    private fun defaultCallback(action: String) = object : GestureResultCallback() {
        override fun onCompleted(gd: GestureDescription) { Log.i(TAG, "$action ✓") }
        override fun onCancelled(gd: GestureDescription) { Log.w(TAG, "$action ✗") }
    }
}
