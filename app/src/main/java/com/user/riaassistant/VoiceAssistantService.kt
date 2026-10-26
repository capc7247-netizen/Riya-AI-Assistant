package com.user.riaassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val DEBUG_TOASTS = true
        private const val CHANNEL_ID = "ria_assistant_channel"
        private const val NOTIFICATION_ID = 1
        private const val CONVERSATION_TIMEOUT_MS = 15000L
        private const val MODEL_DIR_NAME = "model_hi"

        // Hindi model devanagari mein likhta hai, isliye dono tarah ke shabd rakhe hain
        private val WAKE_WORDS = listOf(
            "हे रिया", "हेय रिया", "ए रिया", "हे रीया", "हे रिआ",
            "रिया", "रीया", "रिआ",
            "hey ria", "hey riya"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Vosk ka bhaari kaam (model load, start, stop) alag thread par, taaki app hang na ho
    private val voskThread = HandlerThread("ria-vosk").also { it.start() }
    private val voskHandler = Handler(voskThread.looper)
    private var lastToastTime = 0L

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var overlay: OverlayManager? = null
    private var glow: EdgeGlowOverlay? = null
    private var askedOverlayPermission = false
    private var consecutiveErrors = 0

    // internet na ho to Vosk se hi command suno
    @Volatile
    private var voskCommandMode = false

    // Vosk ka mic kabhi chup ho jaye to har 3 minute mein dobara chalu
    private val voskWatchdog = object : Runnable {
        override fun run() {
            if (!isInConversationMode) startVosk()
            mainHandler.postDelayed(this, 180000L)
        }
    }

    // true = bolne ke baad conversation band karo (jaise gaana chalne par)
    @Volatile
    private var endAfterSpeech = false

    // Vosk (background wake-word)
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var voskSpeechService: SpeechService? = null
    private var lastDebugText = ""

    @Volatile
    private var isInConversationMode = false

    // true = Ria bol rahi hai ya AI ka jawab aane wala hai (tab mic na sunein)
    @Volatile
    private var isSpeaking = false

    private var timeoutRunnable: Runnable? = null

    // ---------------- helpers ----------------

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun lower(s: String): String {
        return (s as java.lang.String).toLowerCase(Locale.getDefault())
    }

    private fun containsAny(text: String, words: Collection<String>): Boolean {
        for (w in words) {
            if (text.contains(w)) return true
        }
        return false
    }

    private fun tokens(text: String): List<String> {
        return text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private fun removeWords(text: String, words: Set<String>): String {
        return tokens(text).filter { it !in words }.joinToString(" ")
    }

    private fun debugToast(prefix: String, text: String) {
        if (!DEBUG_TOASTS) return
        mainHandler.post {
            Toast.makeText(applicationContext, "$prefix: $text", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- lifecycle ----------------

    override fun onCreate() {
        super.onCreate()

        overlay = OverlayManager(this)
        overlay?.onClose = { mainHandler.post { endConversationMode() } }
        glow = EdgeGlowOverlay(this)

        createNotificationChannel()
        val notif = buildNotification("सुन रही हूँ... (हे रिया बोलिए)")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            debugToast("FOREGROUND ERROR", e.message ?: "startForeground fail")
        }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            debugToast("ERROR", "Mic permission nahi hai, app settings mein Microphone allow karo")
        }

        tts = TextToSpeech(this, this)
        setupRecognizer()
        initVosk()
        mainHandler.postDelayed(voskWatchdog, 180000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // AssistActivity (power button) se seedha shuru karna ho to "start_conversation" extra bhejo
        if (intent?.getBooleanExtra("start_conversation", false) == true) {
            mainHandler.post { onWakeWordDetected() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isInConversationMode = false
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacks(voskWatchdog)
        overlay?.hide()
        glow?.hide()
        voskHandler.post {
            stopVoskInternal()
            try {
                voskModel?.close()
            } catch (e: Throwable) {
            }
            voskModel = null
        }
        voskThread.quitSafely()
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
        }
        speechRecognizer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        super.onDestroy()
    }

    // ---------------- notification ----------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Ria Assistant", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ria")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ---------------- TTS ----------------

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            debugToast("TTS ERROR", "TTS init fail, status=$status")
            return
        }
        val result = engine.setLanguage(Locale("hi", "IN"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        try {
            val voices = engine.voices
            if (voices != null) {
                for (v in voices) {
                    if (v.locale.language == engine.voice?.locale?.language &&
                        lower(v.name).contains("female")
                    ) {
                        engine.voice = v
                        break
                    }
                }
            }
        } catch (e: Exception) {
        }
        engine.setPitch(1.1f)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                onSpeechFinished()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onSpeechFinished()
            }
        })
    }

    private fun onSpeechFinished() {
        isSpeaking = false
        mainHandler.post {
            if (endAfterSpeech) {
                endAfterSpeech = false
                if (isInConversationMode) endConversationMode()
                return@post
            }
            if (isInConversationMode) {
                scheduleTimeout()
                restartListeningNow()
            }
        }
    }

    private fun speak(text: String) {
        isSpeaking = true
        mainHandler.post {
            debugToast("बोल रही हूँ", text)
            overlay?.setReply(text)
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
            }
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ria_utterance")
            if (result != TextToSpeech.SUCCESS) {
                isSpeaking = false
                if (isInConversationMode) restartListening()
            }
        }
    }

    // ---------------- overlay (glow ya card) ----------------

    private fun overlayMode(): String {
        return getSharedPreferences("ria_prefs", MODE_PRIVATE).getString("overlay_mode", "glow") ?: "glow"
    }

    private fun setOverlayMode(mode: String) {
        getSharedPreferences("ria_prefs", MODE_PRIVATE).edit().putString("overlay_mode", mode).apply()
        hideOverlayUi()
        showOverlayUi()
        speak(if (mode == "card") "कार्ड मोड चालू कर दिया" else "ग्लो मोड चालू कर दिया")
    }

    private fun showOverlayUi() {
        val canDraw = overlay?.canDraw() == true
        if (!canDraw) {
            askOverlayPermissionOnce()
            return
        }
        if (overlayMode() == "card") {
            overlay?.show("सुन रही हूँ...")
        } else {
            glow?.show()
        }
    }

    private fun hideOverlayUi() {
        overlay?.hide()
        glow?.hide()
    }

    private fun askOverlayPermissionOnce() {
        if (askedOverlayPermission) return
        askedOverlayPermission = true
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                "रिया के लिए 'Display over other apps' चालू कीजिए",
                Toast.LENGTH_LONG
            ).show()
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
            }
        }
    }

    // ---------------- VOSK (wake word) ----------------

    private fun initVosk() {
        voskHandler.post {
            try {
                debugToast("Vosk", "मॉडल लोड हो रहा है...")
                val modelPath = copyModelIfNeeded()
                voskModel = Model(modelPath)
                startVoskInternal()
            } catch (e: Throwable) {
                debugToast("VOSK ERROR", e.message ?: "model load fail")
            }
        }
    }

    private fun copyModelIfNeeded(): String {
        // purana english model hata do (jagah bachegi)
        val oldDir = File(filesDir, "model")
        if (oldDir.exists()) oldDir.deleteRecursively()

        val destDir = File(filesDir, MODEL_DIR_NAME)
        if (!File(destDir, "conf").exists()) {
            destDir.deleteRecursively()
            copyAssetFolder("model", destDir.absolutePath)
        }
        return destDir.absolutePath
    }

    private fun copyAssetFolder(assetPath: String, destPath: String) {
        val list = assets.list(assetPath)
        if (list != null && list.isNotEmpty()) {
            File(destPath).mkdirs()
            for (name in list) {
                copyAssetFolder("$assetPath/$name", "$destPath/$name")
            }
        } else {
            assets.open(assetPath).use { input ->
                File(destPath).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun startVosk(delayMs: Long = 0L) {
        voskHandler.postDelayed({ startVoskInternal() }, delayMs)
    }

    private fun stopVosk() {
        voskHandler.post { stopVoskInternal() }
    }

    // neeche ke dono function sirf voskHandler thread par chalte hain
    private fun startVoskInternal() {
        val model = voskModel ?: return
        if (isInConversationMode) return
        try {
            stopVoskInternal()
            val recognizer = Recognizer(model, 16000.0f)
            val service = SpeechService(recognizer, 16000.0f)
            voskRecognizer = recognizer
            voskSpeechService = service
            lastDebugText = ""
            service.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    onVoskHypothesis(hypothesis, "partial")
                }

                override fun onResult(hypothesis: String?) {
                    onVoskHypothesis(hypothesis, "text")
                }

                override fun onFinalResult(hypothesis: String?) {}

                override fun onError(exception: Exception?) {
                    debugToast("VOSK MIC ERROR", exception?.message ?: "unknown")
                    if (!isInConversationMode) startVosk(3000)
                }

                override fun onTimeout() {}
            })
            debugToast("Vosk", "तैयार")
        } catch (e: Throwable) {
            debugToast("VOSK START ERROR", e.message ?: "start fail")
        }
    }

    private fun stopVoskInternal() {
        try {
            voskSpeechService?.stop()
        } catch (e: Throwable) {
        }
        try {
            voskSpeechService?.shutdown()
        } catch (e: Throwable) {
        }
        voskSpeechService = null
        try {
            voskRecognizer?.close()
        } catch (e: Throwable) {
        }
        voskRecognizer = null
    }

    private fun onVoskHypothesis(hypothesis: String?, key: String) {
        if (hypothesis == null) return
        try {
            val heard = lower(JSONObject(hypothesis).optString(key, "")).trim()
            if (heard.isEmpty()) return
            if (isInConversationMode) {
                if (voskCommandMode) handleVoskCommand(heard, key == "text")
                return
            }
            val now = System.currentTimeMillis()
            if (heard != lastDebugText && now - lastToastTime > 1500) {
                lastDebugText = heard
                lastToastTime = now
                debugToast("Vosk", heard)
            }
            if (containsAny(heard, WAKE_WORDS)) {
                onWakeWordDetected()
            }
        } catch (e: Exception) {
        }
    }

    // Offline mode: wake word ke baad jo bola use Vosk se hi command maano (ek baar mein ek command)
    private fun handleVoskCommand(heard: String, isFinal: Boolean) {
        if (isSpeaking) return
        var rest = heard
        for (w in WAKE_WORDS) {
            rest = rest.replace(w, " ")
        }
        rest = tokens(rest).joinToString(" ")
        if (rest.isEmpty()) return
        overlay?.setUserText(rest)
        if (!isFinal) return
        voskCommandMode = false
        endAfterSpeech = true
        debugToast("सुना", rest)
        processCommand(rest)
    }

    private fun onWakeWordDetected() {
        if (isInConversationMode) return
        isInConversationMode = true
        consecutiveErrors = 0
        updateNotification("कमांड सुन रही हूँ...")
        showOverlayUi()
        scheduleTimeout()
        if (isOnline()) {
            // online: Google recognizer se command, uske liye Vosk band
            voskCommandMode = false
            stopVosk()
            speak("जी बोलिए")
        } else {
            // internet nahi: Vosk chalta rahe, wahi command sunega
            voskCommandMode = true
            try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 70).startTone(ToneGenerator.TONE_PROP_ACK, 150)
            } catch (e: Exception) {
            }
            overlay?.setStatus("ऑफलाइन — बोलिए...")
        }
    }

    private fun endConversationMode() {
        isInConversationMode = false
        isSpeaking = false
        voskCommandMode = false
        endAfterSpeech = false
        consecutiveErrors = 0
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
        }
        hideOverlayUi()
        updateNotification("सुन रही हूँ... (हे रिया बोलिए)")
        startVosk(1200)
    }

    private fun scheduleTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { endConversationMode() }
        timeoutRunnable = runnable
        mainHandler.postDelayed(runnable, CONVERSATION_TIMEOUT_MS)
    }

    // ---------------- Android SpeechRecognizer (command) ----------------

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            debugToast("ERROR", "Speech recognizer is device par available nahi hai")
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = if (matches != null && matches.isNotEmpty()) lower(matches[0]).trim() else ""
                if (spoken.isNotEmpty()) {
                    consecutiveErrors = 0
                    debugToast("सुना", spoken)
                    overlay?.setUserText(spoken)
                    scheduleTimeout()
                    processCommand(spoken)
                }
                if (!isSpeaking && isInConversationMode) restartListening()
            }

            override fun onError(error: Int) {
                if (!isInConversationMode || isSpeaking) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        consecutiveErrors++
                        if (consecutiveErrors >= 3) endConversationMode() else restartListening()
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        consecutiveErrors++
                        if (consecutiveErrors >= 4) {
                            endConversationMode()
                        } else {
                            mainHandler.postDelayed({ restartListeningNow() }, 900)
                        }
                    }
                    else -> {
                        // network / server / language / permission: loop mat chalao
                        debugToast("RECOGNIZER ERROR", "code $error")
                        endAfterSpeech = true
                        speak("आवाज़ पहचान में दिक्कत आई, इंटरनेट चेक करो")
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
        })
        speechRecognizer = recognizer
    }

    private fun listenOnce() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        speechRecognizer?.startListening(intent)
    }

    private fun restartListening() {
        mainHandler.postDelayed({ restartListeningNow() }, 300)
    }

    private fun restartListeningNow() {
        if (!isInConversationMode || isSpeaking) return
        overlay?.setStatus("सुन रही हूँ...")
        try {
            listenOnce()
        } catch (e: Exception) {
        }
    }

    // ---------------- commands ----------------

    private val CALL_WORDS = listOf("call", "कॉल", "कोल", "फोन", "फ़ोन", "phone")
    private val MESSAGE_TOKENS = setOf("message", "sms", "msg", "मैसेज", "मेसेज", "एसएमएस")
    private val WHATSAPP_TOKENS = setOf(
        "whatsapp", "व्हाट्सएप", "व्हाट्सअप", "वॉट्सएप", "वाट्सएप", "व्हाट्स"
    )
    private val ALARM_WORDS = listOf("alarm", "अलार्म", "अलारम")
    private val OPEN_WORDS = listOf("open", "kholo", "खोलो", "ओपन", "खोल")
    private val TIME_WORDS = listOf("time", "समय", "टाइम", "बजे हैं")
    private val SCREEN_WORDS = listOf(
        "screen analyse", "screen analyze", "screen analysis",
        "स्क्रीन एनालाइज", "स्क्रीन पढ़ो", "स्क्रीन देखो", "padho",
        "स्क्रीन पर क्या", "स्क्रीन पे क्या", "screen par kya", "screen pe kya", "इसे समझाओ",
        "एनालिसिस", "एनालाइसिस", "एनालाइज़", "एनालाइज", "analysis", "analyse", "analyze",
        "विश्लेषण", "स्क्रीन देख", "स्क्रीन समझा", "स्क्रीन बता", "screen dekho", "screen padho"
    )
    private val CLICK_WORDS = listOf("click", "क्लिक", "dabao", "दबाओ", "tap")
    private val HOME_WORDS = listOf("home screen", "होम स्क्रीन", "होम")
    private val BACK_WORDS = listOf("back karo", "पीछे", "बैक")
    private val CLOSE_WORDS = listOf("close karo", "band karo", "बंद करो")
    private val MUSIC_WORDS = listOf(
        "gaana bajao", "gana bajao", "song bajao", "music bajao",
        "गाना बजाओ", "गाना चलाओ", "गाना लगाओ", "गाना चला", "song play", "play karo",
        "बजाओ", "बजा दो", "bajao", "bajaao", "play song", "play the song"
    )

    private val CALL_NOISE = setOf(
        "call", "कॉल", "कोल", "phone", "फोन", "फ़ोन", "लगाओ", "लगा", "मिलाओ", "do", "दो",
        "karo", "करो", "kar", "कर", "ko", "को", "please", "kijiye", "कीजिए", "लगाइए"
    )
    private val OPEN_NOISE = setOf(
        "open", "ओपन", "kholo", "खोलो", "खोल", "kar", "करो", "कर", "do", "दो",
        "app", "ऐप", "एप", "please", "को", "ko"
    )
    private val MUSIC_NOISE = setOf(
        "gaana", "gana", "गाना", "song", "music", "bajao", "बजाओ", "चलाओ", "लगाओ", "play",
        "karo", "करो", "kar", "do", "दो", "spotify", "स्पॉटिफाई", "par", "पर", "पे", "on", "ko", "को"
    )
    private val CLICK_NOISE = setOf(
        "click", "क्लिक", "dabao", "दबाओ", "tap", "karo", "करो", "kar", "upar", "ऊपर", "पर", "par", "ko", "को"
    )
    private val NAME_NOISE = setOf("ko", "को", "please", "kijiye", "कीजिए", "send", "to", "भेजो", "bhejo")
    private val SMS_VERB_WORDS = setOf(
        "bhejo", "भेजो", "bhej", "भेज", "send", "karo", "करो", "kar", "कर", "do", "दो",
        "kijiye", "कीजिए", "message", "मैसेज", "मेसेज", "par", "पर", "me", "में", "likho", "लिखो"
    )

    // ---- command pehchan: sirf poore shabd (token) par, taaki normal sawal AI tak pahunche ----
    private val CLICK_TOKENS = setOf("click", "क्लिक", "dabao", "दबाओ", "tap", "टैप")
    private val HOME_TOKENS = setOf("home", "होम", "होमस्क्रीन")
    private val OPEN_TOKENS = setOf("open", "ओपन", "kholo", "खोलो", "खोल")
    private val TIME_TOKENS = setOf("time", "समय", "टाइम", "बजे")
    private val ALARM_TOKENS = setOf("alarm", "अलार्म", "अलारम")
    private val SEND_TOKENS = setOf("bhejo", "भेजो", "bhej", "भेज", "send")
    private val CALL_TOKENS = setOf("call", "कॉल", "कोल")
    private val CALL_AUX = setOf("फोन", "फ़ोन", "phone")
    private val CALL_VERBS = setOf("लगाओ", "लगा", "करो", "मिलाओ", "लगाइए", "karo")
    private val MUSIC_TOKENS = setOf("गाना", "गाने", "song", "music", "gaana", "gana", "सॉन्ग", "म्यूजिक")
    private val PLAY_TOKENS = setOf("बजाओ", "bajao", "bajaao", "चलाओ", "chalao", "लगाओ", "play", "प्ले", "बजा", "सुनाओ")
    private val SCREEN_PART = listOf("स्क्रीन", "screen", "सक्रीन")
    private val SCREEN_ACTION = listOf(
        "एनाल", "analy", "पढ़", "पढ", "देख", "समझा", "बता", "क्या", "dekho", "padho", "kya", "read", "explain", "describe"
    )

    private fun hasTok(parts: List<String>, words: Set<String>): Boolean {
        return parts.any { it in words }
    }

    private fun processCommand(command: String) {
        val parts = tokens(command)
        val short = parts.size <= 5
        when {
            containsAny(command, listOf("ग्लो मोड", "glow mode", "लाइट मोड")) -> setOverlayMode("glow")
            containsAny(command, listOf("कार्ड मोड", "card mode", "बॉक्स मोड")) -> setOverlayMode("card")
            containsAny(command, listOf("मेरा शहर", "my city")) -> saveCity(command)
            containsAny(command, SCREEN_PART) && containsAny(command, SCREEN_ACTION) -> readScreenContent()
            short && hasTok(parts, CLICK_TOKENS) -> {
                val target = removeWords(command, CLICK_NOISE)
                if (target.isNotEmpty()) clickOnScreenElement(target)
                else speak("किस पर क्लिक करना है, समझ नहीं पाई")
            }
            short && hasTok(parts, HOME_TOKENS) -> goToHomeScreen()
            short && containsAny(command, BACK_WORDS) -> goBackScreen()
            short && containsAny(command, CLOSE_WORDS) -> closeCurrentApp()
            isMusicCommand(command, parts) -> {
                playMusicOnSpotify(removeWords(command, MUSIC_NOISE + setOf("प्ले", "सुनाओ", "बजा", "गाने", "chalao", "सॉन्ग", "म्यूजिक")))
            }
            hasTok(parts, WHATSAPP_TOKENS) && (hasTok(parts, MESSAGE_TOKENS) || hasTok(parts, SEND_TOKENS)) -> {
                val (name, message) = splitNameAndMessage(command, WHATSAPP_TOKENS)
                if (name.isNotEmpty() && message.isNotEmpty()) {
                    sendWhatsAppMessage(name, message)
                } else {
                    speak("नाम और मैसेज दोनों एक ही बार में बोलिए")
                }
            }
            parts.size <= 6 && (hasTok(parts, CALL_TOKENS) || (hasTok(parts, CALL_AUX) && hasTok(parts, CALL_VERBS))) -> {
                val name = removeWords(command, CALL_NOISE)
                if (name.isNotEmpty()) makeCall(name) else speak("किसको कॉल करना है, नाम नहीं समझ पाई")
            }
            hasTok(parts, MESSAGE_TOKENS) && (hasTok(parts, SEND_TOKENS) || hasTok(parts, setOf("ko", "को", "to"))) -> {
                val (name, message) = splitNameAndMessage(command, MESSAGE_TOKENS)
                if (name.isNotEmpty() && message.isNotEmpty()) {
                    sendSms(name, message)
                } else if (name.isNotEmpty()) {
                    speak("$name को क्या मैसेज भेजना है, पूरा एक ही बार में बोलिए")
                } else {
                    speak("किसको मैसेज करना है, समझ नहीं पाई")
                }
            }
            parts.size <= 8 && hasTok(parts, ALARM_TOKENS) -> setAlarmFromCommand(command)
            parts.size <= 6 && hasTok(parts, OPEN_TOKENS) -> openApp(removeWords(command, OPEN_NOISE))
            short && hasTok(parts, TIME_TOKENS) -> {
                val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                speak("अभी समय है $time")
            }
            else -> askAiAndSpeak(command)
        }
    }

    private fun isMusicCommand(command: String, parts: List<String>): Boolean {
        if (containsAny(command, listOf("गाना बजाओ", "गाना चलाओ", "गाना लगाओ", "gaana bajao", "gana bajao", "song bajao"))) return true
        if (parts.isNotEmpty() && parts[0] in setOf("play", "प्ले")) return parts.size <= 8
        return parts.size <= 8 && hasTok(parts, MUSIC_TOKENS) && hasTok(parts, PLAY_TOKENS)
    }

    private fun saveCity(command: String) {
        var rest = command.replace("मेरा शहर", " ").replace("my city", " ")
        rest = removeWords(rest, setOf("है", "हैं", "hai", "is", "के", "का", "बताओ"))
        if (rest.isEmpty()) {
            speak("आपका शहर कौन सा है, बोलिए: मेरा शहर और नाम")
            return
        }
        getSharedPreferences("ria_prefs", MODE_PRIVATE).edit().putString("city", rest).apply()
        speak("ठीक है, आपका शहर $rest याद रख लिया")
    }

    private fun askAiAndSpeak(question: String, fallbackText: String? = null) {
        if (!isOnline()) {
            speak("इंटरनेट नहीं है, यह सवाल ऑनलाइन ही हो पाएगा")
            return
        }
        isSpeaking = true // jawab aane tak mic band rakho
        overlay?.setStatus("सोच रही हूँ...")
        GeminiClient.ask(applicationContext, question) { answer ->
            mainHandler.post {
                if (answer != null && answer.trim().isNotEmpty()) {
                    val shortAnswer = if (answer.length > 400) answer.substring(0, 400) else answer
                    speak(shortAnswer)
                } else if (fallbackText != null) {
                    // AI na mile to screen ka text seedha padh do
                    speak(if (fallbackText.length > 400) fallbackText.substring(0, 400) else fallbackText)
                } else {
                    speak("AI से जवाब नहीं मिल पाया, API key और इंटरनेट देखिए")
                }
            }
        }
    }

    private fun splitNameAndMessage(command: String, keywords: Set<String>): Pair<String, String> {
        val parts = tokens(command)
        val idx = parts.indexOfFirst { it in keywords }
        if (idx == -1) return Pair("", "")
        val name = parts.subList(0, idx).filter { it !in NAME_NOISE }.joinToString(" ")
        val message = parts.subList(idx + 1, parts.size).filter { it !in SMS_VERB_WORDS }.joinToString(" ")
        return Pair(name, message)
    }

    // ---------------- contacts / call / sms / whatsapp ----------------

    private fun findContactNumber(name: String): String? {
        val digitsOnly = name.replace(" ", "")
        if (digitsOnly.length >= 6 && digitsOnly.all { it.isDigit() }) return digitsOnly

        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    private fun makeCall(contactName: String) {
        val number = findContactNumber(contactName)
        if (number == null) {
            speak("$contactName का नंबर नहीं मिला")
            return
        }
        val hasPermission = checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (hasPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL)
        intent.data = Uri.parse("tel:" + Uri.encode(number))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            speak("$contactName को कॉल कर रही हूँ")
        } catch (e: Exception) {
            speak("कॉल शुरू नहीं हो पाई")
        }
    }

    private fun sendSms(contactName: String, message: String) {
        val number = findContactNumber(contactName)
        if (number == null) {
            speak("$contactName का नंबर नहीं मिला")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            speak("एसएमएस की अनुमति नहीं है")
            return
        }
        try {
            val manager: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = manager.divideMessage(message)
            manager.sendMultipartTextMessage(number, null, parts, null, null)
            speak("$contactName को मैसेज भेज दिया")
        } catch (e: Exception) {
            speak("मैसेज नहीं भेज पाई")
        }
    }

    private fun sendWhatsAppMessage(contactName: String, message: String) {
        val number = findContactNumber(contactName)
        if (number == null) {
            speak("$contactName का नंबर नहीं मिला")
            return
        }
        var digits = number.filter { it.isDigit() }
        if (digits.length == 10) digits = "91$digits"
        val uri = Uri.parse("https://wa.me/$digits?text=" + Uri.encode(message))
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage("com.whatsapp")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            speak("व्हाट्सएप खोल दिया, भेजें बटन दबा दीजिए")
        } catch (e: Exception) {
            try {
                intent.setPackage(null)
                startActivity(intent)
                speak("व्हाट्सएप खोल दिया, भेजें बटन दबा दीजिए")
            } catch (e2: Exception) {
                speak("व्हाट्सएप नहीं खुल पाया")
            }
        }
    }

    // ---------------- alarm / apps / music ----------------

    private fun hasToken(text: String, word: String): Boolean {
        return tokens(text).contains(word)
    }

    private fun setAlarmFromCommand(command: String) {
        val match = Regex("(\\d{1,2})(?:[:.](\\d{2}))?").find(command)
        if (match == null) {
            speak("कितने बजे का अलार्म लगाना है, समय बोलिए")
            return
        }
        var hour = match.groupValues[1].toInt()
        val minute = if (match.groupValues[2].isNotEmpty()) match.groupValues[2].toInt() else 0

        val isPm = hasToken(command, "pm") ||
            containsAny(command, listOf("शाम", "रात", "दोपहर", "evening", "night", "afternoon"))
        val isAm = hasToken(command, "am") ||
            containsAny(command, listOf("सुबह", "morning", "भोर"))
        if (isPm && hour < 12) hour += 12
        if (isAm && hour == 12) hour = 0

        if (hour > 23 || minute > 59) {
            speak("समय सही नहीं लगा, दोबारा बोलिए")
            return
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
        intent.putExtra(AlarmClock.EXTRA_HOUR, hour)
        intent.putExtra(AlarmClock.EXTRA_MINUTES, minute)
        intent.putExtra(AlarmClock.EXTRA_MESSAGE, "Ria")
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            speak("अलार्म लगा दिया")
        } catch (e: Exception) {
            speak("अलार्म नहीं लगा पाई")
        }
    }

    private val APP_ALIASES = mapOf(
        "व्हाट्सएप" to "whatsapp", "व्हाट्सअप" to "whatsapp", "यूट्यूब" to "youtube",
        "कैमरा" to "camera", "सेटिंग्स" to "settings", "सेटिंग" to "settings",
        "गैलरी" to "gallery", "क्रोम" to "chrome", "फेसबुक" to "facebook",
        "इंस्टाग्राम" to "instagram", "स्पॉटिफाई" to "spotify", "कैलकुलेटर" to "calculator",
        "जीमेल" to "gmail", "टेलीग्राम" to "telegram", "मैप्स" to "maps", "घड़ी" to "clock", "मौसम" to "weather", "वेदर" to "weather"
    )

    @Suppress("DEPRECATION")
    private fun openApp(appNameRaw: String) {
        val appName = lower(appNameRaw).trim()
        if (appName.isEmpty()) {
            speak("कौन सा ऐप खोलना है, नाम बताइए")
            return
        }
        val target = APP_ALIASES[appName] ?: appName
        try {
            val launcher = Intent(Intent.ACTION_MAIN)
            launcher.addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(launcher, 0)
            for (info in apps) {
                val label = lower(info.loadLabel(packageManager).toString())
                if (label.contains(target)) {
                    val launch = packageManager.getLaunchIntentForPackage(info.activityInfo.packageName)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launch)
                        speak("$appNameRaw खोल रही हूँ")
                        return
                    }
                }
            }
            speak("$appNameRaw ऐप नहीं मिला")
        } catch (e: Exception) {
            speak("ऐप नहीं खुल पाया")
        }
    }

    private val MUSIC_PACKAGES = listOf("com.spotify.music", "com.google.android.apps.youtube.music")

    // Gaana khoj kar seedha chalana: Spotify -> YouTube Music -> Spotify search page par tap
    private fun playMusicOnSpotify(song: String) {
        if (song.isEmpty()) {
            speak("कौन सा गाना बजाना है, नाम बताइए")
            return
        }
        endAfterSpeech = true // gaana chalte waqt mic band, warna gaana hi command samajh lega
        speak("$song बजा रही हूँ")
        tryPlayFromSearch(song, 0)
    }

    private fun tryPlayFromSearch(song: String, index: Int) {
        if (index >= MUSIC_PACKAGES.size) {
            fallbackSpotifySearch(song)
            return
        }
        if (!launchPlayFromSearch(MUSIC_PACKAGES[index], song)) {
            tryPlayFromSearch(song, index + 1)
            return
        }
        mainHandler.postDelayed({
            val audio = getSystemService(AudioManager::class.java)
            if (audio == null || !audio.isMusicActive) tryPlayFromSearch(song, index + 1)
        }, 6000)
    }

    private fun launchPlayFromSearch(pkg: String, song: String): Boolean {
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            intent.setPackage(pkg)
            intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            intent.putExtra(SearchManager.QUERY, song)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun fallbackSpotifySearch(song: String) {
        val audio = getSystemService(AudioManager::class.java)
        if (audio != null && audio.isMusicActive) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(song)))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            return
        }
        mainHandler.postDelayed({ tapFirstSpotifyResult(song) }, 4000)
    }

    private fun tapFirstSpotifyResult(song: String) {
        val service = RiaAccessibilityService.instance ?: return
        for (word in tokens(song)) {
            if (service.clickByText(word)) return
        }
        service.clickByContentDescription("play")
    }

    private fun goToHomeScreen() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            speak("होम स्क्रीन पर आ गई")
        } catch (e: Exception) {
            speak("होम स्क्रीन नहीं खुल पाई")
        }
    }

    // ---------------- ACCESSIBILITY wale kaam ----------------

    private fun requireAccessibility(): RiaAccessibilityService? {
        val service = RiaAccessibilityService.instance
        if (service == null) {
            speak("पहले सेटिंग्स में रिया की एक्सेसिबिलिटी अनुमति चालू कीजिए")
        }
        return service
    }

    private fun clickOnScreenElement(target: String) {
        val service = requireAccessibility() ?: return
        val ok = service.clickByText(target) || service.clickByContentDescription(target)
        if (ok) {
            speak("$target पर क्लिक कर दिया")
        } else {
            speak("$target स्क्रीन पर नहीं मिला")
        }
    }

    private fun goBackScreen() {
        val service = requireAccessibility() ?: return
        service.goBack()
        speak("पीछे आ गई")
    }

    // Android kisi doosre app ko seedha "force close" nahi karne deta,
    // isliye ye app ko hata kar home screen par le aata hai.
    private fun closeCurrentApp() {
        val service = requireAccessibility() ?: return
        service.goHome()
        speak("होम स्क्रीन पर आ गई")
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            true
        }
    }

    // Online: screen ka text AI ko bhejo, jawab bolo. Offline: screen ka text seedha padh do.
    private fun readScreenContent() {
        val service = requireAccessibility() ?: return
        val screenText = service.getScreenText()
        if (screenText.isEmpty()) {
            speak("स्क्रीन पर पढ़ने लायक कुछ नहीं मिला")
            return
        }
        if (isOnline()) {
            val limited = if (screenText.length > 3000) screenText.substring(0, 3000) else screenText
            askAiAndSpeak(
                "मेरी फ़ोन स्क्रीन पर यह लिखा है:\n" + limited +
                    "\n\nइसे हिंदी में बहुत छोटे में समझाओ कि स्क्रीन पर क्या है।",
                screenText
            )
        } else {
            val limited = if (screenText.length > 400) screenText.substring(0, 400) else screenText
            speak(limited)
        }
    }
}
