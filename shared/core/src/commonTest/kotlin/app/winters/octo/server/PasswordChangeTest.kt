package app.winters.octo.server

import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// Changing the signed-in user's password: the current one checked first,
// and every way it can go said plainly.
class PasswordChangeTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client(mode: AuthMode = AuthMode.Token) = SubsonicClient(server.url("/"), Credentials("winters", "old", mode), OkHttpClient())

    private fun ok() = server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build())

    private fun error(code: Int) =
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"No"}}}""").build())

    @Test
    fun theFormSaysWhatIsMissingBeforeAnythingIsSent() {
        assertEquals("Enter your current password.", passwordDraftProblem(PasswordDraft("", "a", "a")))
        assertEquals("Enter a new password.", passwordDraftProblem(PasswordDraft("old", "", "")))
        assertEquals("The new passwords don't match.", passwordDraftProblem(PasswordDraft("old", "new", "nwe")))
        assertEquals("That's the password you have now. Choose a new one.", passwordDraftProblem(PasswordDraft("old", "old", "old")))
        assertNull(passwordDraftProblem(PasswordDraft("old", "new", "new")))
    }

    @Test
    fun theCurrentPasswordIsCheckedThenTheNewOneSentInTheBody() = runTest {
        ok()
        ok()
        assertEquals(PasswordChange.Changed, changeOwnPassword(client(), "old", "new one"))
        val check = server.takeRequest()
        assertEquals("ping", check.url.pathSegments.last())
        val change = server.takeRequest()
        assertEquals("POST", change.method)
        assertEquals("username=winters&password=new%20one", change.body!!.utf8().replace("+", "%20"))
        assertFalse(change.url.toString().contains("new"))
    }

    @Test
    fun aWrongCurrentPasswordChangesNothing() = runTest {
        error(40)
        assertEquals(PasswordChange.WrongCurrent, changeOwnPassword(client(), "guess", "new"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anAccountThatMayNotIsToldSo() = runTest {
        ok()
        error(50)
        val result = changeOwnPassword(client(), "old", "new")
        assertEquals(PasswordChange.NotAllowed, result)
        assertEquals("Your server doesn't let this account change its password. Ask whoever runs the server.", passwordChangeWords(result))
    }

    @Test
    fun aServerWithoutTheCallIsToldApartFromARefusal() = runTest {
        ok()
        server.enqueue(MockResponse.Builder().code(501).body("Not implemented").build())
        assertEquals(PasswordChange.NotOffered, changeOwnPassword(client(), "old", "new"))
        // One that ignored the body and missed the password.
        ok()
        error(10)
        assertEquals(PasswordChange.NotOffered, changeOwnPassword(client(), "old", "new"))
    }

    @Test
    fun aKeyHasNoPasswordToChange() = runTest {
        assertEquals(PasswordChange.UsesKey, changeOwnPassword(client(AuthMode.ApiKey), "old", "new"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aServerOutOfReachChangesNothingAndSaysSo() = runTest {
        val gone = SubsonicClient(server.url("/"), Credentials("winters", "old"), OkHttpClient())
        server.close()
        val result = changeOwnPassword(gone, "old", "new")
        assertEquals("Couldn't reach the server, so nothing changed. Try again in a moment.", passwordChangeWords(result))
    }
}
