package app.winters.octo.desktop.health

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.health.FixOutcome
import app.winters.octo.health.FixStep
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.duplicateFix
import app.winters.octo.health.steps
import app.winters.octo.health.SubsonicHealth
import app.winters.octo.health.checkLibrary
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The Library health page's logic: its rows, its columns, the report kept
// off the window's thread, and its fixes, asked of the server by id and
// never by a rating.
@OptIn(ExperimentalCoroutinesApi::class)
class HealthPageTest {
    private val server = FakeServer()

    @After
    fun stop() = server.close()

    private fun client() = server.client()

    private fun song(id: String, title: String, seconds: Int = 200, suffix: String = "flac", bitRate: Int = 900, album: String = "Album", albumId: String = "al", year: Int? = 2020, track: Int? = 1) =
        Song(id = id, title = title, artist = "Artist", album = album, albumId = albumId, duration = seconds, suffix = suffix, bitRate = bitRate, bitDepth = if (suffix == "flac") 16 else null, samplingRate = 44_100, year = year, genre = "Rock", track = track, displayAlbumArtist = "Artist", coverArt = "c", path = "Artist/$album/$title.$suffix")

    private val library = listOf(
        song("mp3", "Holocene", suffix = "mp3", bitRate = 320, track = 1),
        song("flac", "Holocene", track = 1),
        song("towers", "Towers", track = 2),
        song("a1", "One", album = "Split", albumId = "x", track = 1),
        song("b2", "Two", album = "Split", albumId = "y", year = null, track = 2),
    )

    private val allowed = """"libraryActions":{"enabled":true,"allowed":true,"dryRun":false,"actions":["remove"],"keepDays":30}"""

    private fun extensions(vararg names: String) =
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[${names.joinToString(",") { """{"name":"$it","versions":[1]}""" }}]""")

    // The server answers on the network's own threads, which the test's
    // clock does not wait for.
    private suspend fun awaitActions(model: HealthModel) {
        repeat(300) { if (model.actions == null) withContext(Dispatchers.IO) { Thread.sleep(10) } }
    }

    // Rows

    @Test
    fun copiesSitUnderTheSongWithTheOneToKeepFirst() {
        val rows = healthRows(checkLibrary(library, SubsonicHealth), HealthCheck.Duplicates)

        assertEquals(listOf("flac", "mp3"), rows.songs.map { it.id })
        assertEquals(listOf("1", "2"), rows.numbers)
        assertEquals(mapOf(0 to "Holocene by Artist"), rows.titles)
        assertEquals("2 copies. Same title, artist and length. Keep the first, FLAC, 16-bit, 44.1 kHz.", rows.details[0])
    }

    @Test
    fun aSplitAlbumSaysWhatDiffersAndNumbersByTrack() {
        val rows = healthRows(checkLibrary(library, SubsonicHealth), HealthCheck.SplitAlbums)

        assertEquals(listOf("a1", "b2"), rows.songs.map { it.id })
        assertEquals(listOf("1", "2"), rows.numbers)
        assertEquals("Split by Artist, shown as 2 albums", rows.titles[0])
        assertEquals("The year differs: 2020, none.", rows.details[0])
    }

    @Test
    fun aMissingTagListsItsSongsWithNoHeadings() {
        val rows = healthRows(checkLibrary(library, SubsonicHealth), HealthCheck.NoYear)

        assertEquals(listOf("b2"), rows.songs.map { it.id })
        assertTrue(rows.titles.isEmpty())
        assertEquals(listOf("1"), rows.numbers)
    }

    @Test
    fun eachCheckShowsTheColumnThatMatters() {
        assertTrue(SongColumn.Format in healthColumns(HealthCheck.Duplicates))
        assertTrue(SongColumn.Year in healthColumns(HealthCheck.SplitAlbums))
        assertTrue(SongColumn.Genre in healthColumns(HealthCheck.NoGenre))
        assertTrue(SongColumn.Year in healthColumns(HealthCheck.NoYear))
    }

    // The model

    @Test
    fun theReportIsWorkedOutOnTheWorkerAndOnlyOnce() = runTest {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "health-worker") }
        val seen = ConcurrentHashMap.newKeySet<String>()
        // The songs note which threads read them.
        val watched = object : AbstractList<Song>() {
            override val size get() = library.size
            override fun get(index: Int): Song {
                seen += Thread.currentThread().name
                return library[index]
            }
        }
        try {
            val model = HealthModel({ null }, this, worker.asCoroutineDispatcher())
            model.check(watched)
            advanceUntilIdle()
            repeat(200) { if (model.report == null) withContext(Dispatchers.IO) { Thread.sleep(10) } }

            assertEquals(5, model.report!!.checked)
            assertEquals(HealthCheck.Duplicates, model.shown)
            // Only the worker read the songs, never the caller.
            assertTrue(seen.toString(), seen.isNotEmpty() && seen.all { it.startsWith("health-worker") })
            // The same songs again are not checked again.
            val first = model.report
            seen.clear()
            model.check(watched)
            advanceUntilIdle()
            assertTrue(first === model.report)
            assertTrue(seen.isEmpty())
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun aPickedCheckWithNothingLeftFallsBackToTheFirst() = runTest {
        val model = HealthModel({ null }, this, Dispatchers.Unconfined)
        model.check(library)
        advanceUntilIdle()
        model.picked = HealthCheck.NoGenre
        assertEquals(HealthCheck.Duplicates, model.shown)
        model.picked = HealthCheck.SplitAlbums
        assertEquals(HealthCheck.SplitAlbums, model.shown)
    }

    @Test
    fun nothingCanBeDeletedOnAServerWithoutTheExtension() = runTest {
        extensions("formPost")
        val client = client()
        val model = HealthModel({ client }, this, Dispatchers.Unconfined)
        model.check(library)
        model.askServer()
        advanceUntilIdle()

        assertNull(model.actions)
        assertFalse(model.canDelete)
        assertTrue(server.calls.none { it.url.encodedPath.endsWith("getLibraryActions") })
    }

    @Test
    fun aRehearsingServer_OrAUserWhoIsNotAnAdmin_OffersNoDelete() = runTest {
        extensions("octoLibraryActions")
        server.answer("getLibraryActions", allowed.replace("\"dryRun\":false", "\"dryRun\":true"), type = "octo")
        val client = client()
        val model = HealthModel({ client }, this, Dispatchers.Unconfined)
        model.askServer()
        advanceUntilIdle()
        awaitActions(model)
        assertEquals(true, model.actions?.dryRun)
        assertFalse(model.canDelete)

        server.answer("getLibraryActions", allowed.replace("\"keepDays\":30", "\"keepDays\":30,\"admin\":false"), type = "octo")
        val second = HealthModel({ client }, this, Dispatchers.Unconfined)
        second.askServer()
        advanceUntilIdle()
        awaitActions(second)
        assertEquals(false, second.actions?.admin)
        assertFalse(second.canDelete)
    }

    // Waits for the run to finish on the network's threads.
    private suspend fun awaitRun(model: HealthModel, outcome: () -> FixOutcome?) {
        repeat(300) { if (outcome() == null || model.running != null) withContext(Dispatchers.Default) { Thread.sleep(10) } }
    }

    @Test
    fun fixingCopiesFillsTheKeptOneFirst_ThenRemovesTheOther_ByIdAndNeverByARating() = runTest {
        extensions("octoLibraryActions")
        server.answer("getLibraryActions", allowed.replace("[\"remove\"]", "[\"remove\",\"retag\",\"undo\",\"restore\"]"), type = "octo")
        server.answerBy("libraryAction") { request ->
            val id = request.url.queryParameter("id")
            val action = request.url.queryParameter("action")
            server.ok(""""libraryAction":{"id":"$id","action":"$action","state":"applied","detail":"Done."}""", type = "octo")
        }
        val withTags = library.map { if (it.id == "mp3") it.copy(genre = "Folk", year = 2011) else if (it.id == "flac") it.copy(genre = null) else it }
        val client = client()
        val model = HealthModel({ client }, this, Dispatchers.Unconfined)
        model.check(withTags)
        model.askServer()
        advanceUntilIdle()
        awaitActions(model)

        val group = model.report!!.duplicates.single()
        val fix = duplicateFix(group, SubsonicHealth)
        var outcome: FixOutcome? = null
        model.run("Fixing copies", fix.steps(SubsonicHealth), HealthCheck.Duplicates) { outcome = it }
        advanceUntilIdle()
        awaitRun(model) { outcome }

        val calls = server.calls.filter { it.url.encodedPath.endsWith("libraryAction") }
        assertEquals(listOf("flac" to "retag", "mp3" to "remove"), calls.map { it.url.queryParameter("id") to it.url.queryParameter("action") })
        assertEquals("Folk", calls[0].url.queryParameter("genre"))
        // Nothing went near a rating.
        assertTrue(server.calls.none { it.url.encodedPath.endsWith("setRating") || it.url.queryParameter("rating") != null })
        // The set is down to one copy, so it is no longer a duplicate.
        assertTrue(model.report!!.duplicates.isEmpty())
        assertEquals(setOf("flac"), model.changed)
        assertEquals(listOf(FixStep.Restore("mp3", "Holocene"), FixStep.Undo("flac", "Holocene")), outcome!!.undo)
        assertEquals("Removed 1 song, changed the tags of 1 song.", outcome!!.summary())
    }

    @Test
    fun aRehearsalChangesNothingAndSaysSo() = runTest {
        extensions("octoLibraryActions")
        server.answer("getLibraryActions", allowed, type = "octo")
        server.answer("libraryAction", """"libraryAction":{"id":"mp3","action":"remove","state":"rehearsed","detail":"Dry run"}""", type = "octo")
        val client = client()
        val model = HealthModel({ client }, this, Dispatchers.Unconfined)
        model.check(library)
        model.askServer()
        advanceUntilIdle()
        awaitActions(model)

        var outcome: FixOutcome? = null
        model.run("Deleting", listOf(FixStep.Remove("mp3", "Holocene"))) { outcome = it }
        advanceUntilIdle()
        awaitRun(model) { outcome }

        assertTrue(outcome!!.rehearsed)
        assertTrue("dry run" in outcome!!.summary())
        assertEquals(1, model.report!!.duplicates.size)
    }

    @Test
    fun aSongGivenItsYearLeavesNoYear_ButNotTheOtherChecks() = runTest {
        extensions("octoLibraryActions")
        server.answer("getLibraryActions", allowed.replace("[\"remove\"]", "[\"retag\",\"undo\"]"), type = "octo")
        server.answer("libraryAction", """"libraryAction":{"id":"b2","action":"retag","state":"applied","detail":"Changed the year."}""", type = "octo")
        val client = client()
        val model = HealthModel({ client }, this, Dispatchers.Unconfined)
        model.check(library)
        model.askServer()
        advanceUntilIdle()
        awaitActions(model)
        assertEquals(1, model.report!!.count(HealthCheck.NoYear))

        var outcome: FixOutcome? = null
        model.run("Filling in tags", listOf(FixStep.Retag("b2", "Two", mapOf("year" to "2020"))), HealthCheck.NoYear) { outcome = it }
        advanceUntilIdle()
        awaitRun(model) { outcome }

        assertEquals(0, model.report!!.count(HealthCheck.NoYear))
        // Its album is still split until the server's list says otherwise.
        assertEquals(1, model.report!!.count(HealthCheck.SplitAlbums))
    }

    @Test
    fun theTrashIsReadAndAPutBackSongLeavesIt() = runTest {
        extensions("octoLibraryActions")
        server.answer("getLibraryActions", allowed.replace("[\"remove\"]", "[\"remove\",\"restore\"]"), type = "octo")
        server.answer("getLibraryTrash", """"libraryTrash":{"keepDays":30,"songs":[{"id":"gone","title":"Gone","artist":"Artist","album":"Album","goneAt":"2026-11-04T00:00:00Z"}]}""", type = "octo")
        server.answer("libraryAction", """"libraryAction":{"id":"gone","action":"restore","state":"applied","detail":"Put back in your library."}""", type = "octo")
        val client = client()
        val model = HealthModel({ client }, this, Dispatchers.Unconfined)
        model.askServer()
        advanceUntilIdle()
        awaitActions(model)
        assertTrue(model.actions!!.canRestore)

        model.readTrash()
        advanceUntilIdle()
        repeat(300) { if (model.trash == null) withContext(Dispatchers.Default) { Thread.sleep(10) } }
        assertEquals(listOf("Gone"), model.trash!!.songs.map { it.title })

        var outcome: FixOutcome? = null
        model.run("Putting back", listOf(FixStep.Restore("gone", "Gone"))) { outcome = it }
        advanceUntilIdle()
        awaitRun(model) { outcome }

        assertTrue(model.trash!!.songs.isEmpty())
        assertEquals("Put back 1 song.", outcome!!.summary())
    }
}
