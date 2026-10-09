package app.flux.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.flux.FluxApp
import app.flux.engine.SimulatedEvent
import kotlinx.coroutines.launch

/**
 * Debug-only remote control used by the emulator CI job (see .github/scripts/emulator-run.sh):
 *
 *   adb shell am broadcast -n app.flux/.debug.DebugCommandReceiver --es cmd enable
 *   adb shell am broadcast -n app.flux/.debug.DebugCommandReceiver --es cmd event --es name MusicPlaying
 *   adb shell am broadcast -n app.flux/.debug.DebugCommandReceiver --es cmd expand
 *   adb shell am broadcast -n app.flux/.debug.DebugCommandReceiver --es cmd collapse
 *
 * Events go through the same engine and scheduler as the Diagnostics screen's test buttons.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as FluxApp).graph
        val cmd = intent.getStringExtra("cmd")
        Log.i(TAG, "command: $cmd ${intent.getStringExtra("name").orEmpty()}")
        when (cmd) {
            "enable" -> graph.appScope.launch {
                graph.settingsRepo.update { it.copy(overlayEnabled = true) }
                graph.ensureOverlayRunningIfEnabled()
            }
            "event" -> {
                val name = intent.getStringExtra("name") ?: return
                val event = SimulatedEvent.entries.firstOrNull { it.name == name } ?: run {
                    Log.w(TAG, "unknown event $name; known: ${SimulatedEvent.entries.joinToString { it.name }}")
                    return
                }
                graph.mainScope.launch { graph.engine.simulate(event) }
            }
            "expand", "collapse" -> graph.mainScope.launch {
                val key = graph.engine.state.value.frontKey ?: return@launch
                graph.engine.actions.setExpanded(key, cmd == "expand")
            }
            else -> Log.w(TAG, "unknown command $cmd")
        }
    }

    private companion object {
        const val TAG = "FluxDebug"
    }
}
