package app.winters.octo.subsonic

import org.junit.Assert.assertNull
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
    fun anInviteIsAcceptedWithTheChosenPassword() = runTest {
        answer("""{"username":"alex"}""", cookie = "octo_family=s1; Path=/; HttpOnly")
        val web = web()
        assertEquals("alex", web.join("tok_1", "a long password", " Alex "))

        val joined = server.takeRequest()
        assertEquals("POST", joined.method)
        assertEquals("/api/family/join", joined.url.encodedPath)
        assertEquals("1", joined.headers["X-Octo-Family"])
        val body = joined.body!!.utf8()
        assertTrue(body.contains("\"token\":\"tok_1\""))
        assertTrue(body.contains("\"password\":\"a long password\""))
        assertTrue(body.contains("\"displayName\":\"Alex\""))
    }

    @Test
    fun aManagerAddsAMemberAndGetsTheirLink() = runTest {
        answer("""{"member":{"username":"sam","displayName":"Sam","role":"Kid"},"inviteLink":"https://music.example.com/family/join#invite=t9",
            "links":{"anywhere":null,"home":"http://192.168.1.20:4533/family/join#invite=t9"},"anywhereAvailable":false}""")
        answer("""{"username":"sam","displayName":"Sam","role":"Kid"}""")
        val added = web().addMember("sam", "Sam", FamilyPreset.Kid, away = false)
        assertEquals("Kid", added.member.roleName)
        assertEquals("https://music.example.com/family/join#invite=t9", added.inviteLink)
        assertFalse(added.anywhereAvailable)
        assertEquals(null, added.links!!.anywhere)
        assertEquals("http://192.168.1.20:4533/family/join#invite=t9", added.links!!.home)
        assertTrue(server.takeRequest().body!!.utf8().contains("\"preset\":\"Kid\""))
        // Listening away from home, set as an edit to the new member's abilities.
        val edit = server.takeRequest()
        assertEquals("PUT", edit.method)
        assertEquals("/api/family/members/sam", edit.url.encodedPath)
        assertEquals("""{"edits":{"away":false}}""", edit.body!!.utf8())

        answer("""{"inviteLink":"https://music.example.com/family/join#invite=r1","links":{"anywhere":"https://music.example.com/family/join#invite=r1","home":null},"expires":"2026-10-27T18:00:00Z"}""")
        val reset = web().resetMember("sam smith")
        assertEquals("https://music.example.com/family/join#invite=r1", reset.inviteLink)
        assertEquals("2026-10-27T18:00:00Z", reset.expires)
        val resetCall = server.takeRequest()
        assertEquals("POST", resetCall.method)
        assertEquals("/api/family/members/sam%20smith/reset", resetCall.url.encodedPath)

        answer("""{"inviteLink":"https://music.example.com/family/join#invite=t10","homeOnly":true}""")
        val fresh = web().newInvite("sam smith")
        assertEquals("https://music.example.com/family/join#invite=t10", fresh.inviteLink)
        assertTrue(fresh.homeOnly)
        val again = server.takeRequest()
        assertEquals("POST", again.method)
        assertEquals("/api/family/members/sam%20smith/invite", again.url.encodedPath)
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
            web().resetMember("sam")
            fail("A call the server refuses must not pass")
        } catch (e: SubsonicException.WrongCredentials) {
            assertEquals("The server did not take this sign-in for that.", e.message)
        }
    }

    @Test
    fun anAppsCallsCarryItsSubsonicSignInAndNoPassword() = runTest {
        answer("""{"member":{"username":"sam"},"inviteLink":"https://music.example.com/family/join#invite=t9"}""")
        answer("""{"username":"sam"}""")
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

    @Test
    fun aServerBehindAPathKeepsItInEveryCall() = runTest {
        answer("""{"id":"r_1"}""")
        FamilyWeb(server.url("/octo"), OkHttpClient()).redeemSignIn("tok", "Phone", FamilyPlatform.Android)
        assertEquals("/octo/api/family/signin/redeem", server.takeRequest().url.encodedPath)
        answer("""{"inviteLink":"x"}""")
        FamilyWeb(server.url("/octo/"), OkHttpClient()).resetMember("sam")
        assertEquals("/octo/api/family/members/sam/reset", server.takeRequest().url.encodedPath)
    }

    @Test
    fun aHandOverIsStartedDecidedAndSealedBySignedCalls() = runTest {
        val client = SubsonicClient(server.url("/"), Credentials("alex", "pw"), OkHttpClient())
        val web = client.familyWeb()
        answer("""{"token":"tok_9","expires":"2026-10-20T18:02:00Z","links":{"anywhere":"https://music.example.com","home":"http://192.168.1.20:4533"},"anywhereAvailable":true}""")
        val start = web.startSignIn()
        assertEquals("tok_9", start.token)
        assertEquals("http://192.168.1.20:4533", start.links.home)
        assertFalse(start.toString().contains("tok_9"))
        assertEquals("/api/family/signin/start", server.takeRequest().url.encodedPath)

        // Nobody yet: the server answers 204.
        server.enqueue(MockResponse.Builder().code(204).build())
        assertNull(web.signInPending("tok_9"))
        val waiting = server.takeRequest()
        assertEquals("GET", waiting.method)
        assertEquals("/api/family/signin/tok_9/pending", waiting.url.encodedPath)
        assertEquals("alex", waiting.url.queryParameter("u"))

        answer("""{"id":"r_1","deviceName":"Pixel 9","platform":"Android"}""")
        assertEquals(FamilySignInPending("r_1", "Pixel 9", "Android"), web.signInPending("tok_9"))
        server.takeRequest()

        server.enqueue(MockResponse.Builder().code(204).build())
        web.decideSignIn("tok_9", allow = true)
        val decide = server.takeRequest()
        assertEquals("/api/family/signin/tok_9/decide", decide.url.encodedPath)
        assertEquals("""{"allow":true}""", decide.body!!.utf8())

        server.enqueue(MockResponse.Builder().code(204).build())
        web.putSignInBox("tok_9", "c2VhbGVk")
        val box = server.takeRequest()
        assertEquals("/api/family/signin/tok_9/box", box.url.encodedPath)
        assertEquals("""{"box":"c2VhbGVk"}""", box.body!!.utf8())
    }

    @Test
    fun theNewDeviceRedeemsAndWaitsWithNoSignIn() = runTest {
        answer("""{"id":"r_1"}""")
        answer("""{"state":"Waiting"}""")
        answer("""{"state":"Allowed","box":"c2VhbGVk"}""")
        val web = web()
        assertEquals("r_1", web.redeemSignIn("tok_9", " Pixel 9 ", FamilyPlatform.Android))
        val redeem = server.takeRequest()
        assertEquals("POST", redeem.method)
        assertEquals("""{"token":"tok_9","deviceName":"Pixel 9","platform":"Android"}""", redeem.body!!.utf8())
        assertNull(redeem.url.queryParameter("u"))
        assertEquals(FamilySignInState.Waiting, web.signInStatus("r_1").state)
        val allowed = web.signInStatus("r_1")
        assertEquals(FamilySignInState.Allowed, allowed.state)
        assertEquals("c2VhbGVk", allowed.box)
        assertFalse(allowed.toString().contains("c2VhbGVk"))
        assertEquals("/api/family/signin/redeem/r_1", server.takeRequest().url.encodedPath)
    }
}
