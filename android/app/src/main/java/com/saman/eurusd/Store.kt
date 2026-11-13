package com.saman.eurusd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Line(
    val id: String,
    var name: String,
    var support: Boolean,
    var sloped: Boolean,
    var p1: Double,
    var t1: Long,
    var p2: Double,
    var t2: Long,
    var nearPips: Double,
    var sound: String,
    var enabled: Boolean,
    var armed: Boolean
) {
    fun priceAt(nowMs: Long): Double {
        if (!sloped || t2 <= t1) return p2
        return p1 + (p2 - p1) * (nowMs - t1).toDouble() / (t2 - t1).toDouble()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("support", support); put("sloped", sloped)
        put("p1", p1); put("t1", t1); put("p2", p2); put("t2", t2)
        put("near", nearPips); put("sound", sound); put("enabled", enabled); put("armed", armed)
    }

    companion object {
        fun fromJson(o: JSONObject) = Line(
            o.getString("id"), o.optString("name", "Line"),
            o.optBoolean("support", true), o.optBoolean("sloped", true),
            o.getDouble("p1"), o.getLong("t1"), o.getDouble("p2"), o.getLong("t2"),
            o.optDouble("near", 3.0), o.optString("sound", ""),
            o.optBoolean("enabled", true), o.optBoolean("armed", true)
        )

        fun newId(): String = UUID.randomUUID().toString()
    }
}

object Store {
    private lateinit var ctx: Context
    private val lines = ArrayList<Line>()
    private var inited = false

    @Volatile var version = 0
    @Volatile var offsetPips = 0.0
    @Volatile var monitoring = true

    @Synchronized
    fun init(c: Context) {
        if (inited) return
        ctx = c.applicationContext
        val sp = ctx.getSharedPreferences("eurusd", Context.MODE_PRIVATE)
        offsetPips = sp.getString("offset", "0")?.toDoubleOrNull() ?: 0.0
        monitoring = sp.getBoolean("monitoring", true)
        try {
            val arr = JSONArray(sp.getString("lines", "[]"))
            for (i in 0 until arr.length()) lines.add(Line.fromJson(arr.getJSONObject(i)))
        } catch (e: Exception) {
        }
        inited = true
    }

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        lines.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences("eurusd", Context.MODE_PRIVATE).edit()
            .putString("lines", arr.toString())
            .putString("offset", offsetPips.toString())
            .putBoolean("monitoring", monitoring)
            .apply()
        version++
    }

    @Synchronized fun snapshot(): List<Line> = lines.map { it.copy() }

    @Synchronized
    fun upsert(l: Line) {
        val i = lines.indexOfFirst { it.id == l.id }
        if (i >= 0) lines[i] = l.copy() else lines.add(l.copy())
        save()
    }

    @Synchronized
    fun delete(id: String) {
        lines.removeAll { it.id == id }
        save()
    }

    @Synchronized
    fun setEnabled(id: String, v: Boolean) {
        val l = lines.find { it.id == id } ?: return
        l.enabled = v
        if (v) l.armed = true
        save()
    }

    @Synchronized
    fun setArmed(id: String, v: Boolean) {
        val l = lines.find { it.id == id } ?: return
        if (l.armed != v) {
            l.armed = v
            save()
        }
    }

    @Synchronized fun setOffset(v: Double) { offsetPips = v; save() }

    @Synchronized fun setMonitoring(v: Boolean) { monitoring = v; save() }
}
