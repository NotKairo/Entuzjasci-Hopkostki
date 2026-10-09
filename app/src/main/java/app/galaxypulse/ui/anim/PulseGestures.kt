package app.galaxypulse.ui.anim

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** Thin wrapper over [View.performHapticFeedback]; honours the system "touch feedback" setting. */
class PulseHaptics(private val view: View, private val enabled: Boolean) {
    fun tap() = perform(HapticFeedbackConstants.CONTEXT_CLICK)
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)
    fun dismiss() = perform(HapticFeedbackConstants.GESTURE_END)
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)
    fun reject() = perform(HapticFeedbackConstants.REJECT)

    private fun perform(constant: Int) {
        if (enabled) view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberPulseHaptics(enabled: Boolean): PulseHaptics {
    val view = LocalView.current
    return remember(view, enabled) { PulseHaptics(view, enabled) }
}

/** Mutable visual state driven by touch: press squash and horizontal drag. */
@Stable
class PillGestureState(val scope: CoroutineScope, var config: MotionConfig) {
    val pressScale = Animatable(1f)
    val dragX = Animatable(0f)

    suspend fun reset() {
        dragX.snapTo(0f)
        pressScale.snapTo(1f)
    }
}

/**
 * Tap to toggle, press-and-hold squash, horizontal swipe to dismiss. Children that consume their own
 * drags (the seek bar) win over the swipe because pointer events reach children first.
 *
 * @param offscreenPx distance the pill flies to when dismissed, so it fully leaves the screen.
 */
fun Modifier.pillGestures(
    state: PillGestureState,
    tapEnabled: Boolean,
    swipeEnabled: Boolean,
    dismissDistancePx: Float,
    offscreenPx: Float,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
): Modifier = this
    .pointerInput(tapEnabled) {
        detectTapGestures(
            onPress = {
                state.scope.launch { state.pressScale.animateTo(0.965f, PulseMotion.pressSpring(state.config)) }
                tryAwaitRelease()
                state.scope.launch { state.pressScale.animateTo(1f, PulseMotion.pressSpring(state.config)) }
            },
            onTap = { if (tapEnabled) onTap() },
        )
    }
    .pointerInput(swipeEnabled, dismissDistancePx, offscreenPx) {
        if (!swipeEnabled) return@pointerInput
        var lastTime = 0L
        var velocityPxPerSec = 0f

        suspend fun settle() {
            val dx = state.dragX.value
            val flicked = abs(velocityPxPerSec) > FLICK_VELOCITY && sign(velocityPxPerSec) == sign(dx) && dx != 0f
            if (abs(dx) > dismissDistancePx || flicked) {
                val target = if (dx >= 0f) offscreenPx else -offscreenPx
                val duration = if (state.config.reduced) 80 else 190
                state.dragX.animateTo(target, tween(duration, easing = FastOutLinearInEasing))
                onDismiss()
            } else {
                state.dragX.animateTo(0f, PulseMotion.settleSpring(state.config))
            }
        }

        detectHorizontalDragGestures(
            onDragStart = {
                lastTime = SystemClock.uptimeMillis()
                velocityPxPerSec = 0f
            },
            onDragEnd = { state.scope.launch { settle() } },
            onDragCancel = { state.scope.launch { state.dragX.animateTo(0f, PulseMotion.settleSpring(state.config)) } },
        ) { change, dragAmount ->
            change.consume()
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastTime).coerceAtLeast(1L)
            lastTime = now
            velocityPxPerSec = 0.7f * velocityPxPerSec + 0.3f * (dragAmount / dt * 1000f)
            // Slight resistance (like Lenis-style damped follow) so the pill never feels glued to the finger.
            state.scope.launch { state.dragX.snapTo(state.dragX.value + dragAmount * DRAG_RESISTANCE) }
        }
    }

private const val FLICK_VELOCITY = 1100f
private const val DRAG_RESISTANCE = 0.85f
