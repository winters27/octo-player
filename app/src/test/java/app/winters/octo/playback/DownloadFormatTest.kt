package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

// A download can be made as Opus or AAC instead of MP3; streaming and every
// older address and saved name stay as they were.
class DownloadFormatTest {
    private val flac = StreamRef("s", "tr-1", "audio/flac", null)

    @Test
    fun theServerIsAskedForTheChosenFormat() {
        val opus = streamRequest("audio/flac", 900_000, StreamQuality.Kbps128, TranscodeFormat.Opus)
        assertEquals(mapOf("format" to "opus", "maxBitRate" to "128", "estimateContentLength" to "true"), opus.params)
        assertEquals("audio/opus", opus.mimeType("audio/flac"))
        assertEquals("audio/mpeg", streamRequest("audio/flac", 900_000, StreamQuality.Kbps128).mimeType("audio/flac"))
    }

    @Test
    fun aSmallMp3IsKeptForMp3ButMadeAgainForAnotherFormat() {
        assertEquals(StreamRequest(null), streamRequest("audio/mpeg", 128_000, StreamQuality.Kbps256))
        assertEquals(StreamRequest(256, TranscodeFormat.Opus), streamRequest("audio/mpeg", 128_000, StreamQuality.Kbps256, TranscodeFormat.Opus))
        assertEquals(StreamRequest(null), streamRequest("audio/opus", 96_000, StreamQuality.Kbps256, TranscodeFormat.Opus))
    }

    @Test
    fun theFormatSurvivesTheAddress() {
        val pinned = flac.pin(StreamRequest(128, TranscodeFormat.Opus))
        assertEquals(pinned, parseStreamUri(streamUri(pinned)))
        // MP3 writes nothing extra, so addresses are what they were.
        assertEquals(false, "fmt" in streamUri(flac.pin(StreamRequest(128))))
        assertNull(parseStreamUri(streamUri(flac))!!.pinned)
    }

    @Test
    fun eachFormatIsSavedApart() {
        val mp3 = streamCacheKey("s", flac, StreamRequest(128))
        val opus = streamCacheKey("s", flac, StreamRequest(128, TranscodeFormat.Opus))
        assertEquals("s|tr-1|audio/flac|128", mp3)
        assertEquals("s|tr-1|audio/flac|opus128", opus)
        assertNotEquals(mp3, opus)
        val sized = flac.copy(size = 5000)
        assertEquals(opus, cacheKeyWithoutSize(streamCacheKey("s", sized, StreamRequest(128, TranscodeFormat.Opus))))
    }
}
