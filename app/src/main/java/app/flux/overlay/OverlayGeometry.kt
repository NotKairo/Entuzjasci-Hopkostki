package app.flux.overlay

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Plain pixel rectangle (android.graphics.Rect is not available to JVM unit tests). */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** What the platform tells us about the display. Nothing here is hard-coded per device. */
data class DisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    /** Bottom edge of the status-bar inset (ignoring visibility), 0 if unknown. */
    val statusBarBottomPx: Int,
    /** Every bounding rect reported by `DisplayCutout`, in screen coordinates. */
    val cutoutRects: List<PxRect> = emptyList(),
) {
    val isLandscape: Boolean get() = widthPx > heightPx

    /**
     * Lowest y covered by a cutout in the top band. Punch-hole bounding rects do not always start at
     * y=0, so "top cutout" means "starts inside the status-bar band" (or touches the edge when there
     * is no status bar). Rects on the left/right edge (landscape) or the bottom edge do not count.
     * 0 when the display has no top cutout.
     */
    val topCutoutBottomPx: Int
        get() {
            val bandBottom = max(TOP_EDGE_TOLERANCE_PX, statusBarBottomPx)
            return cutoutRects
                .filter { it.top <= bandBottom && it.width > 0 && it.height > 0 }
                .maxOfOrNull { it.bottom } ?: 0
        }

    /** Top of the area we may put interactive UI in: below both the status-bar band and the cutout. */
    val safeTopPx: Int get() = max(statusBarBottomPx, topCutoutBottomPx)

    private companion object {
        const val TOP_EDGE_TOLERANCE_PX = 2
    }
}

data class GeometryPrefs(
    /** 0.8 .. 1.3 */
    val sizeScale: Float = 1f,
    /** Extra distance below the safe area. Negative values are clamped: we never go above safeTop. */
    val verticalOffsetDp: Int = 0,
    val horizontalOffsetDp: Int = 0,
    /** Let the expanded card's artwork backdrop reach the very top of the screen. */
    val extendBackdropToTopEdge: Boolean = true,
)

data class SizeDp(val width: Float, val height: Float)

/** Where something sits on screen, in pixels. */
data class PillFrame(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    val centerX: Float get() = left + width / 2f
}

data class OverlayLayout(
    val compact: PillFrame,
    val expanded: PillFrame,
    /** y at which the expanded card's artwork backdrop starts (0 = screen top edge). */
    val backdropTopPx: Int,
    val safeTopPx: Int,
)

object OverlayGeometry {
    const val SCREEN_MARGIN_DP = 12f
    const val GAP_BELOW_SAFE_AREA_DP = 6f

    /**
     * Computes where the compact pill and the expanded card go.
     *
     * The pill is centred horizontally (plus the user's offset) and placed *below* the status-bar
     * band and below any top cutout, so it can never collide with the camera or the status
     * indicators. The camera itself is never drawn or wrapped.
     */
    fun layout(
        display: DisplayInfo,
        prefs: GeometryPrefs,
        compactSize: SizeDp,
        expandedSize: SizeDp,
    ): OverlayLayout {
        val d = display.density
        val margin = (SCREEN_MARGIN_DP * d).roundToInt()
        val availableWidth = max(display.widthPx - 2 * margin, 1)
        val scale = prefs.sizeScale.coerceIn(0.7f, 1.4f)

        val safeTop = display.safeTopPx
        val top = max(
            safeTop,
            safeTop + (GAP_BELOW_SAFE_AREA_DP * d).roundToInt() + (prefs.verticalOffsetDp * d).roundToInt(),
        )

        val compactW = min((compactSize.width * d * scale).roundToInt(), availableWidth)
        val compactH = (compactSize.height * d * scale).roundToInt()
        val expandedW = min((expandedSize.width * d * scale).roundToInt(), availableWidth)
        val maxExpandedH = max(display.heightPx - top - margin, compactH)
        val expandedH = min((expandedSize.height * d * scale).roundToInt(), maxExpandedH)

        val centerX = display.widthPx / 2f + prefs.horizontalOffsetDp * d
        fun leftFor(width: Int): Int {
            val raw = (centerX - width / 2f).roundToInt()
            val maxLeft = max(display.widthPx - margin - width, margin)
            return raw.coerceIn(margin, maxLeft)
        }

        return OverlayLayout(
            compact = PillFrame(leftFor(compactW), top, compactW, compactH),
            expanded = PillFrame(leftFor(expandedW), top, expandedW, expandedH),
            backdropTopPx = if (prefs.extendBackdropToTopEdge) 0 else top,
            safeTopPx = safeTop,
        )
    }
}
