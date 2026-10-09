package app.galaxypulse

import android.app.Application
import android.os.SystemClock
import android.provider.Settings
import app.galaxypulse.artwork.ArtworkProcessor
import app.galaxypulse.battery.BatteryRepository
import app.galaxypulse.bluetooth.BluetoothRepository
import app.galaxypulse.core.DiagnosticsLog
import app.galaxypulse.engine.PulseEngine
import app.galaxypulse.media.MediaRepository
import app.galaxypulse.notifications.NotificationRepository
import app.galaxypulse.overlay.OverlayServiceControl
import app.galaxypulse.settings.PulseSettings
import app.galaxypulse.settings.SettingsRepository
import app.galaxypulse.timers.TimerNotifier
import app.galaxypulse.timers.TimerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.atomic.AtomicLong

/**
 * Hand-wired dependency container (no DI framework: there are ~10 long-lived objects and one process).
 * Created lazily by [PulseApp] the first time anything needs it — the notification listener, a
 * receiver, the service or the UI — so a cold alarm broadcast pays only for what it touches.
 */
class AppGraph(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val diagnostics = DiagnosticsLog()
    fun log(message: String) = diagnostics.log(message)

    /** One monotonic counter for every "genuinely new event" revision in the scheduler. */
    private val revisions = AtomicLong(0)
    fun nextRevision(): Long = revisions.incrementAndGet()

    val settingsRepo = SettingsRepository(app)
    val settings: StateFlow<PulseSettings> =
        settingsRepo.settings.stateIn(appScope, SharingStarted.Eagerly, PulseSettings())

    val artwork = ArtworkProcessor(app)
    val media = MediaRepository(app, artwork, mainScope) { settings.value.ignoredMediaApps }
    val notifications = NotificationRepository(app, { settings.value }, ::nextRevision, SystemClock::elapsedRealtime, ::log)
    val battery = BatteryRepository(app) { settings.value.lowBatteryThresholdPercent }
    val bluetooth = BluetoothRepository(app, { settings.value }, ::nextRevision, SystemClock::elapsedRealtime, mainScope, ::log)
    val timers = TimerRepository(app, appScope)
    val notifier = TimerNotifier(app)
    val engine = PulseEngine(this)

    /** True while the overlay service is alive. Shown on the home screen and in Diagnostics. */
    val serviceRunning = MutableStateFlow(false)

    init {
        notifier.ensureChannel()
        timers.onTimerFinished = { state ->
            log("timer finished")
            notifier.showFinished(state.totalMs)
        }
    }

    /**
     * Starts the overlay service if the user has it switched on and the permission is still granted.
     * Waits for the real stored settings instead of trusting the not-yet-loaded default.
     */
    suspend fun ensureOverlayRunningIfEnabled(requireStartOnBoot: Boolean = false) {
        val stored = settingsRepo.settings.first()
        val wanted = stored.overlayEnabled && (!requireStartOnBoot || stored.startOnBoot)
        if (wanted && Settings.canDrawOverlays(app)) {
            OverlayServiceControl.start(app)
        } else if (wanted) {
            log("overlay is enabled but the 'display over other apps' permission is missing")
        }
    }
}
