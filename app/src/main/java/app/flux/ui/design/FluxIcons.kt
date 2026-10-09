package app.flux.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Original icon set drawn as vector paths on a 24-unit grid (no icon-font or icon-library
 * dependency, nothing to attribute). Paths are built once per icon and reused.
 */
enum class FluxIcon {
    Play, Pause, Next, Previous, Plus, Close, Check,
    Bolt, Bell, Timer, Stopwatch, Bluetooth, Headphones, Earbuds, Speaker, Watch, Note, Output,
    Gear, Flux, Sliders, Minus, Info,
}

private class IconPart(val path: Path, val filled: Boolean, val strokeWidth: Float = 2f)

private object IconCache {
    private val cache = HashMap<FluxIcon, List<IconPart>>()
    fun parts(icon: FluxIcon): List<IconPart> = cache.getOrPut(icon) { build(icon) }

    private fun fill(block: Path.() -> Unit) = IconPart(Path().apply(block), filled = true)
    private fun line(width: Float = 2f, block: Path.() -> Unit) = IconPart(Path().apply(block), filled = false, strokeWidth = width)

    private fun Path.poly(vararg p: Float) {
        moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) {
            lineTo(p[i], p[i + 1]); i += 2
        }
    }

    private fun Path.rrect(l: Float, t: Float, r: Float, b: Float, radius: Float) =
        addRoundRect(RoundRect(Rect(l, t, r, b), CornerRadius(radius, radius)))

    private fun Path.circle(cx: Float, cy: Float, r: Float) = addOval(Rect(cx - r, cy - r, cx + r, cy + r))

    private fun build(icon: FluxIcon): List<IconPart> = when (icon) {
        FluxIcon.Play -> listOf(fill { poly(8f, 5f, 19f, 12f, 8f, 19f); close() })
        FluxIcon.Pause -> listOf(fill { rrect(6f, 5f, 10.2f, 19f, 1.2f); rrect(13.8f, 5f, 18f, 19f, 1.2f) })
        FluxIcon.Next -> listOf(
            fill { poly(6f, 6f, 16f, 12f, 6f, 18f); close() },
            fill { rrect(17f, 6f, 19.4f, 18f, 1.1f) },
        )
        FluxIcon.Previous -> listOf(
            fill { poly(18f, 6f, 8f, 12f, 18f, 18f); close() },
            fill { rrect(4.6f, 6f, 7f, 18f, 1.1f) },
        )
        FluxIcon.Plus -> listOf(line(2.2f) { poly(12f, 5f, 12f, 19f); poly(5f, 12f, 19f, 12f) })
        FluxIcon.Close -> listOf(line(2.2f) { poly(6f, 6f, 18f, 18f); poly(18f, 6f, 6f, 18f) })
        FluxIcon.Check -> listOf(line(2.4f) { poly(5f, 12.5f, 10f, 17.5f, 19f, 7f) })
        FluxIcon.Bolt -> listOf(fill { poly(13.2f, 2f, 5f, 13.6f, 11f, 13.6f, 10f, 22f, 19f, 10f, 13f, 10f); close() })
        FluxIcon.Bell -> listOf(
            fill {
                moveTo(12f, 3f)
                cubicTo(8.6f, 3f, 6.5f, 5.8f, 6.5f, 9f)
                lineTo(6.5f, 13f)
                lineTo(4.5f, 16.5f)
                lineTo(19.5f, 16.5f)
                lineTo(17.5f, 13f)
                lineTo(17.5f, 9f)
                cubicTo(17.5f, 5.8f, 15.4f, 3f, 12f, 3f)
                close()
            },
            fill { circle(12f, 20f, 1.9f) },
        )
        FluxIcon.Timer -> listOf(
            line(2f) { circle(12f, 13.5f, 7.5f) },
            line(2f) { poly(12f, 13.5f, 12f, 9.5f) },
            line(2.2f) { poly(9.5f, 3.5f, 14.5f, 3.5f) },
        )
        FluxIcon.Stopwatch -> listOf(
            line(2f) { circle(12f, 13.5f, 7.5f) },
            line(2f) { poly(12f, 13.5f, 15f, 10.5f) },
            line(2.2f) { poly(9.5f, 3.5f, 14.5f, 3.5f); poly(12f, 3.5f, 12f, 6f) },
            line(2f) { poly(18.2f, 7f, 19.6f, 5.6f) },
        )
        FluxIcon.Bluetooth -> listOf(line(2f) { poly(7f, 7.5f, 17f, 16.5f, 12f, 21f, 12f, 3f, 17f, 7.5f, 7f, 16.5f) })
        FluxIcon.Headphones -> listOf(
            line(2f) {
                addArc(Rect(4f, 4f, 20f, 20f), 180f, 180f)
                poly(20f, 12f, 20f, 15f)
                poly(4f, 12f, 4f, 15f)
            },
            fill { rrect(3f, 13f, 7.2f, 20.5f, 2f); rrect(16.8f, 13f, 21f, 20.5f, 2f) },
        )
        FluxIcon.Earbuds -> listOf(
            fill { circle(8f, 8.5f, 4f); circle(16f, 8.5f, 4f) },
            line(2.8f) { poly(9.6f, 11.5f, 9.6f, 19.5f); poly(14.4f, 11.5f, 14.4f, 19.5f) },
        )
        FluxIcon.Speaker -> listOf(
            line(2f) { rrect(6f, 3f, 18f, 21f, 3.5f) },
            line(2f) { circle(12f, 15f, 3.2f) },
            fill { circle(12f, 7.6f, 1.3f) },
        )
        FluxIcon.Watch -> listOf(
            line(2f) { rrect(7f, 6.5f, 17f, 17.5f, 3.2f) },
            line(2f) { poly(9.5f, 6.5f, 10.3f, 2.8f, 13.7f, 2.8f, 14.5f, 6.5f); poly(9.5f, 17.5f, 10.3f, 21.2f, 13.7f, 21.2f, 14.5f, 17.5f) },
            line(1.8f) { poly(12f, 9.6f, 12f, 12f, 13.8f, 13.2f) },
        )
        FluxIcon.Note -> listOf(
            fill { circle(8f, 17.6f, 3.2f) },
            fill { rrect(10.4f, 4.5f, 12.4f, 17.6f, 1f) },
            fill {
                moveTo(12.4f, 4.5f)
                cubicTo(15f, 5f, 17f, 6.2f, 17f, 9.2f)
                cubicTo(15.6f, 8f, 14f, 7.7f, 12.4f, 7.7f)
                close()
            },
        )
        FluxIcon.Output -> listOf(
            line(2f) { rrect(3f, 5f, 21f, 15.5f, 2.5f) },
            line(2f) { poly(8f, 19.5f, 16f, 19.5f); poly(12f, 15.5f, 12f, 19.5f) },
        )
        FluxIcon.Gear -> listOf(
            line(2f) { circle(12f, 12f, 4.2f) },
            line(2.2f) {
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val c = Math.cos(a).toFloat()
                    val sn = Math.sin(a).toFloat()
                    poly(12f + 7.6f * c, 12f + 7.6f * sn, 12f + 9.8f * c, 12f + 9.8f * sn)
                }
            },
            line(2f) { circle(12f, 12f, 7.4f) },
        )
        FluxIcon.Flux -> listOf(line(2.2f) { poly(3f, 12f, 7f, 12f, 9.5f, 5f, 13.5f, 19f, 16f, 12f, 21f, 12f) })
        FluxIcon.Sliders -> listOf(
            line(2f) { poly(4f, 7f, 20f, 7f); poly(4f, 12f, 20f, 12f); poly(4f, 17f, 20f, 17f) },
            fill { circle(9f, 7f, 2.6f); circle(15f, 12f, 2.6f); circle(8f, 17f, 2.6f) },
        )
        FluxIcon.Minus -> listOf(line(2.2f) { poly(5f, 12f, 19f, 12f) })
        FluxIcon.Info -> listOf(
            line(2f) { circle(12f, 12f, 8.5f) },
            line(2.2f) { poly(12f, 11f, 12f, 16f) },
            fill { circle(12f, 7.8f, 1.2f) },
        )
    }
}

/** Fixed-size icon. */
@Composable
fun FluxIconView(icon: FluxIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    FluxIconFill(icon, tint, modifier.size(size))
}

/** Icon that fills whatever size [modifier] gives it (always drawn square, centred). */
@Composable
fun FluxIconFill(icon: FluxIcon, tint: Color, modifier: Modifier = Modifier) {
    val parts = remember(icon) { IconCache.parts(icon) }
    Canvas(modifier) {
        val s = this.size.minDimension / 24f
        val dx = (this.size.width - 24f * s) / 2f
        val dy = (this.size.height - 24f * s) / 2f
        translate(dx, dy) {
            scale(s, s, pivot = Offset.Zero) {
                for (part in parts) {
                    if (part.filled) {
                        drawPath(part.path, tint)
                    } else {
                        drawPath(
                            part.path,
                            tint,
                            style = Stroke(width = part.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }
        }
    }
}
