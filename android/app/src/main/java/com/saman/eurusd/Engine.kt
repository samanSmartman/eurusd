package com.saman.eurusd

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

object Engine {
    @Volatile var yahoo = Double.NaN
    @Volatile var binance = Double.NaN
    @Volatile var live = Double.NaN
    @Volatile var yahooMarketMs = 0L
    @Volatile var lastYahooOk = 0L
    @Volatile var lastBinanceOk = 0L
    @Volatile var yahooErr = ""
    @Volatile var binanceErr = ""
    @Volatile var source = "waiting…"

    private var binRef = Double.NaN
    private var lastMarketSec = -1L
    private val hist = ArrayList<Pair<Long, Double>>()

    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        )
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun pollBinance() {
        val urls = listOf(
            "https://api.binance.com/api/v3/ticker/price?symbol=EURUSDT",
            "https://data-api.binance.vision/api/v3/ticker/price?symbol=EURUSDT"
        )
        var err = "error"
        for (u in urls) {
            try {
                val p = JSONObject(http(u)).getString("price").toDouble()
                onBinance(p)
                return
            } catch (e: Exception) {
                err = e.javaClass.simpleName
            }
        }
        binanceErr = err
    }

    fun pollYahoo() {
        val urls = listOf(
            "https://query1.finance.yahoo.com/v8/finance/chart/EURUSD=X?interval=1m&range=1d",
            "https://query2.finance.yahoo.com/v8/finance/chart/EURUSD=X?interval=1m&range=1d"
        )
        var err = "error"
        for (u in urls) {
            try {
                val meta = JSONObject(http(u)).getJSONObject("chart").getJSONArray("result")
                    .getJSONObject(0).getJSONObject("meta")
                onYahoo(meta.getDouble("regularMarketPrice"), meta.optLong("regularMarketTime", 0L))
                return
            } catch (e: Exception) {
                err = e.javaClass.simpleName
            }
        }
        yahooErr = err
    }

    @Synchronized
    private fun onBinance(p: Double) {
        val now = System.currentTimeMillis()
        binance = p
        lastBinanceOk = now
        binanceErr = ""
        hist.add(Pair(now, p))
        while (hist.isNotEmpty() && now - hist[0].first > 10 * 60_000L) hist.removeAt(0)
        recompute()
    }

    @Synchronized
    private fun onYahoo(p: Double, marketSec: Long) {
        val now = System.currentTimeMillis()
        yahoo = p
        lastYahooOk = now
        yahooErr = ""
        yahooMarketMs = if (marketSec > 0) marketSec * 1000 else now
        if (marketSec != lastMarketSec || binRef.isNaN()) {
            lastMarketSec = marketSec
            binRef = binanceAt(yahooMarketMs)
        }
        recompute()
    }

    private fun binanceAt(ms: Long): Double {
        if (hist.isEmpty()) return Double.NaN
        var best = hist[0]
        for (h in hist) if (abs(h.first - ms) < abs(best.first - ms)) best = h
        return best.second
    }

    private fun recompute() {
        val now = System.currentTimeMillis()
        val binFresh = !binance.isNaN() && now - lastBinanceOk < 10_000
        val yahooOkRecent = now - lastYahooOk < 60_000
        val marketClosed = yahooOkRecent && now - yahooMarketMs > 10 * 60_000L
        if (yahoo.isNaN()) {
            if (binFresh) {
                live = binance
                source = "Binance only (raw)"
            }
        } else if (!marketClosed && binFresh && !binRef.isNaN()) {
            live = yahoo + (binance - binRef)
            source = "Yahoo + Binance"
        } else {
            live = yahoo
            source = if (marketClosed) "Yahoo (market closed?)" else "Yahoo only"
        }
    }
}

object Evaluator {
    private val prevDist = HashMap<String, Double>()

    fun run(ctx: Context) {
        val live = Engine.live
        if (live.isNaN()) return
        val now = System.currentTimeMillis()
        val price = live + Store.offsetPips * PIP
        for (l in Store.snapshot()) {
            if (!l.enabled) {
                prevDist.remove(l.id)
                continue
            }
            val lp = l.priceAt(now)
            val dist = price - lp
            val near = l.nearPips * PIP
            val prev = prevDist[l.id]
            prevDist[l.id] = dist
            if (!l.armed) {
                if (abs(dist) > near * 2 + PIP) Store.setArmed(l.id, true)
                continue
            }
            val crossed = prev != null && prev * dist < 0
            if (abs(dist) <= near || crossed) {
                Store.setArmed(l.id, false)
                val kind = if (l.support) "Support" else "Resistance"
                val verb = if (crossed && abs(dist) > near) "crossed" else "reached"
                val side = if (dist >= 0) "above" else "below"
                Alarm.trigger(
                    ctx,
                    Alarm.Trig(
                        l.id,
                        "$kind $verb: ${l.name}",
                        "Price ${f5(price)} • line ${f5(lp)} • ${pipsStr(dist)} pips ($side)",
                        l.sound
                    )
                )
            }
        }
    }
}
