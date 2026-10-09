package app.flux.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDeduperTest {

    @Test
    fun identicalContentInsideWindowIsRejected() {
        val d = EventDeduper()
        assertTrue(d.accept("n", 42, now = 0, windowMs = 10_000))
        assertFalse(d.accept("n", 42, now = 5_000, windowMs = 10_000))
    }

    @Test
    fun changedContentIsAccepted() {
        val d = EventDeduper()
        assertTrue(d.accept("n", 1, 0, 10_000))
        assertTrue(d.accept("n", 2, 100, 10_000))
    }

    @Test
    fun identicalContentIsAcceptedAfterTheWindow() {
        val d = EventDeduper()
        assertTrue(d.accept("n", 1, 0, 10_000))
        assertTrue(d.accept("n", 1, 10_000, 10_000))
    }

    @Test
    fun differentKeysAreIndependent() {
        val d = EventDeduper()
        assertTrue(d.accept("a", 1, 0, 10_000))
        assertTrue(d.accept("b", 1, 1, 10_000))
    }

    @Test
    fun cooldownIgnoresContent() {
        val d = EventDeduper()
        assertTrue(d.acceptCooldown("bt:1", 0, 30_000))
        assertFalse(d.acceptCooldown("bt:1", 29_999, 30_000))
        assertTrue(d.acceptCooldown("bt:1", 30_000, 30_000))
    }

    @Test
    fun rejectedEventDoesNotExtendTheWindow() {
        val d = EventDeduper()
        assertTrue(d.acceptCooldown("k", 0, 1_000))
        assertFalse(d.acceptCooldown("k", 900, 1_000))
        assertTrue("window is measured from the last *accepted* event", d.acceptCooldown("k", 1_000, 1_000))
    }

    @Test
    fun forgetResetsAKey() {
        val d = EventDeduper()
        d.acceptCooldown("k", 0, 1_000_000)
        d.forget("k")
        assertTrue(d.acceptCooldown("k", 1, 1_000_000))
    }

    @Test
    fun memoryIsBounded() {
        val d = EventDeduper(maxKeys = 4)
        repeat(10) { d.acceptCooldown("k$it", 0, 1_000_000) }
        // The oldest keys were evicted, so they are accepted again.
        assertTrue(d.acceptCooldown("k0", 1, 1_000_000))
    }
}
