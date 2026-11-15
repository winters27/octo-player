package app.winters.octo.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.OctoApp
import app.winters.octo.animated.LoopPlayers
import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.startHome
import app.winters.octo.connection.testSecurity
import app.winters.octo.data.installTestKeystore
import app.winters.octo.data.testSessions
import app.winters.octo.family.FamilyHub
import app.winters.octo.family.FamilyNoticeStore
import app.winters.octo.lyrics.LyricsTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import app.winters.octo.offline.OfflineSettings
import app.winters.octo.offline.StreamCache
import app.winters.octo.player.PlayerSettings
import app.winters.octo.ui.common.Feedback
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

// Songs, downloads, covers and loops each reach the server through the
// client that refuses a sign-in page's HTML in place of one, as the app
// wires them: Streams for the player and downloads, the app's image loader,
// and the loop players. Each is made the way the app makes it.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SignInPageWiringTest {
    private val server = MockWebServer()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val security = testSecurity()
    private val releases = mutableListOf<() -> Unit>()
    private lateinit var main: HttpUrl
    private lateinit var http: OkHttpClient

    @Before
    fun start() {
        installTestKeystore()
        main = server.startHome()
        security.configure(main, ConnectionSettings())
        http = security.install(OkHttpClient.Builder()).build()
        // A sign-in page in front of the server sends every request to its
        // own page, which answers 200 with HTML.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.url.encodedPath == "/login") {
                MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8").body("<html><body>Sign in</body></html>").build()
            } else {
                MockResponse.Builder().code(302).addHeader("Location", main.resolve("/login")!!.toString()).build()
            }
        }
    }

    @After
    fun stop() {
        releases.forEach { runCatching(it) }
        server.close()
    }

    // Red when Streams gives Media3 the app's plain client.
    @Test
    fun aDownloadThroughStreamsRefusesASignInPageBehindARedirect() {
        val sessions = testSessions(security = security, http = http)
        val kept = StreamCache(context, OfflineSettings(context))
        val family = FamilyHub(context, sessions, FamilyNoticeStore(context), Feedback())
        val timing = LyricsTiming(context)
        val drops = VersionDrops(kept, { emptyList() }, timing::reset, { emptySet() }, Dispatchers.IO, CoroutineScope(Dispatchers.IO))
        val streams = Streams(
            context, sessions, PlayerSettings(context), http, security, kept, family,
            PlayRestarts(context), StreamVersionFeed(sessions, drops, context), drops,
        )
        // Songs are kept as they play, as the app starts out.
        runBlocking { withTimeout(5_000) { kept.enabled.first { it } } }
        releases += { kept.cache.release() }
        val source = streams.downloadSource()
        val error = assertThrows(IOException::class.java) { source.open(DataSpec(Uri.parse(main.resolve("/rest/stream?id=1")!!.toString()))) }
        source.close()
        assertTrue("The download failed with $error", signInPageIn(error))
        // The request and its redirect, once.
        assertEquals(2, server.requestCount)
    }

    // Red when the app's image loader gets the plain client for covers.
    @Test
    fun aCoverThroughTheAppsImageLoaderRefusesASignInPage() = runBlocking {
        val shared = http
        val sessions = testSessions(security = security, http = shared)
        val app = OctoApp().also {
            it.http = shared
            it.security = security
            it.sessions = sessions
        }
        val loader = app.newImageLoader(context)
        val result = loader.execute(ImageRequest.Builder(context).data(main.resolve("/rest/getCoverArt?id=al-1")!!.toString()).size(64).build())
        val error = (result as? ErrorResult)?.throwable
        assertTrue("The cover came back as $result", error != null && signInPageIn(error))
    }

    // Red when the loop players read loops through the plain client.
    @Test
    fun aLoopThroughTheLoopPlayersRefusesASignInPage() {
        val loops = LoopPlayers(context, http, security)
        val refused = runCatching {
            loops.loopHttp.newCall(Request.Builder().url(main.resolve("/rest/getAnimatedArt?id=al-1")!!).build()).execute().close()
        }.exceptionOrNull()
        assertTrue("The loop came back as ${refused ?: "a page"}", refused != null && signInPageIn(refused))
    }
}
