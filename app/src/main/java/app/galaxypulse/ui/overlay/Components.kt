package app.galaxypulse.ui.overlay

import android.content.Context
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.galaxypulse.artwork.ArtworkBundle
import app.galaxypulse.ui.anim.MotionConfig
import app.galaxypulse.ui.anim.PulseMotion
import app.galaxypulse.ui.design.PulseColors
import app.galaxypulse.ui.design.PulseIcon
import app.galaxypulse.ui.design.PulseIconFill
import app.galaxypulse.ui.design.PulseIconView
import app.galaxypulse.ui.design.PulseType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Single-line (by default) text with the design-system defaults applied. */
@Composable
internal fun PText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = PulseColors.OnSurface,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

/**
 * Places the child at an arbitrary rectangle inside a full-size parent. The rectangle is read in the
 * layout phase, so animating it never recomposes anything.
 */
internal fun Modifier.placedFree(rect: () -> Rect): Modifier = layout { measurable, constraints ->
    val r = rect()
    val w = r.width.roundToInt().coerceAtLeast(0)
    val h = r.height.roundToInt().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(r.left.roundToInt(), r.top.roundToInt())
    }
}

// ── Equalizer ─────────────────────────────────────────────────────────────────────────────────

/** Decorative three-bar equalizer. Animates only while [playing] and [animate]; otherwise it is a static drawing. */
@Composable
internal fun Equalizer(playing: Boolean, color: Color, modifier: Modifier = Modifier, animate: Boolean = true) {
    if (playing && animate) AnimatedEqualizer(color, modifier) else {
        val a = remember { mutableStateOf(0.35f) }
        val b = remember { mutableStateOf(0.6f) }
        val c = remember { mutableStateOf(0.45f) }
        EqualizerCanvas(a, b, c, color.copy(alpha = 0.6f), modifier)
    }
}

@Composable
private fun AnimatedEqualizer(color: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val a = transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b = transition.animateFloat(0.3f, 0.95f, infiniteRepeatable(tween(680, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c = transition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(610, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    EqualizerCanvas(a, b, c, color, modifier)
}

@Composable
private fun EqualizerCanvas(a: State<Float>, b: State<Float>, c: State<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier.size(width = 16.dp, height = 14.dp)) {
        val barWidth = size.width / 5f
        val radius = CornerRadius(barWidth / 2f)
        fun bar(index: Int, fraction: Float) {
            val h = (size.height * fraction).coerceAtLeast(barWidth)
            drawRoundRect(
                color = color,
                topLeft = Offset(index * barWidth * 2f, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = radius,
            )
        }
        bar(0, a.value)
        bar(1, b.value)
        bar(2, c.value)
    }
}

// ── Artwork ───────────────────────────────────────────────────────────────────────────────────

/**
 * The cover, or a premium gradient with a neutral note when there is none. A new cover crossfades
 * over [PulseMotion.ARTWORK_CROSSFADE_MS] while scaling in from slightly smaller.
 */
@Composable
internal fun ArtworkThumb(
    artwork: ArtworkBundle?,
    cornerPx: () -> Float,
    motion: MotionConfig,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.graphicsLayer {
            shape = RoundedCornerShape(cornerPx())
            clip = true
        },
    ) {
        Crossfade(
            targetState = artwork,
            animationSpec = tween(if (motion.reduced) PulseMotion.REDUCED_MS else PulseMotion.ARTWORK_CROSSFADE_MS),
            label = "artwork",
        ) { art ->
            if (art != null) ArtworkImage(art, motion) else FallbackArtwork()
        }
    }
}

@Composable
private fun ArtworkImage(art: ArtworkBundle, motion: MotionConfig) {
    val scale = remember(art.key) { Animatable(if (motion.reduced) 1f else 0.9f) }
    LaunchedEffect(art.key) { scale.animateTo(1f, PulseMotion.settleSpring(motion)) }
    Image(
        bitmap = art.thumb,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.Medium,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
    )
}

@Composable
internal fun FallbackArtwork(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(PulseColors.Violet.copy(alpha = 0.65f), PulseColors.Blue.copy(alpha = 0.55f), PulseColors.Ink),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        PulseIconFill(PulseIcon.Note, PulseColors.OnSurface.copy(alpha = 0.85f), Modifier.fillMaxSize(0.52f))
    }
}

// ── App icons ─────────────────────────────────────────────────────────────────────────────────

internal object AppIconCache {
    private val cache = LruCache<String, ImageBitmap>(48)

    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    fun load(context: Context, packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return try {
            val bitmap = context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            cache.put(packageName, bitmap)
            bitmap
        } catch (_: Exception) {
            // Package not visible (queries) or gone: the letter avatar is shown instead.
            null
        }
    }
}

@Composable
internal fun AppIcon(packageName: String, label: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val icon by produceState(AppIconCache.peek(packageName), packageName) {
        value = withContext(Dispatchers.Default) { AppIconCache.load(context, packageName) }
    }
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.28f)), contentAlignment = Alignment.Center) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.Medium)
        } else {
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(PulseColors.Blue, PulseColors.Violet))),
                contentAlignment = Alignment.Center,
            ) {
                PText(label.take(1).uppercase().ifEmpty { "?" }, PulseType.BodyCompact, color = Color.White)
            }
        }
    }
}

// ── Buttons ───────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun PulseRoundButton(
    icon: PulseIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    container: Color = Color.Transparent,
    tint: Color = PulseColors.OnSurface,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.55f, stiffness = 650f), label = "press")
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = if (enabled) 1f else 0.32f
            }
            .clip(CircleShape)
            .background(container)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PulseIconView(icon, tint, size = iconSize)
    }
}

@Composable
internal fun PulseTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    accent: Color = PulseColors.Blue,
    icon: PulseIcon? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.6f, stiffness = 650f), label = "press")
    val container = if (emphasized) accent else Color.White.copy(alpha = 0.12f)
    val content = if (emphasized) Color(0xFF05060B) else PulseColors.OnSurface
    Box(
        modifier
            .height(38.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = if (enabled) 1f else 0.4f
            }
            .clip(RoundedCornerShape(19.dp))
            .background(container)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) PulseIconView(icon, content, size = 16.dp)
            PText(text, PulseType.BodyCompact, color = content)
        }
    }
}

// ── Progress ──────────────────────────────────────────────────────────────────────────────────

/**
 * Seek bar. [progress] null means the position is unknown, so only an empty track is drawn (nothing
 * is faked). Dragging works only when [seekable]; the final fraction is delivered once, on release.
 */
@Composable
internal fun SeekBar(
    progress: Float?,
    seekable: Boolean,
    accent: Color,
    onScrub: (Float?) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    // The gesture block below is not restarted on recomposition; always call the latest callbacks.
    val latestScrub = rememberUpdatedState(onScrub)
    val latestCommit = rememberUpdatedState(onCommit)
    val thumb = animateFloatAsState(if (scrub != null) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 600f), label = "thumb")
    Box(
        modifier
            .height(24.dp)
            .pointerInput(seekable) {
                if (!seekable) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    var fraction = (down.position.x / width).coerceIn(0f, 1f)
                    scrub = fraction
                    latestScrub.value(fraction)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        fraction = (change.position.x / width).coerceIn(0f, 1f)
                        scrub = fraction
                        latestScrub.value(fraction)
                        change.consume()
                    }
                    latestCommit.value(fraction)
                    scrub = null
                    latestScrub.value(null)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val base = 4.dp.toPx()
            val trackH = base + 2.dp.toPx() * thumb.value
            val y = (size.height - trackH) / 2f
            val radius = CornerRadius(trackH / 2f)
            drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(0f, y), Size(size.width, trackH), radius)
            val shown = scrub ?: progress
            if (shown != null) {
                val w = (size.width * shown).coerceIn(0f, size.width)
                drawRoundRect(accent, Offset(0f, y), Size(w, trackH), radius)
                if (thumb.value > 0.01f) {
                    drawCircle(Color.White, radius = 6.dp.toPx() * thumb.value, center = Offset(w, size.height / 2f))
                }
            }
        }
    }
}

/** Circular progress ring, e.g. battery level or timer. */
@Composable
internal fun ProgressRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 6.dp,
    track: Color = Color.White.copy(alpha = 0.14f),
) {
    val animated = animateFloatAsState(progress.coerceIn(0f, 1f), spring(dampingRatio = 0.9f, stiffness = 120f), label = "ring")
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(color, -90f, 360f * animated.value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}
