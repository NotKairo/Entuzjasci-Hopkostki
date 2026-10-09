package app.flux.ui.app

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
import app.flux.AppGraph
import app.flux.settings.FluxSettings
import app.flux.ui.design.FluxColors
import app.flux.ui.design.FluxIcon
import app.flux.ui.design.FluxIconView
import app.flux.ui.design.FluxType
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: FluxIcon) {
    Setup("Setup", FluxIcon.Flux),
    Preview("Preview", FluxIcon.Sliders),
    Timers("Timers", FluxIcon.Timer),
    Settings("Settings", FluxIcon.Gear),
    Diagnostics("Test", FluxIcon.Info),
}

/** Everything a screen needs to read and change settings. */
class SettingsHandle(private val graph: AppGraph) {
    fun update(transform: (FluxSettings) -> FluxSettings) {
        graph.appScope.launch { graph.settingsRepo.update(transform) }
    }
}

@Composable
fun FluxAppRoot(graph: AppGraph) {
    var tab by rememberSaveable { mutableStateOf(Tab.Setup) }
    val settings by graph.settings.collectAsStateWithLifecycle()
    val handle = androidx.compose.runtime.remember(graph) { SettingsHandle(graph) }

    Scaffold(
        containerColor = FluxColors.Black,
        bottomBar = {
            NavigationBar(containerColor = FluxColors.Surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {
                            FluxIconView(entry.icon, if (tab == entry) FluxColors.OnSurface else FluxColors.OnSurfaceMuted, size = 22.dp)
                        },
                        label = { Text(entry.label, style = FluxType.Label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = FluxColors.Blue.copy(alpha = 0.30f),
                            selectedTextColor = FluxColors.OnSurface,
                            unselectedTextColor = FluxColors.OnSurfaceMuted,
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
