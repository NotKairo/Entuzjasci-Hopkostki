package app.galaxypulse.artwork

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * HSL colour maths used to turn whatever colours an album cover contains into a *restrained*
 * background tint and accent. Pure Kotlin so it can be unit-tested (android.graphics.Color is a stub
 * on the JVM).
 */
object ColorMath {

    /** Hue 0..360, saturation 0..1, lightness 0..1. */
    data class Hsl(val h: Float, val s: Float, val l: Float)

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a and 0xFF shl 24) or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)

    fun red(c: Int) = c shr 16 and 0xFF
    fun green(c: Int) = c shr 8 and 0xFF
    fun blue(c: Int) = c and 0xFF

    fun toHsl(color: Int): Hsl {
        val r = red(color) / 255f
        val g = green(color) / 255f
        val b = blue(color) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) / 2f
        val delta = maxC - minC
        if (delta < 1e-6f) return Hsl(0f, 0f, l)
        val s = delta / (1f - abs(2f * l - 1f))
        val h = when (maxC) {
            r -> ((g - b) / delta) % 6f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        } * 60f
        return Hsl(if (h < 0f) h + 360f else h, s.coerceIn(0f, 1f), l)
    }

    fun fromHsl(hsl: Hsl, alpha: Int = 255): Int {
        val h = ((hsl.h % 360f) + 360f) % 360f
        val s = hsl.s.coerceIn(0f, 1f)
        val l = hsl.l.coerceIn(0f, 1f)
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return argb(alpha, channel(r1), channel(g1), channel(b1))
    }

    /**
     * Dark, low-saturation tint for the blurred backdrop. Keeps the cover's hue (so the card feels
     * connected to the art) but never lets it get loud or bright.
     */
    fun restrainedBackground(color: Int): Int {
        val hsl = toHsl(color)
        return fromHsl(Hsl(hsl.h, min(hsl.s, MAX_BG_SATURATION), hsl.l.coerceIn(MIN_BG_LIGHTNESS, MAX_BG_LIGHTNESS)))
    }

    /**
     * Lighter, moderately saturated accent for the progress bar and equalizer. Perceived brightness
     * differs a lot between hues (a saturated red is "darker" than a yellow of equal HSL lightness),
     * so lightness is raised until the accent reaches [MIN_ACCENT_CONTRAST] against [background].
     */
    fun restrainedAccent(color: Int, background: Int = restrainedBackground(color)): Int {
        val hsl = toHsl(color)
        val s = hsl.s.coerceIn(MIN_ACCENT_SATURATION, MAX_ACCENT_SATURATION)
        var l = hsl.l.coerceIn(MIN_ACCENT_LIGHTNESS, MAX_ACCENT_LIGHTNESS)
        var accent = fromHsl(Hsl(hsl.h, s, l))
        while (contrast(accent, background) < MIN_ACCENT_CONTRAST && l < MAX_ACCENT_LIGHTNESS_LIMIT) {
            l += 0.03f
            accent = fromHsl(Hsl(hsl.h, s, l))
        }
        return accent
    }

    /** WCAG relative luminance, 0..1. */
    fun luminance(color: Int): Float {
        fun lin(v: Int): Float {
            val c = v / 255f
            return if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * lin(red(color)) + 0.7152f * lin(green(color)) + 0.0722f * lin(blue(color))
    }

    /** WCAG contrast ratio, 1..21. */
    fun contrast(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private const val MAX_BG_SATURATION = 0.55f
    private const val MIN_BG_LIGHTNESS = 0.10f
    private const val MAX_BG_LIGHTNESS = 0.24f
    private const val MIN_ACCENT_SATURATION = 0.35f
    private const val MAX_ACCENT_SATURATION = 0.80f
    private const val MIN_ACCENT_LIGHTNESS = 0.58f
    private const val MAX_ACCENT_LIGHTNESS = 0.74f
    private const val MAX_ACCENT_LIGHTNESS_LIMIT = 0.90f
    /** WCAG minimum for non-text UI components. */
    private const val MIN_ACCENT_CONTRAST = 3.2f
}
