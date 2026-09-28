package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// Octo's library actions: what the user may do, and taking a song out of
// the library, never by a rating.
class LibraryActionsTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private fun ok(inner: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","openSubsonic":true,$inner}}"""

    @Test
    fun readsWhatTheUserMayDo() = runTest {
        answer(ok(""""libraryActions":{"enabled":true,"allowed":true,"dryRun":false,"actions":["remove"],"keepDays":30}"""))
        val actions = client().libraryActions()

        val request = server.takeRequest()
        assertEquals("/rest/getLibraryActions", request.url.encodedPath)
        assertEquals("winters", request.url.queryParameter("u"))
        assertEquals(LibraryActions(enabled = true, allowed = true, dryRun = false, actions = listOf("remove"), keepDays = 30), actions)
        assertTrue(actions.canRemove)
    }

    @Test
    fun removingNeedsTheSwitchTheAllowlistARealRunAndTheAction() {
        val all = LibraryActions(enabled = true, allowed = true, dryRun = false, actions = listOf("remove"), keepDays = 30)
        assertTrue(all.canRemove)
        assertFalse(all.copy(enabled = false).canRemove)
        assertFalse(all.copy(allowed = false).canRemove)
        assertFalse(all.copy(dryRun = true).canRemove)
        assertFalse(all.copy(actions = emptyList()).canRemove)
        assertFalse(LibraryActions().canRemove)
    }

    @Test
    fun aMissingAnswerMeansNothingIsAllowed() = runTest {
        answer(ok(""""x":1"""))
        assertFalse(client().libraryActions().canRemove)
    }

    @Test
    fun removeAsksForTheSongByIdAndReadsTheOutcome() = runTest {
        answer(ok(""""libraryAction":{"id":"abc","action":"remove","state":"applied","detail":"Removed. It will not be downloaded again."}"""))
        val result = client().libraryAction("abc")

        val request = server.takeRequest()
        assertEquals("/rest/libraryAction", request.url.encodedPath)
        assertEquals("abc", request.url.queryParameter("id"))
        assertEquals("remove", request.url.queryParameter("action"))
        // Nothing about ratings goes with it.
        assertEquals(null, request.url.queryParameter("rating"))
        assertEquals(LibraryActionState.Applied, result.outcome)
        assertEquals("Removed. It will not be downloaded again.", result.detail)
    }

    @Test
    fun readsEveryStateAndToleratesNewOnes() {
        assertEquals(LibraryActionState.Rehearsed, LibraryActionState.of("rehearsed"))
        assertEquals(LibraryActionState.Skipped, LibraryActionState.of(" Skipped "))
        assertEquals(LibraryActionState.Unresolved, LibraryActionState.of("unresolved"))
        assertEquals(LibraryActionState.Failed, LibraryActionState.of("failed"))
        assertEquals(LibraryActionState.Unknown, LibraryActionState.of("pending"))
        assertEquals(LibraryActionState.Unknown, LibraryActionState.of(null))
    }

    @Test
    fun aServerErrorIsThrown() = runTest {
        answer("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":10,"message":"Required parameter is missing: id"}}}""")
        try {
            client().libraryAction("")
            fail("expected an error")
        } catch (e: SubsonicException) {
            // expected
        }
    }
}
