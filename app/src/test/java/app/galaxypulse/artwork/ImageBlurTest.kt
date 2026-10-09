package app.galaxypulse.artwork

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBlurTest {

    private fun px(r: Int, g: Int, b: Int) = ColorMath.argb(255, r, g, b)

    @Test
    fun uniformImageIsUnchanged() {
        val src = IntArray(8 * 8) { px(40, 90, 200) }
        assertArrayEquals(src, ImageBlur.boxBlur(src, 8, 8, radius = 2))
    }

    @Test
    fun zeroRadiusReturnsACopy() {
        val src = IntArray(16) { px(it * 10, 0, 0) }
        val out = ImageBlur.boxBlur(src, 4, 4, radius = 0)
        assertArrayEquals(src, out)
        assertTrue(out !== src)
    }

    @Test
    fun aHardEdgeBecomesAGradient() {
        val w = 16
        val src = IntArray(w * 4) { i -> if (i % w < w / 2) px(0, 0, 0) else px(255, 255, 255) }
        val out = ImageBlur.boxBlur(src, w, 4, radius = 3, passes = 2)
        val row = IntArray(w) { out[it] and 0xFF }
        // Monotonic, and the transition pixels are strictly between black and white.
        for (i in 1 until w) assertTrue("row not monotonic at $i: ${row.toList()}", row[i] >= row[i - 1])
        assertTrue(row[w / 2 - 1] in 1..254)
        assertTrue(row[w / 2] in 1..254)
    }

    @Test
    fun meanBrightnessIsRoughlyPreserved() {
        val w = 12
        val h = 12
        val src = IntArray(w * h) { i -> px((i * 37) % 256, (i * 91) % 256, (i * 53) % 256) }
        val out = ImageBlur.boxBlur(src, w, h, radius = 2)
        fun mean(a: IntArray) = a.sumOf { ColorMath.red(it) + ColorMath.green(it) + ColorMath.blue(it) } / (a.size * 3.0)
        assertEquals(mean(src), mean(out), 12.0)
    }

    @Test
    fun alphaIsPreserved() {
        val src = IntArray(9) { ColorMath.argb(255, 10, 20, 30) }
        assertTrue(ImageBlur.boxBlur(src, 3, 3, radius = 1).all { it ushr 24 == 255 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedBufferIsRejected() {
        ImageBlur.boxBlur(IntArray(5), 3, 3, radius = 1)
    }

    @Test
    fun singlePixelAndSingleRowImagesDoNotCrash() {
        assertEquals(1, ImageBlur.boxBlur(intArrayOf(px(1, 2, 3)), 1, 1, radius = 3).size)
        assertEquals(5, ImageBlur.boxBlur(IntArray(5) { px(it, it, it) }, 5, 1, radius = 2).size)
    }
}
