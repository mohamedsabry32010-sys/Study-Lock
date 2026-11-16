package com.example.studylock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var a11yTv: TextView
    private lateinit var minutesEt: EditText
    private lateinit var ytCb: CheckBox
    private lateinit var notesEt: EditText
    private lateinit var summaryEt: EditText
    private lateinit var breakEt: EditText
    private lateinit var keyEt: EditText
    private lateinit var langSp: Spinner
    private lateinit var micBtn: Button
    private lateinit var resultTv: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- UI ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(40))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        setContentView(ScrollView(this).apply { addView(root) })

        fun label(t: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
            root.addView(this)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }; root.addView(this)
        }
        fun edit(hint: String, multiline: Boolean = false, number: Boolean = false) = EditText(this).apply {
            this.hint = hint
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 4; gravity = Gravity.TOP
            }
            root.addView(this)
        }

        label("📚 StudyLock", 26f, true)
        status = label("", 20f, true)
        a11yTv = label("")
        button("1) فعّل خدمة القفل (مرة واحدة)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("دوّر على StudyLock في Installed apps وشغّله")
        }

        label("مدة المذاكرة (بالدقايق)")
        minutesEt = edit("مثلاً 120", number = true).apply { setText("120") }
        ytCb = CheckBox(this).apply {
            text = "اسمح باليوتيوب (لو بتذاكر منه)"
            isChecked = Focus.allowYoutube(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Focus.p(this@MainActivity).edit().putBoolean("allowYoutube", v).apply() }
            root.addView(this)
        }
        button("2) ابدأ الجلسة 🔒") {
            val m = minutesEt.text.toString().toLongOrNull() ?: 0L
            if (m <= 0) { toast("اكتب مدة صحيحة"); return@button }
            Focus.p(this).edit()
                .putLong("lockUntil", System.currentTimeMillis() + m * 60_000)
                .putLong("breakUntil", 0L).apply()
            refresh()
            toast("الجلسة بدأت. بالتوفيق! 💪")
        }

        label("بعد ما تخلص محاضرة", 20f, true)
        notesEt = edit("الصق هنا نص أو ملاحظات المحاضرة (أحسن تقييم لو كتبتها)", multiline = true)
        langSp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("لغة الكلام: عربي (مصر)", "Speech language: English"))
            root.addView(this)
        }
        micBtn = button("🎙 سجّل ملخصك بصوتك") { toggleMic() }
        summaryEt = edit("ملخصك هيظهر هنا (تقدر تعدّله أو تكتبه)", multiline = true)
        label("مدة الاستراحة لو فهمت (دقايق)")
        breakEt = edit("10", number = true).apply { setText("10") }
        button("3) قيّم فهمي 🧠") { evaluate() }
        resultTv = label("")

        label("Claude API key", 14f)
        keyEt = edit("sk-ant-...").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Focus.p(this@MainActivity).getString("apiKey", ""))
        }
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker); stopListening() }
    override fun onDestroy() { super.onDestroy(); recognizer?.destroy() }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/")
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        val lu = Focus.lockUntil(this)
        val bu = Focus.breakUntil(this)
        status.text = when {
            now < bu -> "☕ استراحة: باقي ${fmt(bu - now)}"
            now < lu -> "🔒 مقفول: باقي ${fmt(lu - now)}"
            else -> "✅ مفيش جلسة شغالة"
        }
        a11yTv.text = if (a11yOn()) "خدمة القفل: ✅ شغالة" else "خدمة القفل: ❌ مش مفعّلة"
    }

    // ---------- Speech ----------
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startListening()
        else toast("محتاج إذن الميكروفون عشان التسجيل")
    }

    private fun toggleMic() {
        if (listening) { stopListening(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("التعرف على الصوت مش متاح على التليفون ده. اكتب الملخص بإيدك."); return }
        listening = true
        micBtn.text = "⏹ وقّف التسجيل"
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        listenOnce()
    }

    private fun listenOnce() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (langSp.selectedItemPosition == 0) "ar-EG" else "en-US")
        }
        recognizer?.startListening(i)
    }

    private fun stopListening() {
        listening = false
        micBtn.text = "🎙 سجّل ملخصك بصوتك"
        recognizer?.destroy(); recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onResults(r: Bundle?) {
            val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) summaryEt.append((if (summaryEt.text.isEmpty()) "" else " ") + t)
            if (listening) handler.postDelayed({ if (listening) listenOnce() }, 300)
        }
        override fun onError(e: Int) {
            if (!listening) return
            if (e == 6 || e == 7 || e == 8) {          // timeout / no match / busy
                recognizer?.cancel()
                handler.postDelayed({ if (listening) listenOnce() }, 500)
            } else { toast("مشكلة في التعرف على الصوت (كود $e)"); stopListening() }
        }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    // ---------- Claude evaluation ----------
    private fun evaluate() {
        val key = keyEt.text.toString().trim()
        val summary = summaryEt.text.toString().trim()
        val notes = notesEt.text.toString().trim()
        if (key.isEmpty()) { toast("اكتب الـ API key"); return }
        if (summary.isEmpty()) { toast("سجّل أو اكتب ملخصك الأول"); return }
        Focus.p(this).edit().putString("apiKey", key).apply()
        stopListening()
        resultTv.text = "⏳ بيتقيّم..."
        Thread {
            try {
                val verdict = callClaude(key, notes, summary)
                runOnUiThread { showVerdict(verdict) }
            } catch (e: Exception) {
                runOnUiThread { resultTv.text = "حصلت مشكلة: ${e.message}" }
            }
        }.start()
    }

    private fun callClaude(key: String, notes: String, summary: String): JSONObject {
        val prompt = """
You are a strict but fair tutor. A student just finished studying a lecture and gave a spoken summary.

LECTURE NOTES (may be empty):
$notes

STUDENT'S SPOKEN SUMMARY (speech-to-text, may contain transcription errors; ignore those):
$summary

Decide whether the student truly understands the main ideas, not just repeating a few keywords.
If the notes are empty, judge by whether the summary is coherent and factually sound.
Set "understood" to true only if score >= 70.
Reply with ONLY a JSON object, no markdown:
{"understood": true/false, "score": 0-100, "missing": ["important points the student missed or got wrong"], "feedback": "2-3 short sentences in Egyptian Arabic"}
""".trimIndent()

        val body = JSONObject()
            .put("model", "claude-sonnet-5-5")
            .put("max_tokens", 800)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", key)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val txt = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw Exception("HTTP $code: $txt")

        val out = JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text")
        return JSONObject(out.replace("```json", "").replace("```", "").trim())
    }

    private fun showVerdict(v: JSONObject) {
        val ok = v.optBoolean("understood", false)
        val score = v.optInt("score")
        val sb = StringBuilder()
        if (ok) {
            val mins = breakEt.text.toString().toLongOrNull() ?: 10L
            val ms = mins * 60_000
            val now = System.currentTimeMillis()
            val lu = Focus.lockUntil(this)
            if (lu > now) {
                Focus.p(this).edit().putLong("lockUntil", lu + ms).putLong("breakUntil", now + ms).apply()
            }
            sb.append("✅ فاهم! درجتك $score. خد استراحة $mins دقيقة ☕\n\n")
        } else {
            sb.append("❌ لسه مش فاهم كويس (درجتك $score). ارجع اسمع المحاضرة تاني وجرّب تاني 🔁\nالقفل لسه شغال.\n\n")
        }
        sb.append(v.optString("feedback")).append("\n")
        v.optJSONArray("missing")?.let { for (i in 0 until it.length()) sb.append("• ").append(it.getString(i)).append("\n") }
        resultTv.text = sb.toString()
        refresh()
    }
}
