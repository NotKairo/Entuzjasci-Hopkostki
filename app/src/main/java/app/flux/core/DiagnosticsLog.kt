package app.flux.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory ring buffer of recent events for the Diagnostics screen. Never persisted. */
class DiagnosticsLog(private val capacity: Int = 150) {
    private val format = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun log(message: String) {
        val line = "${format.format(Date())}  $message"
        _lines.value = (listOf(line) + _lines.value).take(capacity)
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
