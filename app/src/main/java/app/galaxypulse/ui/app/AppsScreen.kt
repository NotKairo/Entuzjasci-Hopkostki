package app.galaxypulse.ui.app

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.galaxypulse.settings.PulseSettings
import app.galaxypulse.ui.design.PulseColors
import app.galaxypulse.ui.design.PulseType
import app.galaxypulse.ui.overlay.AppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppRow(val packageName: String, val label: String)

/**
 * Per-app rules: show notifications from the app, show their text, and follow its music. Lists apps
 * with a launcher icon (what the manifest's package-visibility query allows) — not every package.
 */
@Composable
fun AppsScreen(settings: PulseSettings, handle: SettingsHandle, onBack: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps by produceState(emptyList<AppRow>()) {
        value = withContext(Dispatchers.Default) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { AppRow(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }
    val shown = apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Back", onBack)
            ScreenTitle("Per-app rules")
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search apps", style = PulseType.Body, color = PulseColors.OnSurfaceFaint) },
        )
        Text(
            "Notifications: show or hide an app, or show only its name. Music: stop following an app's player.",
            style = PulseType.Caption, color = PulseColors.OnSurfaceMuted,
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.packageName }) { app ->
                val blocked = app.packageName in settings.blockedNotificationApps
                val hidesText = app.packageName in settings.hideContentApps
                val ignoredMusic = app.packageName in settings.ignoredMediaApps
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(app.packageName, app.label, 36.dp)
                        Text(app.label, style = PulseType.Body, color = PulseColors.OnSurface)
                    }
                    SettingSwitch("Show notifications", null, !blocked) { show ->
                        handle.update { it.copy(blockedNotificationApps = if (show) it.blockedNotificationApps - app.packageName else it.blockedNotificationApps + app.packageName) }
                    }
                    SettingSwitch("Show message text", "Off: only the app name is shown", !hidesText, enabled = !blocked) { show ->
                        handle.update { it.copy(hideContentApps = if (show) it.hideContentApps - app.packageName else it.hideContentApps + app.packageName) }
                    }
                    SettingSwitch("Follow this app's music", null, !ignoredMusic) { follow ->
                        handle.update { it.copy(ignoredMediaApps = if (follow) it.ignoredMediaApps - app.packageName else it.ignoredMediaApps + app.packageName) }
                    }
                }
                HorizontalDivider(color = PulseColors.Stroke)
            }
        }
    }
}
