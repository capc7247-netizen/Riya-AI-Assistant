package com.user.riaassistant

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Ria ka popup overlay (Google Assistant jaisa, neeche se aata hai).
 * Permission chahiye: Settings > Apps > Riya > "Display over other apps" ON.
 */
class OverlayManager(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var container: FrameLayout? = null
    private var pulse: ObjectAnimator? = null
    private var statusView: TextView? = null
    private var userView: TextView? = null
    private var replyView: TextView? = null

    /** "Band karo" dabane par kya karna hai (service yahan endConversationMode set karegi) */
    var onClose: (() -> Unit)? = null

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show(status: String) {
        handler.post {
            if (!canDraw()) return@post
            if (container == null) build()
            statusView?.text = status
            userView?.text = ""
            replyView?.text = ""
        }
    }

    fun setStatus(text: String) {
        handler.post { statusView?.text = text }
    }

    fun setUserText(text: String) {
        handler.post { userView?.text = "\"" + text + "\"" }
    }

    fun setReply(text: String) {
        handler.post { replyView?.text = text }
    }

    fun hide() {
        handler.post {
            pulse?.cancel()
            pulse = null
            container?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                }
            }
            container = null
            statusView = null
            userView = null
            replyView = null
        }
    }

    private fun build() {
        // bahar ka transparent frame (kinaaron se thoda gap)
        val frame = FrameLayout(context)
        frame.setPadding(dp(12), dp(12), dp(12), dp(16))

        // dark glass card
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setPadding(dp(20), dp(24), dp(20), dp(16))
        val cardBg = GradientDrawable()
        cardBg.setColor(Color.parseColor("#F2101020"))
        cardBg.cornerRadius = dp(28).toFloat()
        cardBg.setStroke(dp(1), Color.parseColor("#44B388FF"))
        card.background = cardBg

        // glowing orb
        val orb = View(context)
        val orbBg = GradientDrawable()
        orbBg.shape = GradientDrawable.OVAL
        orbBg.gradientType = GradientDrawable.RADIAL_GRADIENT
        orbBg.gradientRadius = dp(36).toFloat()
        orbBg.colors = intArrayOf(
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#B388FF"),
            Color.parseColor("#2979FF")
        )
        orb.background = orbBg
        card.addView(orb, LinearLayout.LayoutParams(dp(72), dp(72)))

        pulse = ObjectAnimator.ofPropertyValuesHolder(
            orb,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.2f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.2f)
        ).apply {
            duration = 700
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }

        // status
        val status = TextView(context)
        status.setTextColor(Color.WHITE)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        status.setTypeface(null, Typeface.BOLD)
        status.gravity = Gravity.CENTER
        val statusLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        statusLp.topMargin = dp(18)
        card.addView(status, statusLp)
        statusView = status

        // user ne kya bola
        val user = TextView(context)
        user.setTextColor(Color.parseColor("#B0BEC5"))
        user.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        user.gravity = Gravity.CENTER
        val userLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        userLp.topMargin = dp(8)
        card.addView(user, userLp)
        userView = user

        // Ria ka jawab
        val reply = TextView(context)
        reply.setTextColor(Color.parseColor("#E8EAF6"))
        reply.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        reply.gravity = Gravity.CENTER
        reply.maxLines = 6
        val replyLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        replyLp.topMargin = dp(10)
        card.addView(reply, replyLp)
        replyView = reply

        // band karo button
        val close = TextView(context)
        close.text = "बंद करो"
        close.setTextColor(Color.parseColor("#FF8A80"))
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        close.gravity = Gravity.CENTER
        close.setPadding(dp(24), dp(12), dp(24), dp(12))
        close.setOnClickListener { onClose?.invoke() }
        val closeLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        closeLp.topMargin = dp(8)
        card.addView(close, closeLp)

        frame.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM

        try {
            windowManager.addView(frame, params)
            container = frame
        } catch (e: Exception) {
            pulse?.cancel()
            pulse = null
        }
    }
}
