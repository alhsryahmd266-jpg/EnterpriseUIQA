package com.enterprise.uiqa

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * UiAutomationService — خدمة الوصول المخصصة لأتمتة فحص واجهات المستخدم
 * Enterprise UI QA Automation Framework
 */
class UiAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "UiAutomationService"

        /** مرجع ثابت للوصول للخدمة من أي مكان في التطبيق */
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
    // API الأتمتة العام
    // =====================================================================

    /**
     * ينفّذ سحباً ناعماً بين نقطتين باستخدام محرك بيزيه
     *
     * @param x1 X للبداية   @param y1 Y للبداية
     * @param x2 X للنهاية   @param y2 Y للنهاية
     * @param durationMs مدة الحركة بالمللي ثانية
     * @param callback نتيجة التنفيذ (اختياري)
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
    // أدوات فحص العناصر
    // =====================================================================

    /**
     * يبحث عن أول عنصر بـ contentDescription محدد
     */
    fun findNodeByDescription(description: String): AccessibilityNodeInfo? {
        return rootInActiveWindow?.findAccessibilityNodeInfosByText(description)?.firstOrNull()
    }

    /**
     * يبحث عن عناصر بـ viewId محدد
     */
    fun findNodeById(viewId: String): AccessibilityNodeInfo? {
        return rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
    }

    /**
     * يطبع شجرة العناصر الكاملة للنافذة الحالية في الـ Logcat
     */
    fun dumpWindowTree() {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "No active window found")
            return
        }
        dumpNode(root, 0)
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        Log.d(TAG, "$indent[${node.className}] id=${node.viewIdResourceName} " +
                "text='${node.text}' desc='${node.contentDescription}' " +
                "bounds=${node.getBoundsInScreen(android.graphics.Rect().also { node.getBoundsInScreen(it) })}")
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
