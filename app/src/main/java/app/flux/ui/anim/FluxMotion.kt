package app.flux.ui.anim

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import app.flux.settings.MotionPreset
import app.flux.settings.FluxSettings
import app.flux.settings.ReducedMotionMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Everything that decides *how* things move. Resolved once from settings + system state. */
@Immutable
data class MotionConfig(
    val reduced: Boolean = false,
    val preset: MotionPreset = MotionPreset.Balanced,
    val intensity: Float = 1f,
) {
    companion object {
        /**
         * @param systemAnimationsOff true when the system animator duration scale is 0 (Developer
         * options / Settings → Accessibility → Remove animations).
         */
        fun from(settings: FluxSettings, systemAnimationsOff: Boolean): MotionConfig = MotionConfig(
            reduced = when (settings.reducedMotion) {
                ReducedMotionMode.FollowSystem -> systemAnimationsOff
                ReducedMotionMode.On -> true
                ReducedMotionMode.Off -> false
            },
            preset = settings.motionPreset,
            intensity = settings.animationIntensity,
        )
    }
}

/** Counts animations that are currently running so Diagnostics can show that idle really is idle. */
object AnimationTracker {
    private val running = AtomicInteger(0)
    val activeAnimations: Int get() = running.get()
    suspend fun <T> track(block: suspend () -> T): T {
        running.incrementAndGet()
        try {
            return block()
        } finally {
            running.decrementAndGet()
        }
    }
}

/**
 * Animation tokens. Starting values from the brief (expand ~250–350 ms, artwork 350–600 ms, short
 * notifications 180–250 ms) are encoded as spring parameters whose settle time lands in those ranges;
 * tune on a physical device.
 */
object FluxMotion {
    const val ARTWORK_CROSSFADE_MS = 480
    const val TEXT_SWAP_MS = 260
    const val CONTENT_FADE_MS = 200
    const val NOTIFICATION_MS = 220
    const val REDUCED_MS = 140

    /** How long to keep a grown window after a collapse starts, before shrinking it to the pill. */
    const val COLLAPSE_SETTLE_MS = 480L

    fun <T> reducedTween(): AnimationSpec<T> = tween(REDUCED_MS, easing = FastOutSlowInEasing)

    /** Higher intensity -> less damping -> more bounce. 0 intensity = critically damped. */
    private fun damping(base: Float, config: MotionConfig): Float =
        (1f - (1f - base) * config.intensity).coerceIn(0.40f, 1f)

    private fun tunedSpring(base: Float, stiffness: Float, config: MotionConfig): AnimationSpec<Float> =
        spring(dampingRatio = damping(base, config), stiffness = stiffness, visibilityThreshold = 0.5f)

    /**
     * Width leads height when growing, height leads width when shrinking — that asymmetry is what
     * makes the shape feel like a pill unfolding rather than a box scaling.
     */
    fun frameSprings(config: MotionConfig, expanding: Boolean): FrameSprings {
        if (config.reduced) {
            val t = reducedTween<Float>()
            return FrameSprings(t, t, t, t)
        }
        data class P(val wD: Float, val wS: Float, val hD: Float, val hS: Float, val tD: Float, val tS: Float)
        val p = when (config.preset) {
            MotionPreset.Calm -> if (expanding) P(0.95f, 420f, 0.95f, 360f, 0.95f, 380f) else P(0.98f, 380f, 0.98f, 520f, 0.98f, 520f)
            MotionPreset.Balanced -> if (expanding) P(0.78f, 560f, 0.84f, 400f, 0.88f, 440f) else P(0.92f, 430f, 0.92f, 620f, 0.92f, 620f)
            MotionPreset.Lively -> if (expanding) P(0.62f, 600f, 0.70f, 430f, 0.78f, 480f) else P(0.80f, 450f, 0.80f, 650f, 0.80f, 650f)
        }
        return FrameSprings(
            width = tunedSpring(p.wD, p.wS, config),
            height = tunedSpring(p.hD, p.hS, config),
            top = tunedSpring(p.tD, p.tS, config),
            centerX = tunedSpring(0.9f, 500f, config),
        )
    }

    /** Spring used for press feedback and the sparing squash-and-stretch pulse. */
    fun pressSpring(config: MotionConfig): AnimationSpec<Float> =
        if (config.reduced) reducedTween() else tunedSpring(0.55f, 700f, config)

    fun settleSpring(config: MotionConfig): AnimationSpec<Float> =
        if (config.reduced) reducedTween() else tunedSpring(0.8f, 500f, config)

    /** Slight shape pulse on content changes, scaled by intensity and disabled when reduced. */
    fun pulseAmount(config: MotionConfig): Float = if (config.reduced) 0f else 0.03f * config.intensity
}

@Stable
class FrameSprings(
    val width: AnimationSpec<Float>,
    val height: AnimationSpec<Float>,
    val top: AnimationSpec<Float>,
    val centerX: AnimationSpec<Float>,
)

/** Where the visible shape should be (screen pixels). */
@Immutable
data class FrameTarget(
    val centerX: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * The shape's geometry as four independent animatables. Because each is a plain [Animatable],
 * retargeting mid-flight continues from the current value *and velocity* — nothing ever jumps back
 * to a starting pose when a new event arrives.
 */
@Stable
class FluxFrameState(initial: FrameTarget) {
    val centerX = Animatable(initial.centerX)
    val top = Animatable(initial.top)
    val width = Animatable(initial.width)
    val height = Animatable(initial.height)

    suspend fun animateTo(target: FrameTarget, config: MotionConfig) {
        val expanding = target.height > height.value + 0.5f || target.width > width.value + 0.5f
        val springs = FluxMotion.frameSprings(config, expanding)
        AnimationTracker.track {
            coroutineScope {
                launch { width.animateTo(target.width, springs.width) }
                launch { height.animateTo(target.height, springs.height) }
                launch { top.animateTo(target.top, springs.top) }
                launch { centerX.animateTo(target.centerX, springs.centerX) }
            }
        }
    }

    suspend fun snapTo(target: FrameTarget) {
        width.snapTo(target.width)
        height.snapTo(target.height)
        top.snapTo(target.top)
        centerX.snapTo(target.centerX)
    }
}

@Composable
fun rememberFluxFrame(target: FrameTarget, config: MotionConfig): FluxFrameState {
    val state = remember { FluxFrameState(target) }
    LaunchedEffect(target, config) { state.animateTo(target, config) }
    return state
}

/**
 * A timestamp that advances every [intervalMs], aligned to interval boundaries, only while
 * [active]. When nothing is visible or ticking (paused media, collapsed idle pill) no coroutine
 * runs and nothing recomposes.
 */
@Composable
fun rememberTick(active: Boolean, intervalMs: Long = 1000L): State<Long> {
    val state = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(active, intervalMs) {
        if (!active) return@LaunchedEffect
        while (true) {
            state.longValue = SystemClock.elapsedRealtime()
            val untilNextBoundary = intervalMs - (SystemClock.elapsedRealtime() % intervalMs)
            delay(untilNextBoundary.coerceAtLeast(16L))
        }
    }
    return state
}
