package com.user.riaassistant

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

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
    private lateinit var orbLabel: TextView
    private lateinit var orbCircle: FrameLayout

    private val BG_DARK = Color.parseColor("#0E0B1A")
    private val CARD_DARK = Color.parseColor("#1B1730")
    private val ACCENT_PINK = Color.parseColor("#FF4FA3")
    private val ACCENT_PURPLE = Color.parseColor("#7B5CFA")
    private val ACCENT_CYAN = Color.parseColor("#3FE0D0")
    private val TEXT_WHITE = Color.parseColor("#F3F1FA")
    private val TEXT_GREY = Color.parseColor("#9C97B5")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ria_prefs", MODE_PRIVATE)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG_DARK)
        root.setPadding(40, 90, 40, 40)

        root.addView(buildTopBar())
        root.addView(spacer(40))
        root.addView(buildGreeting())
        root.addView(spacer(40))
        root.addView(buildOrb())
        root.addView(spacer(50))
        root.addView(buildQuickActions())
        root.addView(spacer(30))
        root.addView(buildInfoCards())

        val spacerFill = View(this)
        root.addView(spacerFill, LinearLayout.LayoutParams(0, 0, 1f))

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
        menuBtn.setOnClickListener { Toast.makeText(this, "Menu jald aayega", Toast.LENGTH_SHORT).show() }
        bar.addView(menuBtn, LinearLayout.LayoutParams(90, 90))

        val title = TextView(this)
        title.text = "Ria"
        title.textSize = 24f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(TEXT_WHITE)
        title.gravity = Gravity.CENTER
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val bellBtn = ImageButton(this)
        bellBtn.setImageResource(android.R.drawable.ic_popup_reminder)
        bellBtn.background = null
        bellBtn.setColorFilter(TEXT_WHITE)
        bellBtn.setOnClickListener { Toast.makeText(this, "Koi naya notification nahi", Toast.LENGTH_SHORT).show() }
        bar.addView(bellBtn, LinearLayout.LayoutParams(90, 90))

        val settingsBtn = ImageButton(this)
        settingsBtn.setImageResource(android.R.drawable.ic_menu_manage)
        settingsBtn.background = circleBg(CARD_DARK)
        settingsBtn.setColorFilter(ACCENT_PINK)
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        val settingsParams = LinearLayout.LayoutParams(90, 90)
        settingsParams.marginStart = 16
        bar.addView(settingsBtn, settingsParams)

        return bar
    }

    private fun buildGreeting(): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }

        val greetText = TextView(this)
        greetText.text = greeting
        greetText.textSize = 16f
        greetText.setTextColor(TEXT_GREY)
        col.addView(greetText)

        statusText = TextView(this)
        statusText.textSize = 15f
        statusText.setTextColor(ACCENT_CYAN)
        statusText.setPadding(0, 8, 0, 0)
        col.addView(statusText)

        return col
    }

    private fun buildOrb(): FrameLayout {
        val wrapper = FrameLayout(this)
        val size = 520
        wrapper.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, size)

        val outerRing = FrameLayout(this)
        val ringParams = FrameLayout.LayoutParams(size, size)
        ringParams.gravity = Gravity.CENTER
        val ringBg = GradientDrawable()
        ringBg.shape = GradientDrawable.OVAL
        ringBg.setStroke(10, ACCENT_PURPLE)
        ringBg.setColor(Color.TRANSPARENT)
        outerRing.background = ringBg
        wrapper.addView(outerRing, ringParams)

        orbCircle = FrameLayout(this)
        val innerSize = size - 90
        val innerParams = FrameLayout.LayoutParams(innerSize, innerSize)
        innerParams.gravity = Gravity.CENTER
        val innerBg = GradientDrawable()
        innerBg.shape = GradientDrawable.OVAL
        innerBg.gradientType = GradientDrawable.RADIAL_GRADIENT
        innerBg.gradientRadius = innerSize / 1.4f
        innerBg.colors = intArrayOf(ACCENT_PURPLE, CARD_DARK)
        orbCircle.background = innerBg
        orbCircle.setOnClickListener { onOrbClicked() }

        val innerCol = LinearLayout(this)
        innerCol.orientation = LinearLayout.VERTICAL
        innerCol.gravity = Gravity.CENTER

        val aiLabel = TextView(this)
        aiLabel.text = "AI ASSISTANT"
        aiLabel.textSize = 11f
        aiLabel.setTextColor(TEXT_GREY)
        aiLabel.gravity = Gravity.CENTER
        innerCol.addView(aiLabel)

        orbLabel = TextView(this)
        orbLabel.text = "RIA"
        orbLabel.textSize = 34f
        orbLabel.setTypeface(null, Typeface.BOLD)
        orbLabel.setTextColor(TEXT_WHITE)
        orbLabel.gravity = Gravity.CENTER
        innerCol.addView(orbLabel)

        orbCircle.addView(innerCol, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        wrapper.addView(orbCircle, innerParams)
        return wrapper
    }

    private fun buildQuickActions(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        row.addView(quickActionButton("Music") {
            Toast.makeText(this, "Music feature jald aayega", Toast.LENGTH_SHORT).show()
        }, rowWeightParams())
        row.addView(spacerHorizontal(20))
        row.addView(quickActionButton("Scan") {
            Toast.makeText(this, "Screen scan feature jald aayega", Toast.LENGTH_SHORT).show()
        }, rowWeightParams())
        row.addView(spacerHorizontal(20))
        row.addView(quickActionButton("Journal") {
            Toast.makeText(this, "Journal feature jald aayega", Toast.LENGTH_SHORT).show()
        }, rowWeightParams())

        return row
    }

    private fun quickActionButton(label: String, onClick: () -> Unit): TextView {
        val btn = TextView(this)
        btn.text = label
        btn.textSize = 13f
        btn.setTextColor(TEXT_WHITE)
        btn.gravity = Gravity.CENTER
        btn.setPadding(0, 36, 0, 36)
        val bg = GradientDrawable()
        bg.cornerRadius = 28f
        bg.setColor(CARD_DARK)
        btn.background = bg
        btn.setOnClickListener { onClick() }
        return btn
    }

    private fun buildInfoCards(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        row.addView(infoCard("Weather", "No data"), rowWeightParams())
        row.addView(spacerHorizontal(20))
        row.addView(infoCard("Today", SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date())), rowWeightParams())
        row.addView(spacerHorizontal(20))
        row.addView(infoCard("Mood", "Ready"), rowWeightParams())

        return row
    }

    private fun infoCard(title: String, value: String): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(24, 28, 24, 28)
        val bg = GradientDrawable()
        bg.cornerRadius = 26f
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
        v.setPadding(0, 6, 0, 0)
        col.addView(v)

        return col
    }

    private fun buildBottomNav(): LinearLayout {
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL
        val navParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 170)
        navParams.topMargin = 30
        nav.layoutParams = navParams
        val navBg = GradientDrawable()
        navBg.cornerRadius = 36f
        navBg.setColor(CARD_DARK)
        nav.background = navBg
        nav.setPadding(20, 0, 20, 0)

        nav.addView(navItem("Home", true) {}, rowWeightParams())
        nav.addView(navItem("Memories", false) {
            Toast.makeText(this, "Memories feature jald aayega", Toast.LENGTH_SHORT).show()
        }, rowWeightParams())

        val micBtn = FrameLayout(this)
        val micParams = LinearLayout.LayoutParams(120, 120)
        val micBg = GradientDrawable()
        micBg.shape = GradientDrawable.OVAL
        micBg.setColor(ACCENT_PINK)
        micBtn.background = micBg
        micBtn.setOnClickListener { onOrbClicked() }
        nav.addView(micBtn, micParams)

        nav.addView(navItem("Chat", false) {
            Toast.makeText(this, "Chat feature jald aayega", Toast.LENGTH_SHORT).show()
        }, rowWeightParams())
        nav.addView(navItem("Settings", false) {
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

    private fun spacer(heightPx: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
        return v
    }

    private fun spacerHorizontal(widthPx: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(widthPx, 1)
        return v
    }

    private fun circleBg(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        return d
    }

    // ---------- LOGIC (start/stop service) ----------

    private fun isServiceRunning(): Boolean = prefs.getBoolean("service_running", false)

    private fun setServiceRunning(running: Boolean) {
        prefs.edit().putBoolean("service_running", running).apply()
    }

    private fun updateUiState() {
        val running = isServiceRunning()
        if (running) {
            statusText.text = "Chalu hai - bolo \"Hey Ria\""
            orbLabel.text = "RIA"
        } else {
            statusText.text = "Band hai - mic dabao shuru karne ke liye"
            orbLabel.text = "RIA"
        }
    }

    private fun onOrbClicked() {
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Pehle Settings mein jaakar API key save karo", Toast.LENGTH_LONG).show()
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