package fr.moijeux.alarmeagenda

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract

data class Ev(
    val code: Int,
    val title: String,
    val begin: Long,
    val location: String?,
    val allDay: Boolean,
)

object Scheduler {
    const val ACTION_RING = "fr.moijeux.alarmeagenda.RING"
    const val ACTION_STOP = "fr.moijeux.alarmeagenda.STOP"
    const val ACTION_SNOOZE = "fr.moijeux.alarmeagenda.SNOOZE"
    const val ACTION_SYNC = "fr.moijeux.alarmeagenda.SYNC"

    const val HORIZON_MS = 48L * 3600_000
    const val RESYNC_MS = 6L * 3600_000

    fun hasCalendar(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    /** Lit les occurrences d'événements (récurrences incluses) de l'agenda synchronisé sur le téléphone. */
    fun upcoming(ctx: Context, from: Long, to: Long, skipAllDay: Boolean): List<Ev> {
        if (!hasCalendar(ctx)) return emptyList()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, from)
        ContentUris.appendId(b, to)
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.STATUS,
        )
        val out = ArrayList<Ev>()
        ctx.contentResolver.query(
            b.build(), proj, "${CalendarContract.Instances.VISIBLE} = 1", null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(sans titre)"
                val begin = c.getLong(2)
                val allDay = c.getInt(3) == 1
                val loc = c.getString(4)
                val self = c.getInt(5)
                val status = c.getInt(6)
                if (allDay && skipAllDay) continue
                if (self == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                if (status == CalendarContract.Events.STATUS_CANCELED) continue
                out += Ev("${id}_$begin".hashCode(), title, begin, loc, allDay)
            }
        }
        return out
    }

    private fun ringData(code: Int) = Uri.parse("alarme://ring/$code")

    private fun ringPending(ctx: Context, code: Int, title: String, begin: Long, loc: String?) =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, AlarmReceiver::class.java)
                .setAction(ACTION_RING).setData(ringData(code))
                .putExtra("code", code).putExtra("title", title)
                .putExtra("begin", begin).putExtra("loc", loc),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun showApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Programme une sonnerie à l'heure donnée (setAlarmClock : la plus fiable, traverse Doze). */
    fun scheduleRing(ctx: Context, at: Long, code: Int, title: String, begin: Long, loc: String?) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setAlarmClock(
            AlarmManager.AlarmClockInfo(at, showApp(ctx)),
            ringPending(ctx, code, title, begin, loc),
        )
    }

    fun snooze(ctx: Context, code: Int, title: String, begin: Long, loc: String?) {
        val newCode = code * 31 + 7
        scheduleRing(ctx, System.currentTimeMillis() + Prefs(ctx).snoozeMin * 60_000L, newCode, title, begin, loc)
    }

    /** Annule les anciennes alarmes, relit l'agenda et reprogramme tout sur les 48 prochaines heures. */
    fun sync(ctx: Context) {
        val prefs = Prefs(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)

        for (s in prefs.scheduled) {
            val code = s.toIntOrNull() ?: continue
            PendingIntent.getBroadcast(
                ctx, code,
                Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_RING).setData(ringData(code)),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { am.cancel(it); it.cancel() }
        }
        prefs.scheduled = emptySet()

        if (!prefs.enabled || !hasCalendar(ctx) || !canExact(ctx)) return

        val now = System.currentTimeMillis()
        val lead = prefs.leadMin * 60_000L
        val done = HashSet<String>()
        for (e in upcoming(ctx, now, now + HORIZON_MS, prefs.skipAllDay)) {
            val at = e.begin - lead
            if (at <= now) continue
            scheduleRing(ctx, at, e.code, e.title, e.begin, e.location)
            done += e.code.toString()
        }
        prefs.scheduled = done

        // Filet de sécurité : resynchronisation périodique (agenda modifié, événements ajoutés…).
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, now + RESYNC_MS,
            PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_SYNC),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }
}
