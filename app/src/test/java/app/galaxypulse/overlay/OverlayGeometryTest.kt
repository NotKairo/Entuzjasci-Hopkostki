package app.galaxypulse.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {

    private val compactDp = SizeDp(232f, 40f)
    private val expandedDp = SizeDp(388f, 200f)

    /** Roughly a Galaxy S24-class panel: 1080x2340 @ 2.8125 density, centred hole, ~110px status bar. */
    private fun centredHoleDisplay(statusBar: Int = 110, holeBottom: Int = 100) = DisplayInfo(
        widthPx = 1080, heightPx = 2340, density = 2.8125f, statusBarBottomPx = statusBar,
        cutoutRects = listOf(PxRect(500, 24, 580, holeBottom)),
    )

    @Test
    fun noCutoutMeansSafeAreaIsJustTheStatusBar() {
        val d = DisplayInfo(1080, 2340, 2.8125f, statusBarBottomPx = 90)
        assertEquals(0, d.topCutoutBottomPx)
        assertEquals(90, d.safeTopPx)
    }

    @Test
    fun pillSitsBelowBothStatusBarAndCutout() {
        val display = centredHoleDisplay(statusBar = 110, holeBottom = 160) // cutout taller than status bar
        val layout = OverlayGeometry.layout(display, GeometryPrefs(), compactDp, expandedDp)
        assertEquals(160, layout.safeTopPx)
        assertTrue("compact top ${layout.compact.top} must clear the cutout", layout.compact.top > 160)
        assertEquals(layout.compact.top, layout.expanded.top)
    }

    @Test
    fun pillIsHorizontallyCentredOnTheScreen() {
        val layout = OverlayGeometry.layout(centredHoleDisplay(), GeometryPrefs(), compactDp, expandedDp)
        assertEquals(540f, layout.compact.centerX, 1f)
        assertEquals(540f, layout.expanded.centerX, 1f)
    }

    @Test
    fun anOffCentreCutoutDoesNotShiftTheCentredPill() {
        // S10-style hole near the right corner.
        val display = DisplayInfo(1080, 2280, 2.8125f, 90, listOf(PxRect(900, 20, 990, 105)))
        val layout = OverlayGeometry.layout(display, GeometryPrefs(), compactDp, expandedDp)
        assertEquals(540f, layout.compact.centerX, 1f)
        assertTrue(layout.compact.top >= 105)
    }

    @Test
    fun cutoutsOnOtherEdgesAreIgnored() {
        val display = DisplayInfo(
            2340, 1080, 2.8125f, 0,
            cutoutRects = listOf(PxRect(0, 400, 100, 480), PxRect(1000, 1000, 1100, 1080)),
        )
        assertEquals(0, display.topCutoutBottomPx)
    }

    @Test
    fun negativeVerticalOffsetNeverGoesAboveTheSafeArea() {
        val display = centredHoleDisplay()
        val layout = OverlayGeometry.layout(display, GeometryPrefs(verticalOffsetDp = -200), compactDp, expandedDp)
        assertEquals(display.safeTopPx, layout.compact.top)
    }

    @Test
    fun positiveVerticalOffsetMovesThePillDown() {
        val display = centredHoleDisplay()
        val base = OverlayGeometry.layout(display, GeometryPrefs(), compactDp, expandedDp)
        val moved = OverlayGeometry.layout(display, GeometryPrefs(verticalOffsetDp = 20), compactDp, expandedDp)
        assertEquals(base.compact.top + 56, moved.compact.top) // 20dp * 2.8125 = 56.25px
    }

    @Test
    fun horizontalOffsetShiftsThePillAndStaysOnScreen() {
        val display = centredHoleDisplay()
        val shifted = OverlayGeometry.layout(display, GeometryPrefs(horizontalOffsetDp = 40), compactDp, expandedDp)
        assertTrue(shifted.compact.centerX > 540f)
        val extreme = OverlayGeometry.layout(display, GeometryPrefs(horizontalOffsetDp = 5000), compactDp, expandedDp)
        assertTrue(extreme.compact.right <= display.widthPx)
        assertTrue(extreme.compact.left >= 0)
    }

    @Test
    fun narrowScreensClampWidthsToTheScreenMargin() {
        val tiny = DisplayInfo(720, 1520, 2f, 60)
        val layout = OverlayGeometry.layout(tiny, GeometryPrefs(), compactDp, expandedDp)
        val margin = (OverlayGeometry.SCREEN_MARGIN_DP * 2f).toInt()
        assertTrue(layout.expanded.width <= 720 - 2 * margin)
        assertTrue(layout.expanded.left >= margin)
        assertTrue(layout.expanded.right <= 720 - margin)
    }

    @Test
    fun sizeScaleScalesDimensions() {
        val display = centredHoleDisplay()
        val normal = OverlayGeometry.layout(display, GeometryPrefs(sizeScale = 1f), compactDp, expandedDp)
        val small = OverlayGeometry.layout(display, GeometryPrefs(sizeScale = 0.8f), compactDp, expandedDp)
        assertTrue(small.compact.width < normal.compact.width)
        assertTrue(small.compact.height < normal.compact.height)
    }

    @Test
    fun backdropReachesTheTopEdgeOnlyWhenEnabled() {
        val display = centredHoleDisplay()
        val on = OverlayGeometry.layout(display, GeometryPrefs(extendBackdropToTopEdge = true), compactDp, expandedDp)
        val off = OverlayGeometry.layout(display, GeometryPrefs(extendBackdropToTopEdge = false), compactDp, expandedDp)
        assertEquals(0, on.backdropTopPx)
        assertEquals(off.expanded.top, off.backdropTopPx)
    }

    @Test
    fun expandedCardNeverRunsOffTheBottom() {
        val shortDisplay = DisplayInfo(1080, 700, 2.8125f, 110)
        val layout = OverlayGeometry.layout(shortDisplay, GeometryPrefs(), compactDp, SizeDp(388f, 900f))
        assertTrue(layout.expanded.bottom <= 700)
    }

    @Test
    fun landscapeIsDetected() {
        assertTrue(DisplayInfo(2340, 1080, 2.8125f, 0).isLandscape)
        assertFalse(DisplayInfo(1080, 2340, 2.8125f, 0).isLandscape)
    }
}
