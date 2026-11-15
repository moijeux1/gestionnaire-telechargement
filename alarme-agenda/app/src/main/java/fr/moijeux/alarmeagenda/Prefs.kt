package fr.moijeux.alarmeagenda

import android.content.Context

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) { p.edit().putBoolean("enabled", v).apply() }

    /** Minutes de préavis avant le début (0 = pile à l'heure). */
    var leadMin: Int
        get() = p.getInt("leadMin", 0)
        set(v) { p.edit().putInt("leadMin", v).apply() }

    var skipAllDay: Boolean
        get() = p.getBoolean("skipAllDay", true)
        set(v) { p.edit().putBoolean("skipAllDay", v).apply() }

    var snoozeMin: Int
        get() = p.getInt("snoozeMin", 5)
        set(v) { p.edit().putInt("snoozeMin", v).apply() }

    /** Codes des alarmes actuellement programmées (pour pouvoir les annuler à la resynchro). */
    var scheduled: Set<String>
        get() = p.getStringSet("scheduled", emptySet()) ?: emptySet()
        set(v) { p.edit().putStringSet("scheduled", v).apply() }
}
