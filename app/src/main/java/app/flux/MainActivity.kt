package app.flux

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.flux.ui.app.FluxAppRoot
import app.flux.ui.design.FluxTheme

/** Settings, onboarding, live preview, timers and diagnostics. The overlay itself lives in a service. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as FluxApp).graph
        setContent {
            FluxTheme {
                FluxAppRoot(graph)
            }
        }
    }
}
