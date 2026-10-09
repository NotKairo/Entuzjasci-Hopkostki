package app.flux.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PillShapeMathTest {

    @Test
    fun progressIsZeroWhenCompactAndOneWhenExpandedAndClamped() {
        assertEquals(0f, PillShapeMath.progress(100f, 100f, 600f), 0.0001f)
        assertEquals(1f, PillShapeMath.progress(600f, 100f, 600f), 0.0001f)
        assertEquals(0.5f, PillShapeMath.progress(350f, 100f, 600f), 0.0001f)
        // Spring overshoot must not push content alpha out of range.
        assertEquals(1f, PillShapeMath.progress(650f, 100f, 600f), 0.0001f)
        assertEquals(0f, PillShapeMath.progress(60f, 100f, 600f), 0.0001f)
    }

    @Test
    fun degenerateSpanDoesNotDivideByZero() {
        assertEquals(1f, PillShapeMath.progress(100f, 100f, 100f), 0f)
        assertEquals(0f, PillShapeMath.progress(50f, 100f, 100f), 0f)
    }

    @Test
    fun compactShapeIsAFullPill() {
        val r = PillShapeMath.radii(0f, frameWidth = 600f, frameHeight = 112f, compactFrameHeight = 112f, expandedTopRadius = 0f, expandedBottomRadius = 100f)
        assertEquals(56f, r.top, 0.001f)
        assertEquals(56f, r.bottom, 0.001f)
    }

    @Test
    fun expandedShapeReachesTheTargetRadii() {
        val r = PillShapeMath.radii(1f, 1000f, 600f, 112f, expandedTopRadius = 0f, expandedBottomRadius = 100f)
        assertEquals(0f, r.top, 0.001f)
        assertEquals(100f, r.bottom, 0.001f)
    }

    @Test
    fun radiiNeverExceedHalfTheShortestSideWhileMorphing() {
        for (i in 0..20) {
            val p = i / 20f
            val h = PillShapeMath.lerp(112f, 600f, p)
            val w = PillShapeMath.lerp(650f, 1000f, p)
            val r = PillShapeMath.radii(p, w, h, 112f, 40f, 100f)
            val cap = minOf(w, h) / 2f
            assertTrue(r.top <= cap + 0.001f)
            assertTrue(r.bottom <= cap + 0.001f)
            assertTrue(r.top >= 0f && r.bottom >= 0f)
        }
    }

    @Test
    fun smoothstepIsMonotonicAndBounded() {
        assertEquals(0f, PillShapeMath.smoothstep(0.2f, 0.8f, 0f), 0f)
        assertEquals(1f, PillShapeMath.smoothstep(0.2f, 0.8f, 1f), 0f)
        assertEquals(0.5f, PillShapeMath.smoothstep(0.2f, 0.8f, 0.5f), 0.0001f)
        var last = -1f
        for (i in 0..50) {
            val v = PillShapeMath.smoothstep(0.1f, 0.9f, i / 50f)
            assertTrue(v >= last)
            last = v
        }
    }
}
