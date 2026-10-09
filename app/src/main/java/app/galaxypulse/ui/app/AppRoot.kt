package app.galaxypulse.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.galaxypulse.AppGraph
import app.galaxypulse.settings.PulseSettings
import app.galaxypulse.ui.design.PulseColors
import app.galaxypulse.ui.design.PulseIcon
import app.galaxypulse.ui.design.PulseIconView
import app.galaxypulse.ui.design.PulseType
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: PulseIcon) {
    Setup("Setup", PulseIcon.Pulse),
    Preview("Preview", PulseIcon.Sliders),
    Timers("Timers", PulseIcon.Timer),
    Settings("Settings", PulseIcon.Gear),
    Diagnostics("Test", PulseIcon.Info),
}

/** Everything a screen needs to read and change settings. */
class SettingsHandle(private val graph: AppGraph) {
    fun update(transform: (PulseSettings) -> PulseSettings) {
        graph.appScope.launch { graph.settingsRepo.update(transform) }
    }
}

@Composable
fun PulseAppRoot(graph: AppGraph) {
    var tab by rememberSaveable { mutableStateOf(Tab.Setup) }
    val settings by graph.settings.collectAsStateWithLifecycle()
    val handle = androidx.compose.runtime.remember(graph) { SettingsHandle(graph) }

    Scaffold(
        containerColor = PulseColors.Black,
        bottomBar = {
            NavigationBar(containerColor = PulseColors.Surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {
                            PulseIconView(entry.icon, if (tab == entry) PulseColors.OnSurface else PulseColors.OnSurfaceMuted, size = 22.dp)
                        },
                        label = { Text(entry.label, style = PulseType.Label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = PulseColors.Blue.copy(alpha = 0.30f),
                            selectedTextColor = PulseColors.OnSurface,
                            unselectedTextColor = PulseColors.OnSurfaceMuted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Setup -> SetupScreen(graph, settings, handle)
                Tab.Preview -> PreviewScreen(graph, settings, handle)
                Tab.Timers -> TimersScreen(graph)
                Tab.Settings -> SettingsScreen(graph, settings, handle)
                Tab.Diagnostics -> DiagnosticsScreen(graph, settings, handle)
            }
        }
    }
}
