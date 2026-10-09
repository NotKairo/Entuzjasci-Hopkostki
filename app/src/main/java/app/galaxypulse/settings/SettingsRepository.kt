package app.galaxypulse.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_settings")

/** Persists [PulseSettings]. Unknown/corrupt values fall back to defaults instead of throwing. */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    val settings: Flow<PulseSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun update(transform: (PulseSettings) -> PulseSettings) {
        store.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs.write(updated)
        }
    }

    private object K {
        val overlayEnabled = booleanPreferencesKey("overlay_enabled")
        val startOnBoot = booleanPreferencesKey("start_on_boot")
        val sizeScale = floatPreferencesKey("size_scale")
        val verticalOffsetDp = intPreferencesKey("vertical_offset_dp")
        val horizontalOffsetDp = intPreferencesKey("horizontal_offset_dp")
        val extendBackdrop = booleanPreferencesKey("extend_backdrop_to_top")
        val hideInLandscape = booleanPreferencesKey("hide_in_landscape")
        val theme = stringPreferencesKey("theme")
        val showArtistInCompact = booleanPreferencesKey("show_artist_compact")
        val motionPreset = stringPreferencesKey("motion_preset")
        val animationIntensity = floatPreferencesKey("animation_intensity")
        val reducedMotion = stringPreferencesKey("reduced_motion")
        val haptics = booleanPreferencesKey("haptics")
        val tapToExpand = booleanPreferencesKey("tap_to_expand")
        val swipeToDismiss = booleanPreferencesKey("swipe_to_dismiss")
        val tapOutside = booleanPreferencesKey("tap_outside_collapses")
        val pausedHideDelaySec = intPreferencesKey("paused_hide_delay_s")
        val notificationSec = intPreferencesKey("notification_s")
        val chargingSec = intPreferencesKey("charging_s")
        val bluetoothSec = intPreferencesKey("bluetooth_s")
        val batteryAlertSec = intPreferencesKey("battery_alert_s")
        val enableMedia = booleanPreferencesKey("enable_media")
        val enableNotifications = booleanPreferencesKey("enable_notifications")
        val enableCharging = booleanPreferencesKey("enable_charging")
        val enableBluetooth = booleanPreferencesKey("enable_bluetooth")
        val enableTimers = booleanPreferencesKey("enable_timers")
        val enableLowBattery = booleanPreferencesKey("enable_low_battery")
        val lowBatteryThreshold = intPreferencesKey("low_battery_threshold")
        val priorityOrder = stringPreferencesKey("priority_order")
        val notificationContent = stringPreferencesKey("notification_content")
        val includeSilent = booleanPreferencesKey("include_silent_notifications")
        val blockedApps = stringSetPreferencesKey("blocked_notification_apps")
        val hideContentApps = stringSetPreferencesKey("hide_content_apps")
        val ignoredMediaApps = stringSetPreferencesKey("ignored_media_apps")
        val btBottomCard = booleanPreferencesKey("bt_bottom_card")
        val btCooldown = intPreferencesKey("bt_cooldown_s")
        val btMuted = stringSetPreferencesKey("bt_muted_devices")
        val debugHud = booleanPreferencesKey("debug_hud")
    }

    private inline fun <reified E : Enum<E>> Preferences.enumOf(key: Preferences.Key<String>, default: E): E {
        val raw = this[key] ?: return default
        return enumValues<E>().firstOrNull { it.name == raw } ?: default
    }

    private fun Preferences.toSettings(): PulseSettings {
        val d = PulseSettings()
        return PulseSettings(
            overlayEnabled = this[K.overlayEnabled] ?: d.overlayEnabled,
            startOnBoot = this[K.startOnBoot] ?: d.startOnBoot,
            sizeScale = (this[K.sizeScale] ?: d.sizeScale).coerceIn(0.8f, 1.3f),
            verticalOffsetDp = (this[K.verticalOffsetDp] ?: d.verticalOffsetDp).coerceIn(-8, 64),
            horizontalOffsetDp = (this[K.horizontalOffsetDp] ?: d.horizontalOffsetDp).coerceIn(-120, 120),
            extendBackdropToTopEdge = this[K.extendBackdrop] ?: d.extendBackdropToTopEdge,
            hideInLandscape = this[K.hideInLandscape] ?: d.hideInLandscape,
            theme = enumOf(K.theme, d.theme),
            showArtistInCompact = this[K.showArtistInCompact] ?: d.showArtistInCompact,
            motionPreset = enumOf(K.motionPreset, d.motionPreset),
            animationIntensity = (this[K.animationIntensity] ?: d.animationIntensity).coerceIn(0f, 1.5f),
            reducedMotion = enumOf(K.reducedMotion, d.reducedMotion),
            haptics = this[K.haptics] ?: d.haptics,
            tapToExpand = this[K.tapToExpand] ?: d.tapToExpand,
            swipeToDismiss = this[K.swipeToDismiss] ?: d.swipeToDismiss,
            tapOutsideCollapses = this[K.tapOutside] ?: d.tapOutsideCollapses,
            pausedHideDelaySec = (this[K.pausedHideDelaySec] ?: d.pausedHideDelaySec).coerceIn(2, 120),
            notificationSec = (this[K.notificationSec] ?: d.notificationSec).coerceIn(2, 20),
            chargingSec = (this[K.chargingSec] ?: d.chargingSec).coerceIn(2, 20),
            bluetoothSec = (this[K.bluetoothSec] ?: d.bluetoothSec).coerceIn(2, 20),
            batteryAlertSec = (this[K.batteryAlertSec] ?: d.batteryAlertSec).coerceIn(2, 30),
            enableMedia = this[K.enableMedia] ?: d.enableMedia,
            enableNotifications = this[K.enableNotifications] ?: d.enableNotifications,
            enableCharging = this[K.enableCharging] ?: d.enableCharging,
            enableBluetooth = this[K.enableBluetooth] ?: d.enableBluetooth,
            enableTimers = this[K.enableTimers] ?: d.enableTimers,
            enableLowBattery = this[K.enableLowBattery] ?: d.enableLowBattery,
            lowBatteryThresholdPercent = (this[K.lowBatteryThreshold] ?: d.lowBatteryThresholdPercent).coerceIn(5, 40),
            priorityOrder = decodePriority(this[K.priorityOrder]),
            notificationContent = enumOf(K.notificationContent, d.notificationContent),
            includeSilentNotifications = this[K.includeSilent] ?: d.includeSilentNotifications,
            blockedNotificationApps = this[K.blockedApps] ?: d.blockedNotificationApps,
            hideContentApps = this[K.hideContentApps] ?: d.hideContentApps,
            ignoredMediaApps = this[K.ignoredMediaApps] ?: d.ignoredMediaApps,
            bluetoothBottomCard = this[K.btBottomCard] ?: d.bluetoothBottomCard,
            bluetoothCooldownSec = (this[K.btCooldown] ?: d.bluetoothCooldownSec).coerceIn(0, 600),
            mutedBluetoothDevices = this[K.btMuted] ?: d.mutedBluetoothDevices,
            debugHud = this[K.debugHud] ?: d.debugHud,
        )
    }

    private fun MutablePreferences.write(s: PulseSettings) {
        this[K.overlayEnabled] = s.overlayEnabled
        this[K.startOnBoot] = s.startOnBoot
        this[K.sizeScale] = s.sizeScale
        this[K.verticalOffsetDp] = s.verticalOffsetDp
        this[K.horizontalOffsetDp] = s.horizontalOffsetDp
        this[K.extendBackdrop] = s.extendBackdropToTopEdge
        this[K.hideInLandscape] = s.hideInLandscape
        this[K.theme] = s.theme.name
        this[K.showArtistInCompact] = s.showArtistInCompact
        this[K.motionPreset] = s.motionPreset.name
        this[K.animationIntensity] = s.animationIntensity
        this[K.reducedMotion] = s.reducedMotion.name
        this[K.haptics] = s.haptics
        this[K.tapToExpand] = s.tapToExpand
        this[K.swipeToDismiss] = s.swipeToDismiss
        this[K.tapOutside] = s.tapOutsideCollapses
        this[K.pausedHideDelaySec] = s.pausedHideDelaySec
        this[K.notificationSec] = s.notificationSec
        this[K.chargingSec] = s.chargingSec
        this[K.bluetoothSec] = s.bluetoothSec
        this[K.batteryAlertSec] = s.batteryAlertSec
        this[K.enableMedia] = s.enableMedia
        this[K.enableNotifications] = s.enableNotifications
        this[K.enableCharging] = s.enableCharging
        this[K.enableBluetooth] = s.enableBluetooth
        this[K.enableTimers] = s.enableTimers
        this[K.enableLowBattery] = s.enableLowBattery
        this[K.lowBatteryThreshold] = s.lowBatteryThresholdPercent
        this[K.priorityOrder] = s.priorityOrder.joinToString(",") { it.name }
        this[K.notificationContent] = s.notificationContent.name
        this[K.includeSilent] = s.includeSilentNotifications
        this[K.blockedApps] = s.blockedNotificationApps
        this[K.hideContentApps] = s.hideContentApps
        this[K.ignoredMediaApps] = s.ignoredMediaApps
        this[K.btBottomCard] = s.bluetoothBottomCard
        this[K.btCooldown] = s.bluetoothCooldownSec
        this[K.btMuted] = s.mutedBluetoothDevices
        this[K.debugHud] = s.debugHud
    }

    private fun decodePriority(raw: String?): List<PriorityGroup> {
        val parsed = raw?.split(",")?.mapNotNull { name -> PriorityGroup.entries.firstOrNull { it.name == name } }
            ?.distinct().orEmpty()
        // Always a complete permutation, even if storage is stale (e.g. a group was added later).
        return parsed + PulseSettings.DEFAULT_PRIORITY_ORDER.filterNot { it in parsed }
    }
}
