package app.galaxypulse.ui.overlay

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.galaxypulse.artwork.ArtworkBundle
import app.galaxypulse.battery.BatteryAlertPayload
import app.galaxypulse.battery.ChargingPayload
import app.galaxypulse.bluetooth.BluetoothPayload
import app.galaxypulse.engine.ActivityEntry
import app.galaxypulse.engine.ActivityKind
import app.galaxypulse.media.MediaSnapshot
import app.galaxypulse.notifications.NotificationPayload
import app.galaxypulse.settings.OverlayTheme
import app.galaxypulse.timers.StopwatchPayload
import app.galaxypulse.timers.TimerDonePayload
import app.galaxypulse.timers.TimerPayload
import app.galaxypulse.ui.anim.FrameTarget
import app.galaxypulse.ui.anim.PillGestureState
import app.galaxypulse.ui.anim.PulseFrameState
import app.galaxypulse.ui.anim.PulseMotion
import app.galaxypulse.ui.anim.pillGestures
import app.galaxypulse.ui.anim.rememberPulseFrame
import app.galaxypulse.ui.anim.rememberPulseHaptics
import app.galaxypulse.ui.design.PulseColors
import app.galaxypulse.ui.design.PulseShapes
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_FONT_SCALE = 1.15f

/**
 * The top pill. Hosted by the overlay window in the service, and by the in-app live preview.
 *
 * Rendering model: the visible shape is an animated frame (see [PulseFrameState]) clipped by a shape
 * whose corner radii follow the frame's own size. Scenes inside are laid out once at their final size
 * and merely revealed by the shape, so a morph costs graphics-layer updates, not recomposition.
 */
@Composable
fun PulseTopOverlay(ui: OverlayUi, actions: OverlayActions, modifier: Modifier = Modifier) {
    val front = ui.front
    val keep = remember { arrayOfNulls<ActivityEntry>(1) } // last entry, kept alive for the exit animation
    if (front != null) keep[0] = front
    val entry = front ?: keep[0] ?: return

    val systemDensity = LocalDensity.current
    val scale = ui.settings.sizeScale.coerceIn(0.7f, 1.4f)
    val density = remember(ui.density, scale, systemDensity.fontScale) {
        Density(ui.density * scale, min(systemDensity.fontScale, MAX_FONT_SCALE))
    }

    CompositionLocalProvider(LocalDensity provides density) {
        PillContent(ui, entry, front != null, actions, scale, modifier)
    }
}

@Composable
private fun PillContent(
    ui: OverlayUi,
    entry: ActivityEntry,
    visible: Boolean,
    actions: OverlayActions,
    scale: Float,
    modifier: Modifier,
) {
    val layout = ui.layout
    val expanded = ui.expanded && visible
    val settings = ui.settings
    val rect = if (expanded) layout.expanded else layout.compact
    val topEdge = if (expanded) layout.backdropTopPx else layout.compact.top
    val target = FrameTarget(
        centerX = rect.centerX,
        top = topEdge.toFloat(),
        width = rect.width.toFloat(),
        height = (rect.bottom - topEdge).toFloat(),
    )
    val frame = rememberPulseFrame(target, ui.motion)

    val dims = remember(layout, ui.density, scale) {
        SceneDims(
            compactW = layout.compact.width.toFloat(),
            compactH = layout.compact.height.toFloat(),
            expandedW = layout.expanded.width.toFloat(),
            expandedH = layout.expanded.height.toFloat(),
            compactFrameH = layout.compact.height.toFloat(),
            expandedFrameH = (layout.expanded.bottom - layout.backdropTopPx).toFloat(),
            contentOriginY = layout.compact.top.toFloat(),
            density = ui.density,
            scale = scale,
        )
    }
    val metrics = remember(frame, dims) { SceneMetrics(frame, dims) }

    val cardRadiusPx = with(LocalDensity.current) { PulseShapes.Card.toPx() }
    val shape = remember(metrics, cardRadiusPx, settings.extendBackdropToTopEdge) {
        PillShape(metrics, expandedTopRadius = if (settings.extendBackdropToTopEdge) 0f else cardRadiusPx, expandedBottomRadius = cardRadiusPx)
    }

    val scope = rememberCoroutineScope()
    val gestures = remember { PillGestureState(scope, ui.motion) }.also { it.config = ui.motion }
    val haptics = rememberPulseHaptics(settings.haptics)

    // New content starts at rest and arrives with a tiny squash-and-stretch.
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(entry.key, entry.revision) {
        gestures.reset()
        arrival.snapTo(1f)
        arrival.animateTo(0f, PulseMotion.pressSpring(ui.motion))
    }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        appear.animateTo(
            if (visible) 1f else 0f,
            if (ui.motion.reduced) tween(PulseMotion.REDUCED_MS) else tween(if (visible) 260 else 200),
        )
    }

    // The gesture detectors outlive recompositions, so they must call through to the *latest* state.
    val onTapLatest = rememberUpdatedState {
        haptics.tap()
        actions.setExpanded(entry.key, !ui.expanded)
    }
    val onDismissLatest = rememberUpdatedState {
        haptics.dismiss()
        actions.dismiss(entry.key)
    }

    val origin = ui.windowOrigin
    val squash = PulseMotion.pulseAmount(ui.motion)
    val dismissDistance = with(LocalDensity.current) { 72.dp.toPx() }
    val offscreen = layout.expanded.width * 1.25f

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .placeFrame(frame, origin)
                .layout { measurable, _ ->
                    val w = frame.width.value.roundToInt().coerceAtLeast(1)
                    val h = frame.height.value.roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { placeable.place(0, 0) }
                }
                .pillGestures(
                    state = gestures,
                    tapEnabled = settings.tapToExpand && visible,
                    swipeEnabled = settings.swipeToDismiss && visible,
                    dismissDistancePx = dismissDistance,
                    offscreenPx = offscreen,
                    onTap = { onTapLatest.value() },
                    onDismiss = { onDismissLatest.value() },
                )
                .graphicsLayer {
                    val s = gestures.pressScale.value * (0.88f + 0.12f * appear.value)
                    scaleX = s * (1f + squash * arrival.value)
                    scaleY = s * (1f - squash * 0.6f * arrival.value)
                    translationX = gestures.dragX.value
                    alpha = appear.value * (1f - (abs(gestures.dragX.value) / (layout.expanded.width * 0.9f)).coerceIn(0f, 0.85f))
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    this.shape = shape
                    clip = true
                }
                .border(
                    PulseShapes.Hairline,
                    Brush.verticalGradient(listOf(Color.Transparent, PulseColors.Stroke)),
                    shape,
                ),
        ) {
            val media = (entry.payload as? MediaSnapshot)
            Backdrop(metrics, ui, entry.kind, if (media != null) ui.artwork else null)
            SceneSwitch(metrics, ui, entry, actions)
        }
    }
}

/** Positions the node at the animated frame (screen px), relative to the overlay window's origin. */
private fun Modifier.placeFrame(frame: PulseFrameState, origin: IntOffset): Modifier =
    this.then(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(constraints.maxWidth, constraints.maxHeight) {
                val x = (frame.centerX.value - frame.width.value / 2f).roundToInt() - origin.x
                val y = frame.top.value.roundToInt() - origin.y
                placeable.place(x, y)
            }
        },
    )

/** Clips the pill; corner radii are derived from the live size so they evolve with it. */
private class PillShape(
    private val m: SceneMetrics,
    private val expandedTopRadius: Float,
    private val expandedBottomRadius: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = PillShapeMath.radii(
            progress = m.progress,
            frameWidth = size.width,
            frameHeight = size.height,
            compactFrameHeight = m.dims.compactFrameH,
            expandedTopRadius = expandedTopRadius,
            expandedBottomRadius = expandedBottomRadius,
        )
        return Outline.Rounded(
            RoundRect(
                0f, 0f, size.width, size.height,
                topLeftCornerRadius = CornerRadius(r.top),
                topRightCornerRadius = CornerRadius(r.top),
                bottomRightCornerRadius = CornerRadius(r.bottom),
                bottomLeftCornerRadius = CornerRadius(r.bottom),
            ),
        )
    }
}

@Composable
private fun SceneSwitch(m: SceneMetrics, ui: OverlayUi, entry: ActivityEntry, actions: OverlayActions) {
    // Latest entry per key, so a fading-out scene keeps showing *its* data while the next one fades in.
    val latest = remember { LinkedHashMap<String, ActivityEntry>() }
    latest[entry.key] = entry
    while (latest.size > 4) latest.remove(latest.keys.first())
    val haptics = rememberPulseHaptics(ui.settings.haptics)

    Crossfade(
        targetState = entry.key,
        animationSpec = tween(if (ui.motion.reduced) PulseMotion.REDUCED_MS else PulseMotion.CONTENT_FADE_MS),
        label = "scene",
    ) { key ->
        val e = latest[key] ?: entry
        when (val payload = e.payload) {
            is MediaSnapshot -> MediaScene(m, payload, ui.artwork, ui, actions, haptics)
            is NotificationPayload ->
                if (payload.isIncomingCall) CallScene(m, payload, actions, haptics) else NotificationScene(m, payload, actions, haptics)
            is ChargingPayload -> ChargingScene(m, payload, ui.motion)
            is BatteryAlertPayload -> BatteryAlertScene(m, payload)
            is TimerPayload -> TimerScene(m, payload, actions, haptics, ui.motion)
            is TimerDonePayload -> TimerDoneScene(m, payload, actions, haptics, ui.motion)
            is StopwatchPayload -> StopwatchScene(m, payload, actions, haptics)
            is BluetoothPayload -> BluetoothScene(m, payload, actions, haptics)
            else -> Unit
        }
    }
}

// ── Backdrop ──────────────────────────────────────────────────────────────────────────────────

/** Restrained tint per activity for the expanded wash (the compact pill is always true black). */
private fun kindTint(kind: ActivityKind): Color = when (kind) {
    ActivityKind.Charging -> Color(0xFF0E2A1D)
    ActivityKind.Timer -> Color(0xFF0E1E45)
    ActivityKind.Stopwatch -> Color(0xFF1E1650)
    ActivityKind.Bluetooth -> Color(0xFF0E2048)
    ActivityKind.BatteryAlert -> Color(0xFF3A230A)
    ActivityKind.TimerDone -> Color(0xFF3A230A)
    ActivityKind.Call -> Color(0xFF0E2A1D)
    ActivityKind.Media, ActivityKind.Notification -> Color(0xFF131728)
}

/**
 * Black base, optional blurred-cover layer, a tint wash, then scrims. The top scrim is a smoothly
 * eased fade to solid black over the status-bar band, so the camera sits in a dark region and there is
 * never a visible rectangle where the artwork ends. The camera itself is not drawn or referenced.
 */
@Composable
private fun Backdrop(m: SceneMetrics, ui: OverlayUi, kind: ActivityKind, artwork: ArtworkBundle?) {
    val theme = ui.settings.theme
    val useArtwork = kind == ActivityKind.Media && theme == OverlayTheme.ArtworkTint && artwork != null
    val tint = when {
        useArtwork -> artwork!!.palette.background
        theme == OverlayTheme.Violet -> Color(0xFF1B1446)
        theme == OverlayTheme.Midnight -> Color(0xFF0A1B3D)
        else -> kindTint(kind)
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (useArtwork) {
            Crossfade(
                targetState = artwork,
                animationSpec = tween(if (ui.motion.reduced) PulseMotion.REDUCED_MS else PulseMotion.ARTWORK_CROSSFADE_MS),
                label = "backdrop",
            ) { art ->
                if (art != null) {
                    Image(
                        bitmap = art.backdrop,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = PillShapeMath.smoothstep(0.05f, 0.7f, m.progress) },
                    )
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val reveal = PillShapeMath.smoothstep(0.05f, 0.8f, m.progress)
            if (reveal <= 0.001f) return@Canvas
            val h = size.height
            // Wash: ties the whole card to the cover's colour (or the activity's tint).
            drawRect(
                Brush.verticalGradient(
                    listOf(tint.copy(alpha = 0.50f * reveal), tint.copy(alpha = if (useArtwork) 0.30f * reveal else 0.85f * reveal)),
                ),
            )
            // Bottom scrim for legibility of controls.
            drawRect(
                Brush.verticalGradient(
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.55f * reveal),
                ),
            )
            // Top scrim: solid black through the status-bar band, then an eased fade (no hard edge).
            val inset = m.offsetY.coerceAtLeast(0f)
            if (inset > 0.5f) {
                val fade = 76.dp.toPx()
                fun at(distance: Float) = ((inset + distance) / h).coerceIn(0f, 1f)
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black,
                        at(0f) to Color.Black,
                        at(fade * 0.15f) to Color.Black.copy(alpha = 0.94f),
                        at(fade * 0.30f) to Color.Black.copy(alpha = 0.82f),
                        at(fade * 0.50f) to Color.Black.copy(alpha = 0.60f),
                        at(fade * 0.70f) to Color.Black.copy(alpha = 0.36f),
                        at(fade * 0.85f) to Color.Black.copy(alpha = 0.16f),
                        at(fade) to Color.Transparent,
                    ),
                )
            }
        }
    }
}
