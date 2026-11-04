package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// getStarred2 read as it arrives: pages handed on, and an answer that is
// cut off or reports an error after its lists never returns normally.
class StarredStreamTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private fun songs(count: Int) = (0 until count).joinToString(",") { """{"id":"s$it","title":"Song $it","starred":"2026-01-0${it % 9 + 1}T00:00:00Z"}""" }

    private suspend fun read(): Pair<List<List<StarredItem>>, Throwable?> {
        val pages = mutableListOf<List<StarredItem>>()
        val error = runCatching { client().starredInto { pages += it } }.exceptionOrNull()
        return pages to error
    }

    @Test
    fun aBigAnswerComesInPagesWithTheStatusAfterTheLists() = runTest {
        answer("""{"subsonic-response":{"starred2":{"artist":[{"id":"ar1","name":"A","starred":"2026-01-01T00:00:00Z"}],"album":[{"id":"al1"},{"id":"al2"}],"song":[${songs(1_200)}]},"status":"ok","version":"1.16.1"}}""")
        val (pages, error) = read()
        assertEquals(null, error)
        assertEquals(listOf(500, 500, 203), pages.map { it.size })
        val all = pages.flatten()
        assertEquals(StarredItem(StarredKind.Artist, "ar1", "2026-01-01T00:00:00Z"), all.first())
        assertEquals(listOf("al1", "al2"), all.filter { it.kind == StarredKind.Album }.map { it.id })
        assertEquals(1_200, all.count { it.kind == StarredKind.Song })
        assertTrue(pages.all { it.size <= STARRED_PAGE })
    }

    @Test
    fun aCutOffAnswerIsUnreachable() = runTest {
        answer("""{"subsonic-response":{"status":"ok","starred2":{"song":[${songs(600)}""")
        val (pages, error) = read()
        assertTrue("got $error", error is SubsonicException.Unreachable)
        assertEquals(listOf(500), pages.map { it.size })
    }

    @Test
    fun anErrorAfterTheListsStillFails() = runTest {
        answer("""{"subsonic-response":{"starred2":{"song":[${songs(3)}]},"status":"failed","error":{"code":0,"message":"Try later"}}}""")
        val (_, error) = read()
        assertTrue("got $error", error is SubsonicException.Server)
    }

    @Test
    fun aWrongPasswordReadsAsOne() = runTest {
        answer("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}""")
        assertTrue(read().second is SubsonicException.WrongCredentials)
    }

    @Test
    fun aPageThatIsNotSubsonicSaysSo() = runTest {
        answer("<html><body>Sign in</body></html>")
        assertTrue(read().second is SubsonicException.NotSubsonic)
        answer("""{"something":"else"}""")
        assertTrue(read().second is SubsonicException.NotSubsonic)
    }

    @Test
    fun numberIdsEmptyListsAndNullsAreRead() = runTest {
        answer("""{"subsonic-response":{"status":"ok","starred2":{"song":[{"id":42,"starred":null},{"title":"no id"}],"album":null}}}""")
        val (pages, error) = read()
        assertEquals(null, error)
        assertEquals(listOf(StarredItem(StarredKind.Song, "42", null)), pages.flatten())
        answer("""{"subsonic-response":{"status":"ok"}}""")
        val (none, noError) = read()
        assertEquals(null, noError)
        assertTrue(none.isEmpty())
    }
}
