package com.saman.eurusd

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Calendar
import kotlin.math.abs

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private val DARK = android.R.style.Theme_Material_Dialog_Alert
    private val RC_RINGTONE = 11
    private val RC_FILE = 12

    private val h = Handler(Looper.getMainLooper())
    private lateinit var priceTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var offsetEt: EditText
    private var shownVersion = -1
    private val liveViews = ArrayList<Pair<String, TextView>>()

    private var dlgSound = ""
    private var dlgSoundBtn: Button? = null

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 400)
        }
    }

    private fun tv(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun btn(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(WHITE)
        setBackgroundColor(color)
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int, hh: Int, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, hh, weight).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = ScrollView(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(40))
        }
        root.addView(col)

        col.addView(tv("EURUSD Line Alarm", 20f, WHITE, true))
        priceTv = tv("—", 42f, GREEN, true)
        col.addView(priceTv, lp(-2, -2, 8))
        infoTv = tv("", 12f, GRAY)
        col.addView(infoTv, lp(-1, -2, 2))

        val sw = Switch(this).apply {
            text = "Monitoring service (keeps alarms alive)"
            setTextColor(WHITE)
            isChecked = Store.monitoring
            setOnCheckedChangeListener { _, on ->
                Store.updateMonitoring(on)
                if (on) PriceService.start(this@MainActivity)
                else stopService(Intent(this@MainActivity, PriceService::class.java))
            }
        }
        col.addView(sw, lp(-1, -2, 12))

        col.addView(tv("Price offset (pips). Example: -2 lowers the app price by 2 pips to match your broker chart", 12f, GRAY), lp(-1, -2, 14))
        val offRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        offsetEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = "0"
            setTextColor(WHITE)
            setHintTextColor(GRAY)
            setText(Store.offsetPips.toString())
        }
        offRow.addView(offsetEt, lp(0, -2, 0, 1f))
        offRow.addView(btn("Save", BLUE) {
            val v = offsetEt.text.toString().toD()
            if (v == null) toast("Invalid number") else {
                Store.setOffset(v)
                toast("Offset saved")
            }
        }, lp(dp(90), -2))
        col.addView(offRow)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("+ Add line", GREEN) { showEditor(null) }, lp(0, -2, 0, 1f))
        row.addView(btn("Test alarm", AMBER) {
            Alarm.trigger(this, Alarm.Trig("test", "Test alarm", "This is a test alarm", ""))
        }, lp(0, -2, 0, 1f))
        row.addView(btn("Background setup", BLUE) { showSetup() }, lp(0, -2, 0, 1f))
        col.addView(row, lp(-1, -2, 14))

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox, lp(-1, -2, 14))

        setContentView(root)
        if (Store.monitoring) PriceService.start(this)
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        super.onPause()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun refresh() {
        val live = Engine.live
        val off = Store.offsetPips
        priceTv.text = if (live.isNaN()) "—" else f5(live + off * PIP)
        val err = StringBuilder()
        if (Engine.yahooErr.isNotEmpty()) err.append("\nYahoo error: ${Engine.yahooErr}")
        if (Engine.binanceErr.isNotEmpty()) err.append("\nBinance error: ${Engine.binanceErr}")
        infoTv.text = "Raw ${f5(live)}  •  offset $off pips\nYahoo ${f5(Engine.yahoo)}  |  Binance ${f5(Engine.binance)}\nSource: ${Engine.source}$err"

        val v = Store.version
        if (v != shownVersion) rebuildList(v)
        if (!live.isNaN()) {
            val price = live + off * PIP
            val now = System.currentTimeMillis()
            val map = Store.snapshot().associateBy { it.id }
            for ((id, t) in liveViews) {
                val l = map[id] ?: continue
                val lp = l.priceAt(now)
                val d = price - lp
                t.text = "Line now ${f5(lp)}  •  ${pipsStr(d)} pips (${if (d >= 0) "price above" else "price below"})"
                t.setTextColor(if (abs(d) <= l.nearPips * PIP) AMBER else WHITE)
            }
        }
    }

    private fun rebuildList(v: Int) {
        listBox.removeAllViews()
        liveViews.clear()
        val lines = Store.snapshot()
        if (lines.isEmpty()) {
            listBox.addView(tv("No lines yet. Tap “+ Add line”, enter point 1 (older) and point 2 (newer) with prices and times.", 13f, GRAY))
        }
        for (l in lines) listBox.addView(card(l), lp(-1, -2, 10))
        shownVersion = v
    }

    private fun card(l: Line): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadius = dp(12).toFloat()
            }
        }
        val kind = if (l.support) "Support" else "Resistance"
        val mode = if (l.sloped) "sloped" else "horizontal"
        c.addView(tv("${l.name}  ·  $kind · $mode", 16f, if (l.support) GREEN else RED, true))
        val liveTv = tv("…", 14f, WHITE)
        liveViews.add(Pair(l.id, liveTv))
        c.addView(liveTv, lp(-1, -2, 4))
        val det = if (l.sloped)
            "P1 ${f5(l.p1)} @ ${fmtTime(l.t1)}\nP2 ${f5(l.p2)} @ ${fmtTime(l.t2)}\nAlert within ${l.nearPips} pips"
        else "Level ${f5(l.p2)}\nAlert within ${l.nearPips} pips"
        c.addView(tv(det, 12f, GRAY), lp(-1, -2, 4))
        val state = when {
            !l.enabled -> "Disabled"
            l.armed -> "Armed"
            else -> "Triggered – waiting for price to move away"
        }
        c.addView(tv("Sound: ${soundLabel(l.sound)}  •  $state", 12f, GRAY), lp(-1, -2, 2))

        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val s = Switch(this).apply {
            text = "On"
            setTextColor(WHITE)
            isChecked = l.enabled
            setOnCheckedChangeListener { _, on -> Store.setEnabled(l.id, on) }
        }
        r.addView(s, lp(0, -2, 0, 1f))
        r.addView(btn("Edit", BLUE) { showEditor(l) }, lp(dp(80), -2))
        r.addView(btn("Delete", RED) {
            AlertDialog.Builder(this, DARK).setTitle("Delete “${l.name}”?")
                .setPositiveButton("Delete") { _, _ -> Store.delete(l.id) }
                .setNegativeButton("Cancel", null).show()
        }, lp(dp(90), -2))
        c.addView(r, lp(-1, -2, 8))
        return c
    }

    private fun pickDateTime(initial: Long, onPicked: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = initial }
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, hh, mm ->
                val cc = Calendar.getInstance()
                cc.set(y, m, d, hh, mm, 0)
                cc.set(Calendar.MILLISECOND, 0)
                onPicked(cc.timeInMillis)
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun numEt(hint: String, value: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        this.hint = hint
        setText(value)
    }

    private fun showEditor(ex: Line?) {
        val now = System.currentTimeMillis()
        var t1 = ex?.t1 ?: (now - 3 * 3600_000L)
        var t2 = ex?.t2 ?: now
        dlgSound = ex?.sound ?: ""

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val nameEt = EditText(this).apply {
            hint = "Name"
            setText(ex?.name ?: "Line ${Store.snapshot().size + 1}")
        }
        col.addView(nameEt)

        val typeG = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val supRb = RadioButton(this).apply { text = "Support"; id = View.generateViewId() }
        val resRb = RadioButton(this).apply { text = "Resistance"; id = View.generateViewId() }
        typeG.addView(supRb); typeG.addView(resRb)
        if (ex?.support == false) resRb.isChecked = true else supRb.isChecked = true
        col.addView(typeG)

        val modeG = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val slopRb = RadioButton(this).apply { text = "Sloped line (through point 1 and point 2)"; id = View.generateViewId() }
        val horRb = RadioButton(this).apply { text = "Horizontal (at point 2 price)"; id = View.generateViewId() }
        modeG.addView(slopRb); modeG.addView(horRb)
        if (ex?.sloped == false) horRb.isChecked = true else slopRb.isChecked = true
        col.addView(modeG)

        col.addView(tv("Point 1 (older)", 13f, GRAY), lp(-1, -2, 10))
        val p1Et = numEt("Price 1", if (ex != null) ex.p1.toString() else "")
        col.addView(p1Et)
        val t1Btn = btn(fmtTime(t1), BLUE) {}
        t1Btn.setOnClickListener { pickDateTime(t1) { t1 = it; t1Btn.text = fmtTime(it) } }
        col.addView(t1Btn)

        col.addView(tv("Point 2 (newer)", 13f, GRAY), lp(-1, -2, 10))
        val p2Et = numEt("Price 2", if (ex != null) ex.p2.toString() else "")
        col.addView(p2Et)
        val t2Btn = btn(fmtTime(t2), BLUE) {}
        t2Btn.setOnClickListener { pickDateTime(t2) { t2 = it; t2Btn.text = fmtTime(it) } }
        col.addView(t2Btn)

        col.addView(tv("Alert distance (pips)", 13f, GRAY), lp(-1, -2, 10))
        val nearEt = numEt("3", (ex?.nearPips ?: 3.0).toString())
        col.addView(nearEt)

        val soundBtn = btn("Sound: ${soundLabel(dlgSound)}", AMBER) {}
        dlgSoundBtn = soundBtn
        soundBtn.setOnClickListener { chooseSound() }
        col.addView(soundBtn, lp(-1, -2, 10))

        val dlg = AlertDialog.Builder(this, DARK)
            .setTitle(if (ex == null) "New line" else "Edit line")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val p2 = p2Et.text.toString().toD()
                val sloped = slopRb.isChecked
                val p1 = if (sloped) p1Et.text.toString().toD() else (p1Et.text.toString().toD() ?: p2)
                val near = nearEt.text.toString().toD()
                when {
                    p2 == null || p2 <= 0 -> toast("Enter a valid price for point 2")
                    p1 == null || p1 <= 0 -> toast("Enter a valid price for point 1")
                    near == null || near <= 0 -> toast("Enter a valid alert distance")
                    sloped && t2 <= t1 -> toast("Point 2 must be newer than point 1")
                    else -> {
                        Store.upsert(
                            Line(
                                ex?.id ?: Line.newId(), nameEt.text.toString().ifBlank { "Line" },
                                supRb.isChecked, sloped, p1!!, t1, p2!!, t2, near!!, dlgSound,
                                ex?.enabled ?: true, true
                            )
                        )
                        dlg.dismiss()
                    }
                }
            }
        }
        dlg.show()
    }

    private fun soundLabel(s: String): String {
        if (s.isEmpty()) return "Default alarm"
        return try {
            if (s.startsWith("file:")) File(Uri.parse(s).path ?: "").name.substringAfter("_")
            else RingtoneManager.getRingtone(this, Uri.parse(s)).getTitle(this)
        } catch (e: Exception) {
            "Custom"
        }
    }

    private fun chooseSound() {
        AlertDialog.Builder(this, DARK).setTitle("Alarm sound")
            .setItems(arrayOf("Default alarm sound", "Phone ringtones / alarms", "Pick my own audio file")) { _, w ->
                when (w) {
                    0 -> {
                        dlgSound = ""
                        dlgSoundBtn?.text = "Sound: ${soundLabel(dlgSound)}"
                    }
                    1 -> {
                        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        if (dlgSound.isNotEmpty() && !dlgSound.startsWith("file:"))
                            i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(dlgSound))
                        startActivityForResult(i, RC_RINGTONE)
                    }
                    else -> {
                        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                        startActivityForResult(i, RC_FILE)
                    }
                }
            }.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == RC_RINGTONE) {
            val u: Uri? = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            dlgSound = u?.toString() ?: ""
            dlgSoundBtn?.text = "Sound: ${soundLabel(dlgSound)}"
        } else if (requestCode == RC_FILE) {
            val u = data.data ?: return
            toast("Copying sound…")
            Thread {
                try {
                    var name = "audio"
                    contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) name = it.getString(0) ?: "audio"
                    }
                    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val dir = File(filesDir, "sounds").apply { mkdirs() }
                    val f = File(dir, "${System.currentTimeMillis()}_$safe")
                    contentResolver.openInputStream(u)!!.use { inp -> f.outputStream().use { out -> inp.copyTo(out) } }
                    runOnUiThread {
                        dlgSound = Uri.fromFile(f).toString()
                        dlgSoundBtn?.text = "Sound: ${soundLabel(dlgSound)}"
                    }
                } catch (e: Exception) {
                    runOnUiThread { toast("Could not read that file") }
                }
            }.start()
        }
    }

    private fun showSetup() {
        val msg = "To keep alarms working when the app is closed (Xiaomi / Redmi):\n\n" +
            "1. Battery → No restrictions\n" +
            "2. Autostart → ON\n" +
            "3. App settings → Other permissions → allow “Display pop-up windows while running in the background” and “Show on lock screen”\n" +
            "4. Open Recent apps, long-press the app card and Lock it\n" +
            "5. Turn the Alarm volume up"
        AlertDialog.Builder(this, DARK).setTitle("Background setup").setMessage(msg)
            .setPositiveButton("Battery") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNeutralButton("Autostart") { _, _ ->
                try {
                    startActivity(
                        Intent().setComponent(
                            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                        )
                    )
                } catch (e: Exception) {
                    appSettings()
                }
            }
            .setNegativeButton("App settings") { _, _ -> appSettings() }
            .show()
    }

    private fun appSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}
