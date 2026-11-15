package fr.moijeux.alarmeagenda

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reprogramme les sonneries après un redémarrage, une mise à jour ou un changement d'heure/fuseau. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.sync(ctx)
    }
}
