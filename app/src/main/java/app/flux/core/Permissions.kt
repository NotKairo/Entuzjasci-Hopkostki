package app.flux.core

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.flux.notifications.FluxNotificationListener

/** Current state of every permission Flux can use. Each is optional except overlay + notification access for music. */
data class PermissionStatus(
    val overlay: Boolean,
    val notificationAccess: Boolean,
    val postNotifications: Boolean,
    val bluetoothConnect: Boolean,
    val bluetoothScan: Boolean,
    val exactAlarms: Boolean,
    val batteryUnrestricted: Boolean,
)

object Permissions {

    fun read(context: Context): PermissionStatus {
        val app = context.applicationContext
        return PermissionStatus(
            overlay = Settings.canDrawOverlays(app),
            notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName),
            postNotifications = has(app, Manifest.permission.POST_NOTIFICATIONS),
            bluetoothConnect = has(app, Manifest.permission.BLUETOOTH_CONNECT),
            bluetoothScan = has(app, Manifest.permission.BLUETOOTH_SCAN),
            exactAlarms = app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            batteryUnrestricted = app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName),
        )
    }

    private fun has(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // Each of these opens the *system* screen for the user to decide; nothing is granted silently.

    fun overlaySettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun notificationAccessSettings(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, FluxNotificationListener::class.java).flattenToString(),
            )

    /** Needed on Android 13+ to reach "Allow restricted settings" for sideloaded apps (⋮ menu on this screen). */
    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun exactAlarmSettings(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))

    fun batteryOptimizationList(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun bluetoothSettings(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

    val bluetoothRuntimePermissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
}
