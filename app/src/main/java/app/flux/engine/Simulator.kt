package app.flux.engine

import app.flux.AppGraph
import app.flux.artwork.SampleCover
import app.flux.battery.BatteryAlertPayload
import app.flux.battery.ChargingEventType
import app.flux.battery.ChargingPayload
import app.flux.battery.PlugType
import app.flux.bluetooth.BluetoothPayload
import app.flux.bluetooth.ConnectionPhase
import app.flux.bluetooth.DeviceBattery
import app.flux.bluetooth.DeviceKind
import app.flux.media.MediaCustomAction
import app.flux.media.MediaSnapshot
import app.flux.media.PlaybackStatus
import app.flux.media.TransportCaps
import app.flux.notifications.NotificationActionLabel
import app.flux.notifications.NotificationPayload
import app.flux.ui.overlay.MediaCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test events the Diagnostics screen can fire. They go through the same engine and scheduler as real ones. */
enum class SimulatedEvent(val label: String, val group: String) {
    MusicPlaying("Music: playing", "Music"),
    MusicPaused("Music: paused (auto-hide countdown)", "Music"),
    MusicNextTrack("Music: next track (artwork transition)", "Music"),
    MusicStop("Music: remove", "Music"),
    NotificationMessage("Notification: message", "Notifications"),
    NotificationPrivate("Notification: private (content hidden)", "Notifications"),
    IncomingCall("Incoming call", "Notifications"),
    ChargingStarted("Charging started", "Battery"),
    ChargingFull("Fully charged", "Battery"),
    Unplugged("Charger disconnected", "Battery"),
    LowBattery("Low battery", "Battery"),
    CriticalBattery("Critical battery", "Battery"),
    BluetoothEarbuds("Bluetooth: earbuds connected", "Bluetooth"),
    BluetoothHeadphones("Bluetooth: headphones connected", "Bluetooth"),
    BluetoothPairingCard("Bluetooth: pairing card (tap Connect)", "Bluetooth"),
    TimerDone("Timer finished (card only)", "Timer"),
    Timer15s("Start a real 15-second timer", "Timer"),
    ClearAll("Clear all test events", "Timer"),
}

internal object Simulator {
    /** Locally-administered MAC used by simulated devices so they can never collide with real hardware. */
    const val SIM_ADDRESS = "02:00:00:00:00:01"

    private class Track(val title: String, val artist: String, val album: String, val colors: IntArray)

    private val tracks = listOf(
        Track("Neon Skyline", "Aurora Drive", "Night Signals", intArrayOf(0xFF1B2A6B.toInt(), 0xFF8B3DFF.toInt(), 0xFFFF6A88.toInt())),
        Track("Midnight Drive", "The Lowlights", "Afterglow", intArrayOf(0xFF0F3B3A.toInt(), 0xFF1FA2A6.toInt(), 0xFFF2C94C.toInt())),
    )
    private var trackIndex = 0

    fun run(event: SimulatedEvent, engine: FluxEngine, graph: AppGraph) {
        when (event) {
            SimulatedEvent.MusicPlaying -> startMusic(engine, graph, PlaybackStatus.Playing)
            SimulatedEvent.MusicPaused -> startMusic(engine, graph, PlaybackStatus.Paused)
            SimulatedEvent.MusicNextTrack ->
                if (engine.currentSimulatedMedia == null) startMusic(engine, graph, PlaybackStatus.Playing) else mediaCommand(MediaCommand.Next, engine, graph)
            SimulatedEvent.MusicStop -> engine.setSimulatedMedia(null, null)
            SimulatedEvent.NotificationMessage -> notification(engine, hidden = false)
            SimulatedEvent.NotificationPrivate -> notification(engine, hidden = true)
            SimulatedEvent.IncomingCall -> call(engine)
            SimulatedEvent.ChargingStarted -> charging(engine, ChargingEventType.Started, 62, PlugType.Ac, eta = 38 * 60_000L, temp = 31f)
            SimulatedEvent.ChargingFull -> charging(engine, ChargingEventType.Full, 100, PlugType.Wireless, eta = null, temp = null)
            SimulatedEvent.Unplugged -> charging(engine, ChargingEventType.Disconnected, 84, null, eta = null, temp = null)
            SimulatedEvent.LowBattery -> engine.postSimulated(engine.simulatedEntry("battery-alert", ActivityKind.BatteryAlert, BatteryAlertPayload(14, false), 8_000))
            SimulatedEvent.CriticalBattery -> engine.postSimulated(engine.simulatedEntry("battery-alert", ActivityKind.BatteryAlert, BatteryAlertPayload(4, true), 12_000))
            SimulatedEvent.BluetoothEarbuds -> connectedDevice(engine, "Galaxy Buds2 Pro", DeviceKind.Earbuds, DeviceBattery(left = 82, right = 78, case = 91))
            SimulatedEvent.BluetoothHeadphones -> connectedDevice(engine, "Studio Headphones", DeviceKind.Headphones, DeviceBattery(single = 64))
            SimulatedEvent.BluetoothPairingCard ->
                engine.showSimulatedCard(BluetoothPayload(SIM_ADDRESS, "Galaxy Buds2 Pro", DeviceKind.Earbuds, ConnectionPhase.Discovered))
            SimulatedEvent.TimerDone -> engine.postSimulated(
                engine.simulatedEntry("timer-done", ActivityKind.TimerDone, app.flux.timers.TimerDonePayload(300_000L)),
            )
            SimulatedEvent.Timer15s -> graph.appScope.launch { graph.timers.startTimer(15_000L) }
            SimulatedEvent.ClearAll -> {
                engine.setSimulatedMedia(null, null)
                graph.appScope.launch {
                    graph.timers.cancelTimer()
                    graph.timers.resetStopwatch()
                }
            }
        }
    }

    // ── Music ─────────────────────────────────────────────────────────────────────────────────

    private fun snapshot(status: PlaybackStatus, now: Long, positionMs: Long): MediaSnapshot {
        val t = tracks[trackIndex]
        return MediaSnapshot(
            packageName = "app.flux.simulated", appLabel = "Test Player", title = t.title, artist = t.artist, album = t.album,
            status = status, reportedPositionMs = positionMs, reportedAtElapsedMs = now, speed = 1f, durationMs = 215_000L,
            caps = TransportCaps(canPlay = true, canPause = true, canSkipNext = true, canSkipPrevious = true, canSeek = true),
            artworkKey = "sim:$trackIndex",
            customActions = listOf(MediaCustomAction("shuffle", "Shuffle", 0, "app.flux")),
            outputLabel = "Test speaker",
            upNext = tracks[(trackIndex + 1) % tracks.size].title,
        )
    }

    private fun startMusic(engine: FluxEngine, graph: AppGraph, status: PlaybackStatus) {
        graph.mainScope.launch {
            val artwork = graph.artwork.process("sim:$trackIndex", SampleCover.render(tracks[trackIndex].colors), null)
            engine.setSimulatedMedia(snapshot(status, engine.mediaClock(), 38_000L), artwork)
        }
    }

    fun mediaCommand(command: MediaCommand, engine: FluxEngine, graph: AppGraph) {
        val current = engine.currentSimulatedMedia ?: return
        val now = engine.mediaClock()
        val position = current.positionAt(now) ?: 0L
        when (command) {
            MediaCommand.Play -> engine.setSimulatedMedia(current.copy(status = PlaybackStatus.Playing, reportedPositionMs = position, reportedAtElapsedMs = now), engine.currentSimulatedArtwork)
            MediaCommand.Pause -> engine.setSimulatedMedia(current.copy(status = PlaybackStatus.Paused, reportedPositionMs = position, reportedAtElapsedMs = now), engine.currentSimulatedArtwork)
            is MediaCommand.SeekTo -> engine.setSimulatedMedia(current.copy(reportedPositionMs = command.positionMs, reportedAtElapsedMs = now), engine.currentSimulatedArtwork)
            MediaCommand.Next, MediaCommand.Previous -> {
                trackIndex = (trackIndex + 1) % tracks.size
                graph.mainScope.launch {
                    val artwork = graph.artwork.process("sim:$trackIndex", SampleCover.render(tracks[trackIndex].colors), null)
                    engine.setSimulatedMedia(snapshot(PlaybackStatus.Playing, engine.mediaClock(), 0L), artwork)
                }
            }
            is MediaCommand.Custom, MediaCommand.OpenApp -> Unit
        }
    }

    // ── Everything else ───────────────────────────────────────────────────────────────────────

    private fun notification(engine: FluxEngine, hidden: Boolean) {
        val payload = NotificationPayload(
            sbnKey = "sim-notification", packageName = "app.flux", appLabel = "Messages",
            title = if (hidden) null else "Alex", text = if (hidden) null else "Are we still on for 7? I booked the table by the window.",
            contentHidden = hidden, actions = listOf(NotificationActionLabel(0, "Mark as read")), hasContentIntent = true, isIncomingCall = false,
        )
        engine.postSimulated(engine.simulatedEntry("notif:sim-notification", ActivityKind.Notification, payload, 5_000))
    }

    private fun call(engine: FluxEngine) {
        val payload = NotificationPayload(
            sbnKey = "sim-call", packageName = "app.flux", appLabel = "Phone", title = "Jordan Lee", text = "Incoming call",
            contentHidden = false, actions = listOf(NotificationActionLabel(0, "Decline"), NotificationActionLabel(1, "Answer")),
            hasContentIntent = true, isIncomingCall = true,
        )
        engine.postSimulated(engine.simulatedEntry("call:sim-call", ActivityKind.Call, payload, expiresAt = engine.mediaClock() + 20_000L))
    }

    private fun charging(engine: FluxEngine, type: ChargingEventType, level: Int, plug: PlugType?, eta: Long?, temp: Float?) {
        engine.postSimulated(engine.simulatedEntry("charging", ActivityKind.Charging, ChargingPayload(type, level, plug, eta, temp), 5_000))
    }

    private fun connectedDevice(engine: FluxEngine, name: String, kind: DeviceKind, battery: DeviceBattery) {
        val payload = BluetoothPayload("02:00:00:00:00:02", name, kind, ConnectionPhase.Connected, battery)
        engine.postSimulated(engine.simulatedEntry("bt:${payload.address}", ActivityKind.Bluetooth, payload, 6_000))
        engine.showSimulatedCard(payload)
    }

    /** Plays the whole Connect → Pairing → Connecting → Connected sequence for the simulated card. */
    fun simulateConnect(engine: FluxEngine, graph: AppGraph) {
        graph.mainScope.launch {
            val base = BluetoothPayload(SIM_ADDRESS, "Galaxy Buds2 Pro", DeviceKind.Earbuds, ConnectionPhase.Pairing)
            engine.showSimulatedCard(base)
            delay(1300)
            engine.showSimulatedCard(base.copy(phase = ConnectionPhase.Connecting))
            delay(1500)
            val done = base.copy(phase = ConnectionPhase.Connected, battery = DeviceBattery(left = 82, right = 78, case = 91))
            engine.postSimulated(engine.simulatedEntry("bt:${done.address}", ActivityKind.Bluetooth, done, 6_000))
            engine.showSimulatedCard(done)
        }
    }
}
