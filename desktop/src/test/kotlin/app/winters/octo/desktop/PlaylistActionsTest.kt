package app.winters.octo.desktop

import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.playlists.PlaylistMove
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.subsonic.Playlist
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Playlists edited from the desktop, against a pretend server: moves,
// details, copies, deletes, adding with the duplicate question, and files.
class PlaylistActionsTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private val one = Song("s1", "One", artist = "Radiohead", duration = 200)
    private val two = Song("s2", "Two", artist = "Radiohead", duration = 210)
    private val three = Song("s3", "Three", artist = "Portishead", duration = 220)
    private val four = Song("s4", "Four", artist = "Portishead", duration = 230)

    private val lateNight = Playlist("p1", "Late night", owner = "winters", songCount = 3)
    private val theirs = Playlist("p2", "Theirs", owner = "someone", songCount = 1)

    // A server with the listener's "Late night" (One, Two, Three, with a
    // path for One) and someone else's playlist.
    private fun app(): AppState {
        server.answer(
            "getPlaylists",
            """"playlists":{"playlist":[{"id":"p1","name":"Late night","owner":"winters","songCount":3},{"id":"p2","name":"Theirs","owner":"someone","songCount":1}]}""",
        )
        server.answer(
            "getPlaylist",
            """"playlist":{"id":"p1","name":"Late night","owner":"winters","comment":"After midnight","entry":[""" +
                songJson("s1", "One", "Radiohead").dropLast(1) + ""","path":"Radiohead/OK Computer/01 - One.flac"},""" +
                songJson("s2", "Two", "Radiohead") + "," + songJson("s3", "Three", "Portishead") + "]}",
        )
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
            restored = server.connection(),
        )
        waitFor { app.playlists.isNotEmpty() }
        return app
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("waited too long", what())
    }

    private fun callsTo(endpoint: String) = server.calls.filter { it.url.pathSegments.last() == endpoint }

    private fun shownIds(app: AppState) = app.playlistView("p1")?.entry?.map { it.id }

    // Reorders.

    @Test
    fun aReorderShowsAtOnceThenReplacesTheSongsInOrder() {
        server.answer("createPlaylist")
        val app = app()
        runBlocking { app.loadPlaylist("p1") }
        assertEquals(listOf("s1", "s2", "s3"), shownIds(app))
        app.reorderPlaylist("p1", listOf(three, one, two))
        assertEquals(listOf("s3", "s1", "s2"), shownIds(app))
        waitFor { callsTo("createPlaylist").isNotEmpty() }
        val call = callsTo("createPlaylist").single()
        assertEquals("p1", call.url.queryParameter("playlistId"))
        assertEquals(listOf("s3", "s1", "s2"), call.url.queryParameterValues("songId"))
        // The sidebar is read again once the server has it.
        waitFor { callsTo("getPlaylists").size >= 2 }
        assertNull(app.notice)
    }

    @Test
    fun aReorderTheServerRefusesGoesBackAndSaysSo() {
        server.fail("createPlaylist", 50, "Not allowed")
        val app = app()
        runBlocking { app.loadPlaylist("p1") }
        app.reorderPlaylist("p1", listOf(three, one, two))
        waitFor { app.notice != null }
        assertTrue(app.notice!!.startsWith("Couldn't move the songs"))
        assertEquals(listOf("s1", "s2", "s3"), shownIds(app))
    }

    @Test
    fun aReadThatLandsWhileAMoveIsSavingDoesNotUndoIt() {
        val release = CountDownLatch(1)
        server.answerBy("createPlaylist") {
            release.await(5, TimeUnit.SECONDS)
            server.ok()
        }
        val app = app()
        runBlocking { app.loadPlaylist("p1") }
        app.reorderPlaylist("p1", listOf(two, three, one))
        // The server still has the old order while the move is on its way.
        runBlocking { app.loadPlaylist("p1") }
        assertEquals(listOf("s2", "s3", "s1"), shownIds(app))
        release.countDown()
        waitFor { app.playlistSaves.isEmpty() }
    }

    @Test
    fun theMenuMovesPickedSongs() {
        server.answer("createPlaylist")
        val app = app()
        runBlocking { app.loadPlaylist("p1") }
        app.moveInPlaylist("p1", listOf(2), PlaylistMove.Up)
        waitFor { callsTo("createPlaylist").isNotEmpty() }
        assertEquals(listOf("s1", "s3", "s2"), callsTo("createPlaylist").single().url.queryParameterValues("songId"))
        // A move that changes nothing sends nothing.
        app.moveInPlaylist("p1", listOf(0), PlaylistMove.Top)
        Thread.sleep(200)
        assertEquals(1, callsTo("createPlaylist").size)
    }

    // Details.

    @Test
    fun aRenameAndADescriptionShowAtOnceAndReachTheServer() {
        server.answer("updatePlaylist")
        val app = app()
        runBlocking { app.loadPlaylist("p1") }
        assertEquals("After midnight", app.playlistView("p1")?.comment)
        app.renamePlaylist("p1", "  Later night ")
        assertEquals("Later night", app.playlistView("p1")?.name)
        waitFor { callsTo("updatePlaylist").size == 1 }
        assertEquals("Later night", callsTo("updatePlaylist")[0].url.queryParameter("name"))
        app.setPlaylistComment("p1", "")
        waitFor { callsTo("updatePlaylist").size == 2 }
        assertEquals("", callsTo("updatePlaylist")[1].url.queryParameter("comment"))
        app.setPlaylistPublic("p1", true)
        waitFor { callsTo("updatePlaylist").size == 3 }
        assertEquals("true", callsTo("updatePlaylist")[2].url.queryParameter("public"))
        assertEquals(true, app.playlistView("p1")?.public)
    }

    // Copies and deletes.

    @Test
    fun aCopyIsANewPlaylistWithTheSameSongs() {
        server.answer("createPlaylist", """"playlist":{"id":"p9","name":"Late night (copy)"}""")
        val app = app()
        app.duplicatePlaylist(lateNight)
        waitFor { app.notice != null }
        val call = callsTo("createPlaylist").single()
        assertEquals("Late night (copy)", call.url.queryParameter("name"))
        assertEquals(listOf("s1", "s2", "s3"), call.url.queryParameterValues("songId"))
        assertEquals("Made a copy: Late night (copy)", app.notice)
    }

    @Test
    fun aDeletedPlaylistLeavesItsPageThePinsAndTheRecentList() {
        server.answer("deletePlaylist")
        server.answer("updatePlaylist")
        val app = app()
        app.setPinned("p1", true)
        app.addSongsToPlaylist(lateNight, listOf(four))
        app.navigator.go(Page.Songs)
        app.navigator.go(Page.Playlist("p1"))
        app.deletePlaylist(lateNight)
        waitFor { callsTo("deletePlaylist").isNotEmpty() }
        waitFor { app.navigator.current.page == Page.Songs }
        assertEquals("p1", callsTo("deletePlaylist").single().url.queryParameter("id"))
        assertTrue(app.settings.current.frame.pinnedPlaylists.isEmpty())
        assertTrue(app.settings.current.recentPlaylists.isEmpty())
    }

    @Test
    fun aPlaylistTheServerWillNotDeleteStays() {
        server.fail("deletePlaylist", 50, "Not allowed")
        val app = app()
        app.navigator.go(Page.Playlist("p1"))
        app.deletePlaylist(lateNight)
        waitFor { app.notice != null }
        assertTrue(app.notice!!.startsWith("Couldn't delete the playlist"))
        assertEquals(Page.Playlist("p1"), app.navigator.current.page)
    }

    // Adding, with the duplicate question.

    @Test
    fun songsNotOnThePlaylistGoStraightOnAndSaySo() {
        server.answer("updatePlaylist")
        val app = app()
        val question = runBlocking { app.checkAdd(lateNight, listOf(four, four)) }
        assertNull(question)
        waitFor { callsTo("updatePlaylist").isNotEmpty() }
        // Each song once.
        assertEquals(listOf("s4"), callsTo("updatePlaylist").single().url.queryParameterValues("songIdToAdd"))
        waitFor { app.notice != null }
        assertEquals("Added to Late night", app.notice)
        assertEquals(listOf("p1"), app.settings.current.recentPlaylists)
        assertEquals(listOf(lateNight.id), app.lastPlaylists().map { it.id })
    }

    @Test
    fun songsAlreadyThereAreAskedAboutFirst() {
        server.answer("updatePlaylist")
        val app = app()
        val question = runBlocking { app.checkAdd(lateNight, listOf(two, four)) }
        assertNotNull(question)
        assertEquals("1 of these songs is already in Late night.", question!!.words)
        assertEquals(listOf("s4"), question.fresh.map { it.id })
        assertEquals(listOf("s2", "s4"), question.all.map { it.id })
        assertTrue(callsTo("updatePlaylist").isEmpty())
        // Answering "Add new ones" adds only those.
        app.addSongsToPlaylist(question.playlist, question.fresh)
        waitFor { callsTo("updatePlaylist").isNotEmpty() }
        assertEquals(listOf("s4"), callsTo("updatePlaylist").single().url.queryParameterValues("songIdToAdd"))
    }

    @Test
    fun aDropOfSongsAllThereAsksToAddThemAgain() {
        val app = app()
        var asked: AddQuestion? = null
        app.addToPlaylistChecked(lateNight, listOf(one, two)) { asked = it }
        waitFor { asked != null }
        assertEquals("These songs are already in Late night. Add them again?", asked!!.words)
        assertTrue(callsTo("updatePlaylist").isEmpty())
    }

    @Test
    fun someoneElsesPlaylistTakesNoSongs() {
        val app = app()
        val question = runBlocking { app.checkAdd(theirs, listOf(four)) }
        assertNull(question)
        assertEquals("Only its owner can change Theirs", app.notice)
        assertTrue(callsTo("updatePlaylist").isEmpty())
    }

    @Test
    fun aNewPlaylistWithSongsSaysWhereTheyWent() {
        server.answer("createPlaylist", """"playlist":{"id":"p7","name":"Fresh"}""")
        val app = app()
        app.newPlaylistWith(" Fresh ", listOf(one, two))
        waitFor { app.notice != null }
        assertEquals("Added 2 songs to Fresh", app.notice)
        assertEquals(listOf("p7"), app.settings.current.recentPlaylists)
    }

    // Files.

    @Test
    fun anExportWritesThePathsTheServerGives() {
        val app = app()
        val file = File(temp.root, "Late night.m3u")
        app.exportPlaylist(lateNight, file)
        waitFor { app.notice != null }
        assertEquals("Exported to Late night.m3u", app.notice)
        assertEquals(
            "#EXTM3U\n" +
                "#EXTINF:200,Radiohead - One\nRadiohead/OK Computer/01 - One.flac\n" +
                "#EXTINF:200,Radiohead - Two\nRadiohead - Two\n" +
                "#EXTINF:200,Portishead - Three\nPortishead - Three\n",
            file.readText(),
        )
    }

    @Test
    fun anImportMakesAPlaylistOfTheSongsFoundAndOpensIt() {
        server.answer("getAlbumList2", """"albumList2":{}""")
        server.answer("getArtists", """"artists":{}""")
        server.answer("search3", """"searchResult3":{"song":[${songJson("s1", "One", "Radiohead")},${songJson("s3", "Three", "Portishead")}]}""")
        server.answer("createPlaylist", """"playlist":{"id":"p8","name":"Mix"}""")
        val app = app()
        waitFor { app.library?.index != null }
        val file = File(temp.root, "Mix.m3u8").apply {
            writeText("#EXTM3U\n#EXTINF:200,Portishead - Three\nx.mp3\n#EXTINF:100,Nobody - Nothing\ny.mp3\n#EXTINF:200,Radiohead - One\nz.mp3\n")
        }
        app.importPlaylistFile(file)
        waitFor { app.navigator.current.page == Page.Playlist("p8") }
        val call = callsTo("createPlaylist").single()
        assertEquals("Mix", call.url.queryParameter("name"))
        assertEquals(listOf("s3", "s1"), call.url.queryParameterValues("songId"))
        assertEquals("Imported 2 of 3 songs into \"Mix\"", app.notice)
        assertEquals("Not found: Nobody - Nothing", app.noticeDetail)
    }

    // Brandon: a click on a playlist said "That isn't on the server any
    // more." One the server no longer has leaves the sidebar, its pin and
    // its page, and the list is read again.
    @Test
    fun aPlaylistTheServerNoLongerHasLeavesTheList() {
        val app = app()
        app.setPinned("p2", true)
        server.answerBy("getPlaylist") { request ->
            if (request.url.queryParameter("id") == "p2") server.failed(70, "Playlist not found") else server.ok()
        }
        val listed = callsTo("getPlaylists").size
        // The server's list has lost it too by now.
        server.answer("getPlaylists", """"playlists":{"playlist":[{"id":"p1","name":"Late night","owner":"winters","songCount":3}]}""")
        val why = runBlocking { app.loadPlaylist("p2") }
        assertEquals(PLAYLIST_GONE, why)
        assertEquals(listOf("p1"), app.playlists.map { it.id })
        assertTrue("unpinned", !app.isPinned("p2"))
        waitFor { callsTo("getPlaylists").size > listed }
        assertEquals(listOf("p1"), app.playlists.map { it.id })
    }

    @Test
    fun anyOtherFailureKeepsThePlaylist() {
        val app = app()
        server.answerBy("getPlaylist") { server.failed(0, "Something broke") }
        val why = runBlocking { app.loadPlaylist("p2") }
        assertTrue(why != PLAYLIST_GONE)
        assertEquals(listOf("p1", "p2"), app.playlists.map { it.id })
    }

    // Deleted on the phone, say: coming back to the window reads the list
    // again once it is a minute old, and not before.
    @Test
    fun comingBackToTheWindowReadsAnOldListAgain() {
        val app = app()
        waitFor { callsTo("getPlaylists").isNotEmpty() }
        val listed = callsTo("getPlaylists").size
        app.windowCameBack()
        Thread.sleep(200)
        assertEquals("just read", listed, callsTo("getPlaylists").size)
        server.answer("getPlaylists", """"playlists":{"playlist":[{"id":"p1","name":"Late night","owner":"winters","songCount":3}]}""")
        app.windowCameBack(System.currentTimeMillis() + PLAYLISTS_FRESH_MS)
        waitFor { app.playlists.map { it.id } == listOf("p1") }
    }
}
