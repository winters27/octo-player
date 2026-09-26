package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRefTest {
    private val flac = StreamRef("server:music.example", "tr-42", "audio/flac", 900_000)
    private val mp3 = StreamRef("server:music.example", "tr-7", "audio/mpeg", 256_000)

    @Test
    fun aSongReadsBackAsItWasWritten() {
        assertEquals(flac, parseStreamUri(streamUri(flac)))
        val pinned = flac.pin(StreamRequest(192))
        assertEquals(pinned, parseStreamUri(streamUri(pinned)))
        val raw = flac.pin(StreamRequest(null))
        assertEquals(raw, parseStreamUri(streamUri(raw)))
    }

    @Test
    fun oddIdsAndServersSurviveTheAddress() {
        val odd = StreamRef("server:host:4533", "a/b c+d&e=f", "audio/x-flac", null)
        assertEquals(odd, parseStreamUri(streamUri(odd)))
        val bare = StreamRef(null, "123", null, null)
        assertEquals(bare, parseStreamUri(streamUri(bare)))
    }

    @Test
    fun anythingElseIsNotAServerSong() {
        assertNull(parseStreamUri("content://media/external/audio/media/12"))
        assertNull(parseStreamUri("file:///storage/Music/Octo/song.flac"))
        assertNull(parseStreamUri("https://music.example/rest/stream?id=1"))
        assertNull(parseStreamUri("octo-stream://album/1"))
        assertNull(parseStreamUri("not a uri at all"))
    }

    @Test
    fun theKeyNamesTheSongNeverTheSignedAddress() {
        val key = streamCacheKey("server:music.example", flac, StreamRequest(null))
        assertEquals("server:music.example|tr-42|audio/flac|raw", key)
        assertEquals("server:music.example|tr-42|audio/flac|192", streamCacheKey("server:music.example", flac, StreamRequest(192)))
        // Nothing random goes in, so the same song always finds its saved copy.
        assertEquals(key, streamCacheKey("server:music.example", parseStreamUri(streamUri(flac))!!, StreamRequest(null)))
        assertFalse(key.contains("salt") || key.contains("token") || key.contains("http"))
    }

    @Test
    fun eachServerTypeAndRequestIsItsOwnCopy() {
        val raw = StreamRequest(null)
        val base = streamCacheKey("server:a", flac, raw)
        assertNotEquals(base, streamCacheKey("server:b", flac, raw))
        assertNotEquals(base, streamCacheKey("server:a", flac.copy(mimeType = "audio/mpeg"), raw))
        assertNotEquals(base, streamCacheKey("server:a", flac, StreamRequest(320)))
        assertNotEquals(base, streamCacheKey("server:a", flac.copy(serverId = "tr-43"), raw))
    }

    @Test
    fun variantsRunFromTheFileAsItIsDownToTheSmallestMp3() {
        assertEquals(
            listOf(StreamRequest(null), StreamRequest(320), StreamRequest(256), StreamRequest(192), StreamRequest(128)),
            requestVariants(flac),
        )
        // A 256 kbps MP3 plays as it is at 256 and above.
        assertEquals(listOf(StreamRequest(null), StreamRequest(192), StreamRequest(128)), requestVariants(mp3))
    }

    @Test
    fun theConnectionsSizeIsAskedForWhenNothingIsSaved() {
        assertEquals(StreamRequest(192), chooseRequest("server:a", flac, StreamQuality.Kbps192, fullySaved = { false }))
        assertEquals(StreamRequest(null), chooseRequest("server:a", flac, StreamQuality.Original, fullySaved = { false }))
    }

    @Test
    fun aWholeSavedCopyInAnotherSizePlaysInstead() {
        val saved = setOf(streamCacheKey("server:a", flac, StreamRequest(null)))
        assertEquals(StreamRequest(null), chooseRequest("server:a", flac, StreamQuality.Kbps128, fullySaved = { it in saved }))
    }

    @Test
    fun theWantedSizeWinsWhenItIsSavedToo() {
        val saved = setOf(
            streamCacheKey("server:a", flac, StreamRequest(null)),
            streamCacheKey("server:a", flac, StreamRequest(128)),
        )
        assertEquals(StreamRequest(128), chooseRequest("server:a", flac, StreamQuality.Kbps128, fullySaved = { it in saved }))
    }

    @Test
    fun aSongStartedInOneSizeKeepsItWhenTheConnectionChanges() {
        val started = setOf(streamCacheKey("server:a", flac, StreamRequest(null)))
        val picked = chooseRequest("server:a", flac, StreamQuality.Kbps192, fullySaved = { false }, partlySaved = { it in started })
        assertEquals(StreamRequest(null), picked)
    }

    @Test
    fun aPinnedRequestIsNeverChanged() {
        val pinned = flac.pin(StreamRequest(256))
        assertEquals(StreamRequest(256), chooseRequest("server:a", pinned, StreamQuality.Original, fullySaved = { true }))
        assertEquals(StreamRequest(256), pinned.request(StreamQuality.Original))
        assertTrue(flac.request(StreamQuality.Kbps128) == StreamRequest(128))
    }
}
