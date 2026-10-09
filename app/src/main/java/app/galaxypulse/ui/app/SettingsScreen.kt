package app.galaxypulse.ui.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.galaxypulse.AppGraph
import app.galaxypulse.bluetooth.DeviceKind
import app.galaxypulse.core.Permissions
import app.galaxypulse.settings.NotificationContentMode
import app.galaxypulse.settings.PriorityGroup
import app.galaxypulse.settings.PulseSettings
import app.galaxypulse.ui.design.PulseColors
import app.galaxypulse.ui.design.PulseType
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(graph: AppGraph, settings: PulseSettings, handle: SettingsHandle) {
    var page by rememberSaveable { mutableStateOf(0) }
    BackHandler(enabled = page != 0) { page = 0 }
    if (page == 1) {
        AppsScreen(settings, handle, onBack = { page = 0 })
        return
    }

    ScreenColumn {
        ScreenTitle("Settings")

        SectionCard("What Pulse shows") {
            SettingSwitch("Music", "Follows the active media session", settings.enableMedia) { v -> handle.update { it.copy(enableMedia = v) } }
            SettingSwitch("Notifications", "A brief card, then back to what was showing", settings.enableNotifications) { v -> handle.update { it.copy(enableNotifications = v) } }
            SettingSwitch("Charging", "When a charger connects, disconnects or the battery is full", settings.enableCharging) { v -> handle.update { it.copy(enableCharging = v) } }
            SettingSwitch("Headphones & Bluetooth devices", "Connection card and pill", settings.enableBluetooth) { v -> handle.update { it.copy(enableBluetooth = v) } }
            SettingSwitch("Timer & stopwatch", "A running timer or stopwatch stays in the pill", settings.enableTimers) { v -> handle.update { it.copy(enableTimers = v) } }
            SettingSwitch("Low battery alerts", null, settings.enableLowBattery) { v -> handle.update { it.copy(enableLowBattery = v) } }
            if (settings.enableLowBattery) {
                SettingSlider(
                    "Warn at", "${settings.lowBatteryThresholdPercent}%", settings.lowBatteryThresholdPercent.toFloat(), 5f..40f, steps = 6,
                    onCommit = { v -> handle.update { it.copy(lowBatteryThresholdPercent = v.roundToInt()) } },
                )
            }
        }

        SectionCard("Priority") {
            Text(
                "When several things happen at once, the higher one is shown. Incoming calls, finished timers and critical battery alerts are always first.",
                style = PulseType.Caption, color = PulseColors.OnSurfaceMuted,
            )
            settings.priorityOrder.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider(color = PulseColors.Stroke)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", style = PulseType.Numeric, color = PulseColors.OnSurfaceFaint, modifier = Modifier.padding(end = 12.dp))
                    Text(group.label, style = PulseType.Body, color = PulseColors.OnSurface, modifier = Modifier.weight(1f))
                    SecondaryButton("↑", { handle.update { it.copy(priorityOrder = move(it.priorityOrder, index, -1)) } }, Modifier.padding(end = 6.dp), enabled = index > 0)
                    SecondaryButton("↓", { handle.update { it.copy(priorityOrder = move(it.priorityOrder, index, +1)) } }, enabled = index < settings.priorityOrder.lastIndex)
                }
            }
            Hint("Music can always be interrupted briefly by anything above it and returns afterwards. A running timer is only interrupted by calls and alarms.")
        }

        SectionCard("How long things stay") {
            SettingSlider("Paused music hides after", "${settings.pausedHideDelaySec} s", settings.pausedHideDelaySec.toFloat(), 2f..120f, steps = 0,
                onCommit = { v -> handle.update { it.copy(pausedHideDelaySec = v.roundToInt()) } })
            SettingSlider("Notifications", "${settings.notificationSec} s", settings.notificationSec.toFloat(), 2f..20f, steps = 17,
                onCommit = { v -> handle.update { it.copy(notificationSec = v.roundToInt()) } })
            SettingSlider("Charging", "${settings.chargingSec} s", settings.chargingSec.toFloat(), 2f..20f, steps = 17,
                onCommit = { v -> handle.update { it.copy(chargingSec = v.roundToInt()) } })
            SettingSlider("Bluetooth", "${settings.bluetoothSec} s", settings.bluetoothSec.toFloat(), 2f..20f, steps = 17,
                onCommit = { v -> handle.update { it.copy(bluetoothSec = v.roundToInt()) } })
            SettingSlider("Battery alerts", "${settings.batteryAlertSec} s", settings.batteryAlertSec.toFloat(), 2f..30f, steps = 27,
                onCommit = { v -> handle.update { it.copy(batteryAlertSec = v.roundToInt()) } })
        }

        SectionCard("Gestures") {
            SettingSwitch("Tap to expand / collapse", null, settings.tapToExpand) { v -> handle.update { it.copy(tapToExpand = v) } }
            SettingSwitch("Swipe sideways to dismiss", "Hides the current card. It only returns when something new happens.", settings.swipeToDismiss) { v -> handle.update { it.copy(swipeToDismiss = v) } }
            SettingSwitch("Tap outside to collapse", "Your tap still reaches the app underneath", settings.tapOutsideCollapses) { v -> handle.update { it.copy(tapOutsideCollapses = v) } }
        }

        SectionCard("Notification privacy") {
            ChoiceChips(NotificationContentMode.entries.toList(), settings.notificationContent, { it.label }, { m -> handle.update { it.copy(notificationContent = m) } })
            Text(settings.notificationContent.blurb, style = PulseType.Caption, color = PulseColors.OnSurfaceMuted)
            SettingSwitch("Include silent notifications", "Off: only notifications that would normally make a sound", settings.includeSilentNotifications) { v -> handle.update { it.copy(includeSilentNotifications = v) } }
            SecondaryButton("Per-app rules…", { page = 1 }, Modifier.fillMaxWidth())
            Hint("Pulse never cancels or changes other apps' notifications. Android may still show its own pop-up for the same notification; use a per-app rule to avoid seeing both.")
        }

        BluetoothSection(graph, settings, handle)

        SectionCard("Startup") {
            SettingSwitch("Start after reboot", "Brings the overlay back and re-arms timers", settings.startOnBoot) { v -> handle.update { it.copy(startOnBoot = v) } }
        }
    }
}

private fun move(list: List<PriorityGroup>, index: Int, delta: Int): List<PriorityGroup> {
    val target = index + delta
    if (target !in list.indices) return list
    return list.toMutableList().also { val item = it.removeAt(index); it.add(target, item) }
}

private fun DeviceKind.label() = when (this) {
    DeviceKind.Earbuds -> "Earbuds"
    DeviceKind.Headphones -> "Headphones"
    DeviceKind.Speaker -> "Speaker"
    DeviceKind.Watch -> "Watch"
    DeviceKind.Car -> "Car"
    DeviceKind.Other -> "Device"
}

@Composable
private fun BluetoothSection(graph: AppGraph, settings: PulseSettings, handle: SettingsHandle) {
    val context = LocalContext.current
    val bt = graph.bluetooth
    var perms by remember { mutableStateOf(Permissions.read(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { perms = Permissions.read(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perms = Permissions.read(context)
        bt.onPermissionsChanged()
    }
    val scanning by bt.scanning.collectAsState()
    val discovered by bt.discovered.collectAsState()
    val known = remember(perms.bluetoothConnect, scanning) { bt.knownDevices() }

    SectionCard("Headphones & Bluetooth") {
        SettingSwitch("Bottom connection card", "Slides up when a device connects. Off keeps only the pill.", settings.bluetoothBottomCard) { v -> handle.update { it.copy(bluetoothBottomCard = v) } }
        SettingSlider(
            "Don't repeat the same device within", "${settings.bluetoothCooldownSec} s", settings.bluetoothCooldownSec.toFloat(), 0f..300f, steps = 0,
            onCommit = { v -> handle.update { it.copy(bluetoothCooldownSec = v.roundToInt()) } },
        )

        if (!perms.bluetoothConnect || !perms.bluetoothScan) {
            PrimaryButton("Allow Bluetooth", { launcher.launch(Permissions.bluetoothRuntimePermissions) }, Modifier.fillMaxWidth())
            Hint("Pulse only scans for devices while this screen is open and you tap Scan.")
        } else {
            SecondaryButton(
                if (scanning) "Scanning…" else "Scan for headphones to pair",
                { bt.startScan() }, Modifier.fillMaxWidth(), enabled = !scanning && bt.isEnabled,
            )
            if (!bt.isEnabled) Hint("Bluetooth is off. Turn it on in Quick Settings.")
            discovered.forEach { device ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name, style = PulseType.Body, color = PulseColors.OnSurface)
                        Text(device.kind.label(), style = PulseType.Caption, color = PulseColors.OnSurfaceMuted)
                    }
                    PrimaryButton("Connect", { bt.connect(device.address) })
                }
            }
            if (discovered.isNotEmpty()) {
                Hint("Connect starts Android's own pairing. If Android needs your confirmation it shows its pairing dialog. Keep the overlay on to see the connection card.")
            }
            if (known.isNotEmpty()) {
                HorizontalDivider(color = PulseColors.Stroke)
                Text("Your paired devices", style = PulseType.Caption, color = PulseColors.OnSurfaceMuted)
                known.forEach { device ->
                    SettingSwitch(
                        device.name, "Announce when it connects · ${device.kind.label()}",
                        checked = device.address !in settings.mutedBluetoothDevices,
                    ) { announce ->
                        handle.update {
                            it.copy(mutedBluetoothDevices = if (announce) it.mutedBluetoothDevices - device.address else it.mutedBluetoothDevices + device.address)
                        }
                    }
                }
            }
        }
        Hint("Battery levels appear only when the headset reports them to Android. Left/right/case levels need a vendor protocol Android doesn't expose, so usually you'll see one level or none.")
    }
}
