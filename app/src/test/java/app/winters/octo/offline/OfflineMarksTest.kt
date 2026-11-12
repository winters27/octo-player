package app.winters.octo.offline

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.onAllNodesWithText
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.playback.StreamRef
import app.winters.octo.playback.StreamRequest
import app.winters.octo.playback.streamCacheKey
import app.winters.octo.testing.RobolectricComposeRule
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.common.LocalDownloadLookup
import app.winters.octo.ui.common.LocalOfflineMarks
import app.winters.octo.ui.common.isDownloaded
import app.winters.octo.ui.common.isOutOfReach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

// A song row asks for its own song whether it is downloaded, through the
// marks' lookup, and asks again only when the set of downloaded songs can
// have changed: the mark turns on with the finish, and progress ticks and
// failures ask nothing. With no connection a song that cannot play is
// faint, and only a song that could be asks.
@RunWith(RobolectricTestRunner::class)
class OfflineMarksTest {
    @get:Rule
    val ui = RobolectricComposeRule()

    private val db = openTestCatalog()
    private val book = DownloadBook(db.downloadDao())

    @After
    fun close() = db.close()

    private class CountingLookup(private val book: DownloadBook) : DownloadLookup {
        val asked = AtomicInteger()

        override suspend fun isDone(trackId: String): Boolean {
            asked.incrementAndGet()
            return book.isDone(trackId)
        }
    }

    private fun track(id: String, onPhone: Boolean, nativeId: String = id) = TrackEntity(
        id = id, sourceId = "server:music.example", nativeId = nativeId, title = "", searchKey = "", sortKey = "",
        artist = "", artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = null, durationMs = 0,
        addedAt = 0, mimeType = "audio/flac", sizeBytes = null, artwork = null, uri = null, onPhone = onPhone,
    )

    private fun download(trackId: String, serverId: String = trackId, state: DownloadStatus = DownloadStatus.Queued) = DownloadEntity(
        trackId = trackId, sourceId = "server:music.example", serverId = serverId, path = "", sizeBytes = 0, format = null,
        state = state, progress = 0f, addedAt = 1, reason = Reasons.MANUAL, title = trackId, artist = "",
    )

    private fun shows(text: String) {
        ui.compose.waitUntil(5_000) { ui.compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun asked(lookup: CountingLookup, times: Int) {
        ui.compose.waitUntil(5_000) { lookup.asked.get() >= times }
        ui.compose.waitForIdle()
        assertEquals(times, lookup.asked.get())
    }

    @Test
    fun aSavedKeyNamesItsSong() {
        val key = streamCacheKey("server:music.example", StreamRef(null, "tr-1", "audio/flac", null), StreamRequest(192))
        assertEquals(savedSong("server:music.example", "tr-1"), savedSongOf(key))
        val sized = StreamRef(null, "tr-1", "audio/flac", null, size = 31_000_000)
        assertEquals(savedSong("server:music.example", "tr-1"), savedSongOf(streamCacheKey("server:music.example", sized, StreamRequest(null))))
        assertEquals(savedSong("server:music.example", "tr-1"), savedSongOf(streamCacheKey("server:music.example", sized, StreamRequest(192))))
    }

    @Test
    fun aRowsMarkTurnsOnWithTheFinishAndNothingElseAsksAgain() {
        runBlocking { book.upsert(listOf(download("t1"), download("t2"))) }
        val lookup = CountingLookup(book)
        val song = track("t1", onPhone = false)
        ui.compose.setContent {
            val version by book.doneVersion.collectAsState()
            CompositionLocalProvider(LocalOfflineMarks provides OfflineMarks(doneVersion = version), LocalDownloadLookup provides lookup) {
                BasicText(if (isDownloaded(song)) "downloaded" else "streams")
            }
        }
        asked(lookup, 1)
        shows("streams")
        runBlocking {
            book.markDownloading("server:music.example", "t1")
            repeat(10) { book.setProgress("server:music.example", "t1", it / 10f) }
            repeat(5) { book.setState("server:music.example", "t2", DownloadStatus.Failed) }
            book.setReason("server:music.example", "t2", Reasons.LIKED)
        }
        ui.compose.waitForIdle()
        assertEquals("ticks, failures and reasons ask nothing", 1, lookup.asked.get())
        ui.compose.onAllNodesWithText("streams").fetchSemanticsNodes().single()
        runBlocking { book.finish("server:music.example", "t1", "/music/t1.flac", 10, "audio/flac", 10) }
        shows("downloaded")
        assertEquals(2, lookup.asked.get())
    }

    @Test
    fun withNoConnectionOnlySongsThatCannotPlayAreFaint() {
        runBlocking { book.upsert(listOf(download("down", state = DownloadStatus.Done))) }
        val marks = OfflineMarks(offline = true, saved = setOf(savedSong("server:music.example", "cached")))
        assertFalse(marks.outOfReachUnlessDownloaded(track("phone", onPhone = true)))
        assertFalse(marks.outOfReachUnlessDownloaded(track("x", onPhone = false, nativeId = "cached")))
        assertTrue(marks.outOfReachUnlessDownloaded(track("stream", onPhone = false)))
        // With a connection, everything can play.
        assertFalse(marks.copy(offline = false).outOfReachUnlessDownloaded(track("stream", onPhone = false)))

        val lookup = CountingLookup(book)
        val songs = listOf(track("phone", onPhone = true), track("x", onPhone = false, nativeId = "cached"), track("down", onPhone = false), track("stream", onPhone = false))
        ui.compose.setContent {
            CompositionLocalProvider(LocalOfflineMarks provides marks, LocalDownloadLookup provides lookup) {
                songs.forEach { BasicText("${it.id} " + if (isOutOfReach(it)) "faint" else "plays") }
            }
        }
        // Only the two that could be out of reach ask.
        asked(lookup, 2)
        shows("down plays")
        shows("stream faint")
        shows("phone plays")
        shows("x plays")
    }
}
