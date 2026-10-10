package app.winters.octo.family

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.FamilyRequest
import app.winters.octo.subsonic.FamilyRequestState
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The background check of family requests: each decision is told once, per
// account, and a manager hears how many requests wait.
class FamilyNoticesTest {
    private class MemoryStore : DataStore<Preferences> {
        private val current = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(current.value).also { current.value = it }
    }

    private val server = MockWebServer()
    private var mine = "[]"
    private var waiting = "[]"
    private var approves = false

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val payload = when (request.url.pathSegments.last()) {
                    "getFamily" -> """"family":{"me":{"username":"alex","role":"Listener","managed":true,"abilities":{"approveRequests":$approves}}}"""
                    "getFamilyRequests" -> """"familyRequests":{"request":${if (request.url.queryParameter("all") == "true") waiting else mine}}"""
                    else -> ""
                }
                return MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo",$payload}}""").build()
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("alex", "secret"), OkHttpClient())

    private fun request(id: String, state: String) =
        """{"id":"$id","title":"Song $id","artist":"Artist","state":"$state","kind":"Song","quality":"Flac"}"""

    @Test
    fun eachDecisionIsToldOnce() = runBlocking {
        val store = FamilyNoticeStore(MemoryStore())
        val check = FamilyCheck(store)
        mine = "[${request("r1", "Pending")},${request("r0", "Declined")}]"
        // The first check is where it starts from: old news is not told.
        assertTrue(check.run("home", client()).isEmpty())

        mine = "[${request("r1", "Approved")},${request("r0", "Declined")}]"
        assertEquals(listOf("Approved"), check.run("home", client()).map { it.title })
        assertTrue(check.run("home", client()).isEmpty())

        mine = """[{"id":"r1","title":"Song r1","artist":"Artist","state":"Done","outcome":"AddedFromFamily","kind":"Song","quality":"Flac"}]"""
        val added = check.run("home", client()).single()
        assertEquals("Added", added.title)
        assertEquals("Song r1 · Artist. Added from the family library.", added.text)

        // Another account on the phone keeps its own.
        assertTrue(check.run("work", client()).isEmpty())
    }

    @Test
    fun aRequestTheSheetShowedIsNotToldAgain() = runBlocking {
        val store = FamilyNoticeStore(MemoryStore())
        val check = FamilyCheck(store)
        check.run("home", client())
        store.alreadyTold("home", FamilyRequest(id = "r5", state = FamilyRequestState.Done))
        mine = "[${request("r5", "Done")}]"
        assertTrue(check.run("home", client()).isEmpty())
    }

    @Test
    fun aManagerHearsHowManyWait() = runBlocking {
        approves = true
        waiting = "[${request("r7", "Pending")},${request("r8", "Pending")}]"
        val check = FamilyCheck(FamilyNoticeStore(MemoryStore()))
        assertEquals(listOf("2 requests waiting"), check.run("home", client()).map { it.title })
        assertTrue(check.run("home", client()).isEmpty())
        assertTrue(server.requestCount > 0)
    }
}
