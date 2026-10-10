package app.winters.octo.playback

import app.winters.octo.subsonic.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

// The length of a song is the length of its sound once the player has
// measured it; the listing only stands in until then.
class SongLengthTest {
    @Test
    fun thePlayersMeasureWinsOnceItHasOne() {
        assertEquals(291_000, playingLengthMs(listedMs = 296_000, playerMs = 291_000))
        assertEquals(301_000, playingLengthMs(listedMs = 296_000, playerMs = 301_000))
        // Not measured yet: the listing stands in.
        assertEquals(296_000, playingLengthMs(listedMs = 296_000, playerMs = null))
        assertEquals(0, playingLengthMs(listedMs = 0, playerMs = null))
        assertEquals(0, playingLengthMs(listedMs = -5, playerMs = null))
    }

    @Test
    fun aPlaceholderOrNonsenseIsNotAMeasure() {
        assertNull(measuredLengthMs(0))
        assertNull(measuredLengthMs(-1))
        // A player's "unknown", like Media3's C.TIME_UNSET.
        assertNull(measuredLengthMs(Long.MIN_VALUE + 1))
        assertNull(measuredLengthMs(400))
        assertNull(measuredLengthMs(25 * 60 * 60_000L))
        assertEquals(296_000, playingLengthMs(listedMs = 296_000, playerMs = 0))
        assertEquals(1_000, measuredLengthMs(1_000))
    }

    // A stream that measured only what it had so far, then played on past
    // it: the measure is wrong, and the listing stands in again.
    @Test
    fun aMeasureTheSongPlayedPastIsNotBelieved() {
        assertEquals(10_000, playingLengthMs(listedMs = 296_000, playerMs = 10_000, positionMs = 10_500))
        assertEquals(296_000, playingLengthMs(listedMs = 296_000, playerMs = 10_000, positionMs = 12_000))
        assertEquals(0, playingLengthMs(listedMs = 0, playerMs = 10_000, positionMs = 12_000))
    }

    @Test
    fun aListingWithinASecondIsRight() {
        assertNull(correctedLengthMs(listedMs = 291_000, playerMs = 291_600))
        assertNull(correctedLengthMs(listedMs = 291_000, playerMs = 290_000))
        assertEquals(289_900, correctedLengthMs(listedMs = 291_000, playerMs = 289_900))
        assertEquals(291_000, correctedLengthMs(listedMs = 296_000, playerMs = 291_000))
        // No listed length: any believable measure is news.
        assertEquals(291_000, correctedLengthMs(listedMs = 0, playerMs = 291_000))
        assertNull(correctedLengthMs(listedMs = 296_000, playerMs = null))
        assertNull(correctedLengthMs(listedMs = 296_000, playerMs = 0))
    }

    @Test
    fun aCorrectedSongCarriesWholeSeconds() {
        val song = Song("s", "Song", duration = 296)
        assertEquals(291, song.withLengthMs(291_000).duration)
        assertEquals(291, song.withLengthMs(291_499).duration)
        assertEquals(292, song.withLengthMs(291_500).duration)
    }

    @Test
    fun aWrongListingIsCorrectedEverywhereItIsRead() {
        val lengths = RealLengths()
        val listed = Song("s", "Song", duration = 296)
        assertSame(listed, lengths.applyTo(listed))
        assertEquals(296_000, lengths.lengthMs("s", 296_000))
        assertEquals(291_000, lengths.learn("s", listedMs = 296_000, playerMs = 291_000))
        assertEquals(mapOf("s" to 291_000L), lengths.corrected.value)
        assertEquals(291_000, lengths.lengthMs("s", 296_000))
        assertEquals(291, lengths.applyTo(listed).duration)
        // Played again from its corrected listing: nothing more to fix.
        assertNull(lengths.learn("s", listedMs = 291_000, playerMs = 291_200))
        assertEquals(mapOf("s" to 291_000L), lengths.corrected.value)
        // Other songs are left as listed.
        assertEquals(200_000, lengths.lengthMs("t", 200_000))
    }

    @Test
    fun aRightListingIsNotRecorded() {
        val lengths = RealLengths()
        assertNull(lengths.learn("s", listedMs = 291_000, playerMs = 291_400))
        assertNull(lengths.learn("s", listedMs = 291_000, playerMs = null))
        assertEquals(emptyMap(), lengths.corrected.value)
    }
}
