package app.galaxypulse.engine

/**
 * Pure reducer for the overlay's activity state machine. No clocks, threads or Android types:
 * the caller passes `now` and arranges a wake-up at [nextWakeup]. That keeps every rule below
 * unit-testable and makes the overlay cost nothing while idle.
 *
 * Selection rule (see [select]):
 *  1. The *holder* is the best-ranked eligible persistent entry (ties: most recently posted).
 *  2. The *candidate* is the best-ranked eligible transient entry (ties: first posted).
 *  3. The candidate takes the front when there is no holder, the holder is interruptible, or the
 *     candidate has a strictly better rank. Otherwise it waits until it goes stale.
 *  When the candidate ends, the holder is simply the front again — that is how a notification
 *  "returns" to the previous media state.
 */
object ActivityScheduler {

    private const val MAX_DISMISSED_REMEMBERED = 64

    fun reduce(state: SchedulerState, event: SchedulerEvent, now: Long): SchedulerState {
        val changed = when (event) {
            is SchedulerEvent.Post -> post(state, event.entry)
            is SchedulerEvent.Remove -> remove(state, event.key)
            is SchedulerEvent.Dismiss -> dismiss(state, event.key)
            is SchedulerEvent.SetExpanded -> setExpanded(state, event.key, event.expanded)
            SchedulerEvent.Tick -> state
        }
        return normalize(changed, now)
    }

    /** Earliest time at which [reduce] with [SchedulerEvent.Tick] would change something. */
    fun nextWakeup(state: SchedulerState): Long? {
        var earliest: Long? = null
        fun consider(t: Long?) {
            if (t != null && (earliest == null || t < earliest!!)) earliest = t
        }
        for (e in state.entries.values) {
            if (e.transient) {
                consider(if (e.shownAt != null) e.shownUntil else e.staleAt)
            } else {
                consider(e.expiresAt)
            }
        }
        return earliest
    }

    private fun post(state: SchedulerState, incoming: ActivityEntry): SchedulerState {
        val existing = state.entries[incoming.key]
        val dismissedRevision = state.dismissed[incoming.key]

        if (existing == null) {
            // A re-delivery of an event the user already dismissed must not reopen it.
            if (dismissedRevision != null && incoming.revision <= dismissedRevision) return state
            return state.copy(entries = state.entries + (incoming.key to incoming))
        }

        return when {
            incoming.revision < existing.revision -> state // stale update, ignore
            incoming.revision == existing.revision -> {
                // Same event: refresh payload/expiry but keep display timing so frequent system
                // updates never restart an animation or extend a toast.
                val merged = incoming.copy(
                    postedAt = existing.postedAt,
                    shownAt = existing.shownAt,
                    staleAt = existing.staleAt ?: incoming.staleAt,
                )
                if (merged == existing) state
                else state.copy(entries = state.entries + (incoming.key to merged))
            }
            else -> state.copy(entries = state.entries + (incoming.key to incoming)) // genuinely new
        }
    }

    private fun remove(state: SchedulerState, key: String): SchedulerState {
        if (key !in state.entries) return state
        return state.copy(
            entries = state.entries - key,
            expandedKey = state.expandedKey.takeUnless { it == key },
        )
    }

    private fun dismiss(state: SchedulerState, key: String): SchedulerState {
        val entry = state.entries[key] ?: return state
        val dismissed = LinkedHashMap(state.dismissed)
        dismissed.remove(key) // re-insert so the newest dismissal is last
        dismissed[key] = entry.revision
        while (dismissed.size > MAX_DISMISSED_REMEMBERED) {
            dismissed.remove(dismissed.keys.first())
        }
        return state.copy(
            // Transients are gone; persistent entries stay (they keep running) but are hidden.
            entries = if (entry.transient) state.entries - key else state.entries,
            dismissed = dismissed,
            expandedKey = state.expandedKey.takeUnless { it == key },
        )
    }

    private fun setExpanded(state: SchedulerState, key: String, expanded: Boolean): SchedulerState {
        if (key !in state.entries) return state
        return when {
            expanded -> state.copy(expandedKey = key)
            state.expandedKey == key -> state.copy(expandedKey = null)
            else -> state
        }
    }

    private fun isEligible(state: SchedulerState, e: ActivityEntry): Boolean {
        val d = state.dismissed[e.key] ?: return true
        return e.revision > d
    }

    /** Chooses the entry to present, or null for Hidden. Exposed for tests. */
    fun select(state: SchedulerState): ActivityEntry? {
        val eligible = state.entries.values.filter { isEligible(state, it) }
        val holder = eligible
            .filter { !it.transient }
            .minWithOrNull(
                compareBy<ActivityEntry> { it.rank }
                    .thenByDescending { it.postedAt }
                    .thenBy { it.key },
            )
        val candidate = eligible
            .filter { it.transient }
            .minWithOrNull(
                compareBy<ActivityEntry> { it.rank }
                    .thenBy { it.postedAt }
                    .thenBy { it.key },
            )
        return when {
            candidate == null -> holder
            holder == null -> candidate
            holder.kind.interruptible || candidate.rank < holder.rank -> candidate
            else -> holder
        }
    }

    private fun normalize(input: SchedulerState, now: Long): SchedulerState {
        // 1. Drop everything whose time is up.
        val live = input.entries.filterValues { e ->
            when {
                e.transient && e.shownAt != null -> now < (e.shownUntil ?: Long.MAX_VALUE)
                e.transient -> e.staleAt == null || now < e.staleAt
                else -> e.expiresAt == null || now < e.expiresAt
            }
        }
        var state = if (live.size == input.entries.size) input else input.copy(entries = live)

        // 2. Pick the front and stamp the moment a transient first reaches it.
        val front = select(state)
        if (front != null && front.transient && front.shownAt == null) {
            val stamped = front.copy(shownAt = now)
            state = state.copy(entries = state.entries + (front.key to stamped))
        }

        // 3. A removed entry can no longer be "expanded".
        val expandedKey = state.expandedKey?.takeIf { it in state.entries }
        return state.copy(frontKey = front?.key, expandedKey = expandedKey)
    }
}
