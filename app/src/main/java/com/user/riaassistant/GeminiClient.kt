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
 * API key: Settings screen mein daalo (prefs "ria_prefs" -> "api_key"),
 * ya yahan API_KEY mein.
 */
object GeminiClient {

    // <-- chaaho to yahan bhi apni Gemini API key daal sakte ho
    private const val API_KEY = ""

    // pehla model na mile to doosra try hoga
    private val MODELS = listOf("gemini-3.5-flash", "gemini-2.5-flash")

    private const val PREFS = "ria_prefs"
    private const val KEY_MEMORY = "ria_memory"
    private const val KEY_HISTORY = "ria_history"
    private const val MAX_FACTS = 60
    private const val MAX_HISTORY = 16

    // ---------------- public ----------------

    // callback(answer, error): answer mila to error null, nahi to error mein wajah
    fun ask(
        context: Context,
        question: String,
        remember: Boolean = true,
        callback: (String?, String?) -> Unit
    ) {
        val appContext = context.applicationContext
        Thread {
            val result = try {
                call(appContext, question, remember)
            } catch (e: Exception) {
                Pair<String?, String?>(null, "AI से जवाब नहीं मिला, " + (e.message ?: "नेटवर्क की दिक्कत"))
            }
            callback(result.first, result.second)
        }.start()
    }

    fun addMemory(context: Context, fact: String) {
        val clean = fact.trim()
        if (clean.isEmpty()) return
        synchronized(this) {
            val list = loadFacts(context)
            if (list.any { it.equals(clean, ignoreCase = true) }) return
            list.add(clean)
            while (list.size > MAX_FACTS) list.removeAt(0)
            saveFacts(context, list)
        }
    }

    fun memoryList(context: Context): List<String> {
        return synchronized(this) { loadFacts(context) }
    }

    fun clearMemory(context: Context) {
        synchronized(this) {
            prefs(context).edit().remove(KEY_MEMORY).remove(KEY_HISTORY).apply()
        }
    }

    // ---------------- storage ----------------

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadFacts(context: Context): MutableList<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(prefs(context).getString(KEY_MEMORY, "[]") ?: "[]")
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) {
        }
        return out
    }

    private fun saveFacts(context: Context, list: List<String>) {
        val arr = JSONArray()
        for (s in list) arr.put(s)
        prefs(context).edit().putString(KEY_MEMORY, arr.toString()).apply()
    }

    private fun loadHistory(context: Context): MutableList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val arr = JSONArray(prefs(context).getString(KEY_HISTORY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Pair(o.getString("r"), o.getString("t")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun saveHistory(context: Context, list: List<Pair<String, String>>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("r", p.first).put("t", p.second))
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun remember(context: Context, question: String, answer: String) {
        synchronized(this) {
            val h = loadHistory(context)
            h.add(Pair("user", question))
            h.add(Pair("model", answer))
            while (h.size > MAX_HISTORY) h.removeAt(0)
            saveHistory(context, h)
        }
    }

    // ---------------- key / prompt ----------------

    private fun apiKey(context: Context): String {
        val p = prefs(context)
        // Settings screen "api_key" naam se save karti hai
        val a = p.getString("api_key", "") ?: ""
        if (a.trim().isNotEmpty()) return a.trim()
        val b = p.getString("gemini_api_key", "") ?: ""
        if (b.trim().isNotEmpty()) return b.trim()
        return API_KEY.trim()
    }

    private fun systemText(context: Context): String {
        val city = prefs(context).getString("city", "") ?: ""
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, hh:mm a", Locale("hi", "IN")).format(Date())
        val facts = memoryList(context)
        val sb = StringBuilder()
        sb.append("तुम्हारा नाम रिया है। तुम इस ऐप की दोस्ताना आवाज़ वाली असिस्टेंट हो। ")
        sb.append("तुम Gemini, Google या किसी और कंपनी का मॉडल नहीं हो। ")
        sb.append("कोई तुम्हारा नाम या पहचान पूछे तो बस इतना कहो कि तुम रिया हो, उसकी असिस्टेंट। ")
        sb.append("Gemini, Google या मॉडल का नाम कभी मत बोलो। ")
        sb.append("जवाब उसी भाषा में दो जिसमें सवाल पूछा गया है (हिंदी या अंग्रेज़ी)। ")
        sb.append("जवाब छोटा रखो, ज़्यादा से ज़्यादा 2-3 वाक्य, क्योंकि उसे बोलकर सुनाया जाएगा। ")
        sb.append("सितारे, हैश, सूची, इमोजी या मार्कडाउन बिल्कुल मत लगाओ। ")
        sb.append("ताज़ा जानकारी (मौसम, खबर, स्कोर, भाव) के लिए Google Search का इस्तेमाल करो। ")
        sb.append("अभी का समय: ").append(now).append(" (भारत)। ")
        if (city.isNotEmpty()) {
            sb.append("उपयोगकर्ता का शहर: ").append(city)
            sb.append("। मौसम जैसे सवाल में शहर न बताया जाए तो यही शहर मानो। ")
        }
        if (facts.isNotEmpty()) {
            sb.append("उपयोगकर्ता के बारे में तुम्हें ये बातें याद हैं: ")
            sb.append(facts.takeLast(40).joinToString("; "))
            sb.append("। जहाँ काम की हों वहीं इस्तेमाल करो, हर बार मत दोहराओ। ")
        }
        sb.append("अगर उपयोगकर्ता अपने बारे में कोई टिकाऊ बात बताए (नाम, पसंद, परिवार, काम, शहर, तारीख) ")
        sb.append("तो जवाब के बिल्कुल आख़िर में [[YAAD: एक छोटा वाक्य]] जोड़ दो। ")
        sb.append("बाकी किसी भी सवाल में ये टैग मत लगाओ।")
        return sb.toString()
    }

    // ---------------- network ----------------

    private fun call(context: Context, question: String, remember: Boolean): Pair<String?, String?> {
        val key = apiKey(context)
        if (key.isEmpty()) {
            return Pair(null, "AI की चाबी नहीं मिली, ऐप की सेटिंग में अपनी API key डालिए")
        }

        val contents = JSONArray()
        if (remember) {
            val h = synchronized(this) { loadHistory(context) }
            for (item in h) {
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
                    val raw = parseAnswer(text)
                    if (raw.isNotEmpty()) {
                        val answer = extractMemoryTags(context, raw)
                        if (answer.isNotEmpty()) {
                            if (remember) remember(context, question, answer)
                            return Pair(answer, null)
                        }
                    }
                    lastProblem = "जवाब नहीं बन पाया, सवाल दूसरे तरीके से पूछिए"
                    return Pair(null, lastProblem)
                }
                when {
                    code == 401 || code == 403 ->
                        return Pair(null, "API key सही नहीं है या चालू नहीं है, कोड $code")
                    code == 400 && text.contains("API key", ignoreCase = true) ->
                        return Pair(null, "API key सही नहीं है, सेटिंग में दोबारा डालिए")
                    code == 429 ->
                        return Pair(null, "AI की मुफ़्त लिमिट पूरी हो गई, थोड़ी देर बाद कोशिश कीजिए")
                    code == 404 -> lastProblem = "मॉडल $model नहीं मिला" // agla model try hoga
                    else -> lastProblem = "सर्वर की दिक्कत, कोड $code"
                }
            } finally {
                conn.disconnect()
            }
        }
        return Pair(null, "AI से जवाब नहीं मिला, $lastProblem")
    }

    // [[YAAD: ...]] tag nikaalo, memory mein daalo, jawab se hata do
    private fun extractMemoryTags(context: Context, text: String): String {
        val regex = Regex("\\[\\[\\s*YAAD\\s*:(.*?)]]", RegexOption.DOT_MATCHES_ALL)
        for (m in regex.findAll(text)) {
            addMemory(context, m.groupValues[1])
        }
        return regex.replace(text, "").trim()
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
}