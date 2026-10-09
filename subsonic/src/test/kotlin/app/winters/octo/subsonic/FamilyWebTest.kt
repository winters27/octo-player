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

// The family page's calls: an invite accepted, a device added with the
// page's cookie, a manager's member and link, and plain words on errors.
class FamilyWebTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun web() = FamilyWeb(server.url("/"), OkHttpClient())

    private fun answer(body: String, code: Int = 200, cookie: String? = null) = server.enqueue(
        MockResponse.Builder().code(code).body(body).apply { cookie?.let { addHeader("Set-Cookie", it) } }.build(),
    )

    @Test
    fun anInviteIsAcceptedAndTheDeviceGetsACode() = runTest {
        answer("""{"username":"alex"}""", cookie = "octo_family=s1; Path=/; HttpOnly")
        answer("""{"deviceId":"d_1","kind":"OctoApp","pairCode":"482913","expires":"2026-10-20T18:15:00Z","username":"alex"}""")
        val web = web()
        assertEquals("alex", web.join("tok_1", "a long password", " Alex "))
        assertEquals("482913", web.addMyDevice("Pixel 9", FamilyDeviceKind.OctoApp).pairCode)

        val joined = server.takeRequest()
        assertEquals("POST", joined.method)
        assertEquals("/api/family/join", joined.url.encodedPath)
        assertEquals("1", joined.headers["X-Octo-Family"])
        val body = joined.body!!.utf8()
        assertTrue(body.contains("\"token\":\"tok_1\""))
        assertTrue(body.contains("\"displayName\":\"Alex\""))
        val device = server.takeRequest()
        assertEquals("/api/family/me/devices", device.url.encodedPath)
        // Signed in by the cookie the invite set.
        assertEquals("octo_family=s1", device.headers["Cookie"])
        assertTrue(device.body!!.utf8().contains("\"kind\":\"OctoApp\""))
    }

    @Test
    fun aManagerAddsAMemberAndGetsTheirLink() = runTest {
        answer("""{"member":{"username":"sam","displayName":"Sam","role":"Kid"},"inviteLink":"https://music.example.com/family/join#invite=t9"}""")
        val added = web().addMember("sam", "Sam", FamilyPreset.Kid)
        assertEquals("Kid", added.member.roleName)
        assertEquals("https://music.example.com/family/join#invite=t9", added.inviteLink)
        assertTrue(server.takeRequest().body!!.utf8().contains("\"preset\":\"Kid\""))

        answer("""{"deviceId":"d_5","kind":"OctoApp","pairCode":"104729","username":"sam smith"}""")
        assertEquals("104729", web().addMemberDevice("sam smith", "Tablet", FamilyDeviceKind.OctoApp).pairCode)
        val device = server.takeRequest()
        assertEquals("/api/family/members/sam%20smith/devices", device.url.encodedPath)
        assertTrue(device.body!!.utf8().contains("\"name\":\"Tablet\""))
    }

    @Test
    fun errorsSayWhatHappened() = runTest {
        answer("""{"message":"That invite has been used."}""", code = 410)
        try {
            web().join("tok", "password1", "Alex")
            fail("A used invite must not join")
        } catch (e: SubsonicException.NotFound) {
            assertEquals("That invite has been used.", e.message)
        }
        answer("", code = 401)
        try {
            web().addMyDevice("Phone", FamilyDeviceKind.OctoApp)
            fail("A call the server refuses must not pass")
        } catch (e: SubsonicException.WrongCredentials) {
            assertEquals("The server did not take this sign-in for that.", e.message)
        }
    }

    @Test
    fun anAppsCallsCarryItsSubsonicSignInAndNoPassword() = runTest {
        answer("""{"member":{"username":"sam"},"inviteLink":"https://music.example.com/family/join#invite=t9"}""")
        val client = SubsonicClient(server.url("/"), Credentials("winters", "pw"), OkHttpClient())
        client.familyWeb().addMember("sam", "Sam", FamilyPreset.Kid)
        val url = server.takeRequest().url
        assertEquals("/api/family/members", url.encodedPath)
        assertEquals("winters", url.queryParameter("u"))
        assertTrue(url.queryParameter("t")!!.isNotEmpty())
        assertTrue(url.queryParameter("s")!!.isNotEmpty())
        assertEquals(API_VERSION, url.queryParameter("v"))
        assertEquals("Octo", url.queryParameter("c"))
        // The password itself never travels.
        assertEquals(null, url.queryParameter("p"))
        assertFalse(url.toString().contains("pw"))
    }
}
