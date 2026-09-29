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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

// Changing a password, and checking one, without the password ever being
// written into an address.
class PasswordTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client(mode: AuthMode = AuthMode.Token) = SubsonicClient(server.url("/"), Credentials("winters", "old secret", mode), OkHttpClient())

    private fun ok() = server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build())

    private fun error(code: Int, message: String) =
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}""").build())

    private fun form(body: String): Map<String, String> = body.split('&').filter { it.isNotEmpty() }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    @Test
    fun theNewPasswordGoesInAFormBodyNeverTheAddress() = runTest {
        ok()
        client().changePassword("winters", "new & secret")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("changePassword", request.url.pathSegments.last())
        assertEquals(mapOf("username" to "winters", "password" to "new & secret"), form(request.body!!.utf8()))
        val address = request.url.toString()
        assertNull(request.url.queryParameter("password"))
        assertFalse(address, address.contains("new"))
        assertFalse(address, address.contains("secret"))
        // The sign-in itself is the usual token.
        assertEquals("winters", request.url.queryParameter("u"))
        assertTrue(request.url.queryParameter("t") != null)
    }

    @Test
    fun afterChangingItsOwnPasswordTheClientSignsInWithTheNewOne() = runTest {
        ok()
        ok()
        val client = client()
        client.changePassword("winters", "brand new")
        client.ping()
        server.takeRequest()
        val next = server.takeRequest().url
        assertEquals(md5Hex("brand new" + next.queryParameter("s")), next.queryParameter("t"))
    }

    @Test
    fun anotherUsersPasswordLeavesTheClientsOwn() = runTest {
        ok()
        ok()
        val client = client()
        client.changePassword("guest", "theirs")
        client.ping()
        server.takeRequest()
        val next = server.takeRequest().url
        assertEquals(md5Hex("old secret" + next.queryParameter("s")), next.queryParameter("t"))
    }

    @Test
    fun aRefusalIsTheServersOwnError() = runTest {
        error(50, "User not authorized")
        try {
            client().changePassword("winters", "x")
            fail()
        } catch (e: SubsonicException.Server) {
            assertEquals(50, e.code)
        }
    }

    @Test
    fun aServerWithoutTheCallSaysSoByItsStatus() = runTest {
        server.enqueue(MockResponse.Builder().code(501).body("Not implemented").build())
        try {
            client().changePassword("winters", "x")
            fail()
        } catch (e: SubsonicException.NotSubsonic) {
            assertEquals(501, e.status)
        }
    }

    @Test
    fun aRefusedChangeKeepsTheOldPassword() = runTest {
        error(50, "User not authorized")
        ok()
        val client = client()
        runCatching { client.changePassword("winters", "never") }
        client.ping()
        server.takeRequest()
        val next = server.takeRequest().url
        assertEquals(md5Hex("old secret" + next.queryParameter("s")), next.queryParameter("t"))
    }

    @Test
    fun anotherSecretIsCheckedWithoutTouchingTheClient() = runTest {
        ok()
        ok()
        val client = client(AuthMode.LegacyPassword)
        client.withSecret("typed").ping()
        client.ping()
        assertEquals(legacyPassword("typed"), server.takeRequest().url.queryParameter("p"))
        assertEquals(legacyPassword("old secret"), server.takeRequest().url.queryParameter("p"))
    }

    @Test
    fun aUserTheServerSaysNothingAboutMayChangeTheirSettings() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","user":{"username":"winters","adminRole":false}}}""").build())
        assertTrue(client().user("winters").settingsRole)
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","user":{"username":"winters","settingsRole":false}}}""").build())
        assertFalse(client().user("winters").settingsRole)
    }
}
