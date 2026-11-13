package com.saman.eurusd

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class PriceService : Service() {
    private var exec: ScheduledExecutorService? = null
    private var wl: PowerManager.WakeLock? = null

    companion object {
        const val NOTIF_ID = 1

        fun start(c: Context) {
            try {
                c.startForegroundService(Intent(c, PriceService::class.java))
            } catch (e: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Alarm.ensureChannels(this)
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (!Store.monitoring) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (exec == null) startWork()
        Watchdog.schedule(this)
        return START_STICKY
    }

    private fun startWork() {
        val pm = getSystemService(PowerManager::class.java)
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eurusd:monitor").apply {
            setReferenceCounted(false)
            acquire()
        }
        val e = Executors.newScheduledThreadPool(4)
        val ctx = applicationContext
        e.scheduleWithFixedDelay({ safe { Engine.pollBinance() } }, 0, 1000, TimeUnit.MILLISECONDS)
        e.scheduleWithFixedDelay({ safe { Engine.pollYahoo() } }, 0, 4000, TimeUnit.MILLISECONDS)
        e.scheduleWithFixedDelay({ safe { Evaluator.run(ctx) } }, 1000, 500, TimeUnit.MILLISECONDS)
        e.scheduleWithFixedDelay({ safe { updateNotif() } }, 2000, 3000, TimeUnit.MILLISECONDS)
        exec = e
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
        }
    }

    private fun buildNotif(): Notification {
        val live = Engine.live
        val txt = if (live.isNaN()) "Waiting for price…"
        else "EURUSD ${f5(live + Store.offsetPips * PIP)}  •  ${Engine.source}"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, Alarm.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("EURUSD monitoring active")
            .setContentText(txt)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotif() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Store.monitoring) Watchdog.schedule(this, 2000)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        exec?.shutdownNow()
        exec = null
        try { wl?.release() } catch (_: Exception) { }
        wl = null
        super.onDestroy()
    }
}

object Watchdog {
    fun schedule(c: Context, delayMs: Long = 5 * 60_000L) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            c, 77, Intent(c, WatchdogReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi)
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Store.monitoring) {
            PriceService.start(context)
            Watchdog.schedule(context)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Store.monitoring) {
            PriceService.start(context)
            Watchdog.schedule(context)
        }
    }
}
