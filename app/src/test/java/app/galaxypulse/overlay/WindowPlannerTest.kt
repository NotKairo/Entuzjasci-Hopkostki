package app.galaxypulse.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowPlannerTest {

    private val display = DisplayInfo(1080, 2340, 2.8125f, 110, listOf(PxRect(500, 24, 580, 100)))
    private val layout = OverlayGeometry.layout(display, GeometryPrefs(), SizeDp(232f, 40f), SizeDp(388f, 214f))
    private val pad = 34

    @Test
    fun compactWindowHugsThePillPlusPadding() {
        val r = WindowPlanner.required(layout, expanded = false, padPx = pad, screenWidth = 1080, screenHeight = 2340)
        assertEquals(layout.compact.left - pad, r.left)
        assertEquals(layout.compact.right + pad, r.right)
        assertEquals(layout.compact.bottom + pad, r.bottom)
    }

    @Test
    fun expandedWindowReachesTheTopEdgeWhenTheBackdropDoes() {
        val r = WindowPlanner.required(layout, expanded = true, padPx = pad, screenWidth = 1080, screenHeight = 2340)
        assertEquals(0, r.top)
        assertTrue(r.contains(WindowPlanner.required(layout, false, pad, 1080, 2340)))
    }

    @Test
    fun windowNeverLeavesTheScreen() {
        val r = WindowPlanner.required(layout, expanded = true, padPx = 10_000, screenWidth = 1080, screenHeight = 2340)
        assertEquals(0, r.left)
        assertEquals(1080, r.right)
        assertEquals(0, r.top)
        assertEquals(2340, r.bottom)
    }

    @Test
    fun firstWindowIsExactlyWhatIsRequired() {
        val need = WindowPlanner.required(layout, false, pad, 1080, 2340)
        val plan = WindowPlanner.plan(null, need)
        assertEquals(need, plan.apply)
        assertNull(plan.shrinkTo)
    }

    @Test
    fun growingIsImmediateAndShrinkingIsDeferred() {
        val compact = WindowPlanner.required(layout, false, pad, 1080, 2340)
        val expanded = WindowPlanner.required(layout, true, pad, 1080, 2340)

        val grow = WindowPlanner.plan(compact, expanded)
        assertEquals(expanded, grow.apply)
        assertNull(grow.shrinkTo)

        val collapse = WindowPlanner.plan(expanded, compact)
        assertEquals("stays big while the collapse animation runs", expanded, collapse.apply)
        assertEquals(compact, collapse.shrinkTo)
    }

    @Test
    fun switchingBetweenDifferentSizedPillsUsesTheUnion() {
        val narrow = WindowPlanner.required(
            OverlayGeometry.layout(display, GeometryPrefs(), SizeDp(148f, 40f), SizeDp(388f, 176f)), false, pad, 1080, 2340,
        )
        val wide = WindowPlanner.required(
            OverlayGeometry.layout(display, GeometryPrefs(), SizeDp(280f, 40f), SizeDp(388f, 176f)), false, pad, 1080, 2340,
        )
        val plan = WindowPlanner.plan(narrow, wide)
        assertTrue(plan.apply.contains(narrow) && plan.apply.contains(wide))
        // Going back narrow -> wide -> narrow keeps the larger window until the shrink fires.
        val back = WindowPlanner.plan(plan.apply, narrow)
        assertEquals(plan.apply, back.apply)
        assertEquals(narrow, back.shrinkTo)
    }

    @Test
    fun noShrinkIsScheduledWhenAlreadyTight() {
        val need = WindowPlanner.required(layout, false, pad, 1080, 2340)
        assertNull(WindowPlanner.plan(need, need).shrinkTo)
    }
}
