package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// A song's transition profile as Octo sends it, and the answer while it has none.
class TransitionProfilesTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private fun ok(inner: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","openSubsonic":true,$inner}}"""

    @Test
    fun readsAReadyProfile() = runTest {
        answer(
            ok(
                """"transitionProfile":{"id":"nd-1","ready":true,"version":1,"durationMs":241330,"bodyDb":-11.52,
                "tempo":{"bpm":120.0,"trusted":true,"beatMs":500.0,"firstBeatMs":12.5,"downbeatMs":512.5,"confidence":2.3,"consistency":0.8,"steady":true},
                "head":{"startMs":0,"hopMs":100,"levels":"AAEC","bodyDb":-12.5,"gateDb":-57.5,"soundStartMs":40,"soundEndMs":30000,"introEndMs":3500,"boundariesMs":[]},
                "tail":{"startMs":181330,"hopMs":100,"levels":"AAEC","bodyDb":-11.52,"gateDb":-56.52,"soundStartMs":181330,"soundEndMs":240900,"outroStartMs":230000,"boundariesMs":[200000,231000],"later":true}}""",
            ),
        )
        val profile = client().transitionProfile("nd-1")

        val request = server.takeRequest()
        assertEquals("/rest/getTransitionProfile", request.url.encodedPath)
        assertEquals("nd-1", request.url.queryParameter("id"))
        assertTrue(profile.ready)
        assertEquals(1, profile.version)
        assertEquals(241_330L, profile.durationMs)
        assertEquals(500.0, profile.tempo!!.beatMs, 0.0)
        assertTrue(profile.tempo!!.trusted)
        assertEquals(3_500L, profile.head!!.introEndMs)
        assertNull(profile.head!!.outroStartMs)
        assertEquals(listOf(200_000L, 231_000L), profile.tail!!.boundariesMs)
        assertEquals("AAEC", profile.tail!!.levels)
    }

    @Test
    fun readsANotReadyAnswer() = runTest {
        answer(ok(""""transitionProfile":{"id":"nd-2","ready":false,"version":1}"""))
        val profile = client().transitionProfile("nd-2")
        assertFalse(profile.ready)
        assertEquals("nd-2", profile.id)
        assertEquals(1, profile.version)
        assertNull(profile.head)
    }
}
