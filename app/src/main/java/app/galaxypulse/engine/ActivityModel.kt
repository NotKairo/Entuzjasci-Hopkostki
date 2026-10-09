package app.galaxypulse.engine

/** Marker for the data an activity carries. Concrete payloads live next to their data source. */
interface ActivityPayload

/**
 * Everything the overlay can present.
 *
 * [persistent] kinds stay until their source removes them (or an [ActivityEntry.expiresAt] passes).
 * Transient kinds are shown for a limited time once they reach the front, then disappear.
 *
 * [defaultRank]: lower number = more important. [interruptible] only matters for persistent kinds:
 * an interruptible holder (media) is pre-empted by *any* transient event, then restored when the
 * transient ends. A non-interruptible holder (timer, call) is only pre-empted by a strictly
 * better rank.
 */
enum class ActivityKind(
    val persistent: Boolean,
    val defaultRank: Int,
    val interruptible: Boolean,
) {
    Call(persistent = true, defaultRank = 1, interruptible = false),
    TimerDone(persistent = true, defaultRank = 2, interruptible = false),
    BatteryAlert(persistent = false, defaultRank = 2, interruptible = false),
    Timer(persistent = true, defaultRank = 3, interruptible = false),
    Stopwatch(persistent = true, defaultRank = 3, interruptible = false),
    Bluetooth(persistent = false, defaultRank = 4, interruptible = false),
    Charging(persistent = false, defaultRank = 5, interruptible = false),
    Media(persistent = true, defaultRank = 6, interruptible = true),
    Notification(persistent = false, defaultRank = 7, interruptible = false),
}

/** User-configurable priority order. Missing kinds fall back to [ActivityKind.defaultRank]. */
data class PriorityConfig(val ranks: Map<ActivityKind, Int> = emptyMap()) {
    fun rankOf(kind: ActivityKind): Int = ranks[kind] ?: kind.defaultRank
}

/**
 * One thing the overlay may show, identified by [key] (e.g. "media", "timer", "bt:AA:BB:CC",
 * "notif:com.whatsapp:42").
 *
 * [revision] identifies a *genuinely new event* for that key. Updates that carry the same revision
 * refresh the payload without restarting timers or reopening a dismissed presentation; a higher
 * revision is a new event. Revisions come from a single monotonic counter, so a new event for a
 * key always outranks anything the user dismissed before.
 */
data class ActivityEntry(
    val key: String,
    val kind: ActivityKind,
    val payload: ActivityPayload,
    val rank: Int,
    val revision: Long,
    val postedAt: Long,
    /** Transients: how long to show once at the front. */
    val displayMs: Long = 0L,
    /** Transients: dropped silently if still waiting at this time. */
    val staleAt: Long? = null,
    /** Persistent: removed automatically at this time (e.g. paused media). */
    val expiresAt: Long? = null,
    /** Transients: set by the scheduler when the entry first reaches the front. */
    val shownAt: Long? = null,
) {
    val transient: Boolean get() = !kind.persistent
    val shownUntil: Long? get() = shownAt?.let { it + displayMs }
}

data class SchedulerState(
    val entries: Map<String, ActivityEntry> = emptyMap(),
    /** key -> revision the user dismissed. Anything with a revision <= this stays hidden. */
    val dismissed: Map<String, Long> = emptyMap(),
    val frontKey: String? = null,
    /** The entry the user expanded. Only rendered expanded while it is also the front. */
    val expandedKey: String? = null,
) {
    val front: ActivityEntry? get() = frontKey?.let { entries[it] }
    val expanded: Boolean get() = frontKey != null && frontKey == expandedKey
}

sealed interface SchedulerEvent {
    data class Post(val entry: ActivityEntry) : SchedulerEvent
    data class Remove(val key: String) : SchedulerEvent
    data class Dismiss(val key: String) : SchedulerEvent
    data class SetExpanded(val key: String, val expanded: Boolean) : SchedulerEvent
    /** The user changed the priority order; apply it to entries that are already live. */
    data class Rerank(val config: PriorityConfig) : SchedulerEvent
    data object Tick : SchedulerEvent
}
