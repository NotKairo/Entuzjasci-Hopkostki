package app.flux.ui.app

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flux.AppGraph
import app.flux.core.Permissions
import app.flux.overlay.OverlayServiceControl
import app.flux.settings.FluxSettings
import app.flux.ui.design.FluxColors
import app.flux.ui.design.FluxIcon
import app.flux.ui.design.FluxType

@Composable
fun SetupScreen(graph: AppGraph, settings: FluxSettings, handle: SettingsHandle) {
    val context = LocalContext.current
    var perms by remember { mutableStateOf(Permissions.read(context)) }
    var wantEnable by rememberSaveable { mutableStateOf(false) }
    val running by graph.serviceRunning.collectAsStateWithLifecycle()

    fun enable() {
        handle.update { it.copy(overlayEnabled = true) }
        OverlayServiceControl.start(context)
    }

    // Re-read permissions every time the user comes back from a system settings screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        perms = Permissions.read(context)
        if (wantEnable && perms.overlay) {
            wantEnable = false
            enable()
        }
    }
    // Keep the service in step with the saved switch (after an update or if Android restarted the process).
    LaunchedEffect(settings.overlayEnabled, perms.overlay) {
        if (settings.overlayEnabled && perms.overlay && !running) OverlayServiceControl.start(context)
    }

    val runtimePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perms = Permissions.read(context)
    }

    ScreenColumn {
        ScreenTitle("Flux", "A live activity overlay for your Galaxy")

        SectionCard {
            SettingSwitch(
                title = "Show the Flux overlay",
                subtitle = when {
                    running -> "Running — music, timers, charging and device events appear at the top"
                    settings.overlayEnabled -> "Starting…"
                    else -> "Off"
                },
                checked = settings.overlayEnabled,
                onChange = { on ->
                    if (!on) {
                        wantEnable = false
                        handle.update { it.copy(overlayEnabled = false) }
                        OverlayServiceControl.stop(context)
                    } else if (!perms.overlay) {
                        wantEnable = true
                        context.startActivity(Permissions.overlaySettings(context))
                    } else {
                        enable()
                    }
                },
            )
            if (!perms.overlay) Hint("Turning this on first opens Android's \"Display over other apps\" screen. Flux cannot grant it for you.")
        }

        Text("Permissions", style = FluxType.Title, color = FluxColors.OnSurface)

        PermissionRow(
            title = "Display over other apps",
            required = true,
            granted = perms.overlay,
            why = "Lets Flux draw its pill above other apps. Without it nothing can be shown.",
            actionLabel = "Open settings",
            onAction = { context.startActivity(Permissions.overlaySettings(context)) },
        )

        PermissionRow(
            title = "Notification access",
            granted = perms.notificationAccess,
            why = "Lets Flux see which music player is active and show notifications. Message text is shown only according to your privacy setting, is never stored and never leaves the phone.",
            actionLabel = "Open settings",
            onAction = { context.startActivity(Permissions.notificationAccessSettings(context)) },
            extra = {
                if (!perms.notificationAccess) {
                    Hint("Android 13+ may show \"Restricted setting\" for apps installed outside the Play Store. If so: open App info → ⋮ (top right) → Allow restricted settings, then return here.")
                    SecondaryButton("Open App info", onClick = { context.startActivity(Permissions.appDetails(context)) })
                }
            },
        )

        if (Build.VERSION.SDK_INT >= 33) {
            PermissionRow(
                title = "Notifications",
                granted = perms.postNotifications,
                why = "Needed for the timer alarm and the small \"Flux is on\" notification Android requires for an overlay.",
                actionLabel = "Allow",
                onAction = { runtimePermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
            )
        }

        PermissionRow(
            title = "Bluetooth",
            granted = perms.bluetoothConnect && perms.bluetoothScan,
            why = "Shows a card when your headphones connect and lets the Connect button pair them. Flux only scans while you open the Bluetooth screen — never in the background.",
            actionLabel = "Allow",
            onAction = { runtimePermissions.launch(Permissions.bluetoothRuntimePermissions) },
        )

        if (Build.VERSION.SDK_INT < 33) {
            PermissionRow(
                title = "Alarms & reminders",
                granted = perms.exactAlarms,
                why = "Lets a timer finish on time while the app is closed. Without it the alarm may be late.",
                actionLabel = "Open settings",
                onAction = { context.startActivity(Permissions.exactAlarmSettings(context)) },
            )
        }

        PermissionRow(
            title = "Battery: Unrestricted",
            recommended = true,
            granted = perms.batteryUnrestricted,
            why = "One UI puts apps to sleep to save power, which can stop the overlay. Set this app to Unrestricted.",
            actionLabel = "Open App info",
            onAction = { context.startActivity(Permissions.appDetails(context)) },
            extra = {
                Hint("Settings → Apps → Flux → Battery → Unrestricted. Also check Settings → Battery → Background usage limits and make sure Flux is not in \"Sleeping apps\" or \"Deep sleeping apps\" (add it to \"Never sleeping apps\").", FluxIcon.Bolt)
                SecondaryButton("Open battery optimisation list", onClick = { context.startActivity(Permissions.batteryOptimizationList()) })
            },
        )

        SectionCard("Good to know") {
            Hint("Android draws the status bar above any app overlay, so status icons stay visible on top of Flux. The camera is never drawn or covered.")
            Hint("Overlays cannot appear on the lock screen; Flux hides while locked and returns when you unlock.")
            Hint("Some apps (system Settings, banking) block overlays. Flux respects that.")
            Hint("Installing outside the Play Store? If Play Protect warns about notification access, install with adb or a file manager instead of directly from the browser.")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    why: String,
    actionLabel: String,
    onAction: () -> Unit,
    required: Boolean = false,
    recommended: Boolean = false,
    extra: @Composable () -> Unit = {},
) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = FluxType.Body.copy(fontSize = 16.sp), color = FluxColors.OnSurface)
                Text(
                    when {
                        required -> "Required"
                        recommended -> "Recommended"
                        else -> "Optional — enables a feature"
                    },
                    style = FluxType.Label, color = FluxColors.OnSurfaceFaint,
                )
            }
            StatusPill(if (granted) "Granted" else "Not granted", good = granted)
        }
        Text(why, style = FluxType.Caption, color = FluxColors.OnSurfaceMuted)
        if (!granted) PrimaryButton(actionLabel, onAction, Modifier.fillMaxWidth())
        extra()
    }
}
