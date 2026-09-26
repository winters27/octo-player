package app.winters.octo.playback

import app.winters.octo.player.PlayerPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadsetResumeTest {
    private val both = PlayerPrefs(resumeWired = true, resumeBluetooth = true)
    private val now = 100 * 60_000L

    private fun decide(
        kind: HeadsetKind = HeadsetKind.Bluetooth,
        prefs: PlayerPrefs = both,
        playing: Boolean = false,
        hasQueue: Boolean = true,
        lostOutputAt: Long? = now - 60_000,
    ) = shouldResumeOnConnect(kind, prefs, playing, hasQueue, lostOutputAt, now)

    @Test
    fun resumesWhenTheOutputWentAwayRecently() {
        assertTrue(decide())
        assertTrue(decide(kind = HeadsetKind.Wired))
        assertTrue(decide(lostOutputAt = now - RESUME_WINDOW_MS))
    }

    @Test
    fun notAfterHalfAnHour() {
        assertFalse(decide(lostOutputAt = now - RESUME_WINDOW_MS - 1))
    }

    @Test
    fun notWhenPausedSomeOtherWay() {
        assertFalse(decide(lostOutputAt = null))
    }

    @Test
    fun eachKindHasItsOwnSwitch() {
        assertFalse(decide(kind = HeadsetKind.Wired, prefs = PlayerPrefs(resumeBluetooth = true)))
        assertFalse(decide(kind = HeadsetKind.Bluetooth, prefs = PlayerPrefs(resumeWired = true)))
        assertTrue(decide(kind = HeadsetKind.Wired, prefs = PlayerPrefs(resumeWired = true)))
    }

    @Test
    fun offByDefault() {
        assertFalse(decide(prefs = PlayerPrefs()))
        assertFalse(decide(kind = HeadsetKind.Wired, prefs = PlayerPrefs()))
    }

    @Test
    fun alwaysPlayIgnoresWhyItPaused() {
        val always = both.copy(resumeAlways = true)
        assertTrue(decide(prefs = always, lostOutputAt = null))
        assertTrue(decide(prefs = always, lostOutputAt = now - 5 * RESUME_WINDOW_MS))
        // But still only for a kind that is switched on.
        assertFalse(decide(prefs = PlayerPrefs(resumeAlways = true), lostOutputAt = null))
    }

    @Test
    fun nothingWhenAlreadyPlayingOrNothingQueued() {
        assertFalse(decide(playing = true))
        assertFalse(decide(hasQueue = false))
        assertFalse(decide(prefs = both.copy(resumeAlways = true), hasQueue = false))
    }

    @Test
    fun aClockGoingBackDoesNotCount() {
        assertFalse(decide(lostOutputAt = now + 1_000))
    }

    @Test
    fun devicesAreSortedIntoKinds() {
        // Wired headphones, a wired headset, a USB headset.
        assertEquals(HeadsetKind.Wired, headsetKind(4))
        assertEquals(HeadsetKind.Wired, headsetKind(3))
        assertEquals(HeadsetKind.Wired, headsetKind(22))
        // Bluetooth music, and Bluetooth LE headsets and speakers.
        assertEquals(HeadsetKind.Bluetooth, headsetKind(8))
        assertEquals(HeadsetKind.Bluetooth, headsetKind(26))
        assertEquals(HeadsetKind.Bluetooth, headsetKind(27))
        // The speaker, and Bluetooth for calls, are not headphones coming back.
        assertNull(headsetKind(2))
        assertNull(headsetKind(7))
    }
}
