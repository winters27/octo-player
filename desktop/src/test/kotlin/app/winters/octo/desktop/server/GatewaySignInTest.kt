package app.winters.octo.desktop.server

import app.winters.octo.connection.GatewayDraft
import app.winters.octo.connection.GatewayWords
import app.winters.octo.data.HeaderDraft
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SignInPages
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.server.Reach
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.gateway.basicCredentials
import app.winters.octo.desktop.secrets.SecretStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Sign-in pages in front of a server, as Accounts meets them: named in
// plain words and on server rows, never a sign-out; every check on a client
// of its own, so the server in use's cookies and headers never pass for
// other details; header values kept per account; the sign-in page cookies
// tied to one configuration; and sign-in page headers never sent over plain
// http on the internet. Nothing here touches the real password store or
// settings folder.
class GatewaySignInTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = FakeServer()
    private val other = FakeServer()
    private val secrets = FakeSecrets()
    private val settingsFile get() = File(folder.root, "settings.json")
    private val settings by lazy { SettingsStore(settingsFile) }

    @After
    fun stop() {
        server.close()
        other.close()
    }

    // Accounts on a client set up the way the app's is.
    private fun wired(): Accounts {
        val security = ServerSecurity(settings)
        return Accounts(settings, secrets, security.install(OkHttpClient.Builder()).build(), security)
    }

    private fun fields(user: String, password: String) = GatewayDraft(GatewayKind.Password, user, password)

    // Authentik in front of `at`: a request with its session cookie, or with
    // one of these usernames and app passwords, gets through, and the
    // latter is given the cookie; anything else gets its page.
    private fun authentik(at: FakeServer, vararg logins: Pair<String, String>) {
        val good = logins.map { (user, password) -> basicCredentials(user, password) }.toSet()
        fun lets(request: RecordedRequest) = request.headers["Cookie"].orEmpty().contains("authentik_proxy_s=ok") || request.headers["Authorization"] in good
        at.respond("ping") { request ->
            when {
                request.headers["Cookie"].orEmpty().contains("authentik_proxy_s=ok") -> at.okResponse(type = "octo")
                request.headers["Authorization"] in good -> at.okResponse(type = "octo", setCookie = "authentik_proxy_s=ok; Path=/")
                else -> SignInPages.authentik()
            }
        }
        at.respond("getOpenSubsonicExtensions") { request ->
            if (lets(request)) at.okResponse(""""openSubsonicExtensions":[]""", type = "octo") else SignInPages.authentik()
        }
    }

    // T2, T18: a sign-in page is named with its words, and the form hears
    // which; whether the sign-in page fields went along changes the words.
    // Red when userMessage drops the gateway branch, when Failed loses the
    // gateway, or when the check client goes without the request's headers.
    @Test
    fun aSignInPageIsNamedAndTheFormHearsWhich() = runTest {
        server.respond("ping") { SignInPages.prompt() }
        val accounts = wired()
        val bare = accounts.test(SignInRequest(server.address, "winters", "pw")) as TestOutcome.Failed
        assertEquals(GatewayWords.failure(Gateway.PasswordPrompt, false), bare.message)
        assertEquals(Gateway.PasswordPrompt, bare.gateway)
        val sent = accounts.test(SignInRequest(server.address, "winters", "pw", gateway = fields("alice", "pw"))) as TestOutcome.Failed
        assertEquals(GatewayWords.failure(Gateway.PasswordPrompt, true), sent.message)
        assertEquals(basicCredentials("alice", "pw"), server.calls.last().headers["Authorization"])
    }

    // T18: every sign-in page, with and without the fields sent, in the
    // shared words. Red when the gateway branch of userMessage goes.
    @Test
    fun everySignInPageHasItsWords() {
        for (gateway in Gateway.entries) {
            for (loginSent in listOf(false, true)) {
                val e = SubsonicException.NotSubsonic("HTTP 401 from ping", 401, gateway, loginSent)
                assertEquals(GatewayWords.failure(gateway, loginSent), e.userMessage())
            }
        }
    }

    // T10: the shared client holds Authentik's cookie for the old app
    // password; an edit with a wrong new one still fails, since its check
    // has a client and an empty jar of its own. Red when the check goes
    // through the shared client.
    @Test
    fun aWrongNewAppPasswordFailsTheEditThoughTheOldCookieWouldPass() = runTest {
        authentik(server, "alice" to "right")
        val accounts = wired()
        val done = accounts.signIn(SignInRequest(server.address, "winters", "pw", gateway = fields("alice", "right"))) as SignInOutcome.Done
        done.connection.client.ping()
        done.connection.client.ping()
        assertEquals("the shared client holds the cookie", "authentik_proxy_s=ok", server.calls.last().headers["Cookie"])
        val edit = SignInRequest(server.address, "winters", "", gateway = GatewayDraft(GatewayKind.Password, "alice", "wrong", saved = GatewayKind.Password))
        val failed = accounts.edit(done.connection.server.id, edit) as SignInOutcome.Failed
        assertEquals(GatewayWords.failure(Gateway.Authentik, true), failed.message)
        assertEquals(Gateway.Authentik, failed.gateway)
        assertTrue("the saved values stay", secrets.kept[headersAccount(done.connection.server)]!!.contains(basicCredentials("alice", "right")))
    }

    // T10, T5, T2: a second account at the same address never rides the
    // first one's session, in a test, a look at its row or a switch; and a
    // sign-in page refusing it signs nobody out and forgets no password.
    // Red when any of them goes through the shared client.
    @Test
    fun anotherAccountAtTheSameAddressDoesNotRideTheFirstOnesSession() = runTest {
        authentik(server, "alice" to "right", "bob" to "bobs")
        val accounts = wired()
        val alice = accounts.signIn(SignInRequest(server.address, "winters", "pw", gateway = fields("alice", "right"))) as SignInOutcome.Done
        alice.connection.client.ping()
        val bare = accounts.test(SignInRequest(server.address, "guest", "pw")) as TestOutcome.Failed
        assertEquals(GatewayWords.failure(Gateway.Authentik, false), bare.message)
        val bob = accounts.add(SignInRequest(server.address, "guest", "pw2", gateway = fields("bob", "bobs"))) as SignInOutcome.Saved
        // Authentik stops taking bob's app password.
        authentik(server, "alice" to "right")
        val row = accounts.check(bob.server.id, alice.connection).reach as Reach.SignInPage
        assertEquals(Gateway.Authentik, row.gateway)
        assertTrue(row.loginSent)
        val switch = accounts.switchTo(bob.server.id) as SwitchOutcome.Failed
        assertTrue(switch.message, switch.message.endsWith(GatewayWords.failure(Gateway.Authentik, true)))
        assertEquals(alice.connection.server.id, settings.current.activeServer)
        assertFalse(accounts.find(bob.server.id)!!.signedOut)
        assertEquals("pw2", secrets.kept[secretAccount("guest", bob.server.address)])
    }

    // T10: while another server is checked, the server in use keeps its own
    // headers. Red when a check configures the shared security.
    @Test
    fun duringACheckTheServerInUseKeepsItsHeaders() = runTest {
        server.answer("ping", type = "octo")
        val accounts = wired()
        val done = accounts.signIn(SignInRequest(server.address, "winters", "pw", headers = listOf(HeaderDraft("X-Token", "mine")))) as SignInOutcome.Done
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        other.answerBy("ping") {
            checking.countDown()
            release.await(5, TimeUnit.SECONDS)
            other.ok()
        }
        val test = async(Dispatchers.IO) { accounts.test(SignInRequest(other.address, "guest", "pw", headers = listOf(HeaderDraft("X-Token", "theirs")))) }
        assertTrue(withContext(Dispatchers.IO) { checking.await(5, TimeUnit.SECONDS) })
        server.calls.clear()
        withContext(Dispatchers.IO) { done.connection.client.ping() }
        assertEquals("mine", server.calls.single().headers["X-Token"])
        release.countDown()
        assertTrue(test.await() is TestOutcome.Reached)
        assertEquals("theirs", other.calls.first().headers["X-Token"])
    }

    // T15: two accounts at one address each keep their own header values,
    // in this run and the next. Red when values are filed by address.
    @Test
    fun headerValuesAreKeptPerAccount() = runTest {
        server.answer("ping", type = "octo")
        val accounts = wired()
        val winters = accounts.signIn(SignInRequest(server.address, "winters", "pw", headers = listOf(HeaderDraft("X-Token", "w")))) as SignInOutcome.Done
        val guest = accounts.add(SignInRequest(server.address, "guest", "pw", headers = listOf(HeaderDraft("X-Token", "g")))) as SignInOutcome.Saved
        assertTrue(secrets.kept[headersAccount(winters.connection.server)]!!.contains("\"w\""))
        assertTrue(secrets.kept[headersAccount(guest.server)]!!.contains("\"g\""))
        assertNull("nothing filed by address", secrets.kept[headersAccount(server.address)])
        val onGuest = accounts.switchTo(guest.server.id) as SwitchOutcome.Done
        assertEquals(mapOf("X-Token" to "g"), onGuest.connection.headers)
        val later = Accounts(SettingsStore(settingsFile), secrets, OkHttpClient())
        assertEquals(mapOf("X-Token" to "g"), later.restore()!!.headers)
        val back = later.switchTo(winters.connection.server.id) as SwitchOutcome.Done
        assertEquals(mapOf("X-Token" to "w"), back.connection.headers)
    }

    // T15: values an older version filed by address move under each
    // account as it is read, and the address entry goes once nobody reads
    // it. Red when it is deleted at the first move, or never.
    @Test
    fun headerValuesFiledByAddressMoveUnderEachAccount() = runTest {
        server.answer("ping", type = "octo")
        val address = server.address
        settings.update {
            it.copy(
                servers = listOf(
                    SavedServer(address, "winters", headerNames = listOf("X-Token"), id = "w1"),
                    SavedServer(address, "guest", headerNames = listOf("X-Token"), id = "g1"),
                ),
                activeServer = "w1",
            )
        }
        secrets.kept[secretAccount("winters", address)] = "pw"
        secrets.kept[secretAccount("guest", address)] = "pw2"
        secrets.kept[headersAccount(address)] = """[{"name":"X-Token","value":"old"}]"""
        val accounts = Accounts(settings, secrets, OkHttpClient())
        assertEquals(mapOf("X-Token" to "old"), accounts.restore()!!.headers)
        assertNotNull("winters' own copy", secrets.kept[headersAccount(accounts.find("w1")!!)])
        assertNotNull("guest still reads it there", secrets.kept[headersAccount(address)])
        assertEquals(Reach.Answers, accounts.check("g1").reach)
        assertNotNull("guest's own copy", secrets.kept[headersAccount(accounts.find("g1")!!)])
        assertNull("read by nobody now", secrets.kept[headersAccount(address)])
    }

    // T6/T8 at run time: sign-in page headers never go over plain http to a
    // host on the internet, whether typed or kept from an older version; at
    // home they may. Red when the kept server's run-time rule in `check` is
    // removed (the request would be tried); the sign-in half rides on
    // resolveConnectionHeaders.
    @Test
    fun signInPageHeadersNeverCrossTheInternetInPlainHttp() = runTest {
        val accounts = Accounts(settings, secrets, OkHttpClient())
        val failed = accounts.test(SignInRequest("http://music.invalid", "winters", "pw", headers = listOf(HeaderDraft("Authorization", "Bearer t0ken")))) as TestOutcome.Failed
        assertEquals(GatewayWords.NEEDS_HTTPS, failed.message)
        settings.update { it.copy(servers = listOf(SavedServer("http://music.invalid/", "winters", headerNames = listOf("Authorization"), id = "w1"))) }
        secrets.kept[secretAccount("winters", "http://music.invalid/")] = "pw"
        secrets.kept[headersAccount(accounts.find("w1")!!)] = """[{"name":"Authorization","value":"Basic YTpi"}]"""
        assertEquals(Reach.NeedsHttps, accounts.check("w1").reach)
        server.answer("ping")
        assertTrue(accounts.test(SignInRequest(server.address, "winters", "pw", headers = listOf(HeaderDraft("Authorization", "Bearer t0ken")))) is TestOutcome.Reached)
    }

    // T9 on the desktop: the sign-in page's cookie goes back while the
    // configuration is the same, and a header or trusted certificate change
    // starts with none; signed out, nothing goes. Red when the jar outlives
    // a header change, when pins are left out of the comparison, or when
    // the same configuration loses it.
    @Test
    fun theJarBelongsToTheConfiguration() = runTest {
        val security = ServerSecurity(settings)
        val http = security.install(OkHttpClient.Builder()).build()
        val main = server.address.toHttpUrl()
        val client = SubsonicClient(main, Credentials("winters", "pw"), http)
        server.respond("ping") { server.okResponse(setCookie = "authentik_proxy_s=ok; Path=/") }
        suspend fun cookieSent(): String? {
            client.ping()
            return server.calls.last().headers["Cookie"]
        }
        security.configure(main, null, mapOf("X-Token" to "a"))
        assertNull(cookieSent())
        assertEquals("authentik_proxy_s=ok", cookieSent())
        security.configure(main, null, mapOf("X-Token" to "a"))
        assertEquals("the same configuration keeps it", "authentik_proxy_s=ok", cookieSent())
        security.configure(main, null, mapOf("X-Token" to "b"))
        assertNull("another header value starts with none", cookieSent())
        assertEquals("authentik_proxy_s=ok", cookieSent())
        security.trust("elsewhere.test", "ab".repeat(32))
        security.configure(main, null, mapOf("X-Token" to "b"))
        assertNull("a certificate trusted since starts with none", cookieSent())
        security.clear()
        client.ping()
        assertNull(server.calls.last().headers["Cookie"])
        assertNull(server.calls.last().headers["X-Token"])
    }

    // A sign-out while a held switch runs is in the settings at once,
    // on disk too (a quit at any moment keeps it), and stays there once the
    // switch is done: the server signed out of is signed out and none is in
    // use. Only the password store waits its turn. Red when signOut writes
    // the settings under the writer lock, or when a switch overtaken by the
    // sign-out still makes its server the one in use.
    @Test
    fun aSignOutWhileAHeldSwitchRunsIsWrittenAtOnceAndStays() = runTest {
        server.answer("ping", type = "octo")
        other.answer("ping", type = "octo")
        val held = HeldDeletes(secrets)
        val security = ServerSecurity(settings)
        val accounts = Accounts(settings, held, security.install(OkHttpClient.Builder()).build(), security)
        val first = accounts.signIn(SignInRequest(server.address, "winters", "pw")) as SignInOutcome.Done
        val second = accounts.add(SignInRequest(other.address, "guest", "pw2")) as SignInOutcome.Saved
        val asked = CountDownLatch(1)
        val release = CountDownLatch(1)
        other.answerBy("ping") {
            asked.countDown()
            release.await(5, TimeUnit.SECONDS)
            other.ok(type = "octo")
        }
        val switch = async(Dispatchers.IO) { accounts.switchTo(second.server.id, accounts.ticket()) }
        assertTrue(withContext(Dispatchers.IO) { asked.await(5, TimeUnit.SECONDS) })
        held.hold = true
        val id = first.connection.server.id
        val stop = accounts.stopTicket(id)
        val out = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { accounts.signOut(id, stop) }
        fun signedOut(store: SettingsStore) = store.current.activeServer == null && store.current.servers.single { it.id == id }.signedOut
        assertTrue("written at once", signedOut(settings))
        assertTrue("on disk at once", signedOut(SettingsStore(settingsFile)))
        release.countDown()
        assertEquals(SwitchOutcome.Overtaken, switch.await())
        assertTrue("still signed out once the switch is done", signedOut(settings))
        assertTrue(signedOut(SettingsStore(settingsFile)))
        assertNull(accounts.active)
        held.letGo.countDown()
        out.join()
        assertNull(secrets.kept[secretAccount("winters", first.connection.server.address)])
        assertEquals("the other keeps its password", "pw2", secrets.kept[secretAccount("guest", second.server.address)])
    }

    // The share line follows any header a sign-in page reads, whatever
    // its case, as on the phones. Red when behindSignInPage goes by the
    // saved kind (a Bearer token or a half Cloudflare pair would say
    // nothing).
    @Test
    fun aServerBehindASignInPageIsKnownByAnyOfItsHeaders() {
        fun behind(vararg headers: Pair<String, String>) =
            Connection(server.client(), SavedServer(server.address, "winters"), mapOf(*headers)).behindSignInPage
        assertTrue(behind("Authorization" to "Bearer t0k"))
        assertTrue(behind("cf-access-client-id" to "id.access"))
        assertTrue(behind("Authorization" to basicCredentials("alice", "pw")))
        assertFalse(behind("X-Token" to "t"))
        assertFalse(behind())
    }
}

// A password store that holds each delete while `hold` is on, until let
// go, so what a sign-out does before its turn at the store is seen alone.
private class HeldDeletes(private val inner: FakeSecrets) : SecretStore by inner {
    @Volatile var hold = false
    val letGo = CountDownLatch(1)

    override fun delete(account: String) {
        if (hold) letGo.await(5, TimeUnit.SECONDS)
        inner.delete(account)
    }
}
