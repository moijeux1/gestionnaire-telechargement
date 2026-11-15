package fr.moijeux.alarmeagenda

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.graphics.drawable.Icon
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val code = intent.getIntExtra("code", 0)
        when (intent.action) {
            Scheduler.ACTION_RING -> {
                ring(
                    ctx, code,
                    intent.getStringExtra("title") ?: "Agenda",
                    intent.getLongExtra("begin", System.currentTimeMillis()),
                    intent.getStringExtra("loc"),
                )
                Scheduler.sync(ctx)
            }
            Scheduler.ACTION_STOP -> nm.cancel(code)
            Scheduler.ACTION_SNOOZE -> {
                nm.cancel(code)
                Scheduler.snooze(
                    ctx, code,
                    intent.getStringExtra("title") ?: "Agenda",
                    intent.getLongExtra("begin", System.currentTimeMillis()),
                    intent.getStringExtra("loc"),
                )
            }
            Scheduler.ACTION_SYNC -> Scheduler.sync(ctx)
        }
    }

    companion object {
        private const val CHANNEL = "sonnerie_v1"

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) != null) return
            val ch = NotificationChannel(CHANNEL, "Sonnerie d'agenda", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }

        private fun action(ctx: Context, act: String, code: Int, title: String, begin: Long, loc: String?) =
            PendingIntent.getBroadcast(
                ctx, code,
                Intent(ctx, AlarmReceiver::class.java).setAction(act)
                    .setData(Uri.parse("alarme://${act.substringAfterLast('.')}/$code"))
                    .putExtra("code", code).putExtra("title", title)
                    .putExtra("begin", begin).putExtra("loc", loc),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** Affiche la notification-alarme : son d'alarme en boucle (volume « alarme »), vibration, Arrêter / Reporter. */
        fun ring(ctx: Context, code: Int, title: String, begin: Long, loc: String?) {
            ensureChannel(ctx)
            val hour = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(begin))
            val text = "Commence à $hour" + if (!loc.isNullOrBlank()) " — $loc" else ""
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val snooze = Prefs(ctx).snoozeMin
            val icon = Icon.createWithResource(ctx, android.R.drawable.ic_lock_idle_alarm)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .setTimeoutAfter(120_000)
                .addAction(
                    Notification.Action.Builder(icon, "Arrêter",
                        action(ctx, Scheduler.ACTION_STOP, code, title, begin, loc)).build(),
                )
                .addAction(
                    Notification.Action.Builder(icon, "Reporter $snooze min",
                        action(ctx, Scheduler.ACTION_SNOOZE, code, title, begin, loc)).build(),
                )
                .build()
            n.flags = n.flags or Notification.FLAG_INSISTENT // le son se répète jusqu'à l'arrêt (max 2 min)
            ctx.getSystemService(NotificationManager::class.java).notify(code, n)
        }
    }
}
