package app.winters.octo.desktop

import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.playlists.songMoveOf
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.ui.CollectionAction
import app.winters.octo.desktop.ui.SongAction
import app.winters.octo.desktop.ui.SongPlace
import app.winters.octo.desktop.ui.playlistMenuActions
import app.winters.octo.desktop.ui.songMenuActions
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.playlists.PlaylistFolderWords
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

// Folders, songs found online and stale pages, from the desktop, against a
// pretend server listing octoPlaylistSongs and octoPlaylistFolders (or not).
class PlaylistFoldersDesktopTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private val one = Song("s1", "One")
    private val found = Song("ext-deezer-song-9", "Found", isExternal = true)

    private val first = "2026-11-14T10:00:00.000Z"
    private val later = "2026-11-14T10:05:00.000Z"
    private val road = "pfAAAAAAAAAAAAAAAAAAAA"

    private val folders =
        """"playlistFolders":{"changed":"$first","folder":[{"id":"$road","name":"Road trips","created":"$first"}],"place":[{"playlist":"p1","folder":"$road"}]}"""

    private val both = listOf("octoPlaylistSongs:1", "octoPlaylistFolders:1")

    // The listener's "Late night", a Review notice list, and an imported
    // playlist's copy; tests answer getPlaylist themselves.
    private fun app(extensions: List<String> = both): AppState {
        server.answer(
            "getPlaylists",
            """"playlists":{"playlist":[""" +
                """{"id":"p1","name":"Late night","owner":"winters","songCount":3,"changed":"$first"},""" +
                """{"id":"r1","name":"Review","owner":"winters","songCount":2,"octoNotice":"review","octoUpkeep":true,"octoUpkeepKind":"notice"},""" +
                """{"id":"oi1234567890123456789A","name":"Imported","owner":"winters","readonly":true,"octoImport":true}]}""",
        )
        server.answer("getPlaylistFolders", folders)
        val settings = SettingsStore(File(temp.root, "settings.json"), 0)
        val http = OkHttpClient()
        val app = AppState(
            settings,
            Accounts(settings, SessionOnlySecrets(), http),
            http,
            scope,
            DesktopOs.Windows,
            SilentPlayer(),
            OnlineLyrics(http, server.address.toHttpUrl()),
            restored = server.connection(extensions),
        )
        waitFor { app.playlists.isNotEmpty() }
        return app
    }

    private fun playlist(ids: List<String>, changed: String) =
        server.ok(""""playlist":{"id":"p1","name":"Late night","owner":"winters","changed":"$changed","entry":[${ids.joinToString(",") { songJson(it, it) }}]}""")

    private val conflict =
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":0,"message":"The playlist changed. Read it again.","octoConflict":true}}}"""

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("waited too long", what())
    }

    private fun callsTo(endpoint: String) = server.calls.filter { it.url.pathSegments.last() == endpoint }

    @Test
    fun foldersAreReadWithThePlaylistsAndAMoveSendsEveryPick() {
        server.answer("setPlaylistFolder", folders)
        val app = app()
        waitFor { !app.folderTree.isEmpty }
        assertEquals(road, app.folderTree.folderOf("p1"))
        app.placePlaylists(listOf("p1", "or1234567890123456789A", "x2"), null)
        waitFor { callsTo("setPlaylistFolder").isNotEmpty() }
        val call = callsTo("setPlaylistFolder").single()
        assertEquals("Octo's own lists are never filed", listOf("p1", "x2"), call.url.queryParameterValues("playlistId"))
        assertNull("the top level names no folder", call.url.queryParameter("folderId"))
    }

    @Test
    fun aFolderMadeHereGoesToTheServerWithItsOwnId() {
        server.answer("savePlaylistFolder", folders)
        server.answer("deletePlaylistFolder", folders)
        val app = app()
        app.createFolder("  Night drives ", null)
        waitFor { callsTo("savePlaylistFolder").isNotEmpty() }
        val made = callsTo("savePlaylistFolder").single()
        assertTrue(Regex("pf[0-9A-Za-z]{20}").matches(made.url.queryParameter("id").orEmpty()))
        assertEquals("Night drives", made.url.queryParameter("name"))
        assertNull(made.url.queryParameter("parentId"))
        waitFor { !app.folderTree.isEmpty }
        app.deleteFolder(road)
        waitFor { callsTo("deletePlaylistFolder").isNotEmpty() }
        assertEquals(road, callsTo("deletePlaylistFolder").single().url.queryParameter("id"))
    }

    @Test
    fun anOldServerIsNeverOfferedSongsFoundOnline() {
        assertFalse(songMenuActions(1, SongPlace.Library, outside = true, outsideToPlaylists = false).flatten().contains(SongAction.AddToPlaylist))
        assertTrue(songMenuActions(1, SongPlace.Library, outside = true, outsideToPlaylists = true).flatten().contains(SongAction.AddToPlaylist))
        server.answer("updatePlaylist")
        val app = app(extensions = emptyList())
        assertFalse(app.playlistSongs)
        app.addSongsToPlaylist(app.playlists.first { it.id == "p1" }, listOf(one, found))
        waitFor { callsTo("updatePlaylist").isNotEmpty() }
        assertEquals(listOf("s1"), callsTo("updatePlaylist").single().url.queryParameterValues("songIdToAdd"))
    }

    @Test
    fun aNoticeListNeverTakesSongsFoundOnlineAndSkippedSongsAreNamed() {
        server.answer("updatePlaylist", """"octoSkipped":["ext-deezer-song-9"]""")
        val app = app()
        val review = app.playlists.first { it.id == "r1" }
        assertNull(runBlocking { app.checkAdd(review, listOf(found)) })
        assertEquals(UPKEEP_REFUSES_FINDS, app.notice)
        assertTrue(callsTo("updatePlaylist").isEmpty())
        app.addSongsToPlaylist(app.playlists.first { it.id == "p1" }, listOf(one, found))
        waitFor { app.notice == PlaylistFolderWords.SKIPPED_SONGS }
        assertEquals(listOf("s1", "ext-deezer-song-9"), callsTo("updatePlaylist").single().url.queryParameterValues("songIdToAdd"))
    }

    @Test
    fun aReorderCarriesThePagesStampAndIsMadeAgainOverAChangedList() {
        val replaces = AtomicInteger()
        server.answerBy("getPlaylist") { if (replaces.get() == 0) playlist(listOf("s1", "s2", "s3"), first) else playlist(listOf("s1", "s2", "s3", "s4"), later) }
        server.answerBy("createPlaylist") { if (replaces.incrementAndGet() == 1) conflict else server.ok() }
        val app = app()
        runBlocking { app.loadPlaylist("p1") }
        val shown = app.playlistView("p1")!!.entry
        app.reorderPlaylist("p1", listOf(shown[2], shown[0], shown[1]), songMoveOf(shown.map { it.id }, listOf(2), 0))
        waitFor { callsTo("createPlaylist").size == 2 }
        val (refused, sent) = callsTo("createPlaylist")
        assertEquals(first, refused.url.queryParameter("octoIfStamp"))
        assertEquals("1", refused.url.queryParameter("octoOwnIds"))
        assertEquals(listOf("s3", "s1", "s2"), refused.url.queryParameterValues("songId"))
        assertEquals(later, sent.url.queryParameter("octoIfStamp"))
        assertEquals("the other device's add is kept", listOf("s3", "s1", "s2", "s4"), sent.url.queryParameterValues("songId"))
        waitFor { app.playlistSaves.isEmpty() }
        assertNull(app.notice)
    }

    @Test
    fun aReorderWhoseMovedSongIsGoneSaysToTryAgain() {
        val replaces = AtomicInteger()
        server.answerBy("getPlaylist") { if (replaces.get() == 0) playlist(listOf("s1", "s2", "s3"), first) else playlist(listOf("s1", "s2"), later) }
        server.answerBy("createPlaylist") { replaces.incrementAndGet(); conflict }
        val app = app()
        runBlocking { app.loadPlaylist("p1") }
        val shown = app.playlistView("p1")!!.entry
        app.reorderPlaylist("p1", listOf(shown[2], shown[0], shown[1]), songMoveOf(shown.map { it.id }, listOf(2), 0))
        waitFor { app.notice != null }
        assertEquals("Couldn't move the songs: $PLAYLIST_CHANGED_TRY_AGAIN", app.notice)
        assertEquals(1, callsTo("createPlaylist").size)
        assertEquals(listOf("s1", "s2"), app.playlistView("p1")?.entry?.map { it.id })
    }

    @Test
    fun aServerWithoutTheExtensionGetsTheReorderAsBefore() {
        server.answerBy("getPlaylist") { playlist(listOf("s1", "s2", "s3"), first) }
        server.answer("createPlaylist")
        val app = app(extensions = emptyList())
        runBlocking { app.loadPlaylist("p1") }
        val shown = app.playlistView("p1")!!.entry
        app.reorderPlaylist("p1", listOf(shown[1], shown[0], shown[2]))
        waitFor { callsTo("createPlaylist").isNotEmpty() }
        val call = callsTo("createPlaylist").single()
        assertNull(call.url.queryParameter("octoIfStamp"))
        assertNull(call.url.queryParameter("octoOwnIds"))
    }

    @Test
    fun removalsGoBySongOnAServerThatKeepsSongsFoundOnline() {
        server.answerBy("getPlaylist") { playlist(listOf("s1", "s2", "s1"), first) }
        server.answer("updatePlaylist")
        val app = app()
        runBlocking { app.loadPlaylist("p1") }
        app.removeFromPlaylist("p1", listOf(2))
        waitFor { callsTo("updatePlaylist").isNotEmpty() }
        val call = callsTo("updatePlaylist").single()
        assertEquals(listOf("s1@2"), call.url.queryParameterValues("octoSongToRemove"))
        assertTrue(call.url.queryParameterValues("songIndexToRemove").isEmpty())
    }

    @Test
    fun anImportedPlaylistsCopyOffersNoRenameOrDelete() {
        val rows = playlistMenuActions(owns = true, imported = true, files = true).flatten()
        assertFalse(CollectionAction.Rename in rows)
        assertFalse(CollectionAction.Delete in rows)
        assertFalse(CollectionAction.Public in rows)
        assertTrue(CollectionAction.Duplicate in rows)
        assertTrue(CollectionAction.MoveToFolder in rows)
        assertFalse(CollectionAction.MoveToFolder in playlistMenuActions(owns = true).flatten())
        val app = app()
        val imported = app.playlists.first { it.octoImport }
        assertFalse("an imported copy takes no songs, so no picker offers it", app.access(imported).add)
    }
}
