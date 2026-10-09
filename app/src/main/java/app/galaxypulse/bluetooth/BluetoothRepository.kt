package app.galaxypulse.bluetooth

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.galaxypulse.engine.EventDeduper
import app.galaxypulse.settings.PulseSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Bluetooth device events and the pairing flow, using only supported public APIs:
 *  - connection broadcasts (ACL, A2DP, headset profile) to learn that a device connected;
 *  - `createBond()` for **Connect** — Android itself shows the pairing dialog when it needs one;
 *  - the Hands-Free vendor-specific broadcast for headset battery, where a headset reports it.
 *
 * It never scans in the background: discovery runs only while the user asks for it in the app.
 * Per-device mute rules and a cooldown stop the same connection from being announced twice.
 */
class BluetoothRepository(
    context: Context,
    private val settings: () -> PulseSettings,
    private val nextRevision: () -> Long,
    private val now: () -> Long,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    sealed interface Event {
        /** A relevant device connected. Drives the top pill and the bottom card. */
        data class Connected(val payload: BluetoothPayload, val revision: Long) : Event
        data class Disconnected(val address: String) : Event
        /** Card-only progress: discovered, pairing, connecting, failed, battery arrived. */
        data class Progress(val payload: BluetoothPayload, val revision: Long) : Event
    }

    data class KnownDevice(val address: String, val name: String, val kind: DeviceKind)

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val deduper = EventDeduper()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events

    private val _discovered = MutableStateFlow<List<BluetoothPayload>>(emptyList())
    val discovered: StateFlow<List<BluetoothPayload>> = _discovered

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val batteryByAddress = HashMap<String, DeviceBattery>()
    private var pairingAddress: String? = null
    private var pairingJob: Job? = null
    private var scanJob: Job? = null
    private var cardShownForScan = false
    private var registered = false

    fun hasConnectPermission() = granted(Manifest.permission.BLUETOOTH_CONNECT)
    fun hasScanPermission() = granted(Manifest.permission.BLUETOOTH_SCAN)
    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                handle(intent)
            } catch (_: SecurityException) {
                log("bluetooth: permission revoked while handling ${intent.action}")
            }
        }
    }

    private var users = 0
    private var scanHoldsRef = false

    /** The overlay engine (and an active scan) each hold a reference; the receiver lives while any is held. */
    fun start() {
        if (users++ == 0) register()
    }

    fun stop() {
        if (users > 0 && --users == 0) unregister()
    }

    /** Call after the user grants Bluetooth permission so a waiting registration can complete. */
    fun onPermissionsChanged() {
        if (users > 0 && !registered) register()
    }

    private fun register() {
        if (registered || !hasConnectPermission()) return
        val main = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        // Intents of this action carry a per-vendor category, so it needs its own registration.
        val vendor = IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT).apply {
            addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + "." + APPLE_COMPANY_ID)
        }
        ContextCompat.registerReceiver(appContext, receiver, main, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(appContext, receiver, vendor, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    private fun unregister() {
        if (!registered) return
        appContext.unregisterReceiver(receiver)
        registered = false
        pairingJob?.cancel()
    }

    // ── Pairing flow ──────────────────────────────────────────────────────────────────────────

    /** The **Connect** button. Starts system bonding; Android shows its pairing UI if required. */
    fun connect(address: String) {
        val device = remote(address) ?: return
        pairingAddress = address
        pairingJob?.cancel()
        emitProgress(device, ConnectionPhase.Pairing)
        try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                // Already paired. Android connects bonded audio devices itself when they are in range,
                // and offers no public call to force it — so we say so honestly after a short wait.
                emitProgress(device, ConnectionPhase.Connecting)
                armPairingTimeout(address, CONNECT_WAIT_MS, ConnectionPhase.PairedNotConnected)
            } else if (device.createBond()) {
                armPairingTimeout(address, PAIRING_WAIT_MS, ConnectionPhase.Failed)
            } else {
                emitProgress(device, ConnectionPhase.Failed)
            }
        } catch (_: SecurityException) {
            emitProgress(device, ConnectionPhase.Failed)
        }
    }

    private fun armPairingTimeout(address: String, afterMs: Long, phase: ConnectionPhase) {
        pairingJob?.cancel()
        pairingJob = scope.launch {
            delay(afterMs)
            if (pairingAddress == address) {
                remote(address)?.let { emitProgress(it, phase) }
                pairingAddress = null
            }
        }
    }

    // ── Discovery (only while the user asks) ──────────────────────────────────────────────────

    fun startScan() {
        val bt = adapter ?: return
        if (!hasScanPermission() || !bt.isEnabled) return
        try {
            _discovered.value = emptyList()
            cardShownForScan = false
            if (bt.startDiscovery()) {
                if (!scanHoldsRef) {
                    scanHoldsRef = true
                    start()
                }
                _scanning.value = true
                scanJob?.cancel()
                scanJob = scope.launch {
                    delay(SCAN_MS)
                    stopScan()
                }
            }
        } catch (_: SecurityException) {
            _scanning.value = false
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        try {
            val bt = adapter
            if (bt != null && hasScanPermission() && bt.isDiscovering) bt.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        _scanning.value = false
        if (scanHoldsRef) {
            scanHoldsRef = false
            stop()
        }
    }

    /** Paired devices, for the per-device notification rules. */
    fun knownDevices(): List<KnownDevice> = try {
        if (!hasConnectPermission()) emptyList()
        else adapter?.bondedDevices.orEmpty().map { KnownDevice(it.address, safeName(it), kindOf(it)) }
    } catch (_: SecurityException) {
        emptyList()
    }

    // ── Broadcast handling ────────────────────────────────────────────────────────────────────

    private fun handle(intent: Intent) {
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> device?.let(::onConnected)
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED ->
                if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) device?.let(::onConnected)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> device?.let { _events.tryEmit(Event.Disconnected(it.address)) }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> device?.let {
                onBond(it, intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1), intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1))
            }
            BluetoothDevice.ACTION_FOUND -> device?.let(::onFound)
            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _scanning.value = false
            BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> device?.let { onVendorEvent(it, intent) }
        }
    }

    private fun onConnected(device: BluetoothDevice) {
        val address = device.address
        if (address in settings().mutedBluetoothDevices) return
        if (!isRelevant(device)) return

        val wasPairing = pairingAddress == address
        // A connection that completes a pairing the user started is always shown; others obey the cooldown.
        val cooldownMs = settings().bluetoothCooldownSec * 1000L
        if (!wasPairing && !deduper.acceptCooldown("bt:$address", now(), cooldownMs)) return
        if (wasPairing) {
            pairingAddress = null
            pairingJob?.cancel()
            deduper.acceptCooldown("bt:$address", now(), cooldownMs)
        }
        val payload = payload(device, ConnectionPhase.Connected)
        log("bluetooth connected: ${payload.name} (${payload.kind})")
        _events.tryEmit(Event.Connected(payload, nextRevision()))
    }

    private fun onBond(device: BluetoothDevice, state: Int, previous: Int) {
        if (pairingAddress != device.address) return
        when (state) {
            BluetoothDevice.BOND_BONDING -> emitProgress(device, ConnectionPhase.Pairing)
            BluetoothDevice.BOND_BONDED -> {
                emitProgress(device, ConnectionPhase.Connecting)
                armPairingTimeout(device.address, CONNECT_WAIT_MS, ConnectionPhase.PairedNotConnected)
            }
            BluetoothDevice.BOND_NONE -> if (previous == BluetoothDevice.BOND_BONDING) {
                pairingJob?.cancel()
                pairingAddress = null
                emitProgress(device, ConnectionPhase.Failed)
            }
        }
    }

    private fun onFound(device: BluetoothDevice) {
        val alreadyPaired = try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            return
        }
        if (alreadyPaired || !isRelevant(device)) return
        val payload = payload(device, ConnectionPhase.Discovered)
        if (_discovered.value.any { it.address == payload.address }) return
        _discovered.value = _discovered.value + payload
        if (!cardShownForScan && !payload.address.let { it in settings().mutedBluetoothDevices }) {
            cardShownForScan = true
            log("bluetooth discovered: ${payload.name}")
            _events.tryEmit(Event.Progress(payload, nextRevision()))
        }
    }

    private fun onVendorEvent(device: BluetoothDevice, intent: Intent) {
        if (intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD) != "+IPHONEACCEV") return
        @Suppress("DEPRECATION")
        val args = (intent.extras?.get(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS) as? Array<*>)?.toList() ?: return
        val percent = HeadsetBatteryParser.parseIphoneAccev(args) ?: return
        batteryByAddress[device.address] = DeviceBattery(single = percent)
        // Lets a visible card/pill for this device pick up the level; the engine ignores it otherwise.
        _events.tryEmit(Event.Progress(payload(device, ConnectionPhase.Connected), nextRevision()))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private fun remote(address: String): BluetoothDevice? = try {
        adapter?.getRemoteDevice(address)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun emitProgress(device: BluetoothDevice, phase: ConnectionPhase) {
        _events.tryEmit(Event.Progress(payload(device, phase), nextRevision()))
    }

    private fun safeName(device: BluetoothDevice): String = try {
        device.alias?.takeIf { it.isNotBlank() } ?: device.name?.takeIf { it.isNotBlank() } ?: device.address
    } catch (_: SecurityException) {
        device.address
    }

    private fun kindOf(device: BluetoothDevice): DeviceKind = try {
        val cls = device.bluetoothClass
        DeviceClassifier.classify(cls?.majorDeviceClass, cls?.deviceClass, safeName(device))
    } catch (_: SecurityException) {
        DeviceKind.Other
    }

    private fun isRelevant(device: BluetoothDevice): Boolean = try {
        val cls = device.bluetoothClass
        DeviceClassifier.isRelevant(cls?.majorDeviceClass, cls?.deviceClass, safeName(device))
    } catch (_: SecurityException) {
        false
    }

    private fun payload(device: BluetoothDevice, phase: ConnectionPhase) = BluetoothPayload(
        address = device.address,
        name = safeName(device),
        kind = kindOf(device),
        phase = phase,
        battery = batteryByAddress[device.address] ?: DeviceBattery(),
    )

    private companion object {
        const val APPLE_COMPANY_ID = 76
        const val PAIRING_WAIT_MS = 45_000L
        const val CONNECT_WAIT_MS = 15_000L
        const val SCAN_MS = 12_000L
    }
}
