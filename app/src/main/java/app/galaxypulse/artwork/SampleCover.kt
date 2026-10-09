package app.galaxypulse.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * Generates a cover from a few colours — layered gradients, nothing copyrighted. Used by the in-app
 * preview and the Diagnostics simulator, so both exercise the same artwork pipeline real covers use.
 */
object SampleCover {
    fun render(colors: IntArray, size: Int = 512): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), colors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = RadialGradient(size * 0.7f, size * 0.3f, size * 0.45f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(size * 0.7f, size * 0.3f, size * 0.45f, paint)
        paint.shader = null
        paint.color = 0x22000000
        canvas.drawCircle(size * 0.25f, size * 0.8f, size * 0.3f, paint)
        return bitmap
    }
}
