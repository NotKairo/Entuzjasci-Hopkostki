package app.galaxypulse.overlay

import kotlin.math.max
import kotlin.math.min

data class WindowRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun union(o: WindowRect) = WindowRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    fun contains(o: WindowRect) = left <= o.left && top <= o.top && right >= o.right && bottom >= o.bottom
}

/**
 * Sizes the overlay window. A transparent window still swallows touches inside its frame, so the window
 * must hug what is visible. It grows *immediately* when something needs more room (so a spring never
 * gets clipped) and shrinks back only after the motion has settled.
 */
object WindowPlanner {

    /** Smallest window that holds the pill (plus [padPx] for spring overshoot) in the given state. */
    fun required(layout: OverlayLayout, expanded: Boolean, padPx: Int, screenWidth: Int, screenHeight: Int): WindowRect {
        val frame = if (expanded) layout.expanded else layout.compact
        val top = if (expanded) layout.backdropTopPx else frame.top
        return WindowRect(
            left = max(0, frame.left - padPx),
            top = max(0, top - padPx),
            right = min(screenWidth, frame.right + padPx),
            bottom = min(screenHeight, frame.bottom + padPx),
        )
    }

    data class Plan(val apply: WindowRect, val shrinkTo: WindowRect?)

    /**
     * @param current the window as currently applied (null when none is attached yet)
     * @param required what the present state needs
     * @return what to apply now, and — if that is larger than needed — the rect to shrink to later.
     */
    fun plan(current: WindowRect?, required: WindowRect): Plan {
        if (current == null) return Plan(required, null)
        val grown = if (current.contains(required)) current else current.union(required)
        return Plan(apply = grown, shrinkTo = required.takeIf { it != grown })
    }
}
