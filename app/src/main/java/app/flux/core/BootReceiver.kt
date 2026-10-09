package app.flux.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.flux.FluxApp
import kotlinx.coroutines.launch

/**
 * After a reboot (or an app update) alarms are gone, so: settle the persisted timer against the new
 * boot, re-arm its alarm, and bring the overlay back if the user had it on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = (context.applicationContext as FluxApp).graph
        val pending = goAsync()
        graph.appScope.launch {
            try {
                graph.log("boot/update: restoring timers and overlay")
                graph.timers.settle()
                graph.ensureOverlayRunningIfEnabled(requireStartOnBoot = true)
            } finally {
                pending.finish()
            }
        }
    }
}
