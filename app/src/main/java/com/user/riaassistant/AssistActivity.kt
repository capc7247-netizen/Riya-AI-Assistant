package com.user.riaassistant

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class AssistActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, VoiceAssistantService::class.java)
        startService(serviceIntent)

        finish()
    }
}