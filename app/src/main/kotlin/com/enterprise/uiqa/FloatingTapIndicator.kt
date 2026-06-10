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
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator

/**
 * FloatingTapIndicator — مؤشر بصري عائم يتحرك مع كل ضغطة
 *
 * يعرض دايرة حمرا فوق كل التطبيقات تتحرك لإحداثيات الهدف
 * وتعمل animation عند كل ضغطة. يعمل عبر TYPE_ACCESSIBILITY_OVERLAY
 * بدون حاجة لإذن SYSTEM_ALERT_WINDOW.
 */
class FloatingTapIndicator(private val context: Context) {

    companion object {
        private const val TAG = "FloatingTapIndicator"
        @Volatile var instance: FloatingTapIndicator? = null
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val uiHandler = Handler(Looper.getMainLooper())
    private var cursorView: CursorView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // ── الدايرة ────────────────────────────────────────────────────────────
    private inner class CursorView(ctx: Context) : View(ctx) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCF44336")   // أحمر شفاف
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val radius = 32f

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, radius, fillPaint)
            canvas.drawCircle(cx, cy, radius - 2f, ringPaint)
            canvas.drawCircle(cx, cy, 7f, dotPaint)
        }
    }

    // ── إظهار المؤشر ───────────────────────────────────────────────────────
    fun show() {
        uiHandler.post {
            if (cursorView != null) return@post
            try {
                val view = CursorView(context)
                val size = (view.radius * 2 + 16).toInt()
                val params = WindowManager.LayoutParams(
                    size, size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 100; y = 200
                }
                windowManager.addView(view, params)
                cursorView = view
                layoutParams = params
                Log.i(TAG, "Floating indicator shown")
            } catch (e: Exception) {
                Log.e(TAG, "show() failed: ${e.message}")
            }
        }
    }

    // ── تحريك المؤشر للهدف ─────────────────────────────────────────────────
    fun moveTo(x: Float, y: Float) {
        uiHandler.post {
            val lp   = layoutParams ?: return@post
            val view = cursorView   ?: return@post
            val off  = (view.radius + 8).toInt()
            lp.x = (x - off).toInt().coerceAtLeast(0)
            lp.y = (y - off).toInt().coerceAtLeast(0)
            try {
                windowManager.updateViewLayout(view, lp)
                animateTap(view)
                Log.d(TAG, "moved → (${x.toInt()},${y.toInt()})")
            } catch (e: Exception) {
                Log.w(TAG, "moveTo failed: ${e.message}")
            }
        }
    }

    // ── animation الضغطة ───────────────────────────────────────────────────
    private fun animateTap(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.55f).scaleY(0.55f)
            .setDuration(90)
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setInterpolator(OvershootInterpolator(3.5f))
                    .setDuration(220)
                    .start()
            }.start()
    }

    // ── إخفاء المؤشر ───────────────────────────────────────────────────────
    fun dismiss() {
        uiHandler.post {
            cursorView?.let {
                try { windowManager.removeView(it) } catch (e: Exception) {
                    Log.w(TAG, "dismiss failed: ${e.message}")
                }
            }
            cursorView = null
            layoutParams = null
            Log.i(TAG, "Floating indicator dismissed")
        }
    }
}
