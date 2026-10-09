package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlendTimingTest {
    private val playing = FadeSong(albumId = "a", albumOrder = 3, durationMs = 200_000)

    // A radio song from outside the library: listed with no length.
    private val outside = FadeSong(albumId = null, albumOrder = null, durationMs = 0)

    @Test
    fun anUnknownLengthComesFromTheSpareOnceItHasOpened() {
        assertEquals(0L, nextSongLengthMs(listedMs = 0, spareMs = null))
        assertEquals(214_000L, nextSongLengthMs(listedMs = 0, spareMs = 214_000))
        // The list's own length wins when it has one.
        assertEquals(190_000L, nextSongLengthMs(listedMs = 190_000, spareMs = 214_000))
        // A spare that has not measured it yet does not count.
        assertEquals(0L, nextSongLengthMs(listedMs = 0, spareMs = 0))
    }

    @Test
    fun aRadioSongWithItsRealLengthBlends() {
        val measured = outside.copy(durationMs = nextSongLengthMs(0, 214_000))
        assertEquals(6_000L, crossfadeLength(playing, measured, 6_000, repeatOne = false, stopAtEndOfSong = false))
    }

    @Test
    fun onlyAnUnknownLengthIsWaitedOn() {
        assertTrue(waitsOnLength(playing, outside, 6_000, repeatOne = false, stopAtEndOfSong = false))
        // Known lengths are decided at once.
        assertFalse(waitsOnLength(playing, outside.copy(durationMs = 190_000), 6_000, false, false))
        // Every other rule still says no blend, whatever the length.
        assertFalse(waitsOnLength(playing, outside, 0, false, false))
        assertFalse(waitsOnLength(playing, outside, 6_000, repeatOne = true, stopAtEndOfSong = false))
        assertFalse(waitsOnLength(playing, outside, 6_000, repeatOne = false, stopAtEndOfSong = true))
        assertFalse(waitsOnLength(playing, outside.copy(albumId = "a", albumOrder = 4), 6_000, false, false))
        assertFalse(waitsOnLength(playing, null, 6_000, false, false))
    }

    @Test
    fun anUnknownLengthLoadsFifteenSecondsBeforeTheLongestBlend() {
        assertEquals(21_000L, loadLeadMs(blendMs = 0, fadeMs = 6_000, lengthKnown = false))
        assertEquals(11_000L, loadLeadMs(blendMs = 6_000, fadeMs = 6_000, lengthKnown = true))
    }
}
