package com.user.riaassistant

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Service aur home screen ke beech chhoti si saajhi sthiti.
 * Service in dono ko update kar de to gola aawaaz ke saath bada-chhota hota hai.
 */
object RiaState {
    @Volatile
    var level = 0f // 0..1, aawaaz ka level

    @Volatile
    var speaking = false // Ria bol rahi hai
}

class MainActivity : AppCompatActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS
    )

    private val PERMISSION_REQUEST_CODE = 101
    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var orb: OrbView
    private lateinit var stateValue: TextView

    private val BG_DARK = Color.parseColor("#0E0B1A")
    private val CARD_DARK = Color.parseColor("#1B1730")
    private val ACCENT_PINK = Color.parseColor("#FF4FA3")
    private val ACCENT_PURPLE = Color.parseColor("#7B5CFA")
    private val ACCENT_CYAN = Color.parseColor("#3FE0D0")
    private val TEXT_WHITE = Color.parseColor("#F3F1FA")
    private val TEXT_GREY = Color.parseColor("#9C97B5")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ria_prefs", MODE_PRIVATE)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG_DARK)
        root.setPadding(dp(16), dp(36), dp(16), dp(14))

        root.addView(buildTopBar())

        // beech ka hissa scroll hota hai, neeche ka nav hamesha dikhta hai
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.addView(spacer(18))
        content.addView(buildGreeting())
        content.addView(spacer(10))
        content.addView(buildOrb())
        content.addView(spacer(14))
        content.addView(buildQuickActions())
        content.addView(spacer(16))
        content.addView(buildInfoCards())
        content.addView(spacer(10))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(buildBottomNav())

        setContentView(root)
        updateUiState()

        if (!hasAllPermissions()) {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    // ---------- UI SECTIONS ----------

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL

        val menuBtn = ImageButton(this)
        menuBtn.setImageResource(android.R.drawable.ic_menu_sort_by_size)
        menuBtn.background = null
        menuBtn.setColorFilter(TEXT_WHITE)
        menuBtn.setOnClickListener { showMemoriesDialog() }
        bar.addView(menuBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        val title = TextView(this)
        title.text = "रिया"
        title.textSize = 24f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(TEXT_WHITE)
        title.gravity = Gravity.CENTER
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val bellBtn = ImageButton(this)
        bellBtn.setImageResource(android.R.drawable.ic_popup_reminder)
        bellBtn.background = null
        bellBtn.setColorFilter(TEXT_WHITE)
        bellBtn.setOnClickListener {
            Toast.makeText(this, "कोई नई सूचना नहीं है", Toast.LENGTH_SHORT).show()
        }
        bar.addView(bellBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        val settingsBtn = ImageButton(this)
        settingsBtn.setImageResource(android.R.drawable.ic_menu_manage)
        settingsBtn.background = circleBg(CARD_DARK)
        settingsBtn.setColorFilter(ACCENT_PINK)
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        val settingsParams = LinearLayout.LayoutParams(dp(40), dp(40))
        settingsParams.marginStart = dp(8)
        bar.addView(settingsBtn, settingsParams)

        return bar
    }

    private fun buildGreeting(): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(6), 0, 0, 0)

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> "सुप्रभात"
            hour < 17 -> "नमस्कार"
            else -> "शुभ संध्या"
        }

        val greetText = TextView(this)
        greetText.text = greeting
        greetText.textSize = 16f
        greetText.setTextColor(TEXT_GREY)
        col.addView(greetText)

        statusText = TextView(this)
        statusText.textSize = 15f
        statusText.setTextColor(ACCENT_CYAN)
        statusText.setPadding(0, dp(3), 0, 0)
        col.addView(statusText)

        return col
    }

    private fun buildOrb(): OrbView {
        orb = OrbView(this)
        orb.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(300))
        orb.setOnClickListener { onOrbClicked() }
        return orb
    }

    private fun buildQuickActions(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        row.addView(quickActionButton("गाना") {
            Toast.makeText(this, "बोलिए: हे रिया, यूट्यूब पर गाना बजाओ", Toast.LENGTH_LONG).show()
        }, rowWeightParams())
        row.addView(spacerHorizontal(10))
        row.addView(quickActionButton("स्क्रीन स्कैन") {
            Toast.makeText(this, "बोलिए: हे रिया, स्क्रीन एनालाइज करो", Toast.LENGTH_LONG).show()
        }, rowWeightParams())
        row.addView(spacerHorizontal(10))
        row.addView(quickActionButton("याददाश्त") { showMemoriesDialog() }, rowWeightParams())

        return row
    }

    private fun quickActionButton(label: String, onClick: () -> Unit): TextView {
        val btn = TextView(this)
        btn.text = label
        btn.textSize = 13f
        btn.setTextColor(TEXT_WHITE)
        btn.gravity = Gravity.CENTER
        btn.setPadding(0, dp(16), 0, dp(16))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(14).toFloat()
        bg.setColor(CARD_DARK)
        btn.background = bg
        btn.setOnClickListener { onClick() }
        return btn
    }

    private fun buildInfoCards(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val city = prefs.getString("city", "") ?: ""
        row.addView(infoCard("शहर", if (city.isEmpty()) "—" else city), rowWeightParams())
        row.addView(spacerHorizontal(10))
        row.addView(
            infoCard("आज", SimpleDateFormat("dd MMM", Locale("hi", "IN")).format(Date())),
            rowWeightParams()
        )
        row.addView(spacerHorizontal(10))
        val stateCard = infoCard("स्थिति", "तैयार")
        stateValue = stateCard.getChildAt(1) as TextView
        row.addView(stateCard, rowWeightParams())

        return row
    }

    private fun infoCard(title: String, value: String): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(14), dp(12), dp(14))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(13).toFloat()
        bg.setColor(CARD_DARK)
        col.background = bg

        val t = TextView(this)
        t.text = title
        t.textSize = 11f
        t.setTextColor(TEXT_GREY)
        col.addView(t)

        val v = TextView(this)
        v.text = value
        v.textSize = 15f
        v.setTypeface(null, Typeface.BOLD)
        v.setTextColor(TEXT_WHITE)
        v.setPadding(0, dp(3), 0, 0)
        v.maxLines = 1
        col.addView(v)

        return col
    }

    private fun buildBottomNav(): LinearLayout {
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL
        val navParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72))
        navParams.topMargin = dp(10)
        nav.layoutParams = navParams
        val navBg = GradientDrawable()
        navBg.cornerRadius = dp(22).toFloat()
        navBg.setColor(CARD_DARK)
        nav.background = navBg
        nav.setPadding(dp(8), 0, dp(8), 0)

        nav.addView(navItem("होम", true) {}, rowWeightParams())
        nav.addView(navItem("याददाश्त", false) { showMemoriesDialog() }, rowWeightParams())

        // beech ka gola mic button: halka halka blink karta hai
        val micBtn = FrameLayout(this)
        val micBg = GradientDrawable()
        micBg.shape = GradientDrawable.OVAL
        micBg.setColor(ACCENT_PINK)
        micBtn.background = micBg
        val icon = ImageView(this)
        icon.setImageResource(android.R.drawable.ic_btn_speak_now)
        icon.setColorFilter(Color.WHITE)
        micBtn.addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        micBtn.setOnClickListener { onOrbClicked() }
        val blink = ObjectAnimator.ofFloat(micBtn, "alpha", 1f, 0.55f)
        blink.duration = 900
        blink.repeatMode = ObjectAnimator.REVERSE
        blink.repeatCount = ObjectAnimator.INFINITE
        blink.start()
        nav.addView(micBtn, LinearLayout.LayoutParams(dp(54), dp(54)))

        nav.addView(navItem("चैट", false) {
            Toast.makeText(this, "चैट जल्द आएगी", Toast.LENGTH_SHORT).show()
        }, rowWeightParams())
        nav.addView(navItem("सेटिंग्स", false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, rowWeightParams())

        return nav
    }

    private fun navItem(label: String, active: Boolean, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 11f
        t.gravity = Gravity.CENTER
        t.setTextColor(if (active) ACCENT_PINK else TEXT_GREY)
        t.setOnClickListener { onClick() }
        return t
    }

    // ---------- HELPERS ----------

    private fun rowWeightParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun spacer(heightDp: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
        return v
    }

    private fun spacerHorizontal(widthDp: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
        return v
    }

    private fun circleBg(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        return d
    }

    // ---------- YAADDAASHT ----------

    private fun showMemoriesDialog() {
        val list = GeminiClient.memoryList(applicationContext)
        val message = if (list.isEmpty()) {
            "अभी रिया को आपके बारे में कुछ याद नहीं है।\n\nबोलिए: \"याद रखो, मेरा जन्मदिन 5 मार्च है\""
        } else {
            list.joinToString("\n\n") { "• $it" }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("रिया को क्या याद है")
            .setMessage(message)
            .setPositiveButton("ठीक है", null)
        if (list.isNotEmpty()) {
            builder.setNegativeButton("सब भूल जाओ") { _, _ ->
                GeminiClient.clearMemory(applicationContext)
                Toast.makeText(this, "सब भूल गई", Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }

    // ---------- LOGIC (start/stop service) ----------

    private fun isServiceRunning(): Boolean = prefs.getBoolean("service_running", false)

    private fun setServiceRunning(running: Boolean) {
        prefs.edit().putBoolean("service_running", running).apply()
    }

    private fun updateUiState() {
        val running = isServiceRunning()
        orb.active = running
        if (running) {
            statusText.text = "चालू है, बोलिए \"हे रिया\""
            stateValue.text = "चालू"
        } else {
            statusText.text = "बंद है, शुरू करने के लिए माइक दबाइए"
            stateValue.text = "बंद"
        }
    }

    private fun onOrbClicked() {
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "पहले सेटिंग्स में जाकर API key सेव करो", Toast.LENGTH_LONG).show()
            return
        }
        if (isServiceRunning()) {
            stopAssistantService()
        } else {
            if (hasAllPermissions()) {
                startAssistantService()
            } else {
                requestPermissions()
            }
        }
    }

    private fun hasAllPermissions(): Boolean {
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && hasAllPermissions()) {
            startAssistantService()
        }
    }

    private fun startAssistantService() {
        val serviceIntent = Intent(this, VoiceAssistantService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        setServiceRunning(true)
        updateUiState()
    }

    private fun stopAssistantService() {
        stopService(Intent(this, VoiceAssistantService::class.java))
        setServiceRunning(false)
        updateUiState()
    }
}

// =====================================================================
// Beech ka 3D gola: dhadakta hua gola + teen ghoomti chamakti ring
// =====================================================================
class OrbView(context: Context) : View(context) {

    var active = false

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val haloShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            Color.argb(255, 123, 92, 250),
            Color.argb(120, 255, 79, 163),
            Color.argb(0, 255, 79, 163)
        ),
        floatArrayOf(0.3f, 0.65f, 1f),
        Shader.TileMode.CLAMP
    )
    private val bodyShader = RadialGradient(
        -0.35f, -0.4f, 1.6f,
        intArrayOf(
            Color.parseColor("#B9A8FF"),
            Color.parseColor("#7B5CFA"),
            Color.parseColor("#1B1244")
        ),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP
    )

    private val ringColors = intArrayOf(
        Color.parseColor("#FF4FA3"),
        Color.parseColor("#3FE0D0"),
        Color.parseColor("#FFD54F")
    )

    private var smooth = 0f

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    init {
        haloPaint.style = Paint.Style.FILL
        haloPaint.shader = haloShader
        bodyPaint.style = Paint.Style.FILL
        bodyPaint.shader = bodyShader
        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeCap = Paint.Cap.ROUND
        wavePaint.style = Paint.Style.STROKE
        textPaint.color = Color.WHITE
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textAlign = Paint.Align.CENTER
        smallPaint.color = Color.parseColor("#C9C3E6")
        smallPaint.textAlign = Paint.Align.CENTER
    }

    // 3D mein ghoomti ring ko screen par projekt karke segments mein banao
    private fun drawRing3D(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        ax: Float, ay: Float, az: Float, color: Int, strokeW: Float
    ) {
        val n = 72
        var px = 0f
        var py = 0f
        var pz = 0f
        val cax = cos(ax)
        val sax = sin(ax)
        val cay = cos(ay)
        val say = sin(ay)
        val caz = cos(az)
        val saz = sin(az)
        ringPaint.color = color
        for (i in 0..n) {
            val a = i * (6.2831855f / n)
            val x = cos(a)
            val y = sin(a)
            // X axis par ghumao
            val y1 = y * cax
            val z1 = y * sax
            // Y axis par ghumao
            val x2 = x * cay + z1 * say
            val z2 = -x * say + z1 * cay
            // Z axis par ghumao
            val x3 = x2 * caz - y1 * saz
            val y3 = x2 * saz + y1 * caz
            val sx = cx + x3 * radius
            val sy = cy + y3 * radius
            if (i > 0) {
                val depth = ((pz + z2) / 2f).coerceIn(-1f, 1f) // -1 peechhe, +1 aage
                ringPaint.alpha = (150 + 105 * depth).toInt().coerceIn(40, 255)
                ringPaint.strokeWidth = strokeW * (0.75f + 0.45f * (depth + 1f) / 2f)
                canvas.drawLine(px, py, sx, sy, ringPaint)
            }
            px = sx
            py = sy
            pz = z2
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val t = (SystemClock.uptimeMillis() % 1000000L) / 1000f

        // aawaaz ka level: service se aaya level ya bolte waqt halki dhadkan
        val voice = if (active) {
            if (RiaState.speaking) 0.45f + 0.3f * sin(t * 8f) * sin(t * 3f) else RiaState.level
        } else 0f
        smooth += (voice - smooth) * (if (voice > smooth) 0.35f else 0.1f)

        val speed = if (active) 1.8f else 1f
        val breathe = 0.5f + 0.5f * sin(t * (if (active) 3.2f else 1.5f))
        val base = min(w, h) * 0.27f
        val r = base * (1f + 0.05f * breathe + 0.2f * smooth)

        // faile hue lehraate gole (aawaaz par)
        if (active) {
            for (i in 0..1) {
                val p = ((t * 0.7f + i * 0.5f) % 1f)
                wavePaint.color = Color.parseColor("#7B5CFA")
                wavePaint.alpha = ((1f - p) * 110).toInt().coerceIn(0, 255)
                wavePaint.strokeWidth = dp(2f)
                canvas.drawCircle(cx, cy, r * (1.25f + p * (0.9f + smooth)), wavePaint)
            }
        }

        // halo
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(r * 2.15f, r * 2.15f)
        haloPaint.alpha = (95 + 70 * breathe + 90 * smooth).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, 1f, haloPaint)
        canvas.restore()

        // peechhe ka aadha hissa teeno ring ka (halka), phir gola, phir aage ka hissa
        val rr = r * 1.42f
        val sw = dp(3.2f)
        drawRing3D(canvas, cx, cy, rr, t * 0.9f * speed, t * 0.55f * speed, 0.3f, ringColors[0], sw)
        drawRing3D(canvas, cx, cy, rr * 1.06f, 1.1f + t * 0.5f * speed, t * 0.8f * speed, 1.2f, ringColors[1], sw)
        drawRing3D(canvas, cx, cy, rr * 1.12f, 0.4f + t * 0.35f * speed, 1.6f + t * 0.6f * speed, t * 0.45f * speed, ringColors[2], sw * 0.9f)

        // gola (3D sphere)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(r, r)
        bodyPaint.alpha = if (active) 255 else 235
        canvas.drawCircle(0f, 0f, 1f, bodyPaint)
        canvas.restore()

        // gole ke upar ki chamak (highlight)
        smallPaint.alpha = 255
        ringPaint.color = Color.WHITE
        ringPaint.alpha = 70
        ringPaint.strokeWidth = dp(2f)
        canvas.drawArc(cx - r * 0.82f, cy - r * 0.82f, cx + r * 0.82f, cy + r * 0.82f, 200f, 70f, false, ringPaint)

        // naam: "रिया"
        textPaint.textSize = r * 0.62f
        textPaint.setShadowLayer(dp(10f), 0f, 0f, Color.parseColor("#FF4FA3"))
        canvas.drawText("रिया", cx, cy + r * 0.18f, textPaint)
        smallPaint.textSize = r * 0.17f
        canvas.drawText("AI असिस्टेंट", cx, cy - r * 0.42f, smallPaint)

        postInvalidateOnAnimation()
    }
}