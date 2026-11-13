package com.saman.eurusd

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val PIP = 0.0001

val BG = 0xFF0B1220.toInt()
val CARD = 0xFF131F33.toInt()
val WHITE = 0xFFF1F5F9.toInt()
val GRAY = 0xFF94A3B8.toInt()
val GREEN = 0xFF22C55E.toInt()
val RED = 0xFFEF4444.toInt()
val AMBER = 0xFFF59E0B.toInt()
val BLUE = 0xFF3B82F6.toInt()

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun f5(d: Double): String = if (d.isNaN()) "—" else String.format(Locale.US, "%.5f", d)

fun pipsStr(d: Double): String = String.format(Locale.US, "%+.1f", d / PIP)

fun fmtTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

fun String.toD(): Double? {
    val sb = StringBuilder()
    for (ch in this.trim()) {
        val c: Char = when (ch) {
            in '۰'..'۹' -> '0' + (ch - '۰')
            in '٠'..'٩' -> '0' + (ch - '٠')
            ',', '٫', '،' -> '.'
            else -> ch
        }
        sb.append(c)
    }
    return sb.toString().toDoubleOrNull()
}
