package app.galaxypulse.overlay

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import app.galaxypulse.AppGraph
import app.galaxypulse.artwork.ArtworkBundle
import app.galaxypulse.engine.ActivityKind
import app.galaxypulse.engine.SchedulerState
import app.galaxypulse.settings.PulseSettings
import app.galaxypulse.ui.anim.MotionConfig
import app.galaxypulse.ui.anim.PulseMotion
import app.galaxypulse.ui.design.GalaxyPulseTheme
import app.galaxypulse.ui.overlay.ActivitySizes
import app.galaxypulse.ui.overlay.BluetoothCardDimens
import app.galaxypulse.ui.overlay.BluetoothCardHost
import app.galaxypulse.ui.overlay.BluetoothCardModel
import app.galaxypulse.ui.overlay.OverlayUi
import app.galaxypulse.ui.overlay.PulseTopOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owns the two overlay windows — the top pill and the bottom Bluetooth card — and everything that
 * makes them behave like part of the system rather than a floating widget:
 *
 *  - `TYPE_APPLICATION_OVERLAY` windows created from a *window context* (so rotation and fold changes
 *    reach them), sized to hug what is visible so taps elsewhere reach the app underneath;
 *  - geometry from the real display cutout and status bar (never a fixed offset);
 *  - windows exist only while there is something to show — when idle no view is attached, no
 *    composition runs, and nothing draws;
 *  - hidden while the screen is off, the device is locked (overlays cannot draw there anyway), or in
 *    landscape if the user asked for that.
 */
class OverlayWindowHost(private val service: Service, private val graph: AppGraph) {

    private val windowContext: Context = run {
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }
    private val windowManager = windowContext.getSystemService(WindowManager::class.java)
    private val owner = OverlayLifecycleOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val displayInfo = MutableStateFlow(DisplayInfoReader.read(windowContext))
    private val screenAllowed = MutableStateFlow(computeScreenAllowed())

    // Top pill
    private var topRoot: OutsideAwareLayout? = null
    private var topParams: WindowManager.LayoutParams? = null
    private var topRect: WindowRect? = null
    private val topUi = mutableStateOf<OverlayUi?>(null)
    private var lastUi: OverlayUi? = null
    private var lastKind = ActivityKind.Media
    private var shrinkJob: Job? = null
    private var removeTopJob: Job? = null
    private var currentSettings = PulseSettings()

    // Bottom card
    private var cardRoot: FrameLayout? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private val cardModel = mutableStateOf<BluetoothCardModel?>(null)
    private val cardMotion = mutableStateOf(MotionConfig())
    private val cardHaptics = mutableStateOf(true)
    private var removeCardJob: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenAllowed.value = computeScreenAllowed()
        }
    }

    fun start() {
        owner.create()
        owner.resume()
        ContextCompat.registerReceiver(
            service, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        graph.log("overlay host started; display: ${DisplayInfoReader.describe(displayInfo.value)}")

        val engine = graph.engine
        scope.launch {
            combine(engine.state, graph.settings, displayInfo, engine.artwork, screenAllowed) { state, settings, display, artwork, allowed ->
                Inputs(state, settings, display, artwork, allowed)
            }.collect(::renderTop)
        }
        scope.launch {
            combine(engine.bluetoothCard, screenAllowed, graph.settings, displayInfo) { model, allowed, settings, display ->
                CardInputs(model.takeIf { allowed }, settings, display)
            }.collect(::renderCard)
        }
    }

    fun stop() {
        scope.cancel()
        runCatching { service.unregisterReceiver(screenReceiver) }
        detachTop()
        detachCard()
        owner.destroy()
        graph.log("overlay host stopped")
    }

    /** Rotation, fold/unfold, display-size change: re-read the geometry; windows re-plan themselves. */
    fun onConfigurationChanged() {
        displayInfo.value = DisplayInfoReader.read(windowContext)
        graph.log("display changed: ${DisplayInfoReader.describe(displayInfo.value)}")
    }

    private fun computeScreenAllowed(): Boolean {
        val power = service.getSystemService(PowerManager::class.java)
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    private fun systemAnimationsOff(): Boolean =
        Settings.Global.getFloat(service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    // ── Top pill ──────────────────────────────────────────────────────────────────────────────

    private data class Inputs(
        val state: SchedulerState,
        val settings: PulseSettings,
        val display: DisplayInfo,
        val artwork: ArtworkBundle?,
        val allowed: Boolean,
    )

    private fun renderTop(inputs: Inputs) {
        val settings = inputs.settings
        currentSettings = settings
        val visible = inputs.allowed && !(settings.hideInLandscape && inputs.display.isLandscape)
        val front = if (visible) inputs.state.front else null

        if (front == null) {
            // Let the pill play its exit animation, then drop the window entirely.
            lastUi?.let { topUi.value = it.copy(front = null, expanded = false) }
            if (topRoot != null && removeTopJob == null) {
                val exitMs = if (MotionConfig.from(settings, systemAnimationsOff()).reduced) PulseMotion.REDUCED_MS + 80L else EXIT_MS
                removeTopJob = scope.launch {
                    delay(exitMs)
                    detachTop()
                    lastUi = null
                }
            }
            return
        }
        removeTopJob?.cancel()
        removeTopJob = null

        val kind = front.kind
        lastKind = kind
        val expanded = inputs.state.expanded
        val display = inputs.display
        val layout = OverlayGeometry.layout(
            display,
            GeometryPrefs(settings.sizeScale, settings.verticalOffsetDp, settings.horizontalOffsetDp, settings.extendBackdropToTopEdge),
            ActivitySizes.compact(kind),
            ActivitySizes.expanded(kind),
        )
        val pad = (WINDOW_PAD_DP * display.density).roundToInt()
        val required = WindowPlanner.required(layout, expanded, pad, display.widthPx, display.heightPx)
        val plan = WindowPlanner.plan(topRect, required)

        // Grow the window *before* the animation starts so a spring never gets clipped...
        applyTopRect(plan.apply, watchOutside = expanded && settings.tapOutsideCollapses)
        shrinkJob?.cancel()
        shrinkJob = null
        // ...and shrink it back only after the collapse has settled, so it stops swallowing touches.
        plan.shrinkTo?.let { target ->
            shrinkJob = scope.launch {
                delay(PulseMotion.COLLAPSE_SETTLE_MS)
                applyTopRect(target, watchOutside = false)
                lastUi?.let { topUi.value = it.copy(windowOrigin = IntOffset(target.left, target.top)).also { updated -> lastUi = updated } }
            }
        }

        val applied = topRect ?: return
        val ui = OverlayUi(
            front = front,
            expanded = expanded,
            layout = layout,
            windowOrigin = IntOffset(applied.left, applied.top),
            settings = settings,
            motion = MotionConfig.from(settings, systemAnimationsOff()),
            artwork = inputs.artwork,
            bootCount = graph.timers.bootCount,
            density = display.density,
        )
        lastUi = ui
        topUi.value = ui
    }

    private fun applyTopRect(rect: WindowRect, watchOutside: Boolean) {
        val flags = BASE_FLAGS or (if (watchOutside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0)
        val existing = topRoot
        if (existing == null) {
            val params = layoutParams(rect, flags)
            val root = OutsideAwareLayout(windowContext) {
                if (currentSettings.tapOutsideCollapses) graph.engine.actions.collapse()
            }
            val compose = ComposeView(windowContext).apply {
                setContent {
                    GalaxyPulseTheme {
                        val ui = topUi.value
                        if (ui != null) PulseTopOverlay(ui, graph.engine.actions)
                    }
                }
            }
            owner.attachTo(root)
            owner.attachTo(compose)
            root.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            try {
                windowManager.addView(root, params)
            } catch (e: Exception) {
                graph.log("could not add the overlay window: ${e.javaClass.simpleName}: ${e.message}")
                return
            }
            topRoot = root
            topParams = params
        } else {
            val params = topParams ?: return
            val changed = params.x != rect.left || params.y != rect.top || params.width != rect.width ||
                params.height != rect.height || params.flags != flags
            if (changed) {
                params.x = rect.left
                params.y = rect.top
                params.width = rect.width
                params.height = rect.height
                params.flags = flags
                try {
                    windowManager.updateViewLayout(existing, params)
                } catch (e: Exception) {
                    graph.log("overlay layout update failed: ${e.message}")
                }
            }
        }
        topRect = rect
    }

    private fun detachTop() {
        shrinkJob?.cancel()
        removeTopJob?.cancel()
        removeTopJob = null
        topRoot?.let { runCatching { windowManager.removeViewImmediate(it) } }
        topRoot = null
        topParams = null
        topRect = null
        topUi.value = null
    }

    private fun layoutParams(rect: WindowRect, flags: Int) = WindowManager.LayoutParams(
        rect.width, rect.height, rect.left, rect.top,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Let the window sit over the cutout area; where the camera is, the artwork backdrop is already black.
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        // Ignore system-bar insets so the frame we compute is exactly the frame we get.
        fitInsetsTypes = 0
        title = "GalaxyPulse"
    }

    // ── Bottom card ───────────────────────────────────────────────────────────────────────────

    private data class CardInputs(val model: BluetoothCardModel?, val settings: PulseSettings, val display: DisplayInfo)

    private fun renderCard(inputs: CardInputs) {
        cardMotion.value = MotionConfig.from(inputs.settings, systemAnimationsOff())
        cardHaptics.value = inputs.settings.haptics
        val model = inputs.model
        if (model == null) {
            cardModel.value = null
            if (cardRoot != null && removeCardJob == null) {
                removeCardJob = scope.launch {
                    delay(CARD_EXIT_MS)
                    detachCard()
                }
            }
            return
        }
        removeCardJob?.cancel()
        removeCardJob = null
        ensureCard(inputs.display)
        cardModel.value = model
    }

    private fun ensureCard(display: DisplayInfo) {
        val d = display.density
        val width = min(display.widthPx, ((BluetoothCardDimens.MAX_WIDTH_DP + 2 * BluetoothCardDimens.SIDE_MARGIN_DP) * d).roundToInt())
        val height = ((BluetoothCardDimens.CARD_HEIGHT_DP + 16f) * d).roundToInt()
        val bottom = DisplayInfoReader.navigationBarInset(windowContext) + (BluetoothCardDimens.BOTTOM_MARGIN_DP * d).roundToInt()
        val existing = cardRoot
        if (existing != null) {
            cardParams?.let { p ->
                if (p.width != width || p.height != height || p.y != bottom) {
                    p.width = width; p.height = height; p.y = bottom
                    runCatching { windowManager.updateViewLayout(existing, p) }
                }
            }
            return
        }
        val params = WindowManager.LayoutParams(
            width, height, 0, bottom,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, BASE_FLAGS, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "GalaxyPulseCard"
        }
        val root = FrameLayout(windowContext)
        val compose = ComposeView(windowContext).apply {
            setContent {
                GalaxyPulseTheme {
                    BluetoothCardHost(cardModel.value, cardMotion.value, graph.engine.actions, cardHaptics.value)
                }
            }
        }
        owner.attachTo(root)
        owner.attachTo(compose)
        root.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        try {
            windowManager.addView(root, params)
            cardRoot = root
            cardParams = params
        } catch (e: Exception) {
            graph.log("could not add the Bluetooth card window: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun detachCard() {
        removeCardJob?.cancel()
        removeCardJob = null
        cardRoot?.let { runCatching { windowManager.removeViewImmediate(it) } }
        cardRoot = null
        cardParams = null
        cardModel.value = null
    }

    private companion object {
        /** Room around the pill for spring overshoot and anti-aliasing. */
        const val WINDOW_PAD_DP = 12f
        const val EXIT_MS = 300L
        const val CARD_EXIT_MS = 340L
        const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
