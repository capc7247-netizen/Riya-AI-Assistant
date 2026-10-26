package com.user.riaassistant

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var apiKeyInput: EditText
    private lateinit var notificationSwitch: Switch

    private val ACCENT_COLOR = Color.parseColor("#6750A4")
    private val BG_COLOR = Color.parseColor("#F5F5F7")
    private val CARD_COLOR = Color.parseColor("#FFFFFF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ria_prefs", MODE_PRIVATE)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(BG_COLOR)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 90, 48, 48)

        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL

        val backButton = ImageButton(this)
        backButton.setImageResource(android.R.drawable.ic_menu_revert)
        backButton.background = null
        backButton.setColorFilter(Color.parseColor("#1C1B1F"))
        backButton.setOnClickListener { finish() }
        topBar.addView(backButton, LinearLayout.LayoutParams(100, 100))

        val titleText = TextView(this)
        titleText.text = "Settings"
        titleText.textSize = 26f
        titleText.setTypeface(null, Typeface.BOLD)
        titleText.setTextColor(Color.parseColor("#1C1B1F"))
        titleText.setPadding(24, 0, 0, 0)
        topBar.addView(titleText)
        root.addView(topBar)
        root.addView(spacer())

        val apiCard = makeCard()
        apiCard.addView(sectionLabel("AI API KEY"))

        apiKeyInput = EditText(this)
        apiKeyInput.hint = "Gemini API key yahan paste karo"
        apiKeyInput.setText(prefs.getString("api_key", ""))
        apiKeyInput.setPadding(20, 20, 20, 20)
        apiCard.addView(apiKeyInput)

        val saveKeyButton = Button(this)
        saveKeyButton.text = "API KEY SAVE KARO"
        saveKeyButton.setTextColor(Color.WHITE)
        val saveBg = GradientDrawable()
        saveBg.cornerRadius = 30f
        saveBg.setColor(ACCENT_COLOR)
        saveKeyButton.background = saveBg
        saveKeyButton.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isNotEmpty()) {
                prefs.edit().putString("api_key", key).apply()
                Toast.makeText(this, "API key save ho gayi", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Pehle key daalo", Toast.LENGTH_SHORT).show()
            }
        }
        val btnParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 140)
        btnParams.topMargin = 24
        apiCard.addView(saveKeyButton, btnParams)
        root.addView(apiCard)
        root.addView(spacer())

        val notifCard = makeCard()
        val notifRow = LinearLayout(this)
        notifRow.orientation = LinearLayout.HORIZONTAL
        notifRow.gravity = Gravity.CENTER_VERTICAL

        val notifTextCol = LinearLayout(this)
        notifTextCol.orientation = LinearLayout.VERTICAL
        val notifTitle = TextView(this)
        notifTitle.text = "Detailed Notification"
        notifTitle.textSize = 16f
        notifTitle.setTextColor(Color.parseColor("#1C1B1F"))
        val notifDesc = TextView(this)
        notifDesc.text = "Status bar mein Ria kya sun rahi hai wo dikhao"
        notifDesc.textSize = 12f
        notifDesc.setTextColor(Color.parseColor("#79747E"))
        notifTextCol.addView(notifTitle)
        notifTextCol.addView(notifDesc)
        notifRow.addView(notifTextCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        notificationSwitch = Switch(this)
        notificationSwitch.isChecked = prefs.getBoolean("detailed_notification", true)
        notificationSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("detailed_notification", isChecked).apply()
        }
        notifRow.addView(notificationSwitch)
        notifCard.addView(notifRow)
        root.addView(notifCard)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun makeCard(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(40, 40, 40, 40)
        val bg = GradientDrawable()
        bg.setColor(CARD_COLOR)
        bg.cornerRadius = 32f
        card.background = bg
        return card
    }

    private fun sectionLabel(text: String): TextView {
        val label = TextView(this)
        label.text = text
        label.textSize = 12f
        label.setTextColor(Color.parseColor("#79747E"))
        label.setPadding(0, 0, 0, 16)
        return label
    }

    private fun spacer(): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(0, 50)
        return v
    }
}