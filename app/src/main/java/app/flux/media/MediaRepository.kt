package app.flux.media

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.flux.artwork.ArtworkBundle
import app.flux.artwork.ArtworkProcessor
import app.flux.ui.overlay.MediaCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class MediaAccess { Unknown, Granted, Denied }

/**
 * Follows the active MediaSession of any standard player (Spotify, YouTube Music, Samsung Music…) and
 * forwards transport commands to the real session. Reading sessions requires notification-listener
 * access: [attach] is called by the listener service once Android has connected it.
 *
 * Everything shown comes from the session. Nothing is invented: no artwork -> no artwork, no position
 * -> no progress, unsupported action -> disabled control.
 */
class MediaRepository(
    context: Context,
    private val artwork: ArtworkProcessor,
    private val scope: CoroutineScope,
    private val ignoredApps: () -> Set<String>,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _snapshot = MutableStateFlow<MediaSnapshot?>(null)
    val snapshot: StateFlow<MediaSnapshot?> = _snapshot

    private val _artwork = MutableStateFlow<ArtworkBundle?>(null)
    val artworkBundle: StateFlow<ArtworkBundle?> = _artwork

    private val _access = MutableStateFlow(MediaAccess.Unknown)
    val access: StateFlow<MediaAccess> = _access

    private class Tracked(val id: String, val controller: MediaController, val callback: MediaController.Callback)

    /** Kept in the system's priority order (most recently active first). */
    private var tracked = LinkedHashMap<MediaSession.Token, Tracked>()
    private var nextId = 0
    private var currentId: String? = null
    private var listenerComponent: ComponentName? = null
    private var lastArtworkKey: String? = null
    private var artworkJob: kotlinx.coroutines.Job? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        sync(controllers.orEmpty())
    }
    private val refreshRunnable = Runnable { refresh() }

    fun attach(component: ComponentName) {
        listenerComponent = component
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            _access.value = MediaAccess.Granted
            sync(manager.getActiveSessions(component))
        } catch (_: SecurityException) {
            _access.value = MediaAccess.Denied
        }
    }

    fun detach() {
        try {
            manager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        tracked.values.forEach { it.controller.unregisterCallback(it.callback) }
        tracked = LinkedHashMap()
        currentId = null
        handler.removeCallbacks(refreshRunnable)
        _snapshot.value = null
        _artwork.value = null
        lastArtworkKey = null
        _access.value = MediaAccess.Unknown
    }

    // ── Session bookkeeping ───────────────────────────────────────────────────────────────────

    private fun sync(controllers: List<MediaController>) {
        val next = LinkedHashMap<MediaSession.Token, Tracked>()
        for (c in controllers) {
            val token = c.sessionToken
            next[token] = tracked[token] ?: track(c)
        }
        for ((token, t) in tracked) {
            if (token !in next) t.controller.unregisterCallback(t.callback)
        }
        tracked = next
        scheduleRefresh()
    }

    private fun track(controller: MediaController): Tracked {
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = scheduleRefresh()
            override fun onMetadataChanged(metadata: MediaMetadata?) = scheduleRefresh()
            override fun onSessionDestroyed() = scheduleRefresh()
        }
        controller.registerCallback(callback, handler)
        return Tracked("s${nextId++}", controller, callback)
    }

    /** Players fire several callbacks per track change; coalesce them into one refresh. */
    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS)
    }

    private fun refresh() {
        val ignored = ignoredApps()
        val candidates = tracked.values.map {
            MediaSessionPicker.Candidate(it.id, statusOf(it.controller.playbackState), it.controller.packageName in ignored)
        }
        currentId = MediaSessionPicker.pick(candidates, currentId)
        val chosen = tracked.values.firstOrNull { it.id == currentId }
        if (chosen == null) {
            _snapshot.value = null
            _artwork.value = null
            lastArtworkKey = null
            return
        }
        val meta = chosen.controller.metadata
        val key = artworkKey(chosen.controller.packageName, meta)
        _snapshot.value = buildSnapshot(chosen.controller, meta, key)
        updateArtwork(key, meta)
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────────────────────

    private fun statusOf(state: PlaybackState?): PlaybackStatus = when (state?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> PlaybackStatus.Playing
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> PlaybackStatus.Buffering
        PlaybackState.STATE_PAUSED -> PlaybackStatus.Paused
        else -> PlaybackStatus.Stopped
    }

    private fun buildSnapshot(controller: MediaController, meta: MediaMetadata?, artworkKey: String?): MediaSnapshot {
        val state = controller.playbackState
        val status = statusOf(state)
        val actions = state?.actions ?: 0L
        fun has(flag: Long) = actions and flag != 0L

        val title = meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        val speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f

        return MediaSnapshot(
            packageName = controller.packageName,
            appLabel = appLabel(controller.packageName),
            title = title,
            artist = artist,
            album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            status = status,
            reportedPositionMs = state?.position ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
            reportedAtElapsedMs = state?.lastPositionUpdateTime ?: 0L,
            speed = speed,
            durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L } ?: 0L,
            caps = TransportCaps(
                canPlay = has(PlaybackState.ACTION_PLAY) || has(PlaybackState.ACTION_PLAY_PAUSE),
                canPause = has(PlaybackState.ACTION_PAUSE) || has(PlaybackState.ACTION_PLAY_PAUSE),
                canSkipNext = has(PlaybackState.ACTION_SKIP_TO_NEXT),
                canSkipPrevious = has(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
                canSeek = has(PlaybackState.ACTION_SEEK_TO),
            ),
            artworkKey = artworkKey,
            customActions = state?.customActions.orEmpty().take(2).map {
                MediaCustomAction(it.action, it.name?.toString().orEmpty(), it.icon, controller.packageName)
            },
            outputLabel = outputLabel(),
            upNext = upNext(controller, state),
        )
    }

    /** The queue entry after the active one, if the session exposes a queue at all. */
    private fun upNext(controller: MediaController, state: PlaybackState?): String? {
        val queue = controller.queue ?: return null
        val activeId = state?.activeQueueItemId ?: return null
        val index = queue.indexOfFirst { it.queueId == activeId }
        if (index < 0) return null
        return queue.getOrNull(index + 1)?.description?.title?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun appLabel(packageName: String): String = try {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    /** Name of a connected non-built-in output (Bluetooth, wired, USB) if the platform exposes one. */
    private fun outputLabel(): String? = try {
        val audio = appContext.getSystemService(AudioManager::class.java)
        val devices: List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= 33) {
            audio.getAudioDevicesForAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        devices.firstOrNull { it.type in EXTERNAL_OUTPUT_TYPES }?.productName?.toString()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    // ── Artwork ───────────────────────────────────────────────────────────────────────────────

    private fun artworkBitmap(meta: MediaMetadata?): Bitmap? =
        meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    private fun artworkUri(meta: MediaMetadata?): Uri? =
        listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .firstNotNullOfOrNull { meta?.getString(it)?.takeIf(String::isNotBlank) }
            ?.let { Uri.parse(it) }

    /** Identity of a cover; null when the session offers none we could read. */
    private fun artworkKey(pkg: String, meta: MediaMetadata?): String? {
        if (meta == null) return null
        val bitmap = artworkBitmap(meta)
        val uri = artworkUri(meta)
        val source = when {
            bitmap != null -> "bmp:${bitmap.generationId}:${bitmap.width}x${bitmap.height}"
            uri != null -> "uri:$uri"
            else -> return null
        }
        return "$pkg|${meta.getString(MediaMetadata.METADATA_KEY_ALBUM)}|${meta.getString(MediaMetadata.METADATA_KEY_TITLE)}|$source"
    }

    private fun updateArtwork(key: String?, meta: MediaMetadata?) {
        if (key == lastArtworkKey) return
        lastArtworkKey = key
        artworkJob?.cancel()
        if (key == null) {
            _artwork.value = null
            return
        }
        artwork.cached(key)?.let { _artwork.value = it; return }
        val bitmap = artworkBitmap(meta)
        val uri = artworkUri(meta)
        artworkJob = scope.launch {
            // The previous cover stays visible until the new one is ready, then the UI crossfades.
            val bundle = artwork.process(key, bitmap, uri)
            if (key == lastArtworkKey) _artwork.value = bundle
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────────────────────

    private fun currentController(): MediaController? = tracked.values.firstOrNull { it.id == currentId }?.controller

    fun send(command: MediaCommand) {
        val controller = currentController() ?: return
        val controls = controller.transportControls
        when (command) {
            MediaCommand.Play -> controls.play()
            MediaCommand.Pause -> controls.pause()
            MediaCommand.Next -> controls.skipToNext()
            MediaCommand.Previous -> controls.skipToPrevious()
            is MediaCommand.SeekTo -> controls.seekTo(command.positionMs)
            is MediaCommand.Custom -> controls.sendCustomAction(command.action.actionId, null)
            MediaCommand.OpenApp -> openPlayer(controller)
        }
    }

    private fun openPlayer(controller: MediaController) {
        try {
            val session: PendingIntent? = controller.sessionActivity
            if (session != null) {
                session.send()
            } else {
                appContext.packageManager.getLaunchIntentForPackage(controller.packageName)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(it)
                }
            }
        } catch (_: Exception) {
            // Activity start can be refused (e.g. blocked background start); nothing else to do.
        }
    }

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 120L
        val EXTERNAL_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        )
    }
}
