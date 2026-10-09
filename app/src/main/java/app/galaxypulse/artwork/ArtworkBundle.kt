package app.galaxypulse.artwork

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

/** Restrained colours derived from a cover (see [ColorMath]). */
@Immutable
class ArtworkPalette(val background: Color, val accent: Color)

/**
 * Everything the UI needs from one cover, produced once off the main thread. The *same* decoded
 * source feeds the sharp thumbnail and the pre-blurred backdrop, so they can never disagree.
 * Identity is the [key]; two bundles with the same key are interchangeable (keeps crossfades from
 * firing when the same cover is delivered twice).
 */
@Immutable
class ArtworkBundle(
    val key: String,
    val thumb: ImageBitmap,
    val backdrop: ImageBitmap,
    val palette: ArtworkPalette,
) {
    override fun equals(other: Any?): Boolean = other is ArtworkBundle && other.key == key
    override fun hashCode(): Int = key.hashCode()
}
