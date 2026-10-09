package app.galaxypulse.overlay

import android.content.Context
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.max

/**
 * Reads the display geometry from the platform — screen size, status-bar height and the display
 * cutout(s) — through a window context. Nothing is assumed about a particular Galaxy model.
 */
object DisplayInfoReader {

    fun read(windowContext: Context): DisplayInfo {
        val wm = windowContext.getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        val insets = wm.currentWindowMetrics.windowInsets

        val statusFromInsets = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val statusBar = if (statusFromInsets > 0) statusFromInsets else statusBarResourceHeight(windowContext)

        // The window's own insets know about the cutout once attached; the display always does.
        val cutout = insets.displayCutout ?: try {
            windowContext.display?.cutout
        } catch (_: UnsupportedOperationException) {
            null
        }
        val rects = cutout?.boundingRects.orEmpty().map { PxRect(it.left, it.top, it.right, it.bottom) }

        return DisplayInfo(
            widthPx = bounds.width(),
            heightPx = bounds.height(),
            density = windowContext.resources.displayMetrics.density,
            statusBarBottomPx = statusBar,
            cutoutRects = rects,
        )
    }

    /** Height of the system navigation bar / gesture area, so the bottom card clears it. */
    fun navigationBarInset(windowContext: Context): Int {
        val wm = windowContext.getSystemService(WindowManager::class.java)
        return wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
    }

    private fun statusBarResourceHeight(context: Context): Int {
        val res = context.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    /** A one-line description for Diagnostics. */
    fun describe(info: DisplayInfo): String =
        "${info.widthPx}×${info.heightPx}px @${"%.2f".format(info.density)}x, status bar ${info.statusBarBottomPx}px, " +
            "cutouts ${info.cutoutRects.ifEmpty { null }?.joinToString { "[${it.left},${it.top} ${it.width}×${it.height}]" } ?: "none"}, " +
            "safe top ${info.safeTopPx}px" + if (max(info.widthPx, info.heightPx) == info.widthPx) " (landscape)" else ""
}
