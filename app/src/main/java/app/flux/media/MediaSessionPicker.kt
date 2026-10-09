package app.flux.media

/**
 * Chooses which of several active media sessions the overlay follows. Pure, so the "which player wins"
 * rules (the thing users notice most when several apps are open) are tested rather than guessed.
 *
 * Candidates arrive in the system's own priority order (most recently active first). Rules:
 *  1. The first session that is playing (or buffering) wins.
 *  2. Otherwise stay with the session we were already following, as long as it still exists —
 *     pausing one app must not make the overlay jump to another app's stale session.
 *  3. Otherwise the first paused session, then simply the first one.
 *  Sessions from apps the user ignored are never considered.
 */
object MediaSessionPicker {

    data class Candidate(val id: String, val status: PlaybackStatus, val ignored: Boolean = false)

    fun pick(candidates: List<Candidate>, currentId: String?): String? {
        val usable = candidates.filterNot { it.ignored }
        return usable.firstOrNull { it.status == PlaybackStatus.Playing || it.status == PlaybackStatus.Buffering }?.id
            ?: usable.firstOrNull { it.id == currentId }?.id
            ?: usable.firstOrNull { it.status == PlaybackStatus.Paused }?.id
            ?: usable.firstOrNull()?.id
    }
}
