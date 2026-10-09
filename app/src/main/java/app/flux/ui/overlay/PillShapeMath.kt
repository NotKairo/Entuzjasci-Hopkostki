package app.flux.ui.overlay

import kotlin.math.min

/**
 * Pure maths behind the pill's morph: how "expanded" the shape currently is, and the corner radii
 * that go with it. Radii are derived from the live animated size, so the corners evolve with the
 * pill's dimensions instead of being animated separately (and can never exceed half the shortest side).
 */
object PillShapeMath {

    /** 0 = compact, 1 = fully expanded, derived from the animated frame height. */
    fun progress(frameHeight: Float, compactFrameHeight: Float, expandedFrameHeight: Float): Float {
        val span = expandedFrameHeight - compactFrameHeight
        if (span <= 1f) return if (frameHeight >= expandedFrameHeight) 1f else 0f
        return ((frameHeight - compactFrameHeight) / span).coerceIn(0f, 1f)
    }

    data class Radii(val top: Float, val bottom: Float)

    fun radii(
        progress: Float,
        frameWidth: Float,
        frameHeight: Float,
        compactFrameHeight: Float,
        expandedTopRadius: Float,
        expandedBottomRadius: Float,
    ): Radii {
        val cap = min(frameWidth, frameHeight) / 2f
        val compactRadius = compactFrameHeight / 2f
        return Radii(
            top = lerp(compactRadius, expandedTopRadius, progress).coerceIn(0f, cap),
            bottom = lerp(compactRadius, expandedBottomRadius, progress).coerceIn(0f, cap),
        )
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** 0 below [edge0], 1 above [edge1], smooth in between — used to stagger content in and out. */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
