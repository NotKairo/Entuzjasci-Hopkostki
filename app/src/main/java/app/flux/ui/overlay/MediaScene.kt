package app.flux.ui.overlay

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.flux.artwork.ArtworkBundle
import app.flux.media.CustomActionIcons
import app.flux.media.MediaCustomAction
import app.flux.media.MediaSnapshot
import app.flux.media.PlaybackStatus
import app.flux.timers.TimeFormat
import app.flux.ui.anim.MotionConfig
import app.flux.ui.anim.FluxHaptics
import app.flux.ui.anim.FluxMotion
import app.flux.ui.anim.rememberTick
import app.flux.ui.design.FluxColors
import app.flux.ui.design.FluxIcon
import app.flux.ui.design.FluxIconView
import app.flux.ui.design.FluxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun lerp(a: Float, b: Float, t: Float) = PillShapeMath.lerp(a, b, t)

/**
 * Music scene. One artwork thumbnail glides between its compact and expanded slots as the pill
 * morphs; compact and expanded layers cross-reveal on the same progress value, and the expanded
 * layer is only composed once the shape is big enough to hold it.
 */
@Composable
internal fun MediaScene(
    m: SceneMetrics,
    media: MediaSnapshot,
    artwork: ArtworkBundle?,
    ui: OverlayUi,
    actions: OverlayActions,
    haptics: FluxHaptics,
) {
    val accent = artwork?.palette?.accent ?: FluxColors.Blue
    val showExpanded by remember(m) { derivedStateOf { m.progress > 0.30f } }
    val showCompact by remember(m) { derivedStateOf { m.progress < 0.70f } }

    Box(Modifier.fillMaxSize()) {
        if (showCompact) CompactMediaLayer(m, media, accent, ui.settings.showArtistInCompact, ui.motion)
        if (showExpanded) ExpandedMediaLayer(m, media, accent, ui, actions, haptics)
        ArtworkThumb(
            artwork = artwork,
            cornerPx = { m.dp(lerp(8f, 18f, m.progress)) },
            motion = ui.motion,
            modifier = Modifier.placedFree { thumbRect(m) },
        )
    }
}

private fun thumbRect(m: SceneMetrics): Rect {
    val p = m.progress
    val size = lerp(m.dp(26f), m.dp(92f), p)
    val left = lerp(m.compactLeft + m.dp(9f), m.expandedLeft + m.dp(20f), p)
    val top = m.offsetY + lerp((m.dims.compactH - m.dp(26f)) / 2f, m.dp(16f), p)
    return Rect(left, top, left + size, top + size)
}

// ── Compact ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun CompactMediaLayer(
    m: SceneMetrics,
    media: MediaSnapshot,
    accent: Color,
    showArtist: Boolean,
    motion: MotionConfig,
) {
    val fade = { 1f - PillShapeMath.smoothstep(0f, 0.4f, m.progress) }
    Box(
        Modifier
            .placedFree {
                val left = m.compactLeft + m.dp(9f + 26f + 10f)
                val right = m.compactLeft + m.dims.compactW - m.dp(14f + 16f + 8f)
                Rect(left, m.offsetY, right, m.offsetY + m.dims.compactH)
            }
            .graphicsLayer { alpha = fade() },
        contentAlignment = Alignment.CenterStart,
    ) {
        TrackText(media, compact = true, showArtist = showArtist, motion = motion)
    }
    Box(
        Modifier
            .placedFree {
                val w = m.dp(16f)
                val h = m.dp(14f)
                val left = m.compactLeft + m.dims.compactW - m.dp(14f) - w
                val top = m.offsetY + (m.dims.compactH - h) / 2f
                Rect(left, top, left + w, top + h)
            }
            .graphicsLayer { alpha = fade() },
    ) {
        Equalizer(playing = media.isPlaying, color = accent, animate = !motion.reduced)
    }
}

@Composable
private fun TrackText(media: MediaSnapshot, compact: Boolean, showArtist: Boolean, motion: MotionConfig) {
    AnimatedContent(
        targetState = media.title to media.artist,
        transitionSpec = {
            if (motion.reduced) {
                fadeIn(tween(FluxMotion.REDUCED_MS)) togetherWith fadeOut(tween(FluxMotion.REDUCED_MS))
            } else {
                (slideInVertically(tween(FluxMotion.TEXT_SWAP_MS)) { it / 3 } + fadeIn(tween(FluxMotion.TEXT_SWAP_MS))) togetherWith
                    (slideOutVertically(tween(180)) { -it / 3 } + fadeOut(tween(140)))
            }
        },
        label = "track-text",
    ) { (title, artist) ->
        if (compact) {
            Column(verticalArrangement = Arrangement.Center) {
                PText(title.ifBlank { media.appLabel }, FluxType.BodyCompact)
                if (showArtist && artist.isNotBlank()) {
                    PText(artist, FluxType.Label, color = FluxColors.OnSurfaceMuted)
                }
            }
        } else {
            Column {
                PText(title.ifBlank { media.appLabel }, FluxType.Title)
                if (artist.isNotBlank()) {
                    PText(artist, FluxType.Body, color = FluxColors.OnSurfaceMuted)
                }
            }
        }
    }
}

// ── Expanded ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ExpandedMediaLayer(
    m: SceneMetrics,
    media: MediaSnapshot,
    accent: Color,
    ui: OverlayUi,
    actions: OverlayActions,
    haptics: FluxHaptics,
) {
    val reveal = { PillShapeMath.smoothstep(0.55f, 1f, m.progress) }

    Box(
        Modifier
            .placedFree {
                val left = m.expandedLeft + m.dp(20f + 92f + 14f)
                Rect(left, m.offsetY + m.dp(18f), m.expandedLeft + m.dims.expandedW - m.dp(20f), m.offsetY + m.dp(108f))
            }
            .graphicsLayer {
                alpha = reveal()
                translationY = (1f - reveal()) * m.dp(8f)
            },
    ) {
        Column {
            TrackText(media, compact = false, showArtist = true, motion = ui.motion)
            Spacer(Modifier.height(6.dp))
            media.upNext?.let {
                PText("Up next  ·  $it", FluxType.Label, color = FluxColors.OnSurfaceMuted)
                Spacer(Modifier.height(2.dp))
            }
            val footer = listOfNotNull(media.appLabel.takeIf { it.isNotBlank() }, media.outputLabel).joinToString("  ·  ")
            if (footer.isNotEmpty()) {
                Row(
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                    ) { actions.media(MediaCommand.OpenApp) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FluxIconView(FluxIcon.Output, FluxColors.OnSurfaceFaint, size = 13.dp)
                    PText(footer, FluxType.Label, color = FluxColors.OnSurfaceFaint)
                }
            }
        }
    }

    Box(
        Modifier
            .placedFree {
                Rect(
                    m.expandedLeft + m.dp(20f),
                    m.offsetY + m.dp(114f),
                    m.expandedLeft + m.dims.expandedW - m.dp(20f),
                    m.offsetY + m.dp(156f),
                )
            }
            .graphicsLayer { alpha = reveal() },
    ) {
        MediaProgress(media, accent, onSeek = { actions.media(MediaCommand.SeekTo(it)); haptics.tick() })
    }

    Box(
        Modifier
            .placedFree {
                Rect(
                    m.expandedLeft + m.dp(12f),
                    m.offsetY + m.dp(158f),
                    m.expandedLeft + m.dims.expandedW - m.dp(12f),
                    m.offsetY + m.dp(208f),
                )
            }
            .graphicsLayer { alpha = reveal() },
    ) {
        MediaControls(media, actions, haptics)
    }
}

@Composable
private fun MediaProgress(media: MediaSnapshot, accent: Color, onSeek: (Long) -> Unit) {
    // Ticks only while a song is actually playing; a paused card does no periodic work.
    val tick by rememberTick(active = media.isPlaying, intervalMs = 250L)
    @Suppress("UNUSED_VARIABLE") val subscribe = tick
    val now = SystemClock.elapsedRealtime()

    var scrub by remember { mutableStateOf<Float?>(null) }
    // After a seek the bar holds the target for a moment so it doesn't snap back before the session confirms.
    var pending by remember(media.reportedAtElapsedMs, media.reportedPositionMs) { mutableStateOf<Pair<Float, Long>?>(null) }
    val pendingFraction = pending?.takeIf { now - it.second < 1200L }?.first

    val canSeek = media.caps.canSeek && media.hasDuration && media.hasPosition
    val actual = media.progressAt(now)
    val shown = scrub ?: pendingFraction ?: actual
    val positionMs: Long? = when {
        media.hasDuration && shown != null && (scrub != null || pendingFraction != null) -> (shown * media.durationMs).toLong()
        else -> media.positionAt(now)
    }

    Column(Modifier.fillMaxWidth()) {
        SeekBar(
            progress = shown,
            seekable = canSeek,
            accent = accent,
            onScrub = { scrub = it },
            onCommit = { fraction ->
                pending = fraction to SystemClock.elapsedRealtime()
                onSeek((fraction * media.durationMs).toLong())
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PText(
                positionMs?.let { TimeFormat.clockFloor(it) }.orEmpty(),
                FluxType.Caption.copy(fontFeatureSettings = "tnum"),
                color = FluxColors.OnSurfaceFaint,
            )
            if (media.hasDuration) {
                PText(
                    TimeFormat.clockFloor(media.durationMs),
                    FluxType.Caption.copy(fontFeatureSettings = "tnum"),
                    color = FluxColors.OnSurfaceFaint,
                )
            }
        }
    }
}

@Composable
private fun MediaControls(media: MediaSnapshot, actions: OverlayActions, haptics: FluxHaptics) {
    val caps = media.caps
    val showPause = media.status == PlaybackStatus.Playing || media.status == PlaybackStatus.Buffering
    val left = media.customActions.getOrNull(0)
    val right = media.customActions.getOrNull(1)
    Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (left != null) CustomActionButton(left) { haptics.tap(); actions.media(MediaCommand.Custom(left)) }
        FluxRoundButton(
            icon = FluxIcon.Previous,
            enabled = caps.canSkipPrevious,
            onClick = { haptics.tap(); actions.media(MediaCommand.Previous) },
        )
        FluxRoundButton(
            icon = if (showPause) FluxIcon.Pause else FluxIcon.Play,
            enabled = if (showPause) caps.canPause else caps.canPlay,
            size = 56.dp,
            iconSize = 28.dp,
            container = Color.White.copy(alpha = 0.16f),
            onClick = {
                haptics.tap()
                actions.media(if (showPause) MediaCommand.Pause else MediaCommand.Play)
            },
        )
        FluxRoundButton(
            icon = FluxIcon.Next,
            enabled = caps.canSkipNext,
            onClick = { haptics.tap(); actions.media(MediaCommand.Next) },
        )
        if (right != null) CustomActionButton(right) { haptics.tap(); actions.media(MediaCommand.Custom(right)) }
    }
}

/** A session-published action (shuffle, repeat…) drawn with the icon from the media app itself. */
@Composable
private fun CustomActionButton(action: MediaCustomAction, onClick: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val icon by produceState<ImageBitmap?>(CustomActionIcons.peek(action), action) {
        value = withContext(Dispatchers.Default) { CustomActionIcons.load(context, action) }
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = action.label,
                colorFilter = ColorFilter.tint(FluxColors.OnSurfaceMuted),
                modifier = Modifier.size(22.dp),
            )
        } else {
            PText(action.label.take(3), FluxType.Label, color = FluxColors.OnSurfaceMuted)
        }
    }
}
