package app.winters.octo.offline

import app.winters.octo.playback.SavedCopies
import app.winters.octo.playback.StreamQuality
import app.winters.octo.playback.StreamRef
import app.winters.octo.playback.StreamRequest
import app.winters.octo.playback.savedCopyKey
import app.winters.octo.playback.streamCacheKey
import app.winters.octo.playback.streamRequest
import org.junit.Assert.assertEquals
import org.junit.Test

// Someone who downloaded MP3s at a cap before Download format existed now
// has Opus as their format. Every MP3 they have stays found, answers the
// swapped-file check as before, and its streamed copy keeps its name.
class ExistingDownloadsTest {
    private fun legacy(sizeBytes: Long, fileSize: Long?, serverSize: Long?, serverType: String = "audio/flac") =
        DownloadedFile("server:a", "tr-1", "audio/mpeg", sizeBytes, fileSize, serverSize, serverType, path = "/music/Octo/Artist - Song [a1b2c3d4].mp3")

    @Test
    fun anOldMp3DownloadAnswersTheCheckAsBefore() {
        assertEquals(KeptCopyCheck.Keep, checkDownload(legacy(7_000_000, 31_000_000, 31_000_000)))
        assertEquals(KeptCopyCheck.Refetch, checkDownload(legacy(7_000_000, 31_000_000, 28_500_000)))
        assertEquals(KeptCopyCheck.Note, checkDownload(legacy(7_000_000, null, 31_000_000)))
        assertEquals(KeptCopyCheck.Note, checkDownload(legacy(7_000_000, null, 9_000_000, serverType = "audio/mpeg")))
        assertEquals(KeptCopyCheck.Keep, checkDownload(legacy(7_000_000, null, null)))
    }

    @Test
    fun theRulesAddNothingForADownloadedSong() {
        val plan = planDownloads(held = mapOf("tr-1" to setOf(Reasons.LIKED)), wanted = mapOf("tr-1" to setOf(Reasons.LIKED)), downloadable = setOf("tr-1"))
        assertEquals(DownloadPlan(), plan)
    }

    @Test
    fun aStreamedCopyIsFoundUnderTheNameItAlwaysHad() {
        val ref = StreamRef("server:a", "tr-1", "audio/flac", 900_000, size = 31_000_000)
        val request = streamRequest("audio/flac", 900_000, StreamQuality.Kbps128)
        val key = streamCacheKey("server:a", ref, request)
        assertEquals("server:a|tr-1|audio/flac|128|31000000", key)
        val saved = object : SavedCopies {
            override fun has(key: String) = key == "server:a|tr-1|audio/flac|128"
            override fun length(key: String) = 3_840_000L
            override fun notedSize(key: String): Long? = null
            override fun note(key: String, size: Long) {}
            override fun drop(key: String) = error("an old MP3 copy is never let go")
        }
        // A copy saved before names carried the file's size is still read.
        assertEquals("server:a|tr-1|audio/flac|128", savedCopyKey("server:a", ref, request, saved))
        assertEquals(StreamRequest(128), request)
    }
}
