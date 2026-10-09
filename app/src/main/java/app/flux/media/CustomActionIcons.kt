package app.flux.media

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

/**
 * Loads the icon a media app published for one of its custom actions (usually shuffle / repeat).
 * The drawable lives in the *media app's* resources, so a package context is needed; if the package
 * is not visible or the resource is gone, the caller falls back to the action's text label.
 */
object CustomActionIcons {
    private val cache = LruCache<String, ImageBitmap>(24)

    fun peek(action: MediaCustomAction): ImageBitmap? = cache.get(key(action))

    fun load(context: Context, action: MediaCustomAction): ImageBitmap? {
        val k = key(action)
        cache.get(k)?.let { return it }
        return try {
            val pkgContext = context.createPackageContext(action.packageName, 0)
            val drawable = ContextCompat.getDrawable(pkgContext, action.iconResId) ?: return null
            val bitmap = drawable.mutate().toBitmap(64, 64).asImageBitmap()
            cache.put(k, bitmap)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun key(a: MediaCustomAction) = "${a.packageName}/${a.iconResId}"
}
