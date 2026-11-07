package app.winters.octo.desktop.audio

import app.winters.octo.audio.ErrorKind
import app.winters.octo.connection.GatewayWords
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.gatewayOrigins
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// Songs as the engine is handed them when a sign-in page sits in front of
// the server: its headers only where they may go, its answer in place of a
// song said plainly, and the queue fetched afresh when the connection
// changes.
class GatewayStreamsTest {
    private val main = "https://music.example.com/".toHttpUrl()
    private val home = "http://192.168.1.20:4533/".toHttpUrl()
    private val headers = mapOf("Authorization" to "Basic YTpi", "CF-Access-Client-Secret" to "s", "X-Token" to "t")

    // T8: on the main address the engine gets every header; on an http
    // home address none a sign-in page reads; on an https one, all again.
    // Red when ServerSongs ignores the gateway origins.
    @Test
    fun anHttpHomeRouteGetsNoSignInPageHeaders() {
        var route = main
        val client = SubsonicClient(main, Credentials("winters", "pw"), OkHttpClient(), route = { route })
        val entry = QueueEntry(1, Song("s1", "One"))
        val plain = ServerSongs({ headers }, client = { client }, gatewayOrigins = { gatewayOrigins(main, home) })
        assertEquals(headers.keys, queueItem(entry, plain).headers.map { it.name }.toSet())
        route = home
        assertEquals(listOf("X-Token"), queueItem(entry, plain).headers.map { it.name })
        val secureHome = "https://192.168.1.20:4533/".toHttpUrl()
        route = secureHome
        val secure = ServerSongs({ headers }, client = { client }, gatewayOrigins = { gatewayOrigins(main, secureHome) })
        assertEquals(headers.keys, queueItem(entry, secure).headers.map { it.name }.toSet())
    }

    // An http home route hands the engine a sign-in page's headers only as
    // secure headers, for a hop of the same server up to https; the main
    // https address needs none. Red when ServerSongs leaves
    // secureStreamHeaders out, or queueItem drops them.
    @Test
    fun anHttpHomeRouteGivesTheSignInPageHeadersForItsHttpsTwinOnly() {
        var route = home
        val client = SubsonicClient(main, Credentials("winters", "pw"), OkHttpClient(), route = { route })
        val entry = QueueEntry(2, Song("s2", "Two"))
        val songs = ServerSongs({ headers }, client = { client }, gatewayOrigins = { gatewayOrigins(main, home) })
        val item = queueItem(entry, songs)
        assertEquals(listOf("X-Token"), item.headers.map { it.name })
        assertEquals(setOf("Authorization", "CF-Access-Client-Secret"), item.secureHeaders.map { it.name }.toSet())
        route = main
        assertTrue(queueItem(entry, songs).secureHeaders.isEmpty())
    }

    // T18: the engine's sign-in page failure in the shared words. Red when
    // the mapping goes (it falls to "Other").
    @Test
    fun aSignInPageInPlaceOfASongIsSaidSo() {
        assertEquals(GatewayWords.PLAY_FAILURE, plainWords(ErrorKind.SIGN_IN_PAGE))
        assertEquals(GatewayWords.PLAY_FAILURE, plainWords(ErrorKind.SIGN_IN_PAGE, outside = true))
    }

    // T17: a changed connection loads the engine's queue afresh, every song
    // at its new address, from where the song was, playing or paused as it
    // was. Red when reconnect does nothing or replaces the queue.
    @Test
    fun aChangedConnectionLoadsTheQueueAgainWhereItWas() {
        var at = "https://music.example.com/"
        var now = 0L
        val engine = FakeEngine()
        val player = EnginePlayer(engine, { SongAddress("$at${it.id}") }, Random(3), clock = { now })
        player.play((1..3).map { Song("s$it", "Song $it", duration = 200) }, 1, shuffle = false)
        now = 10_000
        engine.heard = Heard(engine.queue[1].id, 42_000.0, null)
        at = "http://192.168.1.20:4533/"
        player.reconnect()
        assertEquals("load 3 at 1 from 42000 playing", engine.calls.last())
        assertTrue(engine.queue.all { it.source.startsWith(at) })
        player.pause()
        player.reconnect()
        assertEquals("load 3 at 1 from 42000 paused", engine.calls.last())
    }
}
