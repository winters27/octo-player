package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A download asks for the chosen format with no estimated length; a stream
// asks for an MP3 with one, as it always did.
@RunWith(RobolectricTestRunner::class)
class DownloadFormatRequestTest {
    private val flac = StreamRef("s", "tr-1", "audio/flac", 900_000)

    @Test
    fun aDownloadAsksTheFormatAndNoEstimate() {
        val opus = downloadRequest("audio/flac", 900_000, StreamQuality.Kbps128, DownloadFormat.Opus)
        assertEquals(mapOf("format" to "opus", "maxBitRate" to "128"), opus.params)
        assertEquals(mapOf("format" to "aac", "maxBitRate" to "128"), downloadRequest("audio/flac", 900_000, StreamQuality.Kbps128, DownloadFormat.Aac).params)
        assertEquals(mapOf("format" to "mp3", "maxBitRate" to "128"), downloadRequest("audio/flac", 900_000, StreamQuality.Kbps128, DownloadFormat.Mp3).params)
        // A lossy file within the cap comes as it is, whatever the format.
        assertEquals(StreamRequest(null), downloadRequest("audio/mpeg", 128_000, StreamQuality.Kbps256, DownloadFormat.Opus))
        assertEquals(StreamRequest(null), downloadRequest("audio/flac", 900_000, StreamQuality.Original, DownloadFormat.Opus))
    }

    @Test
    fun aStreamIsAsItWas() {
        assertEquals(
            mapOf("format" to "mp3", "maxBitRate" to "128", "estimateContentLength" to "true"),
            streamRequest("audio/flac", 900_000, StreamQuality.Kbps128).params,
        )
        assertEquals("audio/mpeg", streamRequest("audio/flac", 900_000, StreamQuality.Kbps128).mimeType("audio/flac"))
    }

    @Test
    fun theFormatAndTheNoEstimateMarkSurviveTheAddress() {
        val pinned = flac.pin(StreamRequest(128, DownloadFormat.Opus, estimate = false))
        assertEquals(pinned, parseStreamUri(streamUri(pinned)))
        val mp3Download = flac.pin(StreamRequest(128, DownloadFormat.Mp3, estimate = false))
        assertEquals(mp3Download, parseStreamUri(streamUri(mp3Download)))
        // A stream's address is what it always was.
        val stream = streamUri(flac.pin(StreamRequest(128)))
        assertFalse(stream, "fmt=" in stream || "est=" in stream)
    }

    @Test
    fun eachFormatIsSavedApartAndAnMp3KeepsItsName() {
        val mp3 = streamCacheKey("s", flac, StreamRequest(128))
        assertEquals("s|tr-1|audio/flac|128", mp3)
        assertEquals("s|tr-1|audio/flac|128", streamCacheKey("s", flac, StreamRequest(128, DownloadFormat.Mp3, estimate = false)))
        assertEquals("s|tr-1|audio/flac|opus128", streamCacheKey("s", flac, StreamRequest(128, DownloadFormat.Opus)))
        assertEquals("s|tr-1|audio/flac|aac128", streamCacheKey("s", flac, StreamRequest(128, DownloadFormat.Aac)))
        assertNotEquals(mp3, streamCacheKey("s", flac, StreamRequest(128, DownloadFormat.Opus)))
        val sized = flac.copy(size = 5000)
        for (format in DownloadFormat.entries) {
            val request = StreamRequest(128, format)
            assertEquals(streamCacheKey("s", flac, request), cacheKeyWithoutSize(streamCacheKey("s", sized, request)))
        }
        assertEquals("s|tr-1|audio/flac|raw", cacheKeyWithoutSize("s|tr-1|audio/flac|raw|5000"))
    }

    // An Opus download never reads a saved MP3 stream copy of the same song.
    @Test
    fun anOpusDownloadLooksUnderItsOwnName() {
        val saved = setOf(streamCacheKey("s", flac, StreamRequest(128)))
        val download = flac.pin(downloadRequest("audio/flac", 900_000, StreamQuality.Kbps128, DownloadFormat.Opus))
        assertTrue(streamCacheKey("s", download, download.pinned!!) !in saved)
    }

    @Test
    fun progressWithoutALengthComesFromTheBitrate() {
        assertEquals(3_840_000L, expectedBytes(240_000, 128))
        assertEquals(0L, expectedBytes(240_000, null))
    }

    // A download or relay that reads a song whose server answer changed
    // since its saved copy was made lets the copy go instead of reading on.
    @Test
    fun aDownloadReadsThroughTheShapeGuard() {
        val dropped = ArrayList<String>()
        val shapes = object : StreamShapes {
            override fun shape(key: String) = StreamShape("audio/mp4", 4_000_000)
            override fun note(key: String, shape: StreamShape) {}
            override fun drop(key: String) {
                dropped += key
            }
        }
        val upstream = androidx.media3.datasource.DataSource.Factory {
            object : androidx.media3.datasource.BaseDataSource(true) {
                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long = 3_000_000
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                override fun getUri(): android.net.Uri? = null
                override fun getResponseHeaders(): Map<String, List<String>> = mapOf("Content-Type" to listOf("audio/mpeg"), "Content-Length" to listOf("3000000"))
                override fun close() {}
            }
        }
        val chain = downloadChain(upstream, { true }, shapes) { null }.createDataSource()
        val spec = androidx.media3.datasource.DataSpec.Builder().setUri("octo-stream://song/tr-1").setKey("k").build()
        val thrown = runCatching { chain.open(spec) }.exceptionOrNull()
        assertTrue(thrown is StreamChanged)
        assertEquals(listOf("k"), dropped)
    }
}
