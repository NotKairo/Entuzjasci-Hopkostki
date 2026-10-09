package app.galaxypulse

import android.app.Application

class PulseApp : Application() {
    /** Created on first use; see [AppGraph]. */
    val graph: AppGraph by lazy { AppGraph(this) }
}
