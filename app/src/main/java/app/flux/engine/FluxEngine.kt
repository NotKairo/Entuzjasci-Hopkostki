package app.flux.engine

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import app.flux.AppGraph
import app.flux.artwork.ArtworkBundle
import app.flux.battery.BatteryAlertPayload
import app.flux.battery.BatteryEvent
import app.flux.battery.ChargingEventType
import app.flux.battery.ChargingPayload
import app.flux.bluetooth.BluetoothPayload
import app.flux.bluetooth.BluetoothRepository
import app.flux.bluetooth.ConnectionPhase
import app.flux.media.MediaSnapshot
import app.flux.notifications.NotificationRepository
import app.flux.settings.FluxSettings
import app.flux.timers.StopwatchPayload
import app.flux.timers.StopwatchPhase
import app.flux.timers.StopwatchState
import app.flux.timers.TimerDonePayload
import app.flux.timers.TimerPayload
import app.flux.timers.TimerPhase
import app.flux.timers.TimerState
import app.flux.ui.overlay.BluetoothCardModel
import app.flux.ui.overlay.BluetoothCommand
import app.flux.ui.overlay.MediaCommand
import app.flux.ui.overlay.OverlayActions
import app.flux.ui.overlay.StopwatchCommand
import app.flux.ui.overlay.TimerCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Glue between the data sources and the pure [ActivityScheduler]. Every source is translated into
 * scheduler events here, with the user's settings applied; the UI only ever sees [state].
 *
 * Runs on the main thread while the overlay service is alive. When it is stopped nothing is
 * collected, no receiver is registered and no timer is pending: an idle Flux costs nothing.
 */
class FluxEngine(private val graph: AppGraph) {

    private val settings: FluxSettings get() = graph.settings.value
    private fun now() = SystemClock.elapsedRealtime()

    private val _state = MutableStateFlow(SchedulerState())
    val state: StateFlow<SchedulerState> = _state

    private val _bluetoothCard = MutableStateFlow<BluetoothCardModel?>(null)
    val bluetoothCard: StateFlow<BluetoothCardModel?> = _bluetoothCard

    private val _dragging = MutableStateFlow(false)
    /** True while the user drags the pill; the window host widens the window for the travel. */
    val dragging: StateFlow<Boolean> = _dragging

    private val _artwork = MutableStateFlow<ArtworkBundle?>(null)
    /** Cover for the media entry: a simulated one during tests, otherwise the real session's. */
    val artwork: StateFlow<ArtworkBundle?> = _artwork

    private var scope: CoroutineScope? = null
    private var wakeJob: Job? = null
    private var cardJob: Job? = null

    val isRunning: Boolean get() = scope != null

    private val mediaTracker = MediaEntryTracker(graph::nextRevision)
    private var realMedia: MediaSnapshot? = null
    private var simulatedMedia: MediaSnapshot? = null
    private var simulatedArtwork: ArtworkBundle? = null
    private var lastTimer: TimerState? = null
    private var timerRevision = 0L
    private var lastStopwatch: StopwatchState? = null
    private var stopwatchRevision = 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────────────────────────

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        graph.log("engine started")

        s.launch {
            graph.settings.collect { st ->
                dispatch(SchedulerEvent.Rerank(st.priorityConfig()))
                refreshMedia()
                lastTimer?.let(::onTimer)
                lastStopwatch?.let(::onStopwatch)
                if (!st.enableBluetooth) hideCard()
            }
        }
        s.launch { graph.media.snapshot.collect { realMedia = it; refreshMedia() } }
        s.launch { graph.media.artworkBundle.collect { if (simulatedMedia == null) _artwork.value = it } }
        s.launch { graph.notifications.events.collect(::onNotificationEvent) }
        s.launch { graph.battery.events.collect(::onBatteryEvent) }
        s.launch { graph.bluetooth.events.collect(::onBluetoothEvent) }
        s.launch { graph.timers.timer.collect(::onTimer) }
        s.launch { graph.timers.stopwatch.collect(::onStopwatch) }

        graph.battery.start()
        graph.bluetooth.start()
        s.launch { graph.timers.settle() }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        wakeJob = null
        cardJob = null
        graph.battery.stop()
        graph.bluetooth.stop()
        mediaTracker.reset()
        _state.value = SchedulerState()
        _bluetoothCard.value = null
        simulatedMedia = null
        simulatedArtwork = null
        graph.log("engine stopped")
    }

    // ── Scheduler plumbing ────────────────────────────────────────────────────────────────────

    private fun dispatch(event: SchedulerEvent) {
        val next = ActivityScheduler.reduce(_state.value, event, now())
        if (next != _state.value) _state.value = next
        scheduleWake()
    }

    private fun scheduleWake() {
        wakeJob?.cancel()
        val at = ActivityScheduler.nextWakeup(_state.value) ?: return
        wakeJob = scope?.launch {
            delay((at - now()).coerceAtLeast(0L))
            dispatch(SchedulerEvent.Tick)
        }
    }

    private fun entry(
        key: String,
        kind: ActivityKind,
        payload: ActivityPayload,
        revision: Long,
        displayMs: Long = 0L,
        waitMs: Long? = null,
        expiresAt: Long? = null,
    ): ActivityEntry = ActivityEntry(
        key = key,
        kind = kind,
        payload = payload,
        rank = settings.priorityConfig().rankOf(kind),
        revision = revision,
        postedAt = now(),
        displayMs = displayMs,
        staleAt = waitMs?.let { now() + it },
        expiresAt = expiresAt,
    )

    // ── Media ─────────────────────────────────────────────────────────────────────────────────

    private fun refreshMedia() {
        val st = settings
        val snapshot = simulatedMedia ?: realMedia
        if (snapshot == null || !st.enableMedia || snapshot.packageName in st.ignoredMediaApps) {
            mediaTracker.reset()
            dispatch(SchedulerEvent.Remove(MEDIA_KEY))
            return
        }
        val decision = mediaTracker.update(snapshot, now(), st.pausedHideDelaySec * 1000L)
        dispatch(SchedulerEvent.Post(entry(MEDIA_KEY, ActivityKind.Media, snapshot, decision.revision, expiresAt = decision.expiresAt)))
    }

    // ── Notifications & calls ─────────────────────────────────────────────────────────────────

    private fun onNotificationEvent(event: NotificationRepository.Event) {
        when (event) {
            is NotificationRepository.Event.Posted -> {
                val p = event.payload
                if (p.isIncomingCall) {
                    // A ringing call stays until its notification goes away (with a safety expiry).
                    dispatch(SchedulerEvent.Post(entry("call:${p.sbnKey}", ActivityKind.Call, p, event.revision, expiresAt = now() + CALL_SAFETY_MS)))
                } else if (settings.enableNotifications) {
                    dispatch(
                        SchedulerEvent.Post(
                            entry("notif:${p.sbnKey}", ActivityKind.Notification, p, event.revision, settings.notificationSec * 1000L, waitMs = NOTIFICATION_WAIT_MS),
                        ),
                    )
                }
            }
            is NotificationRepository.Event.Removed -> {
                dispatch(SchedulerEvent.Remove("call:${event.sbnKey}"))
                dispatch(SchedulerEvent.Remove("notif:${event.sbnKey}"))
            }
        }
    }

    // ── Battery ───────────────────────────────────────────────────────────────────────────────

    private fun onBatteryEvent(event: BatteryEvent) {
        val st = settings
        when (event) {
            is BatteryEvent.ChargingStarted -> if (st.enableCharging) postCharging(ChargingEventType.Started, event.reading.levelPercent, event.reading.plug, event.reading.temperatureC, withEta = true)
            is BatteryEvent.ChargingStopped -> if (st.enableCharging) postCharging(ChargingEventType.Disconnected, event.reading.levelPercent, null, null, withEta = false)
            is BatteryEvent.BecameFull -> if (st.enableCharging) postCharging(ChargingEventType.Full, 100, event.reading.plug, event.reading.temperatureC, withEta = false)
            is BatteryEvent.Low -> if (st.enableLowBattery) {
                dispatch(
                    SchedulerEvent.Post(
                        entry(
                            "battery-alert", ActivityKind.BatteryAlert, BatteryAlertPayload(event.levelPercent, event.critical),
                            graph.nextRevision(), displayMs = st.batteryAlertSec * 1000L * (if (event.critical) 2 else 1), waitMs = 20_000L,
                        ),
                    ),
                )
            }
        }
    }

    private fun postCharging(type: ChargingEventType, level: Int, plug: app.flux.battery.PlugType?, temp: Float?, withEta: Boolean) {
        val payload = ChargingPayload(type, level, plug, if (withEta) graph.battery.timeToFullMs() else null, temp)
        dispatch(SchedulerEvent.Post(entry("charging", ActivityKind.Charging, payload, graph.nextRevision(), settings.chargingSec * 1000L, waitMs = 8_000L)))
    }

    // ── Bluetooth ─────────────────────────────────────────────────────────────────────────────

    private fun onBluetoothEvent(event: BluetoothRepository.Event) {
        val st = settings
        if (!st.enableBluetooth) return
        when (event) {
            is BluetoothRepository.Event.Connected -> {
                val p = event.payload
                dispatch(SchedulerEvent.Post(entry("bt:${p.address}", ActivityKind.Bluetooth, p, event.revision, st.bluetoothSec * 1000L, waitMs = 10_000L)))
                if (st.bluetoothBottomCard) showCard(p, event.revision)
            }
            is BluetoothRepository.Event.Progress -> {
                val p = event.payload
                val current = _bluetoothCard.value
                // A battery update (phase Connected) only refreshes an already visible card/pill.
                if (p.phase == ConnectionPhase.Connected) {
                    if (current?.payload?.address == p.address) showCard(p, current.serial, keepTimer = true)
                    updateExistingBluetoothEntry(p)
                } else {
                    showCard(p, event.revision)
                }
            }
            is BluetoothRepository.Event.Disconnected -> Unit
        }
    }

    private fun updateExistingBluetoothEntry(p: BluetoothPayload) {
        val existing = _state.value.entries["bt:${p.address}"] ?: return
        dispatch(SchedulerEvent.Post(existing.copy(payload = p)))
    }

    private fun showCard(payload: BluetoothPayload, serial: Long, keepTimer: Boolean = false) {
        _bluetoothCard.value = BluetoothCardModel(payload, serial)
        if (keepTimer && cardJob?.isActive == true) return
        cardJob?.cancel()
        val hideAfter = when (payload.phase) {
            ConnectionPhase.Connected -> settings.bluetoothSec * 1000L
            ConnectionPhase.Failed, ConnectionPhase.PairedNotConnected -> 12_000L
            else -> 90_000L
        }
        cardJob = scope?.launch {
            delay(hideAfter)
            _bluetoothCard.value = null
        }
    }

    private fun hideCard() {
        cardJob?.cancel()
        _bluetoothCard.value = null
    }

    // ── Timer & stopwatch ─────────────────────────────────────────────────────────────────────

    private fun onTimer(state: TimerState) {
        val previous = lastTimer
        lastTimer = state
        if (previous == null || previous.phase != state.phase || previous.totalMs != state.totalMs) timerRevision = graph.nextRevision()
        val enabled = settings.enableTimers
        when (state.phase) {
            TimerPhase.Idle -> {
                dispatch(SchedulerEvent.Remove(TIMER_KEY))
                dispatch(SchedulerEvent.Remove(TIMER_DONE_KEY))
            }
            TimerPhase.Running, TimerPhase.Paused -> {
                dispatch(SchedulerEvent.Remove(TIMER_DONE_KEY))
                if (enabled) dispatch(SchedulerEvent.Post(entry(TIMER_KEY, ActivityKind.Timer, TimerPayload(state, graph.timers.bootCount), timerRevision)))
                else dispatch(SchedulerEvent.Remove(TIMER_KEY))
            }
            TimerPhase.Finished -> {
                dispatch(SchedulerEvent.Remove(TIMER_KEY))
                // A finished timer is critical: shown even if the timer pill itself is switched off.
                dispatch(SchedulerEvent.Post(entry(TIMER_DONE_KEY, ActivityKind.TimerDone, TimerDonePayload(state.totalMs), timerRevision)))
            }
        }
    }

    private fun onStopwatch(state: StopwatchState) {
        val previous = lastStopwatch
        lastStopwatch = state
        if (previous == null || previous.phase != state.phase || previous.lapTotalsMs.size != state.lapTotalsMs.size) {
            stopwatchRevision = graph.nextRevision()
        }
        if (state.phase == StopwatchPhase.Idle || !settings.enableTimers) {
            dispatch(SchedulerEvent.Remove(STOPWATCH_KEY))
        } else {
            dispatch(SchedulerEvent.Post(entry(STOPWATCH_KEY, ActivityKind.Stopwatch, StopwatchPayload(state, graph.timers.bootCount), stopwatchRevision)))
        }
    }

    // ── What the overlay can ask for ──────────────────────────────────────────────────────────

    val actions: OverlayActions = object : OverlayActions {
        override fun setExpanded(key: String, expanded: Boolean) = dispatch(SchedulerEvent.SetExpanded(key, expanded))

        override fun dismiss(key: String) {
            if (key.startsWith("bt:")) hideCard()
            dispatch(SchedulerEvent.Dismiss(key))
        }

        override fun setDragging(active: Boolean) {
            _dragging.value = active
        }

        override fun collapse() {
            val front = _state.value.frontKey ?: return
            dispatch(SchedulerEvent.SetExpanded(front, false))
        }

        override fun media(command: MediaCommand) {
            if (simulatedMedia != null) simulateMediaCommand(command) else graph.media.send(command)
        }

        override fun timer(command: TimerCommand) {
            graph.appScope.launch {
                when (command) {
                    TimerCommand.Pause -> graph.timers.pauseTimer()
                    TimerCommand.Resume -> graph.timers.resumeTimer()
                    TimerCommand.Cancel -> graph.timers.cancelTimer()
                    is TimerCommand.Add -> graph.timers.addTimerTime(command.millis)
                    TimerCommand.DismissAlarm -> {
                        graph.notifier.cancelFinished()
                        graph.timers.dismissTimerAlarm()
                    }
                }
            }
        }

        override fun stopwatch(command: StopwatchCommand) {
            graph.appScope.launch {
                when (command) {
                    StopwatchCommand.Start -> graph.timers.startStopwatch()
                    StopwatchCommand.Pause -> graph.timers.pauseStopwatch()
                    StopwatchCommand.Lap -> graph.timers.lapStopwatch()
                    StopwatchCommand.Reset -> graph.timers.resetStopwatch()
                }
            }
        }

        override fun openNotification(sbnKey: String) {
            graph.notifications.open(sbnKey)
            dispatch(SchedulerEvent.Dismiss("notif:$sbnKey"))
        }

        override fun notificationAction(sbnKey: String, index: Int) {
            graph.notifications.performAction(sbnKey, index)
            dispatch(SchedulerEvent.Dismiss("notif:$sbnKey"))
            dispatch(SchedulerEvent.Dismiss("call:$sbnKey"))
        }

        override fun bluetooth(command: BluetoothCommand) {
            when (command) {
                is BluetoothCommand.Connect ->
                    if (command.address == Simulator.SIM_ADDRESS) Simulator.simulateConnect(this@FluxEngine, graph) else graph.bluetooth.connect(command.address)
                BluetoothCommand.OpenSystemSettings -> {
                    graph.app.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    hideCard()
                }
                BluetoothCommand.DismissCard -> hideCard()
            }
        }
    }

    // ── Simulation (Diagnostics test panel) ───────────────────────────────────────────────────

    fun simulate(event: SimulatedEvent) {
        if (scope == null) {
            graph.log("simulate ignored: overlay service is not running")
            return
        }
        graph.log("simulate: ${event.label}")
        Simulator.run(event, this, graph)
    }

    internal fun postSimulated(entry: ActivityEntry) = dispatch(SchedulerEvent.Post(entry))

    internal fun simulatedEntry(
        key: String, kind: ActivityKind, payload: ActivityPayload, displayMs: Long = 0L,
        expiresAt: Long? = null,
    ) = entry(key, kind, payload, graph.nextRevision(), displayMs, waitMs = 10_000L, expiresAt = expiresAt)

    internal fun showSimulatedCard(payload: BluetoothPayload) = showCard(payload, graph.nextRevision())

    internal fun setSimulatedMedia(snapshot: MediaSnapshot?, artwork: ArtworkBundle?) {
        simulatedMedia = snapshot
        simulatedArtwork = artwork
        _artwork.value = if (snapshot != null) artwork else graph.media.artworkBundle.value
        refreshMedia()
    }

    internal val currentSimulatedMedia: MediaSnapshot? get() = simulatedMedia
    internal val currentSimulatedArtwork: ArtworkBundle? get() = simulatedArtwork

    private fun simulateMediaCommand(command: MediaCommand) = Simulator.mediaCommand(command, this, graph)

    internal fun mediaClock() = now()

    private companion object {
        const val MEDIA_KEY = "media"
        const val TIMER_KEY = "timer"
        const val TIMER_DONE_KEY = "timer-done"
        const val STOPWATCH_KEY = "stopwatch"
        const val CALL_SAFETY_MS = 90_000L
        const val NOTIFICATION_WAIT_MS = 15_000L
    }
}
