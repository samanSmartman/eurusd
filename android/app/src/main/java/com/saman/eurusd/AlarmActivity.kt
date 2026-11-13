package com.saman.eurusd

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

@Suppress("DEPRECATION")
class AlarmActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var titleTv: TextView
    private lateinit var bodyTv: TextView
    private var lineId: String? = null

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        titleTv = TextView(this).apply {
            textSize = 26f
            setTextColor(AMBER)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        bodyTv = TextView(this).apply {
            textSize = 17f
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(32))
        }
        val keep = Button(this).apply {
            text = "Stop alarm & KEEP line"
            isAllCaps = false
            textSize = 17f
            setTextColor(WHITE)
            setBackgroundColor(GREEN)
            setOnClickListener { resolve(false) }
        }
        val dis = Button(this).apply {
            text = "Stop alarm & DISABLE line"
            isAllCaps = false
            textSize = 17f
            setTextColor(WHITE)
            setBackgroundColor(RED)
            setOnClickListener { resolve(true) }
        }
        col.addView(titleTv)
        col.addView(bodyTv)
        col.addView(keep, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(16) })
        col.addView(dis, LinearLayout.LayoutParams(-1, dp(64)))
        setContentView(col)
    }

    private fun resolve(disable: Boolean) {
        val id = lineId ?: return
        Alarm.resolve(this, id, disable)
        refresh()
    }

    private fun refresh() {
        val t = Alarm.current()
        if (t == null) {
            finish()
            return
        }
        lineId = t.lineId
        titleTv.text = t.title
        val l = Store.snapshot().find { it.id == t.lineId }
        val live = Engine.live
        bodyTv.text = if (l != null && !live.isNaN()) {
            val price = live + Store.offsetPips * PIP
            val lp = l.priceAt(System.currentTimeMillis())
            "Price ${f5(price)}\nLine ${f5(lp)}\n${pipsStr(price - lp)} pips"
        } else t.text
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        super.onPause()
    }
}
