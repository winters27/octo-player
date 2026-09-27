package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfadeTest {
    private val song = FadeSong(albumId = "a", albumOrder = 3, durationMs = 200_000)
    private val otherAlbum = FadeSong(albumId = "b", albumOrder = 0, durationMs = 180_000)

    @Test
    fun songsFromDifferentAlbumsBlend() {
        assertEquals(6_000L, crossfadeLength(song, otherAlbum, 6_000, repeatOne = false, stopAtEndOfSong = false))
    }

    @Test
    fun theNextSongOfTheSameAlbumStaysGapless() {
        val next = FadeSong("a", 4, 190_000)
        assertEquals(0L, crossfadeLength(song, next, 6_000, repeatOne = false, stopAtEndOfSong = false))
    }

    @Test
    fun theSameAlbumOutOfOrderStillBlends() {
        val shuffled = FadeSong("a", 9, 190_000)
        assertEquals(6_000L, crossfadeLength(song, shuffled, 6_000, repeatOne = false, stopAtEndOfSong = false))
    }

    @Test
    fun songsWithNoAlbumBlend() {
        val loose = FadeSong(null, null, 190_000)
        assertEquals(6_000L, crossfadeLength(loose, loose, 6_000, repeatOne = false, stopAtEndOfSong = false))
    }

    @Test
    fun offWhenTurnedOffOrNothingFollows() {
        assertEquals(0L, crossfadeLength(song, otherAlbum, 0, repeatOne = false, stopAtEndOfSong = false))
        assertEquals(0L, crossfadeLength(song, null, 6_000, repeatOne = false, stopAtEndOfSong = false))
    }

    @Test
    fun repeatOneAndTheSleepTimerEndOfSongNeverBlend() {
        assertEquals(0L, crossfadeLength(song, otherAlbum, 6_000, repeatOne = true, stopAtEndOfSong = false))
        assertEquals(0L, crossfadeLength(song, otherAlbum, 6_000, repeatOne = false, stopAtEndOfSong = true))
    }

    @Test
    fun unknownLengthsNeverBlend() {
        val unknown = FadeSong("c", 0, 0)
        assertEquals(0L, crossfadeLength(unknown, otherAlbum, 6_000, repeatOne = false, stopAtEndOfSong = false))
        assertEquals(0L, crossfadeLength(song, unknown, 6_000, repeatOne = false, stopAtEndOfSong = false))
    }

    @Test
    fun aShortSongShrinksTheFadeToHalfItsLength() {
        val short = FadeSong("c", 0, 8_000)
        assertEquals(4_000L, crossfadeLength(short, otherAlbum, 12_000, repeatOne = false, stopAtEndOfSong = false))
        assertEquals(4_000L, crossfadeLength(song, short, 12_000, repeatOne = false, stopAtEndOfSong = false))
    }

    @Test
    fun aTinySongGetsNoFadeAtAll() {
        val tiny = FadeSong("c", 0, 800)
        assertEquals(0L, crossfadeLength(tiny, otherAlbum, 6_000, repeatOne = false, stopAtEndOfSong = false))
    }

    @Test
    fun theFadeKeepsLoudnessEven() {
        for (step in 0..20) {
            val progress = step / 20f
            val out = fadeOutVolume(progress)
            val into = fadeInVolume(progress)
            assertEquals(1f, out * out + into * into, 0.0001f)
        }
    }

    @Test
    fun theFadeStartsAndEndsCleanlyAndClamps() {
        assertEquals(1f, fadeOutVolume(0f), 0.0001f)
        assertEquals(0f, fadeInVolume(0f), 0.0001f)
        assertEquals(0f, fadeOutVolume(1f), 0.0001f)
        assertEquals(1f, fadeInVolume(1f), 0.0001f)
        assertEquals(1f, fadeInVolume(3f), 0.0001f)
        assertEquals(1f, fadeOutVolume(-1f), 0.0001f)
    }
}
