package app.winters.octo.sound

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.mp3.Mp3InfoReplayGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ReplayGainTest {
    private fun db(factor: Float) = 20 * kotlin.math.log10(factor)

    @Test
    fun readsId3UserTextFrames() {
        val metadata = Metadata(
            TextInformationFrame("TXXX", "replaygain_track_gain", "-6.54 dB"),
            TextInformationFrame("TXXX", "REPLAYGAIN_TRACK_PEAK", "0.988251"),
            TextInformationFrame("TXXX", "REPLAYGAIN_ALBUM_GAIN", "-7.10 dB"),
            TextInformationFrame("TIT2", null, "A title"),
        )
        val info = ReplayGain.read(metadata)!!
        assertEquals(-6.54f, info.trackGain!!, 1e-4f)
        assertEquals(0.988251f, info.trackPeak!!, 1e-6f)
        assertEquals(-7.10f, info.albumGain!!, 1e-4f)
        assertNull(info.albumPeak)
    }

    @Test
    fun readsVorbisCommentsWithCommaDecimals() {
        val metadata = Metadata(
            VorbisComment("REPLAYGAIN_ALBUM_GAIN", "-8,25 dB"),
            VorbisComment("replaygain_album_peak", "1,02"),
            VorbisComment("TITLE", "A title"),
        )
        val info = ReplayGain.read(metadata)!!
        assertEquals(-8.25f, info.albumGain!!, 1e-4f)
        assertEquals(1.02f, info.albumPeak!!, 1e-4f)
        assertNull(info.trackGain)
    }

    @Test
    fun r128GainsAreQuarterDecibelIntegersMovedToTheStandardLevel() {
        // -1792 / 256 = -7 dB against -23 LUFS, which is -2 dB against -18.
        val info = ReplayGain.read(Metadata(VorbisComment("R128_TRACK_GAIN", "-1792"), VorbisComment("R128_ALBUM_GAIN", "256")))!!
        assertEquals(-2f, info.trackGain!!, 1e-4f)
        assertEquals(6f, info.albumGain!!, 1e-4f)
    }

    @Test
    fun standardTagsWinOverR128() {
        val info = ReplayGain.fromTags(listOf("R128_TRACK_GAIN" to "0", "REPLAYGAIN_TRACK_GAIN" to "-3 dB"))!!
        assertEquals(-3f, info.trackGain!!, 1e-4f)
    }

    @Test
    fun readsMp4FreeFormTags() {
        val metadata = Metadata(
            InternalFrame("com.apple.iTunes", "replaygain_track_gain", "+1.20 dB"),
            InternalFrame("com.apple.iTunes", "replaygain_track_peak", "0.5"),
            InternalFrame("com.example.other", "replaygain_album_gain", "-9 dB"),
        )
        val info = ReplayGain.read(metadata)!!
        assertEquals(1.2f, info.trackGain!!, 1e-4f)
        assertEquals(0.5f, info.trackPeak!!, 1e-4f)
        assertNull(info.albumGain)
    }

    @Test
    fun theMp3HeaderIsOnlyALastResort() {
        // Radio (track) gain of -3.0 dB: name 1, originator 3, sign bit set, 30 tenths.
        val header = Mp3InfoReplayGain.parse(0.9f, (1 shl 13) or (3 shl 10) or (1 shl 9) or 30, 0)!!
        val alone = ReplayGain.read(Metadata(header))!!
        assertEquals(-3f, alone.trackGain!!, 1e-4f)
        assertEquals(0.9f, alone.trackPeak!!, 1e-4f)
        val tagged = ReplayGain.read(Metadata(header, TextInformationFrame("TXXX", "REPLAYGAIN_TRACK_GAIN", "-5 dB")))!!
        assertEquals(-5f, tagged.trackGain!!, 1e-4f)
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

    @Test
    fun songsWithoutTagsHaveNoInfo() {
        assertNull(ReplayGain.read(null))
        assertNull(ReplayGain.read(Metadata(VorbisComment("TITLE", "A title"))))
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
