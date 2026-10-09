package app.flux

import android.app.Application

class FluxApp : Application() {
    /** Created on first use; see [AppGraph]. */
    val graph: AppGraph by lazy { AppGraph(this) }
}
