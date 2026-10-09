package app.galaxypulse.notifications

import app.galaxypulse.notifications.NotificationPrivacy.VISIBILITY_PRIVATE
import app.galaxypulse.notifications.NotificationPrivacy.VISIBILITY_PUBLIC
import app.galaxypulse.notifications.NotificationPrivacy.VISIBILITY_SECRET
import app.galaxypulse.settings.NotificationContentMode.AppOnly
import app.galaxypulse.settings.NotificationContentMode.Full
import app.galaxypulse.settings.NotificationContentMode.HideWhenLocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationPrivacyTest {

    private fun decide(mode: app.galaxypulse.settings.NotificationContentMode, locked: Boolean, vis: Int, appHides: Boolean = false) =
        NotificationPrivacy.decide(mode, locked, vis, appHides)

    @Test
    fun secretNotificationsAreNeverShownWhileLocked() {
        for (mode in listOf(Full, HideWhenLocked, AppOnly)) {
            assertEquals(ContentVisibility.Suppress, decide(mode, locked = true, vis = VISIBILITY_SECRET))
        }
    }

    @Test
    fun unlockedShowsEverythingUnlessTheUserOptedOut() {
        assertEquals(ContentVisibility.ShowAll, decide(Full, false, VISIBILITY_PRIVATE))
        assertEquals(ContentVisibility.ShowAll, decide(HideWhenLocked, false, VISIBILITY_SECRET))
        assertEquals(ContentVisibility.ShowAppOnly, decide(AppOnly, false, VISIBILITY_PUBLIC))
    }

    @Test
    fun defaultModeHidesContentWhileLockedEvenForPublicNotifications() {
        assertEquals(ContentVisibility.ShowAppOnly, decide(HideWhenLocked, true, VISIBILITY_PUBLIC))
        assertEquals(ContentVisibility.ShowAppOnly, decide(HideWhenLocked, true, VISIBILITY_PRIVATE))
    }

    @Test
    fun fullModeStillHonoursTheNotificationsOwnLockScreenPrivacy() {
        assertEquals(ContentVisibility.ShowAll, decide(Full, true, VISIBILITY_PUBLIC))
        assertEquals(ContentVisibility.ShowAppOnly, decide(Full, true, VISIBILITY_PRIVATE))
    }

    @Test
    fun perAppHideContentWinsOverEverythingExceptSuppress() {
        assertEquals(ContentVisibility.ShowAppOnly, decide(Full, false, VISIBILITY_PUBLIC, appHides = true))
        assertEquals(ContentVisibility.Suppress, decide(Full, true, VISIBILITY_SECRET, appHides = true))
    }

    @Test
    fun clipTrimsAndEllipsises() {
        assertNull(NotificationPrivacy.clip(null, 10))
        assertNull(NotificationPrivacy.clip("   ", 10))
        assertEquals("hello", NotificationPrivacy.clip("  hello ", 10))
        assertEquals("abcd…", NotificationPrivacy.clip("abcdefghij", 5))
    }
}
