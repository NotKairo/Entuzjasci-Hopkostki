package app.galaxypulse.engine

/**
 * Suppresses repeated deliveries of the same event. Many sources are chatty: notification apps
 * re-post identical notifications, Bluetooth stacks fire several connection broadcasts per
 * connect, battery changes arrive every percent.
 *
 * An event is accepted when its key is new, its content differs from the last accepted content for
 * that key, or [windowMs] has elapsed since the last accepted one. Not thread-safe; use from one
 * dispatcher.
 */
class EventDeduper(private val maxKeys: Int = 128) {
    private class Seen(val contentHash: Int, val at: Long)

    private val seen = LinkedHashMap<String, Seen>()

    fun accept(key: String, contentHash: Int, now: Long, windowMs: Long): Boolean {
        val previous = seen[key]
        if (previous != null && previous.contentHash == contentHash && now - previous.at < windowMs) {
            return false
        }
        seen.remove(key)
        seen[key] = Seen(contentHash, now)
        while (seen.size > maxKeys) seen.remove(seen.keys.first())
        return true
    }

    /** Cooldown flavour: ignore *any* repeat of [key] inside [windowMs], whatever its content. */
    fun acceptCooldown(key: String, now: Long, windowMs: Long): Boolean {
        val previous = seen[key]
        if (previous != null && now - previous.at < windowMs) return false
        seen.remove(key)
        seen[key] = Seen(0, now)
        while (seen.size > maxKeys) seen.remove(seen.keys.first())
        return true
    }

    fun forget(key: String) {
        seen.remove(key)
    }

    fun clear() = seen.clear()
}
