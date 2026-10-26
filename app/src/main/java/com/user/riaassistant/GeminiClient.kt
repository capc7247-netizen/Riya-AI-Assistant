package com.user.riaassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ria ka AI. Google Search se judta hai, isliye mausam, khabar, score, bhav
 * jaise taaza sawalon ka bhi jawab deta hai.
 *
 * API key Settings screen se daalo (prefs "ria_prefs" mein "api_key" naam se save hoti hai).
 */
object GeminiClient {

    // Key yahan mat likho, repo public hai. Settings screen se daalo.
    private const val API_KEY = ""

    // pehla model na mile to doosra try hoga
    private val MODELS = listOf("gemini-3.5-flash", "gemini-2.5-flash")

    private val history = ArrayList<Pair<String, String>>() // (role, text)

    fun ask(context: Context, question: String, callback: (String?) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            val answer = try {
                call(appContext, question)
            } catch (e: Exception) {
                "AI से जवाब नहीं मिला, " + (e.message ?: "नेटवर्क की दिक्कत")
            }
            callback(answer)
        }.start()
    }

    private fun apiKey(context: Context): String {
        val prefs = context.getSharedPreferences("ria_prefs", Context.MODE_PRIVATE)
        val fromSettings = (prefs.getString("api_key", "") ?: "").trim()
        if (fromSettings.isNotEmpty()) return fromSettings
        val old = (prefs.getString("gemini_api_key", "") ?: "").trim()
        if (old.isNotEmpty()) return old
        return API_KEY.trim()
    }

    private fun systemText(context: Context): String {
        val city = context.getSharedPreferences("ria_prefs", Context.MODE_PRIVATE)
            .getString("city", "") ?: ""
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, hh:mm a", Locale("hi", "IN")).format(Date())
        val sb = StringBuilder()
        sb.append("तुम रिया हो, एक दोस्ताना आवाज़ वाली असिस्टेंट। ")
        sb.append("जवाब उसी भाषा में दो जिसमें सवाल पूछा गया है (हिंदी या अंग्रेज़ी)। ")
        sb.append("जवाब छोटा रखो, ज़्यादा से ज़्यादा 2-3 वाक्य, क्योंकि उसे बोलकर सुनाया जाएगा। ")
        sb.append("सितारे, हैश, सूची, इमोजी या मार्कडाउन बिल्कुल मत लगाओ। ")
        sb.append("ताज़ा जानकारी (मौसम, खबर, स्कोर, भाव) के लिए Google Search का इस्तेमाल करो। ")
        sb.append("अभी का समय: ").append(now).append(" (भारत)। ")
        if (city.isNotEmpty()) {
            sb.append("उपयोगकर्ता का शहर: ").append(city)
            sb.append("। मौसम जैसे सवाल में शहर न बताया जाए तो यही शहर मानो।")
        }
        return sb.toString()
    }

    private fun call(context: Context, question: String): String {
        val key = apiKey(context)
        if (key.isEmpty()) {
            return "API की नहीं मिली, Settings में अपनी Gemini API की डालकर सेव कीजिए"
        }

        val contents = JSONArray()
        synchronized(history) {
            for (item in history) {
                contents.put(
                    JSONObject()
                        .put("role", item.first)
                        .put("parts", JSONArray().put(JSONObject().put("text", item.second)))
                )
            }
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", question)))
        )

        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText(context))))
            )
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .toString()

        var lastProblem = "जवाब खाली आया"
        for (model in MODELS) {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 40000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("x-goog-api-key", key)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

                if (code in 200..299) {
                    val answer = parseAnswer(text)
                    if (answer.isNotEmpty()) {
                        remember(question, answer)
                        return answer
                    }
                    lastProblem = "जवाब नहीं बन पाया, सवाल दूसरे तरीके से पूछिए"
                    return lastProblem
                }

                when (code) {
                    400, 401, 403 -> return "API की सही नहीं है या चालू नहीं है, कोड $code"
                    429 -> return "AI की मुफ़्त लिमिट पूरी हो गई, थोड़ी देर बाद कोशिश कीजिए"
                    404 -> lastProblem = "मॉडल $model नहीं मिला" // agla model try hoga
                    else -> lastProblem = "सर्वर की दिक्कत, कोड $code"
                }
            } finally {
                conn.disconnect()
            }
        }
        return "AI से जवाब नहीं मिला, $lastProblem"
    }

    private fun parseAnswer(json: String): String {
        val root = JSONObject(json)
        val candidates = root.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val t = parts.getJSONObject(i).optString("text", "")
            if (t.isNotEmpty()) sb.append(t)
        }
        return sb.toString()
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .replace("\n\n", ". ")
            .replace("\n", " ")
            .trim()
    }

    private fun remember(question: String, answer: String) {
        synchronized(history) {
            history.add(Pair("user", question))
            history.add(Pair("model", answer))
            while (history.size > 12) history.removeAt(0)
        }
    }
}