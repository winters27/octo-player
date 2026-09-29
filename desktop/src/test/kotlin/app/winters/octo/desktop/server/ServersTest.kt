package app.winters.octo.desktop.server

import app.winters.octo.data.HeaderDraft
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.name
import app.winters.octo.livelists.accountKey
import app.winters.octo.server.PasswordChange
import app.winters.octo.subsonic.AuthMode
import java.io.File
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Several servers kept at once, against two pretend ones: adding, switching,
// editing, signing out, removing, changing a password and looking at them.
class ServersTest {
    @get:Rule val folder = TemporaryFolder()
    private val home = FakeServer()
    private val work = FakeServer()
    private val secrets = FakeSecrets()
    private val settingsFile get() = File(folder.root, "settings.json")
    private val settings by lazy { SettingsStore(settingsFile) }
    private val accounts by lazy { Accounts(settings, secrets, OkHttpClient()) }

    @After fun stop() {
        home.close()
        work.close()
    }

    // Each pretend server takes one password, as a real one would.
    private fun takes(server: FakeServer, password: String, type: String) {
        server.answerBy("ping") { request ->
            if (signedWith(request, password)) server.ok(type = type) else server.failed(40, "Wrong username or password")
        }
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"formPost","versions":[1]}]""", type = type)
    }

    private fun signedWith(request: RecordedRequest, password: String): Boolean {
        val salt = request.url.queryParameter("s") ?: return false
        return request.url.queryParameter("t") == md5(password + salt)
    }

    private fun md5(text: String) = java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private suspend fun twoServers(): Pair<String, String> {
        takes(home, "home pw", "octo")
        takes(work, "work pw", "navidrome")
        accounts.signIn(home.address, "winters", "home pw") as SignInOutcome.Done
        val added = accounts.add(SignInRequest(work.address, "brandon", "work pw")) as SignInOutcome.Saved
        return accounts.servers.first().id to added.server.id
    }

    @Test
    fun addingAServerKeepsTheOneInUse() = runTest {
        val (homeId, workId) = twoServers()
        assertEquals(homeId, settings.current.activeServer)
        assertEquals(homeId, settings.current.server!!.id)
        assertEquals(listOf(homeId, workId), accounts.servers.map { it.id })
        // Each password under its own account name, none in the file.
        assertEquals("home pw", secrets.kept[secretAccount("winters", home.address)])
        assertEquals("work pw", secrets.kept[secretAccount("brandon", work.address)])
        assertFalse(settingsFile.readText().contains(" pw"))
        assertEquals("ids are the folders older versions made", accountKey("brandon", work.address), workId)
    }

    @Test
    fun addingTheSameAccountAgainUpdatesItRatherThanKeepingItTwice() = runTest {
        val (_, workId) = twoServers()
        takes(work, "new pw", "navidrome")
        accounts.add(SignInRequest(work.address, "brandon", "new pw")) as SignInOutcome.Saved
        assertEquals(2, accounts.servers.size)
        assertEquals("new pw", secrets.kept[secretAccount("brandon", work.address)])
        assertEquals(workId, accounts.servers.last().id)
    }

    @Test
    fun aServerThatFailsTheSignInIsNotAdded() = runTest {
        takes(home, "home pw", "octo")
        takes(work, "work pw", "navidrome")
        accounts.signIn(home.address, "winters", "home pw")
        val failed = accounts.add(SignInRequest(work.address, "brandon", "nope")) as SignInOutcome.Failed
        assertEquals("Wrong username or password.", failed.message)
        assertEquals(1, accounts.servers.size)
    }

    @Test
    fun switchingSignsInWithTheSavedPassword() = runTest {
        val (homeId, workId) = twoServers()
        work.calls.clear()
        val done = accounts.switchTo(workId) as SwitchOutcome.Done
        assertEquals(workId, settings.current.activeServer)
        assertEquals("brandon", done.connection.client.username)
        assertEquals("navidrome", done.connection.server.serverType)
        assertTrue("the new one was asked first", work.endpoints().contains("ping"))
        // The next run opens on it.
        val later = Accounts(SettingsStore(settingsFile), secrets, OkHttpClient())
        assertEquals(workId, later.restore()!!.server.id)
        // And back.
        accounts.switchTo(homeId) as SwitchOutcome.Done
        assertEquals(homeId, settings.current.server!!.id)
    }

    @Test
    fun aServerOutOfReachLeavesTheOneInUse() = runTest {
        val (homeId, workId) = twoServers()
        work.close()
        val failed = accounts.switchTo(workId) as SwitchOutcome.Failed
        assertTrue(failed.message, failed.message.startsWith("Couldn't switch to ${accounts.find(workId)!!.name}. Couldn't reach the server."))
        assertEquals(homeId, settings.current.activeServer)
    }

    @Test
    fun aPasswordChangedElsewhereIsAskedFor() = runTest {
        val (_, workId) = twoServers()
        takes(work, "changed on the web", "navidrome")
        val asked = accounts.switchTo(workId) as SwitchOutcome.NeedsPassword
        assertTrue(asked.note!!.contains("didn't take the saved password"))
    }

    @Test
    fun signingOutOfOneKeepsItListedWithoutItsPassword() = runTest {
        val (homeId, workId) = twoServers()
        accounts.signOut(workId)
        assertEquals("the one in use stays in use", homeId, settings.current.activeServer)
        assertNull(secrets.kept[secretAccount("brandon", work.address)])
        assertTrue(accounts.find(workId)!!.signedOut)
        // Switching to it asks for the password, and nothing is sent to it.
        work.calls.clear()
        assertTrue(accounts.switchTo(workId) is SwitchOutcome.NeedsPassword)
        assertEquals(Reach.Unknown, accounts.check(workId).reach)
        assertTrue(work.calls.isEmpty())
        // Signing in again clears the mark.
        accounts.signIn(work.address, "brandon", "work pw") as SignInOutcome.Done
        assertFalse(accounts.find(workId)!!.signedOut)
        assertEquals(workId, settings.current.activeServer)
    }

    @Test
    fun signingOutOfTheOneInUseLeavesNoneInUse() = runTest {
        val (homeId, workId) = twoServers()
        accounts.signOut()
        assertNull(settings.current.activeServer)
        assertNull(settings.current.server)
        assertNull(accounts.restore())
        assertEquals(homeId, accounts.last!!.id)
        assertEquals("the other keeps its password", "work pw", secrets.kept[secretAccount("brandon", work.address)])
        assertTrue(accounts.switchTo(workId) is SwitchOutcome.Done)
    }

    @Test
    fun removingAServerTakesItsPasswordAndHeadersButNotAnotherOnesAtTheSameAddress() = runTest {
        takes(home, "home pw", "octo")
        accounts.signIn(SignInRequest(home.address, "winters", "home pw", headers = listOf(HeaderDraft("X-Token", "t0ken"))))
        home.answerBy("ping") { home.ok(type = "octo") }
        val guest = accounts.add(SignInRequest(home.address, "guest", "guest pw", headers = listOf(HeaderDraft("X-Token", "t0ken")))) as SignInOutcome.Saved
        accounts.remove(guest.server.id)
        assertNull(secrets.kept[secretAccount("guest", home.address)])
        assertNotNull("winters still needs the headers", secrets.kept[headersAccount(home.address)])
        val homeId = accounts.servers.single().id
        accounts.remove(homeId)
        assertTrue(secrets.kept.isEmpty())
        assertTrue(accounts.servers.isEmpty())
        assertNull(settings.current.activeServer)
        assertNull(settings.current.server)
    }

    @Test
    fun editingTheAddressMovesThePasswordAndKeepsTheId() = runTest {
        val (_, workId) = twoServers()
        // The same server at another address: the pretend home one, taking work's password.
        takes(home, "work pw", "navidrome")
        val saved = accounts.edit(workId, SignInRequest(home.address, "brandon", ""), label = "Work") as SignInOutcome.Saved
        assertEquals(workId, saved.server.id)
        assertEquals("Work", saved.server.name)
        assertEquals(home.address, saved.server.address)
        assertEquals("the saved password was used", "work pw", secrets.kept[secretAccount("brandon", home.address)])
        assertNull(secrets.kept[secretAccount("brandon", work.address)])
    }

    @Test
    fun editingInAnotherUserGivesThemTheirOwnPlaysAndQueue() = runTest {
        val (_, workId) = twoServers()
        work.answerBy("ping") { work.ok() }
        val saved = accounts.edit(workId, SignInRequest(work.address, "guest", "guest pw")) as SignInOutcome.Saved
        assertEquals(accountKey("guest", work.address), saved.server.id)
        assertNull(accounts.find(workId))
        assertNull(secrets.kept[secretAccount("brandon", work.address)])
    }

    @Test
    fun anEditThatFailsItsSignInChangesNothing() = runTest {
        val (_, workId) = twoServers()
        val before = accounts.find(workId)
        val failed = accounts.edit(workId, SignInRequest(work.address, "brandon", "wrong")) as SignInOutcome.Failed
        assertEquals("Wrong username or password.", failed.message)
        assertEquals(before, accounts.find(workId))
        assertEquals("work pw", secrets.kept[secretAccount("brandon", work.address)])
    }

    @Test
    fun anEditCannotMakeTwoOfTheSameAccount() = runTest {
        val (homeId, workId) = twoServers()
        takes(work, "home pw", "navidrome")
        val failed = accounts.edit(workId, SignInRequest(home.address, "winters", "home pw")) as SignInOutcome.Failed
        assertEquals("${accounts.find(homeId)!!.name} is already in your list with that address and username.", failed.message)
    }

    @Test
    fun editingTheOneInUseSignsInToItAgain() = runTest {
        val (homeId, _) = twoServers()
        val done = accounts.edit(homeId, SignInRequest(home.address, "winters", ""), label = "Home") as SignInOutcome.Done
        assertEquals("Home", done.connection.server.name)
        assertEquals(homeId, settings.current.activeServer)
    }

    @Test
    fun aNameIsJustAName() = runTest {
        val (homeId, _) = twoServers()
        home.calls.clear()
        accounts.rename(homeId, "  Living room ")
        assertEquals("Living room", accounts.find(homeId)!!.name)
        assertEquals("Living room", settings.current.server!!.label)
        assertTrue(home.calls.isEmpty())
        accounts.rename(homeId, "")
        assertEquals("an empty name shows the host", home.address.substringAfter("://").substringBefore(":"), accounts.find(homeId)!!.name)
    }

    @Test
    fun changingThePasswordSendsItInTheBodyAndKeepsTheNewOne() = runTest {
        twoServers()
        val connection = accounts.restore()!!
        home.answerBy("changePassword") { home.ok() }
        home.calls.clear()
        val outcome = accounts.changePassword(connection, "home pw", "new home pw")
        assertEquals(PasswordChange.Changed, outcome.result)
        val change = home.calls.single { it.url.pathSegments.last() == "changePassword" }
        assertEquals("POST", change.method)
        val form = change.body!!.utf8().split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals(mapOf("username" to "winters", "password" to "new home pw"), form)
        home.calls.forEach { assertFalse(it.url.toString().contains("new")) }
        assertEquals("new home pw", secrets.kept[secretAccount("winters", home.address)])
        // The connection in use signs in with it from now on.
        takes(home, "new home pw", "octo")
        connection.client.ping()
    }

    @Test
    fun aWrongCurrentPasswordLeavesTheStoreAlone() = runTest {
        twoServers()
        val connection = accounts.restore()!!
        val outcome = accounts.changePassword(connection, "guess", "new")
        assertEquals(PasswordChange.WrongCurrent, outcome.result)
        assertEquals("home pw", secrets.kept[secretAccount("winters", home.address)])
        assertTrue(home.endpoints().none { it == "changePassword" })
    }

    @Test
    fun aServerThatWillNotChangeItSaysSo() = runTest {
        twoServers()
        val connection = accounts.restore()!!
        home.fail("changePassword", 50, "User not authorized")
        assertEquals(PasswordChange.NotAllowed, accounts.changePassword(connection, "home pw", "new").result)
        assertEquals("home pw", secrets.kept[secretAccount("winters", home.address)])
    }

    @Test
    fun aNewPasswordTheStoreRefusesIsKeptForThisRunAndSaid() = runTest {
        twoServers()
        val connection = accounts.restore()!!
        home.answerBy("changePassword") { home.ok() }
        secrets.refuse = true
        val outcome = accounts.changePassword(connection, "home pw", "new home pw")
        assertEquals(PasswordChange.Changed, outcome.result)
        assertTrue(outcome.note!!.contains("will ask for it next time"))
    }

    @Test
    fun lookingAtServersSaysHowTheyAnswer() = runTest {
        val (homeId, workId) = twoServers()
        val inUse = accounts.restore()!!
        assertEquals(Reach.Answers, accounts.check(homeId, inUse).reach)
        assertNotNull(accounts.check(workId, inUse).ms)
        takes(work, "changed", "navidrome")
        assertEquals(Reach.WrongPassword, accounts.check(workId, inUse).reach)
        work.close()
        assertEquals(Reach.Unreachable, accounts.check(workId, inUse).reach)
    }

    @Test
    fun aServerUpdatedSinceShowsItsNewVersion() = runTest {
        val (_, workId) = twoServers()
        work.answerBy("ping") { """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.59.1","openSubsonic":true}}""" }
        accounts.check(workId)
        assertEquals("0.59.1", accounts.find(workId)!!.serverVersion)
    }

    @Test
    fun anApiKeySignInHasNoPasswordToChange() = runTest {
        takes(home, "home pw", "octo")
        home.answer("tokenInfo", """"tokenInfo":{"username":"brandon"}""")
        home.answerBy("ping") { home.ok(type = "octo") }
        val done = accounts.signIn(SignInRequest(home.address, "", "k3y", mode = AuthMode.ApiKey)) as SignInOutcome.Done
        assertEquals(PasswordChange.UsesKey, accounts.changePassword(done.connection, "x", "y").result)
    }
}
