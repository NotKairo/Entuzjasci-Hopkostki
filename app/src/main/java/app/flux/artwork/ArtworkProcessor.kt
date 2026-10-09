package app.flux.artwork

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Turns whatever cover a media session offers into an [ArtworkBundle]: a sharp thumbnail, a
 * pre-blurred backdrop and a restrained palette — all from one decode, all off the main thread, and
 * cached so a repeated track never decodes twice.
 *
 * Only local sources are read (the session's own bitmap, or `content://`, `file://`,
 * `android.resource://` URIs). Network URLs are deliberately ignored: the app has no INTERNET
 * permission and the core experience stays local.
 */
class ArtworkProcessor(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val cache = object : LruCache<String, ArtworkBundle>(CACHE_ENTRIES) {}

    /** Cheap lookup so callers can skip all work for a cover they've already processed. */
    fun cached(key: String): ArtworkBundle? = cache.get(key)

    /**
     * @param key identity of this cover (callers derive it from track + bitmap generation / URI)
     * @return the bundle, or null when the source can't be read — the UI then shows the gradient fallback.
     */
    suspend fun process(key: String, bitmap: Bitmap?, uri: Uri?): ArtworkBundle? {
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            val decoded = try {
                when {
                    bitmap != null && !bitmap.isRecycled -> downscaleCopy(bitmap, THUMB_SIZE)
                    uri != null && uri.scheme in LOCAL_SCHEMES -> decode(uri)
                    else -> null
                }
            } catch (_: Exception) {
                null
            } ?: return@withContext null

            val bundle = build(key, decoded)
            decoded.recycle()
            cache.put(key, bundle)
            bundle
        }
    }

    private fun build(key: String, square: Bitmap): ArtworkBundle {
        // The thumbnail owns its pixels, so the source can be recycled by the caller afterwards.
        // (Copied first: for a cover that is already tiny, the "scaled" bitmap below can be the same instance.)
        val thumb = square.copy(Bitmap.Config.ARGB_8888, false)

        // Palette + backdrop come from a tiny version of the same pixels as the thumbnail.
        val tiny = Bitmap.createScaledBitmap(square, TINY_SIZE, TINY_SIZE, true)
        val pixels = IntArray(TINY_SIZE * TINY_SIZE)
        tiny.getPixels(pixels, 0, TINY_SIZE, 0, 0, TINY_SIZE, TINY_SIZE)
        val blurred = ImageBlur.boxBlur(pixels, TINY_SIZE, TINY_SIZE, radius = 3, passes = 2)
        val backdrop = Bitmap.createBitmap(blurred, TINY_SIZE, TINY_SIZE, Bitmap.Config.ARGB_8888)

        val palette = Palette.from(tiny).maximumColorCount(12).generate()
        val base = palette.dominantSwatch ?: palette.mutedSwatch ?: palette.vibrantSwatch
        val vivid = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.mutedSwatch ?: base
        val bgSource = base?.rgb ?: FALLBACK_RGB
        val bg = ColorMath.restrainedBackground(bgSource)
        val accent = ColorMath.restrainedAccent(vivid?.rgb ?: bgSource, bg)
        if (tiny !== square) tiny.recycle()

        return ArtworkBundle(
            key = key,
            thumb = thumb.asImageBitmap(),
            backdrop = backdrop.asImageBitmap(),
            palette = ArtworkPalette(Color(bg), Color(accent)),
        )
    }

    private fun decode(uri: Uri): Bitmap? {
        val source = ImageDecoder.createSource(resolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > THUMB_SIZE) {
                val factor = THUMB_SIZE.toFloat() / longest
                decoder.setTargetSize(
                    max(1, (info.size.width * factor).toInt()),
                    max(1, (info.size.height * factor).toInt()),
                )
            }
        }
        return centerCropSquare(bitmap, THUMB_SIZE)
    }

    private fun downscaleCopy(src: Bitmap, target: Int): Bitmap {
        // Hardware bitmaps can't be read back; a software copy is made first in that case.
        val readable = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val result = centerCropSquare(readable, target)
        if (readable !== src && readable !== result) readable.recycle()
        return result
    }

    /** Crops the middle square (covers are often letterboxed or wider than tall) and scales it down. */
    private fun centerCropSquare(src: Bitmap, target: Int): Bitmap {
        val side = min(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val out = min(side, target)
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        val scaled = if (out == side) cropped else Bitmap.createScaledBitmap(cropped, out, out, true)
        if (cropped !== scaled && cropped !== src) cropped.recycle()
        return if (scaled === src) src.copy(Bitmap.Config.ARGB_8888, false) else scaled
    }

    private companion object {
        const val THUMB_SIZE = 256
        const val TINY_SIZE = 48
        const val CACHE_ENTRIES = 12
        const val FALLBACK_RGB = 0xFF3A3F66.toInt()
        val LOCAL_SCHEMES = setOf(ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE, ContentResolver.SCHEME_ANDROID_RESOURCE)
    }
}
