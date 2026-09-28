package app.winters.octo.desktop

import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Ratings, radios and removals from the menus, against a pretend server.
class SongActionsTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val player = SilentPlayer()

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun app(extensions: List<String> = emptyList()): AppState {
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
            restored = server.connection(extensions),
        )
    }

    // Waits a little for the fake server to hear a call, since the client
    // answers on its own thread.
    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("waited too long", what())
    }

    private fun callsTo(endpoint: String) = server.calls.filter { it.url.pathSegments.last() == endpoint }

    private fun queueIds() = player.state.value.queue.map { it.song.id }

    private val one = Song("s1", "One", artist = "Radiohead", userRating = 2)
    private val two = Song("s2", "Two", artist = "Radiohead")

    @Test
    fun aRatingShowsAtOnceAndReachesTheServer() {
        server.answer("setRating")
        val app = app()
        assertEquals(2, app.ratingOf(one))
        assertEquals(0, app.ratingOf(two))
        app.setRating(listOf(one, two), 4)
        assertEquals(4, app.ratingOf(one))
        assertEquals(4, app.ratingOf(two))
        waitFor { callsTo("setRating").size == 2 }
        assertEquals(listOf("s1" to "4", "s2" to "4"), callsTo("setRating").map { it.url.queryParameter("id") to it.url.queryParameter("rating") })
        assertEquals(4, app.ratingOf(one))
        assertNull(app.notice)
    }

    @Test
    fun aRatingTheServerRefusesGoesBackAndSaysSo() {
        server.answerBy("setRating") { request -> if (request.url.queryParameter("id") == "s2") server.failed(50, "Not allowed") else server.ok() }
        val app = app()
        app.setRating(listOf(one, two), 5)
        waitFor { app.notice != null }
        assertEquals(5, app.ratingOf(one))
        assertEquals(0, app.ratingOf(two))
        assertTrue(app.notice!!.startsWith("Couldn't change the rating"))
    }

    @Test
    fun clearingARatingSendsZero() {
        server.answer("setRating")
        val app = app()
        app.setRating(listOf(one), 0)
        assertEquals(0, app.ratingOf(one))
        waitFor { callsTo("setRating").isNotEmpty() }
        assertEquals("0", callsTo("setRating").single().url.queryParameter("rating"))
    }

    @Test
    fun aRadioPlaysTheSongThenSongsLikeIt() {
        // The server sends the seed back and one song twice.
        server.answer("getSimilarSongs2", """"similarSongs2":{"song":[${songJson("s1", "One")},${songJson("s7", "Seven")},${songJson("s8", "Eight")},${songJson("s7", "Seven")}]}""")
        val app = app()
        app.startRadio(one)
        assertEquals("s1", player.state.value.current?.song?.id)
        waitFor { queueIds().size == 3 }
        assertEquals(listOf("s1", "s7", "s8"), queueIds())
        assertEquals("s1", callsTo("getSimilarSongs2").single().url.queryParameter("id"))
        assertEquals("50", callsTo("getSimilarSongs2").single().url.queryParameter("count"))
    }

    @Test
    fun aRadioWithNothingLikeItKeepsTheSongPlayingAndSaysSo() {
        server.fail("getSimilarSongs2", 70, "Not found")
        val app = app()
        app.startRadio(one)
        waitFor { app.notice != null }
        assertEquals("Couldn't find songs like One", app.notice)
        assertEquals(listOf("s1"), queueIds())
        assertTrue(player.state.value.playing)
    }

    @Test
    fun aRadioAddsNothingOnceTheListenerPutOnSomethingElse() {
        val answered = CountDownLatch(1)
        server.answerBy("getSimilarSongs2") {
            answered.await(5, TimeUnit.SECONDS)
            server.ok(""""similarSongs2":{"song":[${songJson("s7", "Seven")}]}""")
        }
        val app = app()
        app.startRadio(one)
        app.play(listOf(two))
        answered.countDown()
        waitFor { callsTo("getSimilarSongs2").isNotEmpty() }
        Thread.sleep(300)
        assertEquals(listOf("s2"), queueIds())
        assertNull(app.notice)
    }

    @Test
    fun anArtistRadioFallsBackToTheirTopSongs() {
        server.answer("getSimilarSongs2", """"similarSongs2":{}""")
        server.answer("getTopSongs", """"topSongs":{"song":[${songJson("t1", "Top one")},${songJson("t2", "Top two")}]}""")
        val app = app()
        app.startArtistRadio("ar1", "Radiohead")
        waitFor { queueIds().isNotEmpty() }
        assertEquals(listOf("t1", "t2"), queueIds())
        assertEquals("ar1", callsTo("getSimilarSongs2").single().url.queryParameter("id"))
        assertEquals("Radiohead", callsTo("getTopSongs").single().url.queryParameter("artist"))
    }

    @Test
    fun theRadioSeedIsTheMostPlayedSongOrTheFirst() {
        val songs = listOf(Song("a", playCount = 0), Song("b", playCount = 3), Song("c", playCount = 9), Song("d", playCount = 9))
        assertEquals("c", radioSeed(songs)?.id)
        assertEquals("a", radioSeed(listOf(Song("a"), Song("b")))?.id)
        assertNull(radioSeed(emptyList()))
    }

    @Test
    fun rowsComeOutOfAPlaylistInOneCallByTheirPlaces() {
        server.answer("updatePlaylist")
        val app = app()
        app.removeFromPlaylist("p1", listOf(7, 2, 7))
        waitFor { callsTo("updatePlaylist").isNotEmpty() }
        val call = callsTo("updatePlaylist").single()
        assertEquals("GET", call.method)
        assertEquals("p1", call.url.queryParameter("playlistId"))
        assertEquals(listOf("2", "7"), call.url.queryParameterValues("songIndexToRemove"))
        // The sidebar's counts are read again afterwards.
        waitFor { callsTo("getPlaylists").size >= 2 }
    }

    @Test
    fun aServerThatTakesFormsGetsTheRemovalsInTheBody() {
        server.answer("updatePlaylist")
        val app = app(extensions = listOf("formPost:1"))
        app.removeFromPlaylist("p1", listOf(3))
        waitFor { callsTo("updatePlaylist").isNotEmpty() }
        val call = callsTo("updatePlaylist").single()
        assertEquals("POST", call.method)
        assertEquals("playlistId=p1&songIndexToRemove=3", call.body?.utf8())
    }

    @Test
    fun aRemovalTheServerRefusesSaysSo() {
        server.fail("updatePlaylist", 50, "Not allowed")
        val app = app()
        app.removeFromPlaylist("p1", listOf(0))
        waitFor { app.notice != null }
        assertTrue(app.notice!!.startsWith("Couldn't remove from the playlist"))
    }

    @Test
    fun entriesComeOutOfTheQueueByTheirKeys() {
        val app = app()
        app.play(listOf(one, two, Song("s3", "Three")))
        val keys = player.state.value.queue.drop(1).map { it.key }
        app.removeFromQueue(keys)
        assertEquals(listOf("s1"), queueIds())
    }
}
