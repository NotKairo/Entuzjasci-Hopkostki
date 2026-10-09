package app.galaxypulse.artwork

/**
 * Tiny separable box blur on packed ARGB pixels. The artwork backdrop is blurred *once* at a very
 * small size (a few thousand pixels) off the main thread and then simply scaled up when drawn, which
 * is far cheaper than a live blur effect on a window that stays on screen for minutes.
 */
object ImageBlur {

    fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int = 2): IntArray {
        require(pixels.size == width * height) { "pixel buffer does not match ${width}x$height" }
        if (radius <= 0 || passes <= 0 || width == 0 || height == 0) return pixels.copyOf()
        var src = pixels.copyOf()
        var dst = IntArray(pixels.size)
        repeat(passes) {
            blurLine(src, dst, width, height, radius, horizontal = true)
            blurLine(dst, src, width, height, radius, horizontal = false)
        }
        return src
    }

    private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            var a = 0; var r = 0; var g = 0; var b = 0
            fun at(i: Int): Int {
                val clamped = i.coerceIn(0, length - 1)
                return if (horizontal) src[line * w + clamped] else src[clamped * w + line]
            }
            for (i in -radius..radius) {
                val p = at(i)
                a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (i in 0 until length) {
                val out = (((a / window) and 0xFF) shl 24) or (((r / window) and 0xFF) shl 16) or
                    (((g / window) and 0xFF) shl 8) or ((b / window) and 0xFF)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                val add = at(i + radius + 1)
                val sub = at(i - radius)
                a += (add ushr 24) - (sub ushr 24)
                r += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                b += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}
