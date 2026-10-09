package app.galaxypulse.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.galaxypulse.R
import app.galaxypulse.bluetooth.BluetoothPayload
import app.galaxypulse.bluetooth.ConnectionPhase
import app.galaxypulse.bluetooth.DeviceKind
import app.galaxypulse.ui.anim.MotionConfig
import app.galaxypulse.ui.anim.PulseHaptics
import app.galaxypulse.ui.anim.rememberPulseHaptics
import app.galaxypulse.ui.design.PulseColors
import app.galaxypulse.ui.design.PulseIcon
import app.galaxypulse.ui.design.PulseIconFill
import app.galaxypulse.ui.design.PulseType
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.delay

/** What the bottom card shows. [serial] changes for every genuinely new connection event. */
data class BluetoothCardModel(val payload: BluetoothPayload, val serial: Long)

object BluetoothCardDimens {
    const val CARD_HEIGHT_DP = 296f
    const val MAX_WIDTH_DP = 440f
    const val SIDE_MARGIN_DP = 16f
    const val BOTTOM_MARGIN_DP = 10f
}

/**
 * Bottom connection card. Slides up from the bottom edge, then staggers in the illustration, the
 * device name and the status, shows a subtle ring while connecting and a restrained check mark on
 * success. Lives in its own window so it never overlaps the top pill.
 */
@Composable
fun BluetoothCardHost(
    model: BluetoothCardModel?,
    motion: MotionConfig,
    actions: OverlayActions,
    hapticsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val holder = remember { arrayOfNulls<BluetoothCardModel>(1) } // keeps content alive during the exit animation
    if (model != null) holder[0] = model
    val shown = model ?: holder[0]
    val haptics = rememberPulseHaptics(hapticsEnabled)

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = model != null,
            enter = if (motion.reduced) fadeIn(tween(120)) else
                slideInVertically(spring(dampingRatio = 0.82f, stiffness = 380f)) { it } + fadeIn(tween(200)),
            exit = if (motion.reduced) fadeOut(tween(120)) else
                slideOutVertically(tween(260, easing = FastOutLinearInEasing)) { it } + fadeOut(tween(200)),
        ) {
            if (shown != null) BluetoothCard(shown, motion, actions, haptics)
        }
    }
}

@Composable
private fun BluetoothCard(model: BluetoothCardModel, motion: MotionConfig, actions: OverlayActions, haptics: PulseHaptics) {
    val p = model.payload
    var stage by remember(p.address) { mutableIntStateOf(if (motion.reduced) 3 else 0) }
    LaunchedEffect(p.address) {
        if (!motion.reduced) {
            delay(80); stage = 1
            delay(150); stage = 2
            delay(150); stage = 3
        }
    }
    LaunchedEffect(p.phase) {
        when (p.phase) {
            ConnectionPhase.Connected -> haptics.confirm()
            ConnectionPhase.Failed -> haptics.reject()
            else -> Unit
        }
    }

    val shape = RoundedCornerShape(32.dp)
    Box(
        Modifier
            .padding(horizontal = BluetoothCardDimens.SIDE_MARGIN_DP.dp)
            .fillMaxWidth()
            .height(BluetoothCardDimens.CARD_HEIGHT_DP.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xF2151A2B), Color(0xF20A0C14))))
            .border(1.dp, Brush.verticalGradient(listOf(PulseColors.StrokeStrong, PulseColors.Stroke)), shape),
    ) {
        PulseRoundButton(
            icon = PulseIcon.Close,
            onClick = { actions.bluetooth(BluetoothCommand.DismissCard) },
            size = 40.dp, iconSize = 18.dp,
            tint = PulseColors.OnSurfaceMuted,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        )
        Column(
            Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                visible = stage >= 1,
                enter = fadeIn(tween(260)) + scaleIn(initialScale = 0.8f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f)),
            ) {
                Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                    PulseRipple(active = (p.phase == ConnectionPhase.Pairing || p.phase == ConnectionPhase.Connecting) && !motion.reduced)
                    ConnectionRing(p.phase, motion)
                    DeviceIllustration(p.kind, Modifier.size(78.dp))
                    if (p.phase == ConnectionPhase.Connected) CheckBadge(motion, Modifier.align(Alignment.BottomEnd))
                }
            }
            Spacer(Modifier.height(8.dp))
            AnimatedVisibility(visible = stage >= 2, enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 }) {
                PText(p.name, PulseType.Title, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            AnimatedVisibility(visible = stage >= 3, enter = fadeIn(tween(220))) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PText(
                        phaseLabel(p.phase), PulseType.Body,
                        color = when (p.phase) {
                            ConnectionPhase.Connected -> PulseColors.Blue
                            ConnectionPhase.Failed -> PulseColors.Critical
                            else -> PulseColors.OnSurfaceMuted
                        },
                        textAlign = TextAlign.Center,
                    )
                    val parts = batteryParts(p.battery)
                    if (parts.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            parts.forEach { (label, pct) ->
                                PText("$label $pct%", PulseType.Caption.copy(fontFeatureSettings = "tnum"), color = PulseColors.OnSurfaceMuted)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            CardActions(p, actions, haptics)
        }
    }
}

@Composable
private fun CardActions(p: BluetoothPayload, actions: OverlayActions, haptics: PulseHaptics) {
    when (p.phase) {
        ConnectionPhase.Discovered -> PulseTextButton(
            "Connect", emphasized = true, modifier = Modifier.fillMaxWidth(),
            onClick = { haptics.tap(); actions.bluetooth(BluetoothCommand.Connect(p.address)) },
        )
        ConnectionPhase.Pairing, ConnectionPhase.Connecting -> PulseTextButton(
            if (p.phase == ConnectionPhase.Pairing) "Pairing…" else "Connecting…",
            enabled = false, modifier = Modifier.fillMaxWidth(), onClick = {},
        )
        ConnectionPhase.Connected -> PulseTextButton(
            "Done", emphasized = true, modifier = Modifier.fillMaxWidth(),
            onClick = { haptics.tap(); actions.bluetooth(BluetoothCommand.DismissCard) },
        )
        ConnectionPhase.PairedNotConnected -> PulseTextButton(
            "Bluetooth settings", emphasized = true, modifier = Modifier.fillMaxWidth(),
            onClick = { haptics.tap(); actions.bluetooth(BluetoothCommand.OpenSystemSettings) },
        )
        ConnectionPhase.Failed -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PulseTextButton(
                "Settings", modifier = Modifier.weight(1f),
                onClick = { haptics.tap(); actions.bluetooth(BluetoothCommand.OpenSystemSettings) },
            )
            PulseTextButton(
                "Try again", emphasized = true, modifier = Modifier.weight(1f),
                onClick = { haptics.tap(); actions.bluetooth(BluetoothCommand.Connect(p.address)) },
            )
        }
    }
}

/** Decorative Lottie ripple (original artwork) radiating from the device while it connects. */
@Composable
private fun PulseRipple(active: Boolean) {
    if (!active) return
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.pulse_ring))
    LottieAnimation(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = 1.25f
            scaleY = 1.25f
        },
    )
}

/** Faint ring around the device; sweeps while connecting, completes when connected. */
@Composable
private fun ConnectionRing(phase: ConnectionPhase, motion: MotionConfig) {
    val busy = phase == ConnectionPhase.Pairing || phase == ConnectionPhase.Connecting
    val completion = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        val target = if (phase == ConnectionPhase.Connected) 1f else 0f
        if (motion.reduced) completion.snapTo(target) else completion.animateTo(target, tween(480))
    }
    val sweepAngle: State<Float> = if (busy && !motion.reduced) {
        rememberInfiniteTransition(label = "ring").animateFloat(
            0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(Color.White.copy(alpha = 0.10f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (busy) {
            drawArc(
                PulseColors.Blue, sweepAngle.value, if (motion.reduced) 360f else 110f, false,
                Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        if (completion.value > 0f) {
            drawArc(
                PulseColors.Blue, -90f, 360f * completion.value, false,
                Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** Small badge whose check mark draws itself — the only "celebration" the card gets. */
@Composable
private fun CheckBadge(motion: MotionConfig, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(if (motion.reduced) 1f else 0f) }
    val draw = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!motion.reduced) {
            pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 500f))
            draw.animateTo(1f, tween(320))
        }
    }
    Canvas(
        modifier
            .size(30.dp)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            },
    ) {
        drawCircle(PulseColors.Blue)
        val check = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.52f)
            lineTo(size.width * 0.44f, size.height * 0.68f)
            lineTo(size.width * 0.74f, size.height * 0.34f)
        }
        val measure = PathMeasure().apply { setPath(check, false) }
        val partial = Path()
        measure.getSegment(0f, measure.length * draw.value, partial, true)
        drawPath(partial, Color(0xFF05060B), style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ── Original, generic illustrations ───────────────────────────────────────────────────────────

private val BodyLight = Brush.linearGradient(listOf(Color(0xFFF6F8FE), Color(0xFFAAB3CE)))
private val BodyDark = Brush.linearGradient(listOf(Color(0xFF3A4260), Color(0xFF181C2C)))

/**
 * Generic device art drawn in a 100×100 space: headphones, earbuds with a case, speaker, watch.
 * Deliberately not modelled on any manufacturer's industrial design.
 */
@Composable
fun DeviceIllustration(kind: DeviceKind, modifier: Modifier = Modifier) {
    if (kind == DeviceKind.Car || kind == DeviceKind.Other) {
        Box(modifier.clip(CircleShape).background(Brush.linearGradient(listOf(PulseColors.Blue.copy(alpha = 0.35f), PulseColors.Violet.copy(alpha = 0.25f)))), contentAlignment = Alignment.Center) {
            PulseIconFill(PulseIcon.Bluetooth, PulseColors.OnSurface, Modifier.fillMaxSize(0.5f))
        }
        return
    }
    Canvas(modifier) {
        val s = size.minDimension / 100f
        scale(s, s, pivot = Offset.Zero) {
            when (kind) {
                DeviceKind.Headphones -> headphones()
                DeviceKind.Earbuds -> earbuds()
                DeviceKind.Speaker -> speaker()
                DeviceKind.Watch -> watch()
                else -> Unit
            }
        }
    }
}

private fun DrawScope.rr(l: Float, t: Float, r: Float, b: Float, radius: Float, brush: Brush) =
    drawRoundRect(brush, Offset(l, t), Size(r - l, b - t), CornerRadius(radius, radius))

private fun DrawScope.headphones() {
    val band = Path().apply {
        moveTo(19f, 62f)
        cubicTo(19f, 4f, 81f, 4f, 81f, 62f)
    }
    drawPath(band, BodyLight, style = Stroke(8f, cap = StrokeCap.Round))
    rr(8f, 52f, 31f, 90f, 11f, BodyLight)
    rr(69f, 52f, 92f, 90f, 11f, BodyLight)
    rr(15f, 58f, 31f, 84f, 8f, BodyDark)
    rr(69f, 58f, 85f, 84f, 8f, BodyDark)
}

private fun DrawScope.earbuds() {
    // Charging case.
    rr(18f, 52f, 82f, 92f, 16f, BodyLight)
    drawLine(Color(0x33000000), Offset(18f, 66f), Offset(82f, 66f), strokeWidth = 1.6f)
    drawCircle(Color(0x44000000), radius = 2.2f, center = Offset(50f, 79f))
    // Two buds standing in the case.
    for (x in listOf(37f, 63f)) {
        rr(x - 3f, 36f, x + 3f, 58f, 3f, BodyLight)
        drawCircle(BodyLight, radius = 10f, center = Offset(x, 30f))
        drawCircle(Color(0x22000000), radius = 5f, center = Offset(x, 30f))
    }
}

private fun DrawScope.speaker() {
    rr(26f, 8f, 74f, 92f, 18f, BodyDark)
    drawCircle(BodyLight, radius = 7f, center = Offset(50f, 26f))
    drawCircle(BodyLight, radius = 19f, center = Offset(50f, 62f))
    drawCircle(Color(0xFF1A1E2E), radius = 12f, center = Offset(50f, 62f))
    drawCircle(BodyLight, radius = 4f, center = Offset(50f, 62f))
}

private fun DrawScope.watch() {
    rr(38f, 6f, 62f, 38f, 6f, BodyDark)
    rr(38f, 62f, 62f, 94f, 6f, BodyDark)
    rr(24f, 26f, 76f, 74f, 16f, BodyLight)
    rr(30f, 32f, 70f, 68f, 11f, BodyDark)
    drawLine(Color(0xFFF0F3FB), Offset(50f, 50f), Offset(50f, 38f), strokeWidth = 3f, cap = StrokeCap.Round)
    drawLine(Color(0xFFF0F3FB), Offset(50f, 50f), Offset(59f, 55f), strokeWidth = 3f, cap = StrokeCap.Round)
}
