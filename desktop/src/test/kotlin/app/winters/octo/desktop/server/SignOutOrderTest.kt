package app.winters.octo.desktop.server

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.settings.SettingsStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// A change to the kept servers still being checked when a sign-out or
// removal comes in never undoes it: what was signed out stays signed out
// with nothing left in the password store, a change begun before a
// sign-out of the server in use does not make its server the one in use,
// and a server gone from the list is neither brought back nor made the one
// in use.
class SignOutOrderTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = FakeServer()
    private val other = FakeServer()
    private val secrets = FakeSecrets()
    private val settings by lazy { SettingsStore(File(folder.root, "settings.json")) }

    // The held check: asked when it reaches the server, let go by release.
    private val asked = CountDownLatch(1)
    private val release = CountDownLatch(1)

    @After
    fun stop() {
        release.countDown()
        server.close()
        other.close()
    }

    private fun accounts(): Accounts {
        val security = ServerSecurity(settings)
        return Accounts(settings, secrets, security.install(OkHttpClient.Builder()).build(), security)
    }

    // `at` answers like the server, holding each ping `held` picks until let go.
    private fun holding(at: FakeServer, held: (RecordedRequest) -> Boolean = { true }) {
        at.answerBy("ping") { request ->
            if (held(request)) {
                asked.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            at.ok(type = "octo")
        }
    }

    // An edit moving a server to another address, signed out of while it
    // was checked, stays signed out and leaves no password at either
    // address. Red when a keep the sign-out won leaves its new password
    // account in the store.
    @Test
    fun anEditSignedOutOfMeanwhileLeavesNoPasswordBehind() = runTest {
        server.answer("ping", type = "octo")
        val accounts = accounts()
        val first = accounts.signIn(SignInRequest(server.address, "winters", "pw")) as SignInOutcome.Done
        val id = first.connection.server.id
        holding(other)
        val ticket = accounts.ticket()
        val edit = async(Dispatchers.IO) { accounts.edit(id, SignInRequest(other.address, "winters", "pw"), generation = ticket) }
        assertTrue(withContext(Dispatchers.IO) { asked.await(5, TimeUnit.SECONDS) })
        val stop = accounts.stopTicket()
        val out = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { accounts.signOut(id, stop) }
        release.countDown()
        val saved = edit.await() as SignInOutcome.Saved
        out.join()
        assertTrue(saved.server.signedOut)
        assertTrue(accounts.find(id)!!.signedOut)
        assertNull(secrets.kept[secretAccount("winters", accounts.find(id)!!.address)])
        assertNull(secrets.kept[secretAccount("winters", first.connection.server.address)])
        assertNull(settings.current.activeServer)
    }

    // An edit of the server in use under another username, overtaken by a
    // sign-out of it, does not carry "in use" over to the new account. Red
    // when keep carries the server in use to the new id for an overtaken
    // change.
    @Test
    fun anOvertakenEditUnderANewIdIsNotMadeTheServerInUse() = runTest {
        server.answer("ping", type = "octo")
        val accounts = accounts()
        val first = accounts.signIn(SignInRequest(server.address, "winters", "pw")) as SignInOutcome.Done
        holding(server) { it.url.queryParameter("u") == "brandon" }
        val ticket = accounts.ticket()
        val edit = async(Dispatchers.IO) { accounts.edit(first.connection.server.id, SignInRequest(server.address, "brandon", "pw"), generation = ticket) }
        assertTrue(withContext(Dispatchers.IO) { asked.await(5, TimeUnit.SECONDS) })
        accounts.stopTicket()
        release.countDown()
        val saved = edit.await() as SignInOutcome.Saved
        assertTrue(saved.server.id != first.connection.server.id)
        assertNull(accounts.active)
        assertTrue(settings.current.activeServer != saved.server.id)
    }

    // An edit asked for before a sign-out of its server, that waited its
    // turn behind another change and so reads the server already signed
    // out, leaves it signed out. Red when keep goes by the server as it
    // reads it rather than by the order of the tickets.
    @Test
    fun aQueuedEditAskedBeforeASignOutStaysSignedOut() = runTest {
        server.answer("ping", type = "octo")
        other.answer("ping", type = "octo")
        val accounts = accounts()
        val first = accounts.signIn(SignInRequest(server.address, "winters", "pw")) as SignInOutcome.Done
        val second = accounts.add(SignInRequest(other.address, "guest", "pw2")) as SignInOutcome.Saved
        val id = first.connection.server.id
        holding(other)
        val switchTicket = accounts.ticket()
        val switch = async(Dispatchers.IO) { accounts.switchTo(second.server.id, switchTicket) }
        assertTrue(withContext(Dispatchers.IO) { asked.await(5, TimeUnit.SECONDS) })
        val ticket = accounts.ticket()
        val edit = async(Dispatchers.IO) { accounts.edit(id, SignInRequest(server.address, "winters", "pw"), generation = ticket) }
        val stop = accounts.stopTicket()
        val out = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { accounts.signOut(id, stop) }
        release.countDown()
        assertEquals(SwitchOutcome.Overtaken, switch.await())
        val saved = edit.await() as SignInOutcome.Saved
        out.join()
        assertTrue(saved.server.signedOut)
        assertTrue(accounts.find(id)!!.signedOut)
        assertNull(settings.current.activeServer)
        assertNull(secrets.kept[secretAccount("winters", first.connection.server.address)])
    }

    // A sign-in again to a kept server removed while it was checked fails,
    // and neither brings it back nor makes it the one in use. Red when keep
    // does not look whether the server is still kept.
    @Test
    fun aSignInAgainToAServerRemovedMeanwhileFails() = runTest {
        server.answer("ping", type = "octo")
        other.answer("ping", type = "octo")
        val accounts = accounts()
        val first = accounts.signIn(SignInRequest(server.address, "winters", "pw")) as SignInOutcome.Done
        val second = accounts.add(SignInRequest(other.address, "guest", "pw2")) as SignInOutcome.Saved
        holding(other)
        val again = async(Dispatchers.IO) { accounts.signInAgain(second.server.id, SignInRequest(other.address, "guest", "pw2")) }
        assertTrue(withContext(Dispatchers.IO) { asked.await(5, TimeUnit.SECONDS) })
        val out = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { accounts.remove(second.server.id) }
        release.countDown()
        assertTrue(again.await() is SignInOutcome.Failed)
        out.join()
        assertNull(accounts.find(second.server.id))
        assertEquals(first.connection.server.id, settings.current.activeServer)
        assertNull(secrets.kept[secretAccount("guest", second.server.address)])
    }
}
