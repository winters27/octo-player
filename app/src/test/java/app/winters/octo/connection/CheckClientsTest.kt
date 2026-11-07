package app.winters.octo.connection

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// A client made for one check of a server is let go once the check is
// done, wherever the app makes one.
@RunWith(RobolectricTestRunner::class)
class CheckClientsTest {
    private val server = MockWebServer()

    @After
    fun stop() = server.close()

    // Its threads stop and its connections close. Red when letGo
    // leaves either.
    @Test
    fun aCheckClientIsLetGo() = runTest {
        val main = server.startHome()
        server.enqueue(MockResponse.Builder().body("ok").build())
        val own = ServerClients(ApplicationProvider.getApplicationContext(), testDevices()).forServer(main, ConnectionSettings(), 5)
        own.newCall(Request.Builder().url(main.resolve("/rest/ping.view")!!).build()).execute().use { it.body.string() }
        assertEquals(1, own.connectionPool.connectionCount())
        own.letGo()
        assertTrue(own.dispatcher.executorService.isShutdown)
        assertEquals(0, own.connectionPool.connectionCount())
    }

    // Every place that makes a check client lets it go. Red when a
    // call site (ConnectionCheck.look, SessionRepository.switchTo or check,
    // SignInViewModel.receive or join) stops letting its client go.
    @Test
    fun everyCheckClientIsLetGoWhereItIsMade() {
        val sites = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.map { it to it.readText() }
            .filter { (_, text) -> "clients.forJoin(" in text || "clients.forServer(" in text }.toList()
        assertEquals(3, sites.size)
        for ((file, text) in sites) {
            val made = Regex("""clients\.for(Join|Server)\(""").findAll(text).count()
            val letGo = Regex("""\.letGo\(\)""").findAll(text).count()
            assertTrue("${file.name}: $made made, $letGo let go", letGo >= made)
        }
    }
}
