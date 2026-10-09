package app.galaxypulse

import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves the unit-test task is wired up on CI. Real tests live next to the code they cover. */
class ToolchainSanityTest {
    @Test
    fun arithmeticStillWorks() {
        assertEquals(4, 2 + 2)
    }
}
