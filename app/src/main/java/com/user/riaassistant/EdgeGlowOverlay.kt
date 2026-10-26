package com.user.riaassistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.LinearInterpolator

/**
 * Screen ke kinaaron par ghoomti rangeen roshni (Siri / Gemini jaisa).
 * Sirf roshni dikhti hai, koi box nahi. Touch is se aar-paar nikal jaata hai.
 * Clockwise ghoomti hai: upar wale kinare par daayein, daayein kinare par neeche,
 * neeche wale par baayein, baayein kinare par upar.
 */
class EdgeGlowOverlay(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var glowView: GlowView? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show() {
        handler.post {
            if (!canDraw() || glowView != null) return@post
            val view = GlowView(context)
            view.alpha = 0f

            val type = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            if (Build.VERSION.SDK_INT >= 28) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            try {
                windowManager.addView(view, params)
                glowView = view
                view.startSpin()
                view.animate().alpha(1f).setDuration(300).start()
            } catch (e: Exception) {
                glowView = null
            }
        }
    }

    fun hide() {
        handler.post {
            val view = glowView ?: return@post
            glowView = null
            view.animate().alpha(0f).setDuration(250).withEndAction {
                view.stopSpin()
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                }
            }.start()
        }
    }

    private class GlowView(context: Context) : View(context) {

        private val colors = intArrayOf(
            Color.parseColor("#FF1744"), // laal
            Color.parseColor("#FFEA00"), // peela
            Color.parseColor("#00E676"), // hara
            Color.parseColor("#2979FF"), // neela
            Color.parseColor("#FF1744")  // wapas laal (seamless)
        )
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val matrix = Matrix()
        private var shader: SweepGradient? = null
        private var angle = 0f
        private var animator: ValueAnimator? = null
        private var cornerPx = 0f // phone ke screen ke asli gol kone ka radius

        private fun dp(v: Float): Float =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
            )

        init {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            requestApplyInsets()
        }

        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            if (Build.VERSION.SDK_INT >= 31) {
                var biggest = 0
                val positions = intArrayOf(
                    RoundedCorner.POSITION_TOP_LEFT,
                    RoundedCorner.POSITION_TOP_RIGHT,
                    RoundedCorner.POSITION_BOTTOM_LEFT,
                    RoundedCorner.POSITION_BOTTOM_RIGHT
                )
                for (pos in positions) {
                    val corner = insets.getRoundedCorner(pos)
                    if (corner != null && corner.radius > biggest) biggest = corner.radius
                }
                if (biggest > 0) {
                    cornerPx = biggest.toFloat()
                    invalidate()
                }
            }
            return super.onApplyWindowInsets(insets)
        }

        fun startSpin() {
            animator?.cancel()
            val a = ValueAnimator.ofFloat(0f, 360f)
            a.duration = 2800
            a.interpolator = LinearInterpolator()
            a.repeatCount = ValueAnimator.INFINITE
            a.addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
            animator = a
            a.start()
        }

        fun stopSpin() {
            animator?.cancel()
            animator = null
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            shader = SweepGradient(w / 2f, h / 2f, colors, null)
        }

        override fun onDraw(canvas: Canvas) {
            val sh = shader ?: return
            matrix.setRotate(angle, width / 2f, height / 2f)
            sh.setLocalMatrix(matrix)
            paint.shader = sh

            // user ne dp diya ho (prefs: glow_radius_dp) to wahi, warna phone ka asli radius, warna 52dp
            val custom = context.getSharedPreferences("ria_prefs", Context.MODE_PRIVATE)
                .getInt("glow_radius_dp", 0)
            val radius = when {
                custom > 0 -> dp(custom.toFloat())
                cornerPx > 0f -> cornerPx
                else -> dp(52f)
            }
            // 4 parat: bahar se andar, dhundhli se tez, taaki glow jaisa dikhe
            val widths = floatArrayOf(dp(16f), dp(10f), dp(6f), dp(3f))
            val alphas = intArrayOf(40, 80, 150, 255)
            for (i in widths.indices) {
                val half = widths[i] / 2f
                rect.set(half, half, width - half, height - half)
                paint.strokeWidth = widths[i]
                paint.alpha = alphas[i]
                val r = (radius - half).coerceAtLeast(0f)
                canvas.drawRoundRect(rect, r, r, paint)
            }
        }
    }
}
