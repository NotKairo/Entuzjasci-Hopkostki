package app.galaxypulse.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {

    private fun rgb(r: Int, g: Int, b: Int) = ColorMath.argb(255, r, g, b)

    @Test
    fun hslRoundTripIsStable() {
        for (c in listOf(rgb(200, 30, 60), rgb(10, 200, 90), rgb(40, 60, 220), rgb(128, 128, 128), rgb(255, 255, 0))) {
            val back = ColorMath.fromHsl(ColorMath.toHsl(c))
            assertEquals(ColorMath.red(c).toFloat(), ColorMath.red(back).toFloat(), 1.5f)
            assertEquals(ColorMath.green(c).toFloat(), ColorMath.green(back).toFloat(), 1.5f)
            assertEquals(ColorMath.blue(c).toFloat(), ColorMath.blue(back).toFloat(), 1.5f)
        }
    }

    @Test
    fun primaryHuesAreCorrect() {
        assertEquals(0f, ColorMath.toHsl(rgb(255, 0, 0)).h, 0.5f)
        assertEquals(120f, ColorMath.toHsl(rgb(0, 255, 0)).h, 0.5f)
        assertEquals(240f, ColorMath.toHsl(rgb(0, 0, 255)).h, 0.5f)
    }

    @Test
    fun greyHasNoSaturation() {
        assertEquals(0f, ColorMath.toHsl(rgb(90, 90, 90)).s, 0.001f)
    }

    @Test
    fun backgroundIsAlwaysDarkAndRestrained() {
        for (c in listOf(rgb(255, 0, 0), rgb(255, 255, 255), rgb(0, 255, 255), rgb(250, 240, 20))) {
            val bg = ColorMath.toHsl(ColorMath.restrainedBackground(c))
            assertTrue("lightness ${bg.l}", bg.l in 0.09f..0.25f)
            assertTrue("saturation ${bg.s}", bg.s <= 0.56f)
        }
    }

    @Test
    fun backgroundKeepsTheCoversHue() {
        val bg = ColorMath.toHsl(ColorMath.restrainedBackground(rgb(40, 60, 220)))
        assertEquals(ColorMath.toHsl(rgb(40, 60, 220)).h, bg.h, 3f)
    }

    @Test
    fun accentIsLightEnoughToReadOnTheDarkBackground() {
        for (c in listOf(rgb(20, 20, 20), rgb(180, 0, 0), rgb(30, 30, 160))) {
            val accent = ColorMath.restrainedAccent(c)
            val bg = ColorMath.restrainedBackground(c)
            assertTrue("contrast ${ColorMath.contrast(accent, bg)}", ColorMath.contrast(accent, bg) >= 3f)
        }
    }

    @Test
    fun contrastRatioMatchesWcagReferenceValues() {
        assertEquals(21f, ColorMath.contrast(rgb(0, 0, 0), rgb(255, 255, 255)), 0.01f)
        assertEquals(1f, ColorMath.contrast(rgb(10, 20, 30), rgb(10, 20, 30)), 0.001f)
    }
}
