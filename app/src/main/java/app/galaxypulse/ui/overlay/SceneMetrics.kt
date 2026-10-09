package app.galaxypulse.ui.overlay

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import app.galaxypulse.ui.anim.PulseFrameState

/**
 * Pixel dimensions a scene lays itself out against.
 *
 * "Content origin" is the screen position where compact content starts. The visible shape may extend
 * *above* it (the artwork backdrop reaching the top edge), but text and controls always start at the
 * content origin, i.e. below the status-bar band.
 */
@Immutable
internal data class SceneDims(
    val compactW: Float,
    val compactH: Float,
    val expandedW: Float,
    val expandedH: Float,
    /** Height of the visible shape when compact / fully expanded (includes any backdrop above the content origin). */
    val compactFrameH: Float,
    val expandedFrameH: Float,
    /** Screen y of the content origin. */
    val contentOriginY: Float,
    val density: Float,
    val scale: Float,
)

/**
 * Live view onto the animated frame. Everything here is read from layout/draw lambdas only, so
 * animating the pill never recomposes the scenes.
 */
@Stable
internal class SceneMetrics(val frame: PulseFrameState, val dims: SceneDims) {
    val frameW: Float get() = frame.width.value

    /** 0 = compact … 1 = fully expanded, derived from the animated height. */
    val progress: Float
        get() = PillShapeMath.progress(frame.height.value, dims.compactFrameH, dims.expandedFrameH)

    /** Vertical offset of the content origin inside the frame (0 when compact, the top inset when expanded to the edge). */
    val offsetY: Float get() = dims.contentOriginY - frame.top.value

    val compactLeft: Float get() = (frameW - dims.compactW) / 2f
    val expandedLeft: Float get() = (frameW - dims.expandedW) / 2f

    /** dp -> px including the user's size scale. */
    fun dp(value: Float): Float = value * dims.density * dims.scale
}
