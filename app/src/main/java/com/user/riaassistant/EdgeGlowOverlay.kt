package com.user.riaassistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Siri / Gemini jaisa overlay:
 * - halka kaala parda (kinaaron par zyada gehra)
 * - kinaaron par ghoomti rangeen roshni (do parat, thodi 3D)
 * - neeche gola blink karta mic button, aawaaz se bada-chhota, dabane par sunna shuru
 */
class EdgeGlowOverlay(private val context: Context) {

    companion object {
        const val MODE_LISTENING = 0
        const val MODE_THINKING = 1
        const val MODE_SPEAKING = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var glowView: GlowView? = null
    private var micView: MicView? = null

    @Volatile
    private var voiceLevel = 0f

    @Volatile
    private var glowMode = MODE_LISTENING

    // mic button dabane par ye chalega
    var onMicTap: (() -> Unit)? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    /** SpeechRecognizer ke onRmsChanged ki value yahan bhejo */
    fun setLevel(rmsdB: Float) {
        voiceLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }

    fun setMode(mode: Int) {
        glowMode = mode
    }

    private fun dpPx(v: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
        ).toInt()

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    fun show() {
        handler.post {
            if (!canDraw() || glowView != null) return@post
            voiceLevel = 0f
            glowMode = MODE_LISTENING

            // ---- 1) poori screen: parda + kinaare ki roshni (touch aar-paar) ----
            val view = GlowView(context)
            view.alpha = 0f
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
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
                view.animate().alpha(1f).setDuration(350).start()
            } catch (e: Exception) {
                glowView = null
                return@post
            }

            // ---- 2) neeche chhota touchable gola mic ----
            val mic = MicView(context)
            mic.alpha = 0f
            val size = dpPx(150f)
            val mp = WindowManager.LayoutParams(
                size,
                size,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            mp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            mp.y = dpPx(44f)
            try {
                windowManager.addView(mic, mp)
                micView = mic
                mic.startSpin()
                mic.animate().alpha(1f).setDuration(350).start()
            } catch (e: Exception) {
                micView = null
            }
        }
    }

    fun hide() {
        handler.post {
            val view = glowView
            val mic = micView
            glowView = null
            micView = null
            if (view != null) {
                view.animate().alpha(0f).setDuration(250).withEndAction {
                    view.stopSpin()
                    try {
                        windowManager.removeView(view)
                    } catch (e: Exception) {
                    }
                }.start()
            }
            if (mic != null) {
                mic.animate().alpha(0f).setDuration(200).withEndAction {
                    mic.stopSpin()
                    try {
                        windowManager.removeView(mic)
                    } catch (e: Exception) {
                    }
                }.start()
            }
        }
    }

    // aawaaz ka level + mode ke hisaab se chalu hone wala smooth value
    private var sharedSmooth = 0f
    private var lastSmoothTick = 0L

    private fun updateSmooth(t: Float) {
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs == lastSmoothTick) return
        lastSmoothTick = nowMs
        var target = voiceLevel
        voiceLevel = voiceLevel * 0.93f
        target = when (glowMode) {
            MODE_SPEAKING -> (0.45f + 0.35f * sin(t * 8f) * sin(t * 3f)).coerceIn(0f, 1f)
            MODE_THINKING -> 0.18f
            else -> max(target, 0.1f + 0.04f * sin(t * 2f))
        }
        sharedSmooth += (target - sharedSmooth) * (if (target > sharedSmooth) 0.4f else 0.12f)
    }

    // =====================================================================
    // poori screen: parda + kinaare ki roshni
    // =====================================================================
    private inner class GlowView(ctx: Context) : View(ctx) {

        private val ringColorsA = intArrayOf(
            Color.parseColor("#FF1744"), // laal
            Color.parseColor("#FFEA00"), // peela
            Color.parseColor("#00E676"), // hara
            Color.parseColor("#2979FF"), // neela
            Color.parseColor("#FF1744")
        )
        private val ringColorsB = intArrayOf(
            Color.parseColor("#2979FF"),
            Color.parseColor("#D500F9"), // jamuni
            Color.parseColor("#FF1744"),
            Color.parseColor("#00E5FF"),
            Color.parseColor("#2979FF")
        )

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val matrixA = Matrix()
        private val matrixB = Matrix()

        private var shaderA: SweepGradient? = null
        private var shaderB: SweepGradient? = null
        private var animator: ValueAnimator? = null
        private var cornerPx = 0f

        private fun dp(v: Float): Float =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
            )

        init {
            ringPaint.style = Paint.Style.STROKE
            ringPaint.strokeCap = Paint.Cap.ROUND
            dimPaint.style = Paint.Style.FILL
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
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 1000
            a.interpolator = LinearInterpolator()
            a.repeatCount = ValueAnimator.INFINITE
            a.addUpdateListener { invalidate() }
            animator = a
            a.start()
        }

        fun stopSpin() {
            animator?.cancel()
            animator = null
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            shaderA = SweepGradient(w / 2f, h / 2f, ringColorsA, null)
            shaderB = SweepGradient(w / 2f, h / 2f, ringColorsB, null)
            val v = RadialGradient(
                w / 2f, h / 2f, hypot(w.toFloat(), h.toFloat()) / 2f,
                intArrayOf(
                    Color.argb(60, 0, 0, 0),
                    Color.argb(110, 0, 0, 0),
                    Color.argb(190, 0, 0, 0)
                ),
                floatArrayOf(0.35f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
            dimPaint.shader = v
        }

        private fun drawRing(
            canvas: Canvas,
            inset: Float,
            strokeW: Float,
            radius: Float,
            alpha: Int,
            shader: Shader?,
            color: Int
        ) {
            val half = inset + strokeW / 2f
            rect.set(half, half, width - half, height - half)
            ringPaint.shader = shader
            ringPaint.color = color
            ringPaint.strokeWidth = strokeW
            ringPaint.alpha = alpha.coerceIn(0, 255)
            val r = (radius - half).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, r, r, ringPaint)
        }

        override fun onDraw(canvas: Canvas) {
            val a = shaderA ?: return
            val b = shaderB ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            val t = (SystemClock.uptimeMillis() % 100000L) / 1000f
            updateSmooth(t)
            val smooth = sharedSmooth

            // kaala parda
            canvas.drawRect(0f, 0f, w, h, dimPaint)

            val custom = context.getSharedPreferences("ria_prefs", Context.MODE_PRIVATE)
                .getInt("glow_radius_dp", 0)
            val radius = when {
                custom > 0 -> dp(custom.toFloat())
                cornerPx > 0f -> cornerPx
                else -> dp(52f)
            }
            val angle = t * 128f
            matrixA.setRotate(angle, w / 2f, h / 2f)
            a.setLocalMatrix(matrixA)
            matrixB.setRotate(-angle * 0.6f + 90f, w / 2f, h / 2f)
            b.setLocalMatrix(matrixB)

            val breathe = 1f + 0.10f * sin(t * 2.4f) + 0.5f * smooth

            // peechhe wali dhundhli parat
            drawRing(canvas, 0f, dp(30f) * breathe, radius, 35, b, Color.WHITE)
            drawRing(canvas, 0f, dp(20f) * breathe, radius, 70, b, Color.WHITE)
            // aage wali tez parat
            drawRing(canvas, 0f, dp(13f) * breathe, radius, 120, a, Color.WHITE)
            drawRing(canvas, 0f, dp(8f) * breathe, radius, 200, a, Color.WHITE)
            drawRing(canvas, 0f, dp(4f), radius, 255, a, Color.WHITE)
            // andar ki chhaaya (gehrayi)
            drawRing(canvas, dp(11f) * breathe, dp(7f), radius, 110, null, Color.BLACK)
            // chamakti patli lakeer
            drawRing(canvas, dp(2.2f), dp(1.2f), radius, 150, null, Color.WHITE)
        }
    }

    // =====================================================================
    // neeche ka gola mic button (touchable)
    // =====================================================================
    private inner class MicView(ctx: Context) : View(ctx) {

        private val ringColors = intArrayOf(
            Color.parseColor("#FF1744"),
            Color.parseColor("#FFEA00"),
            Color.parseColor("#00E676"),
            Color.parseColor("#2979FF"),
            Color.parseColor("#FF1744")
        )

        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val arcRect = RectF()
        private val matrixOrb = Matrix()

        private val haloShader: RadialGradient
        private val bodyShader: RadialGradient
        private val ringShader: SweepGradient
        private var animator: ValueAnimator? = null

        private fun dp(v: Float): Float =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
            )

        init {
            haloShader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(
                    Color.argb(255, 80, 120, 255),
                    Color.argb(120, 160, 80, 255),
                    Color.argb(0, 160, 80, 255)
                ),
                floatArrayOf(0.25f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
            haloPaint.style = Paint.Style.FILL
            haloPaint.shader = haloShader

            bodyShader = RadialGradient(
                -0.35f, -0.35f, 1.5f,
                intArrayOf(
                    Color.parseColor("#9DB4FF"),
                    Color.parseColor("#3D5AFE"),
                    Color.parseColor("#0B1250")
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            bodyPaint.style = Paint.Style.FILL
            bodyPaint.shader = bodyShader

            ringShader = SweepGradient(0f, 0f, ringColors, null)
            ringPaint.style = Paint.Style.STROKE
            ringPaint.strokeCap = Paint.Cap.ROUND
            ringPaint.shader = ringShader
            ringPaint.strokeWidth = 0.07f

            iconFill.style = Paint.Style.FILL
            iconFill.color = Color.WHITE
            iconStroke.style = Paint.Style.STROKE
            iconStroke.color = Color.WHITE
            iconStroke.strokeCap = Paint.Cap.ROUND

            isClickable = true
            setOnClickListener { onMicTap?.invoke() }
        }

        fun startSpin() {
            animator?.cancel()
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 1000
            a.interpolator = LinearInterpolator()
            a.repeatCount = ValueAnimator.INFINITE
            a.addUpdateListener { invalidate() }
            animator = a
            a.start()
        }

        fun stopSpin() {
            animator?.cancel()
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val t = (SystemClock.uptimeMillis() % 100000L) / 1000f
            updateSmooth(t)
            val smooth = sharedSmooth

            val cx = w / 2f
            val cy = h / 2f
            // dp(30) se dp(46) tak: aawaaz ke saath bada-chhota
            val r = dp(30f) + dp(16f) * smooth + dp(1.5f) * sin(t * 3f)

            // blink: sunte waqt dheere dheere chamakta-bujhta
            val blink = if (glowMode == MODE_LISTENING) 0.78f + 0.22f * sin(t * 5f) else 1f

            // halo
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(r * 1.55f, r * 1.55f)
            haloPaint.alpha = ((110 + 145 * smooth) * blink).toInt().coerceIn(0, 255)
            canvas.drawCircle(0f, 0f, 1f, haloPaint)
            canvas.restore()

            // ghoomti rangeen ring
            val spin = if (glowMode == MODE_THINKING) 3f else 1.5f
            matrixOrb.setRotate(t * 128f * spin, 0f, 0f)
            ringShader.setLocalMatrix(matrixOrb)
            ringPaint.alpha = (255 * blink).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(r * 1.2f, r * 1.2f)
            canvas.drawCircle(0f, 0f, 1f, ringPaint)
            canvas.restore()

            // gola (3D sphere)
            bodyPaint.alpha = (255 * (0.85f + 0.15f * blink)).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(r, r)
            canvas.drawCircle(0f, 0f, 1f, bodyPaint)
            canvas.restore()

            // mic icon
            iconStroke.strokeWidth = r * 0.07f
            rect.set(cx - r * 0.2f, cy - r * 0.5f, cx + r * 0.2f, cy + r * 0.1f)
            canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, iconFill)
            arcRect.set(cx - r * 0.36f, cy - r * 0.32f, cx + r * 0.36f, cy + r * 0.22f)
            canvas.drawArc(arcRect, 0f, 180f, false, iconStroke)
            canvas.drawLine(cx, cy + r * 0.22f, cx, cy + r * 0.42f, iconStroke)
            canvas.drawLine(cx - r * 0.18f, cy + r * 0.42f, cx + r * 0.18f, cy + r * 0.42f, iconStroke)
        }
    }
}