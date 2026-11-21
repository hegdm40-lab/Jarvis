package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val model = "claude-sonnet-5-5"
    private val system = "Sen JARVIS'sin: kullanicinin Android telefonunda calisan, zeki, kibar ve hafif esprili bir sesli asistan. " +
            "Turkce konus. Cevaplarin sesli okunacak: KISA ol (1-3 cumle), emoji, markdown, liste kullanma. " +
            "Telefonla ilgili bir sey istenirse uygun araci cagir, sonra sonucu kisaca soyle. " +
            "Saat/tarih icin get_time kullan, uydurma. Emin degilsen kisa bir soru sor."

    private lateinit var tts: TextToSpeech
    private var rec: SpeechRecognizer? = null
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var keyBox: EditText
    private lateinit var btn: Button
    private val ui = Handler(Looper.getMainLooper())
    private val history = JSONArray()
    private var active = false
    private var awakeUntil = 0L

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val p = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p * 2, p, p)
        }
        keyBox = EditText(this).apply {
            hint = "Anthropic API anahtari (sk-ant-...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(getSharedPreferences("j", MODE_PRIVATE).getString("key", ""))
        }
        btn = Button(this).apply {
            text = "BASLAT"
            textSize = 22f
            setOnClickListener { toggle() }
        }
        logView = TextView(this).apply { textSize = 16f }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(keyBox)
        root.addView(btn)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        tts = TextToSpeech(this, this)
        log("Anahtari gir, BASLAT'a bas, sonra 'Jarvis' de.")
    }

    override fun onInit(st: Int) {
        tts.language = Locale("tr", "TR")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { listen() }
            override fun onDone(id: String?) { listen() }
        })
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) toggle()
        else log("Mikrofon izni gerekli.")
    }

    override fun onDestroy() {
        active = false
        rec?.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    private fun log(t: String) {
        ui.post {
            logView.append(t + "\n\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun toggle() {
        if (active) {
            active = false
            rec?.destroy()
            tts.stop()
            btn.text = "BASLAT"
            return
        }
        val k = keyBox.text.toString().trim()
        if (k.isEmpty()) { log("Once API anahtarini gir."); return }
        getSharedPreferences("j", MODE_PRIVATE).edit().putString("key", k).apply()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        active = true
        btn.text = "DURDUR"
        say("Jarvis cevrimici. Emrinizdeyim.")
    }

    private fun say(t: String) {
        log("JARVIS: $t")
        ui.post { tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime()) }
    }

    private fun listen() {
        if (!active) return
        ui.post {
            rec?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            rec = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
                override fun onError(e: Int) {
                    if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        active = false; btn.text = "BASLAT"; log("Mikrofon izni yok."); return
                    }
                    if (active) ui.postDelayed({ listen() }, 700)
                }
                override fun onResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t.isNullOrBlank()) listen() else handle(t)
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            r.startListening(i)
        }
    }

    private fun handle(raw: String) {
        log("Sen: $raw")
        val low = raw.lowercase(Locale("tr"))
        val w = listOf("jarvis", "carvis", "çarvis", "jarvıs").firstOrNull { low.contains(it) }
        val now = System.currentTimeMillis()
        if (w == null && now > awakeUntil) { listen(); return }
        val cmd = (if (w != null) low.replaceFirst(w, "") else low).trim(' ', ',', '.', '!', '?')
        if (cmd.isEmpty()) { awakeUntil = now + 25000; say("Dinliyorum."); return }
        if (listOf("görüşürüz", "kapan").any { cmd.contains(it) }) {
            say("Görüşürüz.")
            ui.postDelayed({ if (active) toggle() }, 1800)
            return
        }
        thread {
            val r = askClaude(cmd)
            awakeUntil = System.currentTimeMillis() + 25000
            say(r)
        }
    }

    // ---------- Claude ----------
    private fun askClaude(text: String): String {
        history.put(JSONObject().put("role", "user").put("content", text))
        while (history.length() > 20) history.remove(0)
        while (history.length() > 0) {
            val f = history.getJSONObject(0)
            if (f.getString("role") == "user" && f.get("content") is String) break
            history.remove(0)
        }
        for (turn in 0 until 6) {
            val resp = api() ?: return "Internet ya da API baglantisinda sorun var."
            if (resp.has("error")) return "Hata: " + resp.getJSONObject("error").optString("message")
            val content = resp.getJSONArray("content")
            history.put(JSONObject().put("role", "assistant").put("content", content))
            if (resp.optString("stop_reason") != "tool_use") {
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val b = content.getJSONObject(i)
                    if (b.getString("type") == "text") sb.append(b.getString("text")).append(' ')
                }
                return sb.toString().trim().ifEmpty { "Tamamdir." }
            }
            val results = JSONArray()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                if (b.getString("type") == "tool_use") {
                    val out = runTool(b.getString("name"), b.getJSONObject("input"))
                    results.put(JSONObject().put("type", "tool_result")
                        .put("tool_use_id", b.getString("id")).put("content", out))
                }
            }
            history.put(JSONObject().put("role", "user").put("content", results))
        }
        return "Islem uzadi, tekrar dener misin?"
    }

    private fun api(): JSONObject? = try {
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", getSharedPreferences("j", MODE_PRIVATE).getString("key", ""))
        c.setRequestProperty("anthropic-version", "2023-06-01")
        val body = JSONObject().put("model", model).put("max_tokens", 600)
            .put("system", system).put("tools", tools()).put("messages", history)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val st = if (c.responseCode < 400) c.inputStream else c.errorStream
        JSONObject(st.bufferedReader().readText())
    } catch (e: Exception) { null }

    private fun t(n: String, d: String, p: String = "{}", r: String = "[]") = JSONObject()
        .put("name", n).put("description", d)
        .put("input_schema", JSONObject("""{"type":"object","properties":$p,"required":$r}"""))

    private fun tools() = JSONArray().apply {
        put(t("get_time", "Guncel tarih ve saat."))
        put(t("flashlight", "El fenerini acar/kapatir.", """{"on":{"type":"boolean"}}""", """["on"]"""))
        put(t("battery", "Pil yuzdesini verir."))
        put(t("open_app", "Uygulama acar. Uygulamanin adi (ornek: WhatsApp, YouTube, Spotify, Chrome).", """{"name":{"type":"string"}}""", """["name"]"""))
        put(t("open_url", "URL acar.", """{"url":{"type":"string"}}""", """["url"]"""))
        put(t("web_search", "Google'da arama acar.", """{"query":{"type":"string"}}""", """["query"]"""))
        put(t("set_alarm", "Alarm kurar (24 saat).", """{"hour":{"type":"integer"},"minute":{"type":"integer"},"label":{"type":"string"}}""", """["hour","minute"]"""))
        put(t("set_timer", "Zamanlayici kurar.", """{"seconds":{"type":"integer"}}""", """["seconds"]"""))
        put(t("call", "Numarayi tuslayip arama ekranini acar.", """{"number":{"type":"string"}}""", """["number"]"""))
        put(t("send_sms", "SMS taslagi hazirlar, kullanici gonder tusuna basar.", """{"number":{"type":"string"},"text":{"type":"string"}}""", """["number","text"]"""))
        put(t("set_volume", "Medya ses seviyesini yuzde olarak ayarlar.", """{"percent":{"type":"integer"}}""", """["percent"]"""))
    }

    private fun runTool(n: String, a: JSONObject): String = try {
        when (n) {
            "get_time" -> SimpleDateFormat("d MMMM yyyy EEEE, HH:mm", Locale("tr", "TR")).format(Date())
            "flashlight" -> {
                val cm = getSystemService(CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                cm.setTorchMode(id, a.getBoolean("on"))
                "Tamam"
            }
            "battery" -> "Pil %" + (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            "open_app" -> {
                val q = a.getString("name").lowercase()
                val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val app = packageManager.queryIntentActivities(li, 0)
                    .firstOrNull { it.loadLabel(packageManager).toString().lowercase().contains(q) }
                if (app == null) "Uygulama bulunamadi" else {
                    startActivity(packageManager.getLaunchIntentForPackage(app.activityInfo.packageName))
                    "Acildi"
                }
            }
            "open_url" -> { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url")))); "Acildi" }
            "web_search" -> {
                val q = URLEncoder.encode(a.getString("query"), "UTF-8")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q"))); "Arama acildi"
            }
            "set_alarm" -> {
                startActivity(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, a.getInt("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                "Alarm kuruldu"
            }
            "set_timer" -> {
                startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("seconds"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                "Zamanlayici kuruldu"
            }
            "call" -> { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + a.getString("number")))); "Arama ekrani acildi, aramaya sen bas" }
            "send_sms" -> {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + a.getString("number")))
                    .putExtra("sms_body", a.getString("text")))
                "Mesaj hazir, gonder tusuna sen bas"
            }
            "set_volume" -> {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max * a.getInt("percent").coerceIn(0, 100) / 100, 0)
                "Ses ayarlandi"
            }
            else -> "Bilinmeyen arac"
        }
    } catch (e: Exception) { "Arac hatasi: " + e.mess
