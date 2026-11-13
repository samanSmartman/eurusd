package com.saman.eurusd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator

object Alarm {
    const val CH_ALARM = "alarm"
    const val CH_SERVICE = "service"

    class Trig(val lineId: String, val title: String, val text: String, val sound: String)

    private val pending = LinkedHashMap<String, Trig>()
    private var player: MediaPlayer? = null

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Monitoring", NotificationManager.IMPORTANCE_LOW)
        )
        val ch = NotificationChannel(CH_ALARM, "Line alarms", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    @Synchronized
    fun trigger(ctx: Context, t: Trig) {
        if (pending.containsKey(t.lineId)) return
        val first = pending.isEmpty()
        pending[t.lineId] = t
        if (first) {
            startSound(ctx, t.sound)
            startVibration(ctx)
        }
        notify(ctx, t)
        try {
            ctx.startActivity(
                Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
        }
    }

    @Synchronized fun current(): Trig? = pending.values.firstOrNull()

    @Synchronized
    fun resolve(ctx: Context, lineId: String, disable: Boolean) {
        pending.remove(lineId)
        ctx.getSystemService(NotificationManager::class.java).cancel(notifId(lineId))
        if (disable) Store.setEnabled(lineId, false)
        stopSound()
        val next = pending.values.firstOrNull()
        if (next != null) startSound(ctx, next.sound) else stopVibration(ctx)
    }

    private fun notifId(id: String) = 1000 + (id.hashCode() and 0xFFFF)

    private fun startSound(ctx: Context, sound: String) {
        stopSound()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        fun make(uri: Uri): MediaPlayer? {
            val m = MediaPlayer()
            return try {
                m.setAudioAttributes(attrs)
                m.setDataSource(ctx, uri)
                m.isLooping = true
                m.prepare()
                m.start()
                m
            } catch (e: Exception) {
                try { m.release() } catch (_: Exception) { }
                null
            }
        }

        var mp: MediaPlayer? = null
        if (sound.isNotEmpty()) mp = make(Uri.parse(sound))
        if (mp == null) mp = make(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        if (mp == null) mp = make(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        player = mp
    }

    private fun stopSound() {
        player?.let {
            try { it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        player = null
    }

    @Suppress("DEPRECATION")
    private fun startVibration(ctx: Context) {
        try {
            val v = ctx.getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
        } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun stopVibration(ctx: Context) {
        try { ctx.getSystemService(Vibrator::class.java).cancel() } catch (_: Exception) { }
    }

    private fun actionPi(ctx: Context, id: String, disable: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmActionReceiver::class.java).putExtra("id", id).putExtra("disable", disable)
        return PendingIntent.getBroadcast(
            ctx, notifId(id) * 2 + (if (disable) 1 else 0), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun notify(ctx: Context, t: Trig) {
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = Icon.createWithResource(ctx, R.drawable.ic_launcher)
        val n = Notification.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(t.title)
            .setContentText(t.text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(Notification.Action.Builder(icon, "Stop & keep", actionPi(ctx, t.lineId, false)).build())
            .addAction(Notification.Action.Builder(icon, "Stop & disable", actionPi(ctx, t.lineId, true)).build())
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(notifId(t.lineId), n)
    }
}

class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        Alarm.resolve(context, id, intent.getBooleanExtra("disable", false))
    }
}
