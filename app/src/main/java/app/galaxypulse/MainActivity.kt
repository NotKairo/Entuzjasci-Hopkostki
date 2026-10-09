package app.galaxypulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.galaxypulse.ui.app.PulseAppRoot
import app.galaxypulse.ui.design.GalaxyPulseTheme

/** Settings, onboarding, live preview, timers and diagnostics. The overlay itself lives in a service. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as PulseApp).graph
        setContent {
            GalaxyPulseTheme {
                PulseAppRoot(graph)
            }
        }
    }
}
