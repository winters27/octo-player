package app.winters.octo.player

import app.winters.octo.subsonic.PICKED_OCTO
import app.winters.octo.subsonic.PICKED_PERSON
import app.winters.octo.subsonic.PLAYS_FROM_STREAM
import app.winters.octo.subsonic.StreamVersion
import app.winters.octo.subsonic.StreamVersionPlaying
import app.winters.octo.subsonic.StreamVersions
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.VERSION_KIND_VIDEO
import app.winters.octo.versions.StreamVersionWords
import app.winters.octo.versions.StreamVersionsSheetState
import app.winters.octo.versions.VersionsSong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

// The versions sheet's work on the shared reducer: one pick at a time, a
// pick for a song no longer playing never sent, the sheet closing when the
// player moves on, every id dropped once the server took a pick, and the
// entry played again only while it is still the one on.
class StreamVersionsSheetTest {
    private val song = VersionsSong("e1", "ext-1", "Song", "Artist")

    private fun answer(playing: String, versions: List<String>, picked: String = PICKED_OCTO, ids: List<String> = listOf("ext-1")) = StreamVersions(
        id = "ext-1",
        playsFrom = PLAYS_FROM_STREAM,
        playing = StreamVersionPlaying(videoId = playing, title = "Song", picked = picked),
        version = versions.map { StreamVersion(it, "Song ($it)", "Artist", 200, VERSION_KIND_VIDEO) },
        ids = ids,
    )

    private class Port : VersionsPort {
        override val playing = MutableStateFlow<PlayingSong?>(PlayingSong("e1", "ext-1"))
        var listing: suspend () -> StreamVersions = { error("no listing") }
        var choosing: suspend (String) -> StreamVersions = { error("no choice") }
        val choices = mutableListOf<String>()
        val drops = mutableListOf<List<String>>()
        val restarts = mutableListOf<String>()
        var feeds = 0

        override suspend fun list(songId: String): StreamVersions = listing()

        override suspend fun choose(songId: String, video: String): StreamVersions {
            choices += video
            return choosing(video)
        }

        override suspend fun dropNow(ids: List<String>) {
            drops += ids
        }

        override fun restart(entryId: String) {
            restarts += entryId
        }

        override suspend fun readFeed() {
            feeds++
        }
    }

    private fun shown(sheet: StreamVersionsSheet) = sheet.state.value as StreamVersionsSheetState.Shown

    @Test
    fun onePickAtATimeThenEveryIdGoesAndTheSameEntryPlaysAgain() = runTest {
        val port = Port()
        val gate = CompletableDeferred<StreamVersions>()
        port.listing = { answer("v1", listOf("v2", "v3")) }
        port.choosing = { gate.await() }
        val sheet = StreamVersionsSheet(backgroundScope, port)
        sheet.open(song)
        runCurrent()
        assertEquals(listOf("v2", "v3"), shown(sheet).rows.map { it.videoId })
        sheet.pick("v2")
        sheet.pick("v3")
        sheet.undo()
        runCurrent()
        assertEquals(listOf("v2"), port.choices)
        assertEquals("v2", shown(sheet).checking)
        gate.complete(answer("v2", listOf("v1", "v3"), PICKED_PERSON, ids = listOf("ext-1", "ext-9")))
        runCurrent()
        assertEquals("v2", shown(sheet).playing?.videoId)
        assertNull(shown(sheet).checking)
        assertEquals(listOf(listOf("ext-1", "ext-9")), port.drops)
        assertEquals(listOf("e1"), port.restarts)
        assertEquals(1, port.feeds)
    }

    @Test
    fun aPickThatLandsAfterThePlayerMovedOnDropsButNeverRestarts() = runTest {
        val port = Port()
        val gate = CompletableDeferred<StreamVersions>()
        port.listing = { answer("v1", listOf("v2")) }
        port.choosing = { gate.await() }
        val sheet = StreamVersionsSheet(backgroundScope, port)
        sheet.open(song)
        runCurrent()
        sheet.pick("v2")
        runCurrent()
        port.playing.value = PlayingSong("e2", "ext-5")
        runCurrent()
        assertEquals(StreamVersionsSheetState.Closed, sheet.state.value)
        // The last open state stays drawn while the sheet fades away.
        assertTrue(sheet.shown.value is StreamVersionsSheetState.Shown)
        gate.complete(answer("v2", listOf("v1"), PICKED_PERSON))
        runCurrent()
        assertEquals(StreamVersionsSheetState.Closed, sheet.state.value)
        assertEquals(listOf(listOf("ext-1")), port.drops)
        assertTrue(port.restarts.isEmpty())
    }

    @Test
    fun aPickForASongNoLongerPlayingIsNeverSent() = runTest {
        val port = Port()
        port.listing = { answer("v1", listOf("v2")) }
        val sheet = StreamVersionsSheet(backgroundScope, port)
        sheet.open(song)
        runCurrent()
        // The same entry now names another song: nothing is sent.
        port.playing.value = PlayingSong("e1", "ext-7")
        runCurrent()
        sheet.pick("v2")
        runCurrent()
        assertTrue(port.choices.isEmpty())
        assertNull(shown(sheet).checking)
    }

    @Test
    fun aRefusalShowsTheServersWordsUnderItsRowAndNothingGoes() = runTest {
        val port = Port()
        val notOffered = "That version is not offered for this song any more. Open the list again."
        port.listing = { answer("v1", listOf("v2", "v3")) }
        port.choosing = { throw SubsonicException.NotFound(notOffered) }
        val sheet = StreamVersionsSheet(backgroundScope, port)
        sheet.open(song)
        runCurrent()
        sheet.pick("v2")
        runCurrent()
        assertEquals("v2", shown(sheet).refused)
        assertEquals(notOffered, shown(sheet).refusal)
        // Out of reach: the sheet's own words.
        port.choosing = { throw SubsonicException.Unreachable(IOException("down")) }
        sheet.pick("v3")
        runCurrent()
        assertEquals("v3", shown(sheet).refused)
        assertEquals(StreamVersionWords.PICK_UNREACHABLE, shown(sheet).refusal)
        assertTrue(port.drops.isEmpty())
        assertTrue(port.restarts.isEmpty())
    }

    @Test
    fun aListingOutOfReachTriesAgainAndGoingBackSendsAuto() = runTest {
        val port = Port()
        port.listing = { throw SubsonicException.Unreachable(IOException("down")) }
        val sheet = StreamVersionsSheet(backgroundScope, port)
        sheet.open(song)
        runCurrent()
        assertEquals(StreamVersionsSheetState.CouldNotReach(song, server = true), sheet.state.value)
        port.listing = { answer("v1", listOf("v2"), PICKED_PERSON) }
        port.choosing = { answer("v9", listOf("v1", "v2")) }
        sheet.retry()
        runCurrent()
        assertTrue(shown(sheet).canUndo)
        sheet.undo()
        runCurrent()
        assertEquals(listOf("auto"), port.choices)
        assertEquals("v9", shown(sheet).playing?.videoId)
        assertEquals(listOf("e1"), port.restarts)
    }
}
