package app.winters.octo.sound

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.mp3.Mp3InfoReplayGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayGainMetadataTest {
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
    fun songsWithoutTagsHaveNoInfo() {
        assertNull(ReplayGain.read(null))
        assertNull(ReplayGain.read(Metadata(VorbisComment("TITLE", "A title"))))
    }

    private val tagged = ReplayGainInfo(trackGain = -6f, trackPeak = 0.9f, albumGain = -8f, albumPeak = 0.95f)
}
