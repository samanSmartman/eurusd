package app.eurusd.watch

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    lateinit var web: WebView
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = 0xFF0E1726.toInt()
        web = WebView(this)
        web.setBackgroundColor(0xFF0E1726.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                startActivity(Intent(Intent.ACTION_VIEW, r.url)); return true
            }
        }
        web.addJavascriptInterface(Bridge(this), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }
    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.evaluateJavascript("try{pullNative()}catch(e){}", null)
    }
    override fun onBackPressed() { moveTaskToBack(true) }
}

class Bridge(private val a: Activity) {
    @JavascriptInterface fun arm() {
        a.runOnUiThread { if (Build.VERSION.SDK_INT >= 33) a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
    }
    @JavascriptInterface fun sync(json: String) { try { Sched.sync(a, json) } catch (e: Exception) {} }
    @JavascriptInterface fun getPa(): String = Sched.prefs(a).getString("pa", "[]") ?: "[]"
}

object Sched {
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("eu", 0)
    fun sync(c: Context, json: String) {
        val o = JSONObject(json)
        prefs(c).edit()
            .putString("ev", o.getJSONArray("ev").toString())
            .putString("pa", o.getJSONArray("pa").toString())
            .putString("snd", o.getJSONObject("snd").toString())
            .putFloat("vol", o.getDouble("vol").toFloat()).apply()
        schedule(c)
        PriceService.update(c)
    }
    fun pi(c: Context, code: Int, e: JSONObject?): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
        if (e != null) { i.putExtra("t", e.getString("t")); i.putExtra("b", e.getString("b")); i.putExtra("c", e.getString("c")) }
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val p = prefs(c)
        (p.getString("codes", "") ?: "").split(",").filter { it.isNotEmpty() }.forEach { am.cancel(pi(c, it.toInt(), null)) }
        val ev = JSONArray(p.getString("ev", "[]"))
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val now = System.currentTimeMillis()
        val codes = ArrayList<String>()
        for (k in 0 until ev.length()) {
            val e = ev.getJSONObject(k)
            val at = e.getLong("at")
            if (at <= now) continue
            val code = e.getString("id").hashCode()
            codes.add(code.toString())
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi(c, code, e))
        }
        p.edit().putString("codes", codes.joinToString(",")).apply()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        RingService.start(c, i.getStringExtra("t") ?: "EURUSD", i.getStringExtra("b") ?: "", i.getStringExtra("c") ?: "kz")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { try { Sched.schedule(c) } catch (e: Exception) {} }
}

class RingService : Service() {
    private var rt: Ringtone? = null
    private var vib: Vibrator? = null
    companion object {
        fun start(c: Context, t: String, b: String, cat: String) {
            c.startForegroundService(Intent(c, RingService::class.java).putExtra("t", t).putExtra("b", b).putExtra("c", cat))
        }
    }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val t = i?.getStringExtra("t") ?: "EURUSD"
        val b = i?.getStringExtra("b") ?: ""
        val cat = i?.getStringExtra("c") ?: "kz"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ring", "Alarms", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val stop = PendingIntent.getService(this, 1, Intent(this, RingService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "ring")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(t).setContentText(b)
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(open, true).setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "Stop", stop).build())
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(7, n)
        val sp = Sched.prefs(this)
        val snd = JSONObject(sp.getString("snd", "{}") ?: "{}").optString(cat, "bell")
        val type = when (snd) { "siren" -> RingtoneManager.TYPE_ALARM; "beep" -> RingtoneManager.TYPE_RINGTONE; else -> RingtoneManager.TYPE_NOTIFICATION }
        rt?.stop()
        rt = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(type))
        rt?.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        rt?.isLooping = true
        rt?.volume = sp.getFloat("vol", 0.8f)
        rt?.play()
        vib = getSystemService(Vibrator::class.java)
        vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300), 0))
        Handler(Looper.getMainLooper()).postDelayed({ stopSelf() }, 120000)
        return START_NOT_STICKY
    }
    override fun onDestroy() { rt?.stop(); vib?.cancel(); super.onDestroy() }
}

class PriceService : Service() {
    private var alive = false
    private var th: Thread? = null
    companion object {
        fun active(c: Context): Boolean {
            val a = JSONArray(Sched.prefs(c).getString("pa", "[]") ?: "[]")
            for (i in 0 until a.length()) if (a.getJSONObject(i).optBoolean("on", true)) return true
            return false
        }
        fun update(c: Context) {
            val i = Intent(c, PriceService::class.java)
            if (active(c)) c.startForegroundService(i) else c.stopService(i)
        }
    }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("watch", "Price watch", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "watch").setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Watching your EURUSD price alarms").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(8, n)
        if (th == null) {
            alive = true
            th = Thread {
                while (alive) {
                    try { val p = price(); if (p > 0) check(p) } catch (e: Exception) {}
                    try { Thread.sleep(5000) } catch (e: Exception) {}
                }
            }
            th!!.start()
        }
        return START_STICKY
    }
    private fun price(): Double {
        for (b in listOf("https://data-api.binance.vision", "https://api.binance.com")) {
            try {
                val c = URL(b + "/api/v3/ticker/price?symbol=EURUSDT").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val s = c.inputStream.bufferedReader().readText()
                c.disconnect()
                return JSONObject(s).getString("price").toDouble()
            } catch (e: Exception) {}
        }
        return 0.0
    }
    private fun check(p: Double) {
        val sp = Sched.prefs(this)
        val a = JSONArray(sp.getString("pa", "[]") ?: "[]")
        var hit = false
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (!o.optBoolean("on", true)) continue
            val t = o.getDouble("p")
            val dir = o.getString("dir")
            if ((dir == "up" && p >= t) || (dir == "dn" && p <= t)) {
                o.put("on", false); hit = true
                RingService.start(this, "EURUSD hit " + String.format("%.5f", t), "Now " + String.format("%.5f", p), "pr")
            }
        }
        if (hit) { sp.edit().putString("pa", a.toString()).apply(); if (!active(this)) stopSelf() }
    }
    override fun onDestroy() { alive = false; super.onDestroy() }
}
