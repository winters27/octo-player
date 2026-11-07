package app.winters.octo.desktop.server

import app.winters.octo.data.HeaderDraft
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.SettingsStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        val stop = accounts.stopTicket(id)
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
        accounts.stopTicket(first.connection.server.id)
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
        val stop = accounts.stopTicket(id)
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

    // An edit of the server in use under another username, so a new id,
    // held at the server while a sign-out or removal of it takes its
    // ticket; then the sign-out or removal runs before the edit lands
    // (`stopFirst`) or after it.
    private suspend fun newIdEditMeetsAStop(removing: Boolean, stopFirst: Boolean): Pair<Accounts, SignInOutcome> = coroutineScope {
        server.answer("ping", type = "octo")
        val accounts = accounts()
        val first = accounts.signIn(SignInRequest(server.address, "winters", "pw", headers = token)) as SignInOutcome.Done
        val id = first.connection.server.id
        holding(server) { it.url.queryParameter("u") == "brandon" }
        val ticket = accounts.ticket()
        val edit = async(Dispatchers.IO) { accounts.edit(id, SignInRequest(server.address, "brandon", "pw2", headers = token), generation = ticket) }
        assertTrue(withContext(Dispatchers.IO) { asked.await(5, TimeUnit.SECONDS) })
        val stop = accounts.stopTicket(id, removing)
        suspend fun stopIt() = if (removing) accounts.remove(id, stop) else accounts.signOut(id, stop)
        val out = if (stopFirst) launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { stopIt() } else null
        release.countDown()
        val outcome = edit.await()
        if (out != null) out.join() else stopIt()
        accounts to outcome
    }

    private val token = listOf(HeaderDraft("X-Token", "t"))

    private fun assertSignedOut(accounts: Accounts, outcome: SignInOutcome) {
        val saved = (outcome as SignInOutcome.Saved).server
        assertEquals("brandon", saved.username)
        assertTrue(accounts.find(saved.id)!!.signedOut)
        assertEquals(listOf(saved.id), accounts.servers.map { it.id })
        assertNull(secrets.kept[secretAccount("brandon", saved.address)])
        assertNull(settings.current.activeServer)
    }

    private fun assertRemoved(accounts: Accounts, outcome: SignInOutcome) {
        assertTrue(outcome is SignInOutcome.Failed)
        assertEquals(emptyList<String>(), accounts.servers.map { it.id })
        assertEquals("no password or header values left", emptySet<String>(), secrets.kept.keys)
        assertNull(settings.current.activeServer)
    }

    // Red when stopTicket leaves the sign-out's mark to signOut itself: the
    // edit lands first under its new id, which the sign-out no longer finds.
    @Test
    fun aNewIdEditLandingBetweenTheTicketAndTheSignOutStaysSignedOut() = runTest {
        val (accounts, outcome) = newIdEditMeetsAStop(removing = false, stopFirst = false)
        assertSignedOut(accounts, outcome)
    }

    // Red when stopTicket does not mark the removal, or keep does not read
    // the mark: the edit lands first under its new id and outlives it.
    @Test
    fun aNewIdEditLandingBetweenTheTicketAndTheRemovalIsNotKept() = runTest {
        val (accounts, outcome) = newIdEditMeetsAStop(removing = true, stopFirst = false)
        assertRemoved(accounts, outcome)
    }

    // Red when a keep the removal won leaves the header values it wrote
    // under its new id.
    @Test
    fun aNewIdEditLandingAfterTheRemovalLeavesNothingBehind() = runTest {
        val (accounts, outcome) = newIdEditMeetsAStop(removing = true, stopFirst = true)
        assertRemoved(accounts, outcome)
    }

    // A kept server signed out of has no saved password to fall back on,
    // even while the store still holds one: an edit or sign-in again with
    // the password left empty asks for it. Red when edit or signInAgain read
    // the saved secret of a server signed out of.
    @Test
    fun aServerSignedOutOfHasNoSavedPasswordToFallBackOn() = runTest {
        server.answer("ping", type = "octo")
        settings.update { it.copy(servers = listOf(SavedServer(server.address, "winters", id = "w1", signedOut = true))) }
        secrets.kept[secretAccount("winters", server.address)] = "pw"
        val accounts = accounts()
        val again = accounts.signInAgain("w1", SignInRequest(server.address, "winters", ""))
        assertEquals("Enter the password.", (again as SignInOutcome.Failed).message)
        val edit = accounts.edit("w1", SignInRequest(server.address, "winters", ""))
        assertEquals("Enter the password.", (edit as SignInOutcome.Failed).message)
        assertTrue(accounts.find("w1")!!.signedOut)
        assertNull(settings.current.activeServer)
    }
}
