package app.winters.octo.desktop

import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.playback.QueueSource
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

// The queue panel's edits through the app: the notice line with Undo, the
// names lists get, saving the queue, and drops.
class QueueActionsTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val player = SilentPlayer()

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun app(): AppState {
        val settings = SettingsStore(File(temp.root, "settings.json"), 0)
        val http = OkHttpClient()
        return AppState(
            settings,
            Accounts(settings, SessionOnlySecrets(), http),
            http,
            scope,
            DesktopOs.Windows,
            player,
            OnlineLyrics(http, server.address.toHttpUrl()),
            restored = server.connection(),
        )
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("waited too long", what())
    }

    private val songs = (1..4).map { Song("s$it", "Song $it", album = "OK Computer", albumId = "a1", duration = 100) }

    private fun coming() = player.state.value.upcoming.map { it.song.id }

    private fun keyOf(id: String) = player.state.value.queue.first { it.song.id == id }.key

    @Test
    fun takingSongsOutSaysSoWithAnUndoThatPutsThemBack() {
        val app = app()
        app.play(songs)
        app.removeQueued(listOf(keyOf("s2"), keyOf("s3")))
        assertEquals("Removed 2 songs from the queue", app.notice)
        val undo = app.actionFor(app.notice!!)
        assertNotNull(undo)
        assertEquals("Undo", undo!!.label)
        undo.run()
        assertEquals(listOf("s2", "s3", "s4"), coming())
        assertNull("the line goes once it is done", app.notice)
    }

    @Test
    fun anUndoTooLateSaysWhy() {
        val app = app()
        app.play(songs)
        app.removeQueued(listOf(keyOf("s4")))
        assertEquals("Removed from the queue", app.notice)
        player.setShuffle(true)
        app.actionFor(app.notice!!)!!.run()
        assertEquals("Can't undo that now: the queue has changed since", app.notice)
        assertNull(app.actionFor(app.notice!!))
    }

    @Test
    fun anotherNoticeNeverCarriesTheUndo() {
        val app = app()
        app.play(songs)
        app.clearUpcoming()
        assertEquals("Upcoming songs cleared", app.notice)
        app.notice = "Couldn't change favourites: offline"
        assertNull(app.actionFor(app.notice!!))
        // Ctrl+Z still takes the clearing back.
        assertTrue(app.undoQueue())
        assertEquals(listOf("s2", "s3", "s4"), coming())
    }

    @Test
    fun aListIsNamedByThePageItWasPlayedFrom() {
        val app = app()
        app.navigator.go(Page.Songs)
        app.play(songs.take(2) + Song("k1", "Idioteque", albumId = "a2"))
        assertEquals(QueueSource.Played("Songs"), player.state.value.current?.source)
        // A name given wins.
        app.play(songs, source = "Late night")
        assertEquals(QueueSource.Played("Late night"), player.state.value.current?.source)
        // From Home, an album's songs are named for the album.
        app.navigator.go(Page.Home)
        app.play(songs)
        assertEquals(QueueSource.Played("OK Computer"), player.state.value.current?.source)
    }

    @Test
    fun aRadiosSongsAreNamedForItsSong() {
        server.answer("getSimilarSongs2", """"similarSongs2":{"song":[${songJson("s7", "Seven")}]}""")
        val app = app()
        app.startRadio(songs[0])
        waitFor { coming().isNotEmpty() }
        val radio = QueueSource.Played("Song 1 radio")
        assertEquals(radio, player.state.value.current?.source)
        assertEquals(listOf(radio), player.state.value.upcoming.map { it.source })
    }

    @Test
    fun theQueueIsSavedAsAPlaylistInPlayOrderWithoutAutoplaysSongs() {
        server.answer("createPlaylist", """"playlist":{"id":"p9","name":"Mix","songCount":3}""")
        val app = app()
        app.play(songs.take(2), start = 1)
        app.addToQueue(listOf(Song("x", "X")))
        player.addToQueue(listOf(Song("auto", "Auto")), QueueSource.Autoplay)
        app.saveQueueAsPlaylist("  Mix ")
        waitFor { server.calls.any { it.url.pathSegments.last() == "createPlaylist" } }
        val call = server.calls.first { it.url.pathSegments.last() == "createPlaylist" }
        assertEquals("Mix", call.url.queryParameter("name"))
        assertEquals(listOf("s1", "s2", "x"), call.url.queryParameterValues("songId"))
        waitFor { app.notice != null }
        assertEquals("Saved as Mix", app.notice)
    }

    @Test
    fun songsDroppedInGoWhereTheyWereDropped() {
        val app = app()
        app.play(songs)
        app.dropIntoQueue(listOf(Song("x", "X")), 1)
        assertEquals(listOf("s2", "x", "s3", "s4"), coming())
        app.dropIntoQueue(listOf(Song("y", "Y")), 99)
        assertEquals("y", coming().last())
        assertEquals(QueueSource.You, player.state.value.upcoming.last().source)
    }

    @Test
    fun pickedSongsMoveToNextOrToTheEnd() {
        val app = app()
        app.play(songs)
        app.moveToNext(listOf(keyOf("s4"), keyOf("s3")))
        assertEquals(listOf("s3", "s4", "s2"), coming())
        app.moveToEnd(listOf(keyOf("s3")))
        assertEquals(listOf("s4", "s2", "s3"), coming())
    }

    @Test
    fun autoplayIsOnUnlessTurnedOff() {
        val app = app()
        assertTrue(app.settings.current.playback.autoplay)
        app.setAutoplay(false)
        assertEquals(false, app.settings.current.playback.autoplay)
    }
}
