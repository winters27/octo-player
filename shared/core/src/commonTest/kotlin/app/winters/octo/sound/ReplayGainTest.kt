package app.winters.octo.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ReplayGainTest {
    private fun db(factor: Float) = 20 * kotlin.math.log10(factor)

    @Test
    fun standardTagsWinOverR128() {
        val info = ReplayGain.fromTags(listOf("R128_TRACK_GAIN" to "0", "REPLAYGAIN_TRACK_GAIN" to "-3 dB"))!!
        assertEquals(-3f, info.trackGain!!, 1e-4f)
    }

    @Test
    fun readsTheShapesGainsComeIn() {
        assertEquals(-6.54f, ReplayGain.parseGain("-6.54 dB")!!, 1e-4f)
        assertEquals(-6.54f, ReplayGain.parseGain("-6,54 dB")!!, 1e-4f)
        assertEquals(2.1f, ReplayGain.parseGain("+2.1dB")!!, 1e-4f)
        assertEquals(0.5f, ReplayGain.parseGain("  0.5 DB ")!!, 1e-4f)
        assertEquals(-4f, ReplayGain.parseGain("-4")!!, 1e-4f)
        assertNull(ReplayGain.parseGain("loud"))
        assertNull(ReplayGain.parseGain("-300 dB"))
        assertNull(ReplayGain.parsePeak("0"))
    }

    private val tagged = ReplayGainInfo(trackGain = -6f, trackPeak = 0.9f, albumGain = -8f, albumPeak = 0.95f)

    @Test
    fun theModeChoosesTrackOrAlbum() {
        val song = SongLoudness(tagged, followsSameAlbum = false)
        val off = SoundSettings(replayGain = ReplayGainMode.Off)
        assertEquals(1f, replayGainFactor(off, song), 0f)
        assertEquals(-6f, db(replayGainFactor(SoundSettings(replayGain = ReplayGainMode.Track), song)), 1e-4f)
        assertEquals(-8f, db(replayGainFactor(SoundSettings(replayGain = ReplayGainMode.Album), song)), 1e-4f)
    }

    @Test
    fun eachFallsBackToTheOther() {
        val trackOnly = SongLoudness(ReplayGainInfo(trackGain = -4f), false)
        val albumOnly = SongLoudness(ReplayGainInfo(albumGain = -5f), false)
        assertEquals(-4f, db(replayGainFactor(SoundSettings(replayGain = ReplayGainMode.Album), trackOnly)), 1e-4f)
        assertEquals(-5f, db(replayGainFactor(SoundSettings(replayGain = ReplayGainMode.Track), albumOnly)), 1e-4f)
    }

    @Test
    fun thePreampAddsAndTheFallbackCoversUntaggedSongs() {
        val settings = SoundSettings(replayGain = ReplayGainMode.Track, replayGainPreampDb = 2f, replayGainFallbackDb = -7f, preventClipping = false)
        assertEquals(-4f, db(replayGainFactor(settings, SongLoudness(tagged, false))), 1e-4f)
        assertEquals(-7f, db(replayGainFactor(settings, SongLoudness(null, false))), 1e-4f)
        assertEquals(-7f, db(replayGainFactor(settings, null)), 1e-4f)
    }

    @Test
    fun preventingClippingStopsAtThePeak() {
        val loud = SongLoudness(ReplayGainInfo(trackGain = 6f, trackPeak = 0.8f), false)
        val guarded = SoundSettings(replayGain = ReplayGainMode.Track, preventClipping = true)
        assertEquals(1f / 0.8f, replayGainFactor(guarded, loud), 1e-5f)
        val open = guarded.copy(preventClipping = false)
        assertEquals(10f.pow(6f / 20f), replayGainFactor(open, loud), 1e-5f)
        // A cut is never touched by the guard.
        val quiet = SongLoudness(ReplayGainInfo(trackGain = -6f, trackPeak = 0.8f), false)
        assertEquals(-6f, db(replayGainFactor(guarded, quiet)), 1e-4f)
    }

    @Test
    fun smartUsesTheAlbumOnlyWhileAnAlbumPlays() {
        val smart = SoundSettings(replayGain = ReplayGainMode.Smart)
        assertEquals(-6f, db(replayGainFactor(smart, SongLoudness(tagged, followsSameAlbum = false))), 1e-4f)
        assertEquals(-8f, db(replayGainFactor(smart, SongLoudness(tagged, followsSameAlbum = true))), 1e-4f)
    }

    @Test
    fun theAlbumRunFollowsConsecutiveSongs() {
        val run = AlbumRun()
        assertFalse(run.follows("a1", "A"))
        assertTrue(run.follows("a2", "A"))
        assertTrue(run.follows("a3", "A"))
        // Set up again, the same song keeps its answer.
        assertTrue(run.follows("a3", "A"))
        assertFalse(run.follows("b1", "B"))
        assertFalse(run.follows("x", null))
        assertFalse(run.follows("y", null))
    }
}
