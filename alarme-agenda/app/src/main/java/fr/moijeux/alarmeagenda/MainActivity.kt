package fr.moijeux.alarmeagenda

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var events: TextView
    private lateinit var leadBtn: Button
    private val leads = intArrayOf(0, 1, 2, 5, 10, 15, 30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p * 2, p, p)
        }
        fun text(t: String, size: Float = 16f) = TextView(this).apply { text = t; textSize = size }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }

        col.addView(text("⏰ Alarme Agenda", 26f))
        col.addView(text("Sonne à chaque début d'événement de ton agenda Google.").apply { setPadding(0, 8, 0, 16) })

        col.addView(Switch(this).apply {
            text = "Sonneries activées"
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, on -> prefs.enabled = on; Scheduler.sync(this@MainActivity); refresh() }
        })

        leadBtn = button("") {
            val i = leads.indexOf(prefs.leadMin)
            prefs.leadMin = leads[(i + 1) % leads.size]
            Scheduler.sync(this); refresh()
        }
        col.addView(leadBtn)

        col.addView(CheckBox(this).apply {
            text = "Ignorer les événements « toute la journée »"
            isChecked = prefs.skipAllDay
            setOnCheckedChangeListener { _, on -> prefs.skipAllDay = on; Scheduler.sync(this@MainActivity); refresh() }
        })

        status = text("").apply { setPadding(0, 24, 0, 8) }
        col.addView(status)

        col.addView(button("1. Autoriser l'agenda et les notifications") {
            val perms = mutableListOf(Manifest.permission.READ_CALENDAR)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(perms.toTypedArray(), 1)
        })
        col.addView(button("2. Autoriser les alarmes exactes") {
            if (Build.VERSION.SDK_INT >= 31)
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        })
        col.addView(button("3. Ne pas restreindre la batterie") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        col.addView(button("🔔 Tester la sonnerie") {
            AlarmReceiver.ring(this, 4242, "Test de sonnerie", System.currentTimeMillis(), "Ça marche !")
        })

        col.addView(text("Prochains événements (48 h)", 18f).apply { setPadding(0, 32, 0, 8) })
        events = text("")
        col.addView(events)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        Scheduler.sync(this)
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Scheduler.sync(this)
        refresh()
    }

    private fun refresh() {
        leadBtn.text = if (prefs.leadMin == 0) "Sonner : pile à l'heure (toucher pour changer)"
        else "Sonner ${prefs.leadMin} min avant (toucher pour changer)"

        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        status.text = "${mark(Scheduler.hasCalendar(this))} Accès à l'agenda\n" +
            "${mark(notif)} Notifications\n" +
            "${mark(Scheduler.canExact(this))} Alarmes exactes\n" +
            "${mark(battery)} Batterie non restreinte (recommandé)\n" +
            "🔔 ${prefs.scheduled.size} sonnerie(s) programmée(s)"

        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("EEE dd/MM HH:mm", Locale.FRANCE)
        val list = Scheduler.upcoming(this, now, now + Scheduler.HORIZON_MS, prefs.skipAllDay).take(20)
        events.text = if (!Scheduler.hasCalendar(this)) "Autorise l'accès à l'agenda."
        else if (list.isEmpty()) "Aucun événement dans les 48 prochaines heures."
        else list.joinToString("\n") { "${fmt.format(Date(it.begin))} — ${it.title}" }
    }
}
