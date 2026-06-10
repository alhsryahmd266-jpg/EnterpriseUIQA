package com.enterprise.uiqa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * FloatingTapIndicator v2 — زرار عائم قابل للسحب
 *
 * المستخدم يسحبه لأي مكان. عند تفعيل المسح يومض باللون الأخضر.
 * عند التوقف يرجع أحمر. يحدّث TargetStore تلقائياً عند السحب.
 */
class FloatingTapIndicator(private val context: Context) {

    companion object {
        private const val TAG  = "FloatingTapIndicator"
        private const val SIZE = 90   // حجم الزرار بالبكسل
        @Volatile var instance: FloatingTapIndicator? = null
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val uiHandler     = Handler(Looper.getMainLooper())
    private var cursorView: CursorView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isActive = false

    // ── رسم الزرار ────────────────────────────────────────────────────────
    private inner class CursorView(ctx: Context) : View(ctx) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3.5f
        }
        private val dotPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 18f; textAlign = Paint.Align.CENTER
        }
        val radius = SIZE / 2f - 4f

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            fillPaint.color = if (isActive) Color.parseColor("#CC4CAF50")
                              else          Color.parseColor("#CCF44336")
            canvas.drawCircle(cx, cy, radius, fillPaint)
            canvas.drawCircle(cx, cy, radius - 2f, ringPaint)
            canvas.drawCircle(cx, cy, 7f, dotPaint)
            canvas.drawText(if (isActive) "●" else "✛", cx, cy + 6f, labelPaint)
        }
    }

    // ── إظهار الزرار ───────────────────────────────────────────────────────
    fun show() {
        uiHandler.post {
            if (cursorView != null) return@post
            try {
                val view = CursorView(context)
                val params = WindowManager.LayoutParams(
                    SIZE, SIZE,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.START; x = 80; y = 400 }

                // حفظ موضع أولي في TargetStore
                TargetStore.set(80f + SIZE / 2f, 400f + SIZE / 2f)

                // ── السحب ─────────────────────────────────────────────────
                var initX = 0; var initY = 0
                var initTX = 0f; var initTY = 0f

                view.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initX = params.x; initY = params.y
                            initTX = event.rawX; initTY = event.rawY
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = (initX + (event.rawX - initTX)).toInt().coerceAtLeast(0)
                            params.y = (initY + (event.rawY - initTY)).toInt().coerceAtLeast(0)
                            try { windowManager.updateViewLayout(v, params) } catch (_: Exception) {}
                            TargetStore.set(params.x + SIZE / 2f, params.y + SIZE / 2f)
                            true
                        }
                        else -> false
                    }
                }

                windowManager.addView(view, params)
                cursorView = view
                layoutParams = params
                Log.i(TAG, "Floating button shown at (${params.x},${params.y})")
            } catch (e: Exception) {
                Log.e(TAG, "show() failed: ${e.message}")
            }
        }
    }

    // ── تفعيل وضع الضغط (أخضر + نبض) ─────────────────────────────────────
    fun setActive(active: Boolean) {
        uiHandler.post {
            val view = cursorView ?: return@post
            isActive = active
            view.invalidate()
            if (active) pulse(view)
        }
    }

    private fun pulse(view: View) {
        if (!isActive) return
        view.animate().cancel()
        view.animate().scaleX(1.25f).scaleY(1.25f).setDuration(100).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(100).withEndAction {
                if (isActive) pulse(view)
            }.start()
        }.start()
    }

    // ── إخفاء الزرار ───────────────────────────────────────────────────────
    fun dismiss() {
        uiHandler.post {
            cursorView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
            }
            cursorView = null; layoutParams = null
            Log.i(TAG, "dismissed")
        }
    }
}
