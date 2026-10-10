package app.winters.octo.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

// What the phone shows and times a song by: the length the player measured
// of its sound, else its listing, corrected where playing it found it wrong.
class SongLengthsTest {
    @Test
    fun thePlayersMeasureWinsOnceItHasOne() {
        assertEquals(291_000L, songLengthMs(listedMs = 296_000, playerMs = 291_000))
        // Not opened yet: Media3 says C.TIME_UNSET, and the listing stands in.
        assertEquals(296_000L, songLengthMs(listedMs = 296_000, playerMs = C.TIME_UNSET))
        assertEquals(0L, songLengthMs(listedMs = 0, playerMs = C.TIME_UNSET))
        assertEquals(296_000L, songLengthMs(listedMs = 296_000, playerMs = 0))
    }

    // The queue's rows and the next song's blend read the listing through
    // the corrections, so a song found wrong once shows right everywhere.
    @Test
    fun aCorrectedListingReachesEveryRowOfTheSong() {
        val lengths = RealLengths()
        lengths.learn("find:a", listedMs = 296_000, playerMs = 291_000)
        assertEquals(291_000L, songLengthMs(lengths.lengthMs("find:a", 296_000), C.TIME_UNSET))
        assertEquals(200_000L, songLengthMs(lengths.lengthMs("t-1", 200_000), C.TIME_UNSET))
    }
}
