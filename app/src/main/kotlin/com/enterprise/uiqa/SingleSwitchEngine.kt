package com.enterprise.uiqa

import android.util.Log

/**
 * SingleSwitchEngine — التحكم بزر واحد (Single Switch Scanning)
 *
 * للمرضى اللي مش قادرين يتحركوا غير جزء صغير من جسمهم
 * (رمشة، نفَس، حركة إصبع، ضغط على زرار حجم الصوت)
 *
 * طريقة العمل:
 *   - النظام يعرض خيارات واحدة تلو التانية (scanning)
 *   - المريض يضغط الزرار لما يوصل للخيار اللي يريده
 *   - كل ضغطة = تنفيذ الأمر الحالي
 *
 * الوضعيات:
 *   TAP_SHORT  (< 500ms)  = تأكيد الاختيار الحالي
 *   TAP_LONG   (500-2000ms) = الانتقال لمجموعة تانية
 *   TAP_HOLD   (> 2000ms)  = إيقاف / رجوع
 */
object SingleSwitchEngine {

    private const val TAG = "SingleSwitchEngine"

    enum class SwitchAction { CONFIRM, NEXT_GROUP, CANCEL }

    enum class ScanItem {
        FIRE, MOVE_FORWARD, MOVE_LEFT, MOVE_RIGHT, MOVE_BACK,
        JUMP, RELOAD, AIM_MODE, STOP_ALL
    }

    interface ScanListener {
        fun onScanHighlight(item: ScanItem)   // اعرض للمريض الخيار الحالي
        fun onScanExecute(item: ScanItem)     // نفّذ الأمر
    }

    private val scanGroups = listOf(
        listOf(ScanItem.FIRE, ScanItem.AIM_MODE),                            // هجوم
        listOf(ScanItem.MOVE_FORWARD, ScanItem.MOVE_LEFT,
               ScanItem.MOVE_RIGHT, ScanItem.MOVE_BACK),                     // حركة
        listOf(ScanItem.JUMP, ScanItem.RELOAD, ScanItem.STOP_ALL)            // أخرى
    )

    private var currentGroup = 0
    private var currentItem  = 0
    private var pressStartMs = 0L
    private var scanIntervalMs = 1200L   // وقت كل خيار (قابل للضبط)

    var listener: ScanListener? = null
    private var scanThread: Thread? = null
    private var isScanning = false

    fun startScanning() {
        isScanning = true
        scanThread = Thread {
            while (isScanning) {
                val item = scanGroups[currentGroup][currentItem]
                listener?.onScanHighlight(item)
                Thread.sleep(scanIntervalMs)
                if (!isScanning) break
                currentItem = (currentItem + 1) % scanGroups[currentGroup].size
            }
        }.apply { isDaemon = true; start() }
    }

    fun stopScanning() {
        isScanning = false
        scanThread?.interrupt()
    }

    // يُستدعى عند ضغط الزرار (حجم الصوت مثلاً)
    fun onSwitchPress() { pressStartMs = System.currentTimeMillis() }

    // يُستدعى عند رفع الزرار
    fun onSwitchRelease() {
        val duration = System.currentTimeMillis() - pressStartMs
        val action = when {
            duration < 500L  -> SwitchAction.CONFIRM
            duration < 2000L -> SwitchAction.NEXT_GROUP
            else             -> SwitchAction.CANCEL
        }
        Log.d(TAG, "switch ${duration}ms → ${action.name}")
        when (action) {
            SwitchAction.CONFIRM -> {
                val item = scanGroups[currentGroup][currentItem]
                listener?.onScanExecute(item)
                currentItem = 0   // ابدأ من الأول بعد التنفيذ
            }
            SwitchAction.NEXT_GROUP -> {
                currentGroup = (currentGroup + 1) % scanGroups.size
                currentItem  = 0
            }
            SwitchAction.CANCEL -> {
                currentGroup = 0; currentItem = 0
            }
        }
    }

    fun setScanSpeed(ms: Long) { scanIntervalMs = ms.coerceIn(400L, 3000L) }
}
