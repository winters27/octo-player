package app.winters.octo.animated

import app.winters.octo.connection.GATEWAY_PAGE
import app.winters.octo.connection.PAGE_TYPE
import app.winters.octo.subsonic.AnimatedClip
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// A sign-in page is never kept as a loop or its still; a real loop is.
class LoopFilesGatewayTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun aSignInPageIsNeverKeptAsALoopOrItsStill() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/if/flow/default-authentication-flow/").build())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", PAGE_TYPE).body(GATEWAY_PAGE).build())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", PAGE_TYPE).body(GATEWAY_PAGE).build())
        val fetcher = LoopFetcher(OkHttpClient())
        val still = File(folder.root, "still.jpg")
        assertFalse(fetcher.fetchPoster(AnimatedClip(poster = server.url("/art/still.jpg").toString()), still))
        assertFalse(still.exists())
        assertFalse(File(still.path + ".part").exists())
        val loop = File(folder.root, "loop.mp4")
        assertFalse(fetcher.fetch(LoopFile(server.url("/art/loop.mp4"), 6.0), loop))
        assertFalse(loop.exists())
    }

    @Test
    fun aRealLoopIsStillKept() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "video/mp4").body("....ftypmp42").build())
        val loop = File(folder.root, "loop.mp4")
        assertTrue(LoopFetcher(OkHttpClient()).fetch(LoopFile(server.url("/art/loop.mp4"), 6.0), loop))
        assertTrue(loop.isFile)
    }
}
