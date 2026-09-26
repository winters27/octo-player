package app.winters.octo.sound

import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.playback.storedLoudness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

// Which loudness a song plays at: the tags in its sound, the values its
// source kept, or neither, which leaves the fallback level.
class StoredReplayGainTest {
    private val tags = ReplayGainInfo(trackGain = -6f, trackPeak = 0.9f, albumGain = -7f, albumPeak = 0.95f)
    private val stored = ReplayGainInfo(trackGain = -8.4f, trackPeak = 0.98f, albumGain = -7.9f, albumPeak = 1f)
    private val settings = SoundSettings(replayGain = ReplayGainMode.Track, replayGainPreampDb = 0f, preventClipping = false)

    @Test
    fun tagsInTheSoundWin() {
        assertSame(tags, chooseReplayGain(tags, stored))
        assertSame(tags, chooseReplayGain(tags, null))
    }

    @Test
    fun storedValuesStandInWhenTheSoundHasNone() {
        assertSame(stored, chooseReplayGain(null, stored))
        // A stream with only a peak says nothing about how loud to play.
        assertSame(stored, chooseReplayGain(ReplayGainInfo(trackPeak = 0.7f), stored))
        // An album gain alone is still a gain.
        val albumOnly = ReplayGainInfo(albumGain = -3f)
        assertSame(albumOnly, chooseReplayGain(albumOnly, stored))
    }

    @Test
    fun withNeitherTheFallbackLevelApplies() {
        assertNull(chooseReplayGain(null, null))
        val peakOnly = ReplayGainInfo(trackPeak = 0.7f)
        assertSame(peakOnly, chooseReplayGain(peakOnly, null))
        val fallback = settings.copy(replayGainFallbackDb = -6f)
        val level = replayGainFactor(fallback, SongLoudness(chooseReplayGain(null, null), followsSameAlbum = false))
        assertEquals(0.501f, level, 0.001f)
    }

    @Test
    fun storedValuesSetTheLevel() {
        val chosen = chooseReplayGain(null, stored)
        assertEquals(0.380f, replayGainFactor(settings, SongLoudness(chosen, followsSameAlbum = false)), 0.001f)
    }

    @Test
    fun storedValuesAreCheckedLikeTags() {
        assertNull(storedReplayGain(null, 0.9f, null, 1f))
        assertNull(storedReplayGain(Float.NaN, null, 99f, null))
        assertEquals(
            ReplayGainInfo(trackGain = -5f, trackPeak = null, albumGain = null, albumPeak = 0.8f),
            storedReplayGain(-5f, 0f, 70f, 0.8f),
        )
    }

    @Test
    fun theCopyPlayingGivesItsValuesAndOthersFillIn() {
        val phone = copy("p", "device")
        val server = copy("s", "server:music.example", trackGain = -8.4f, trackPeak = 0.98f)
        val other = copy("o", "server:music.example", trackGain = -2f)

        assertEquals(-8.4f, storedLoudness(server, listOf(phone, server, other))?.trackGain)
        // A phone file with no stored values borrows the server copy's.
        assertEquals(-8.4f, storedLoudness(phone, listOf(phone, server, other))?.trackGain)
        assertNull(storedLoudness(phone, listOf(phone)))
        assertNull(storedLoudness(null, emptyList()))
    }

    private fun copy(id: String, source: String, trackGain: Float? = null, trackPeak: Float? = null) = SourceTrackEntity(
        id = id, sourceId = source, nativeId = id, title = "Nightcall", searchKey = "", sortKey = "",
        artist = "Kavinsky", artistId = "", album = "Nightcall", albumId = "", trackNo = null, discNo = null, year = null,
        durationMs = 258_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
        albumOrder = 0, relinkKey = "", genre = "", trackGain = trackGain, trackPeak = trackPeak,
    )
}
