package app.winters.octo.desktop.server

import app.winters.octo.connection.SignInWords
import app.winters.octo.connection.fingerprint
import app.winters.octo.desktop.pages.SignInForm
import app.winters.octo.desktop.pages.receiveFromForm
import app.winters.octo.desktop.pages.signUpFromForm
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.server.CertFixtures.Clients
import app.winters.octo.desktop.server.CertFixtures.PASSWORD
import app.winters.octo.desktop.server.ClientAuthServer.Auth
import app.winters.octo.desktop.settings.CertFile
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.name
import app.winters.octo.server.Reach
import app.winters.octo.server.switchFailedWords
import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.ui.family.HANDOVER_UNREACHABLE
import app.winters.octo.ui.family.HandOverBox
import app.winters.octo.ui.family.HandOverSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ClientAuthTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<AutoCloseable>()
    private val versions = listOf(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)

    @After
    fun stop() = opened.forEach { runCatching { it.close() } }

    private fun serve(
        auth: Auth = Auth.Required,
        clients: Clients = Clients.Root,
        at: InetAddress = InetAddress.getByName("127.0.0.1"),
        schemes: Array<String>? = null,
        http2: Boolean = false,
    ) = ClientAuthServer(auth, at, CertFixtures.serverContext(clients), schemes, http2).also { opened += it }

    // A fresh settings file, password store and Accounts.
    private inner class Setup(
        tls: TlsVersion? = null,
        dns: Dns = LOOPBACK,
        events: (() -> EventListener)? = null,
        tune: (OkHttpClient.Builder) -> Unit = {},
    ) {
        val settingsFile = File(folder.newFolder(), "settings.json")
        val settings = SettingsStore(settingsFile)
        val secrets = CertStore()
        private val parts = tlsParts(settings, secrets, tls, dns, events, tune = tune)
        val accounts = parts.first

        // The app's shared client, which the connections in use go through.
        val http: OkHttpClient = parts.second

        // A kept server written straight into the settings, with its password.
        fun keep(address: String, certificate: CertFile? = null, id: String = "k"): SavedServer {
            val saved = SavedServer(address, "winters", id = id, certificate = certificate)
            settings.update { it.copy(servers = it.servers + saved) }
            secrets.kept[secretAccount("winters", address)] = "pw"
            return saved
        }
    }

    private fun cert(name: String): File = CertFixtures.copy(name, folder.root)

    private fun use(name: String, password: String = PASSWORD) = CertificateDraft.Use(cert(name).path, password)

    private val rsa get() = CertFixtures.leaf("rsa2048.p12")
    private val second get() = CertFixtures.leaf("second-rsa.p12")

    private fun ok(payload: String = "") = ClientAuthServer.ok(payload)

    // Sign-in: the check offers it, and so does the shared client after.
    @Test
    fun aSignInOffersTheCertificateOnBothVersions() = runTest {
        for (tls in versions) {
            val server = serve()
            val setup = Setup(tls)
            val done = setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("rsa2048.p12"))) as SignInOutcome.Done
            done.connection.client.ping()
            assertTrue(tls.toString(), server.leaves.isNotEmpty() && server.leaves.all { it == rsa })
        }
    }

    @Test
    fun aTestOfAKeptServerSendsItsSavedCertificate() = runTest {
        val server = serve()
        val setup = Setup()
        val id = (setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("rsa2048.p12"))) as SignInOutcome.Done).connection.server.id
        server.leaves.clear()
        val tested = setup.accounts.test(SignInRequest(server.address, "winters", "", certificate = CertificateDraft.Keep(id)), keptId = id)
        assertTrue(tested is TestOutcome.Reached)
        assertEquals(rsa, server.lastLeaf)
    }

    @Test
    fun aSwitchAndAGlanceSendTheSavedCertificate() = runTest {
        val server = serve()
        val other = serve(Auth.None)
        val setup = Setup()
        val k = (setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("rsa2048.p12"))) as SignInOutcome.Done).connection.server
        setup.accounts.signIn(SignInRequest(other.address, "brandon", "pw")) as SignInOutcome.Done
        server.leaves.clear()
        assertEquals(Reach.Answers, setup.accounts.check(k.id).reach)
        assertEquals(rsa, server.lastLeaf)
        server.leaves.clear()
        assertTrue(setup.accounts.switchTo(k.id) is SwitchOutcome.Done)
        assertEquals(rsa, server.lastLeaf)
    }

    // The home address is watched through a client of its own, which offers
    // the certificate too.
    @Test
    fun theHomeAddressCheckSendsTheCertificate() = runTest {
        val main = serve()
        val home = serve()
        val setup = Setup()
        setup.accounts.signIn(SignInRequest(main.address, "winters", "pw", home = home.url("localhost").toString(), certificate = use("rsa2048.p12"))) as SignInOutcome.Done
        home.leaves.clear()
        val restarted = tlsAccounts(SettingsStore(setup.settingsFile), setup.secrets)
        assertNotNull(restarted.restore())
        waitFor { home.leaves.isNotEmpty() }
        assertEquals(rsa, home.lastLeaf)
    }

    // A, then B, then none at the same host and port: each next request goes
    // with the one in use, never one resumed from before.
    @Test
    fun theSameHostWithAThenBThenNone() = runTest {
        for (tls in versions) {
            val server = serve(Auth.Wanted)
            val setup = Setup(tls)
            val first = setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("rsa2048.p12"))) as SignInOutcome.Done
            first.connection.client.ping()
            assertEquals(rsa, server.lastLeaf)
            val id = first.connection.server.id
            val b = setup.accounts.edit(id, SignInRequest(server.address, "winters", "", certificate = use("second-rsa.p12"))) as SignInOutcome.Done
            b.connection.client.ping()
            assertEquals(tls.toString(), second, server.lastLeaf)
            val none = setup.accounts.edit(id, SignInRequest(server.address, "winters", "", certificate = CertificateDraft.Remove(id))) as SignInOutcome.Done
            none.connection.client.ping()
            assertNull(tls.toString(), server.lastLeaf)
        }
    }

    @Test
    fun aSignOutThenAnotherCertificateOrNoneAtTheSameHost() = runTest {
        for (tls in versions) {
            val server = serve(Auth.Wanted)
            val setup = Setup(tls)
            setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("rsa2048.p12"))) as SignInOutcome.Done
            setup.accounts.signOut()
            val b = setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("second-rsa.p12"))) as SignInOutcome.Done
            b.connection.client.ping()
            assertEquals(tls.toString(), second, server.lastLeaf)
            setup.accounts.signOut()
            val none = setup.accounts.signIn(SignInRequest(server.address, "brandon", "pw")) as SignInOutcome.Done
            none.connection.client.ping()
            assertNull(tls.toString(), server.lastLeaf)
        }
    }

    // Red when a connection that was never asked for a certificate counts as
    // stale.
    @Test
    fun aServerThatDoesNotAskKeepsWorkingWithACertificateSet() = runTest {
        val server = serve(Auth.None)
        val setup = Setup()
        val done = setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("rsa2048.p12"))) as SignInOutcome.Done
        repeat(3) { done.connection.client.ping() }
        assertTrue(server.leaves.all { it == null })
        assertEquals(0, setup.accounts.security.staleRetries.get())
    }

    // A slow HTTP/2 call made with A finishes on its connection; the call
    // made after the change to B goes on a new connection with B, after one
    // retry. Red when the change leaves the in-use connection to carry B's
    // calls with A's handshake.
    @Test
    fun aSlowHttp2CallAcrossAChangeRetriesTheNextCallOnce() = runTest {
        val server = serve(Auth.Wanted, http2 = true)
        val setup = Setup()
        val first = setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("rsa2048.p12"))) as SignInOutcome.Done
        val http = setup.http
        server.answer("slow") { MockResponse.Builder().body("slow").headersDelay(1_500, TimeUnit.MILLISECONDS).build() }
        val slow = async(Dispatchers.IO) { http.newCall(Request(server.url().resolve("rest/slow")!!)).execute().use { it.body.string() } }
        waitFor { server.calls.any { it.url.encodedPath.endsWith("slow") } }
        setup.accounts.edit(first.connection.server.id, SignInRequest(server.address, "winters", "", certificate = use("second-rsa.p12"))) as SignInOutcome.Done
        val before = setup.accounts.security.staleRetries.get()
        server.leaves.clear()
        val answered = withContext(Dispatchers.IO) { runCatching { http.newCall(Request(server.url().resolve("rest/ping")!!)).execute().close() } }
        assertTrue("the next call is answered: ${answered.exceptionOrNull()}", answered.isSuccess)
        assertEquals(listOf<String?>(second), server.leaves.toList())
        assertEquals(1, setup.accounts.security.staleRetries.get() - before)
        assertEquals("slow", slow.await())
    }

    // Two ports of one host: only the server's own port gets its certificate,
    // and the other answers. Red when the key manager offers it to the other
    // port: the identity check then turns the call down, so it is never
    // answered.
    @Test
    fun anotherPortOfTheSameHostGetsNone() = runTest {
        val server = serve()
        val otherPort = serve(Auth.Wanted)
        val setup = Setup()
        setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("rsa2048.p12"))) as SignInOutcome.Done
        val answered = withContext(Dispatchers.IO) { runCatching { setup.http.newCall(Request(otherPort.url().resolve("rest/ping")!!)).execute().use { it.code } } }
        assertEquals("the other port answers: ${answered.exceptionOrNull()}", 200, answered.getOrNull())
        assertEquals(listOf<String?>(null), otherPort.leaves.toList())
    }

    // The key manager names its certificate only for a socket to one of its
    // own origins; asked over a socket to another port of the host, or one
    // it knows nothing of, it names none. Red when the origin gate is left
    // out.
    @Test
    fun theKeyManagerOffersItsCertificateOnlyToItsOwnOrigins() {
        val opened = openCertificate(cert("rsa2048.p12").readBytes(), PASSWORD, "rsa2048.p12", CertFixtures.NOW) { _, _ -> null }
        val identity = KeyIdentity((opened as CertificateOpened.Found).certificate)
        val own = TlsOrigin("music.test", 443)
        val ownSocket = java.net.Socket()
        val otherSocket = java.net.Socket()
        val marks = mapOf(ownSocket to SocketMark(own), otherSocket to SocketMark(TlsOrigin("music.test", 8443)))
        val keys = HostKeyManager(identity, { setOf(own) }, { marks[it] }) { _, _, _ -> }
        assertEquals("its own origin", identity.alias, keys.chooseClientAlias(arrayOf("RSA"), null, ownSocket))
        assertNull("another port of the host", keys.chooseClientAlias(arrayOf("RSA"), null, otherSocket))
        assertNull("a socket it knows nothing of", keys.chooseClientAlias(arrayOf("RSA"), null, java.net.Socket()))
    }

    @Test
    fun anIpv6AddressGetsItsCertificate() = runTest {
        val loopback = InetAddress.getByName("::1")
        assumeTrue(runCatching { java.net.ServerSocket(0, 1, loopback).close() }.isSuccess)
        val server = serve(at = loopback)
        val setup = Setup()
        val done = setup.accounts.signIn(SignInRequest(server.url("::1").toString(), "winters", "pw", certificate = use("rsa2048.p12"))) as SignInOutcome.Done
        assertEquals(TlsOrigin("::1", server.port), certificateOrigins(server.url("::1"), null).single())
        done.connection.client.ping()
        assertEquals(rsa, server.lastLeaf)
    }

    // The handshake is made with A, then the certificate changes to B before
    // the request is written: it reaches the server as B.
    @Test
    fun aHandshakeWithAThenBBeforeTheFirstReadReachesTheServerAsB() = runTest {
        for (tls in versions) {
            val server = serve(Auth.Wanted)
            var change: (() -> Unit)? = null
            val setup = Setup(tls, events = {
                object : EventListener() {
                    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
                        change?.also { change = null }?.invoke()
                    }
                }
            })
            setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("rsa2048.p12"))) as SignInOutcome.Done
            val b = (setup.accounts.openPicked(cert("second-rsa.p12").path, PASSWORD) as CertificateOpened.Found).certificate
            val http = setup.http
            http.connectionPool.evictAll()
            change = { setup.accounts.security.configure(server.url(), null, emptyMap(), b) }
            server.leaves.clear()
            val answered = withContext(Dispatchers.IO) { runCatching { http.newCall(Request(server.url().resolve("rest/ping")!!)).execute().close() } }
            assertTrue("$tls: the call is answered: ${answered.exceptionOrNull()}", answered.isSuccess)
            assertEquals(tls.toString(), listOf<String?>(second), server.leaves.toList())
        }
    }

    // A key the server cannot take (no Ed25519 among its schemes) is
    // declined; a server that only wants one keeps working, with no retry.
    @Test
    fun aKeyDeclinedForItsTypeKeepsAServerThatOnlyWantsOneWorking() = runTest {
        val server = serve(Auth.Wanted, schemes = RSA_AND_EC)
        val setup = Setup(TlsVersion.TLS_1_3)
        val done = setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("ed25519.p12"))) as SignInOutcome.Done
        repeat(2) { done.connection.client.ping() }
        assertTrue(server.leaves.all { it == null })
        assertEquals(0, setup.accounts.security.staleRetries.get())
    }

    // Red when the chain stays the one from before: the picked file with
    // its intermediate is used at once, with no restart.
    @Test
    fun aChainWithoutItsIntermediateIsTurnedDownAndThePickWithItWorks() = runTest {
        val server = serve()
        val setup = Setup()
        val refused = setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("chain-leaf-only.p12"))) as SignInOutcome.Failed
        assertEquals(SignInWords.CERT_REFUSED, refused.message)
        assertTrue(refused.certificate)
        setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("chain.p12"))) as SignInOutcome.Done
        assertEquals(CertFixtures.leaf("chain.p12"), server.lastLeaf)
    }

    // Asked and none sent, and one sent and turned down, through
    // sign-in, Test, switch, join and hand-over, on both versions.
    @Test
    fun everyFlowSaysTheServerAskedForACertificate() = runTest {
        for (tls in versions) {
            val server = serve()
            val setup = Setup(tls)
            val signIn = setup.accounts.signIn(SignInRequest(server.address, "winters", "pw")) as SignInOutcome.Failed
            assertEquals(tls.toString(), SignInWords.CERT_ASKED, signIn.message)
            assertTrue(signIn.certificate)
            assertEquals(SignInWords.CERT_ASKED, (setup.accounts.test(SignInRequest(server.address, "winters", "pw")) as TestOutcome.Failed).message)
            val k = setup.keep(server.address)
            assertEquals(switchFailedWords(k.name, SignInWords.CERT_ASKED), (setup.accounts.switchTo(k.id) as SwitchOutcome.Failed).message)
            val joining = invited(server)
            assertNull(signUpFromForm(setup.accounts, joining))
            assertEquals(false to SignInWords.CERT_ASKED, joining.result)
            assertTrue(joining.certificatePending && joining.advancedOpen)
            val handing = handedOver(server)
            assertNull(receiveFromForm(setup.accounts, FamilyPlatform.Windows, handing))
            assertEquals(false to SignInWords.CERT_ASKED, handing.result)
        }
    }

    @Test
    fun everyFlowSaysTheServerTurnedTheCertificateDown() = runTest {
        for (tls in versions) {
            val server = serve(clients = Clients.None)
            val setup = Setup(tls)
            val file = cert("rsa2048.p12")
            val signIn = setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = CertificateDraft.Use(file.path, PASSWORD))) as SignInOutcome.Failed
            assertEquals(tls.toString(), SignInWords.CERT_REFUSED, signIn.message)
            assertEquals(SignInWords.CERT_REFUSED, (setup.accounts.test(SignInRequest(server.address, "winters", "pw", certificate = CertificateDraft.Use(file.path, PASSWORD))) as TestOutcome.Failed).message)
            val k = setup.keep(server.address, CertFile(file.path, file.name))
            setup.secrets.putItem(k.id, CertSecret(file.path, PASSWORD))
            assertEquals(switchFailedWords(k.name, SignInWords.CERT_REFUSED), (setup.accounts.switchTo(k.id) as SwitchOutcome.Failed).message)
            val picked = (setup.accounts.openPicked(file.path, PASSWORD) as CertificateOpened.Found).certificate
            val joining = invited(server).also { it.useCertificate(PickedCertificate(file.path, PASSWORD, picked)) }
            assertNull(signUpFromForm(setup.accounts, joining))
            assertEquals(false to SignInWords.CERT_REFUSED, joining.result)
            val handing = handedOver(server).also { it.useCertificate(PickedCertificate(file.path, PASSWORD, picked)) }
            assertNull(receiveFromForm(setup.accounts, FamilyPlatform.Windows, handing))
            assertEquals(false to SignInWords.CERT_REFUSED, handing.result)
        }
    }

    // Red when an offered handshake followed by an outage reads as the
    // certificate turned down: the server took it, and the answer never came.
    @Test
    fun anOfferedHandshakeThenATimeoutSaysNothingOfTheCertificate() = runTest {
        val server = serve()
        server.answer("ping") { MockResponse.Builder().body("late").headersDelay(2, TimeUnit.SECONDS).build() }
        val setup = Setup(tune = { it.readTimeout(300, TimeUnit.MILLISECONDS) })
        val failed = setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("rsa2048.p12"))) as SignInOutcome.Failed
        assertFalse(failed.certificate)
        assertFalse(failed.message, failed.message == SignInWords.CERT_ASKED || failed.message == SignInWords.CERT_REFUSED)
        assertEquals(rsa, server.lastLeaf)
    }

    // nginx answers a missing or refused certificate with its own 400 page
    // over a finished handshake.
    @Test
    fun nginxsPagesForACertificateSayWhatTheyMean() = runTest {
        val asks = serve(Auth.Wanted)
        asks.answer("ping") { request -> if (request.handshake?.peerCertificates.isNullOrEmpty()) ClientAuthServer.body(NGINX_NONE, 400) else ok() }
        val setup = Setup()
        assertEquals(SignInWords.CERT_ASKED, (setup.accounts.signIn(SignInRequest(asks.address, "winters", "pw")) as SignInOutcome.Failed).message)
        val refuses = serve(Auth.Wanted, clients = Clients.Any)
        refuses.answer("ping") { request -> if (request.handshake?.peerCertificates.isNullOrEmpty()) ok() else ClientAuthServer.body(NGINX_REFUSED, 400) }
        val refused = setup.accounts.signIn(SignInRequest(refuses.address, "winters", "pw", certificate = use("rsa2048.p12"))) as SignInOutcome.Failed
        assertEquals(SignInWords.CERT_REFUSED, refused.message)
    }

    @Test
    fun aKeyDeclinedForItsTypeByAServerThatRequiresOneIsTurnedDown() = runTest {
        val server = serve(schemes = RSA_AND_EC)
        val setup = Setup(TlsVersion.TLS_1_3)
        val failed = setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = use("ed25519.p12"))) as SignInOutcome.Failed
        assertEquals(SignInWords.CERT_REFUSED, failed.message)
    }

    // A name that leads to ::1 first, where nothing listens, then to the
    // server: the refused connection does not hide the handshake's failure.
    @Test
    fun aNameWithTwoAddressesStillSaysTheServerAsked() = runTest {
        val bothFamilies = Dns { host -> if (host == "localhost") listOf(InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.1")) else Dns.SYSTEM.lookup(host) }
        for (tls in versions) {
            val server = serve()
            val setup = Setup(tls, dns = bothFamilies)
            val failed = setup.accounts.signIn(SignInRequest(server.url("localhost").toString(), "winters", "pw")) as SignInOutcome.Failed
            assertEquals(tls.toString(), SignInWords.CERT_ASKED, failed.message)
        }
    }

    // music.test leads to the server, whose certificate does not name it:
    // sign-in, Test and join ask about the certificate; switch and hand-over
    // keep today's words, never the client certificate's.
    @Test
    fun aCertificateForAnotherNameAsksFirstOrKeepsTodaysWords() = runTest {
        for (tls in versions) {
            val server = serve(Auth.Wanted)
            val setup = Setup(tls)
            val address = server.url("music.test").toString()
            assertTrue(setup.accounts.signIn(SignInRequest(address, "winters", "pw")) is SignInOutcome.Untrusted)
            assertTrue(setup.accounts.test(SignInRequest(address, "winters", "pw")) is TestOutcome.Untrusted)
            val joining = invited(server, address)
            assertNull(signUpFromForm(setup.accounts, joining))
            assertEquals("music.test", joining.question?.host)
            val k = setup.keep(address)
            val unreachable = "Couldn't reach the server. Check the address, and that you're on the right network."
            assertEquals(switchFailedWords(k.name, unreachable), (setup.accounts.switchTo(k.id) as SwitchOutcome.Failed).message)
            val handing = handedOver(server, address)
            assertNull(receiveFromForm(setup.accounts, FamilyPlatform.Windows, handing))
            assertEquals(false to HANDOVER_UNREACHABLE, handing.result)
        }
    }

    @Test
    fun withACertificateChosenTheOtherNameStillAsksFirst() = runTest {
        val server = serve()
        val setup = Setup()
        val outcome = setup.accounts.signIn(SignInRequest(server.url("music.test").toString(), "winters", "pw", certificate = use("rsa2048.p12")))
        assertTrue(outcome is SignInOutcome.Untrusted)
    }

    // A join and a hand-over to a server that requires the
    // certificate go through when the form sends it.
    @Test
    fun aJoinAndAHandOverGoThroughWithTheCertificate() = runTest {
        val server = serve()
        val setup = Setup()
        val file = cert("rsa2048.p12")
        val picked = (setup.accounts.openPicked(file.path, PASSWORD) as CertificateOpened.Found).certificate
        val joining = invited(server).also { it.useCertificate(PickedCertificate(file.path, PASSWORD, picked)) }
        assertNotNull(signUpFromForm(setup.accounts, joining))
        val handing = handedOver(server, allow = HandOverSignIn("ana", "pw3", server = server.address)).also { it.useCertificate(PickedCertificate(file.path, PASSWORD, picked)) }
        assertNotNull(receiveFromForm(setup.accounts, FamilyPlatform.Windows, handing))
        assertTrue(server.leaves.isNotEmpty() && server.leaves.all { it == rsa })
    }

    // A hand-over to an account already kept with a certificate leaves its
    // certificate alone.
    @Test
    fun aHandOverToAKeptAccountLeavesItsCertificate() = runTest {
        val server = serve(Auth.Wanted)
        val setup = Setup()
        val file = cert("rsa2048.p12")
        val k = (setup.accounts.signIn(SignInRequest(server.address, "winters", "pw", certificate = CertificateDraft.Use(file.path, PASSWORD))) as SignInOutcome.Done).connection.server
        setup.accounts.signOut()
        val handing = handedOver(server, allow = HandOverSignIn("winters", "pw", server = server.address))
        val done = receiveFromForm(setup.accounts, FamilyPlatform.Windows, handing)!!
        assertEquals(k.id, done.connection.server.id)
        assertEquals(CertFile(file.path, "rsa2048.p12"), done.connection.server.certificate)
        assertEquals(CertSecret(file.path, PASSWORD), setup.secrets.item(k.id))
    }

    // A form that holds an invite to `server`, filled in.
    private fun invited(server: ClientAuthServer, address: String = server.address): SignInForm {
        server.answer("join") { ClientAuthServer.body("""{"username":"alex"}""") }
        return SignInForm().also { form ->
            assertTrue(form.takeJoinLink("octo://join?server=${URLEncoder.encode(address, Charsets.UTF_8)}&invite=tok_1"))
            form.inviteName = "Alex"
            form.invitePassword = "long enough"
            form.inviteAgain = "long enough"
        }
    }

    // A form that holds a hand-over from `server`: allowed with `allow`'s
    // sign-in sealed with the link's key, or turned down.
    private fun handedOver(server: ClientAuthServer, address: String = server.address, allow: HandOverSignIn? = null): SignInForm {
        val key = HandOverBox.newKey()
        server.answer("redeemFamilySignIn") { ok(""""familySignInRedeemed":{"id":"r1"}""") }
        server.answer("getFamilySignInRedeem") {
            if (allow == null) ok(""""familySignInRedeem":{"state":"Denied"}""") else ok(""""familySignInRedeem":{"state":"Allowed","box":"${HandOverBox.seal(allow, key)}"}""")
        }
        return SignInForm().also { it.take(FamilyHandOverLink(base = address, token = "t1", key = key, server = address)) }
    }

    private suspend fun waitFor(done: () -> Boolean) {
        withContext(Dispatchers.IO) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!done()) {
                check(System.nanoTime() < until) { "not done in 10 s" }
                Thread.sleep(20)
            }
        }
    }

    companion object {
        // Signature schemes for RSA and EC keys, none for Ed25519.
        val RSA_AND_EC = arrayOf("rsa_pss_rsae_sha256", "rsa_pkcs1_sha256", "ecdsa_secp256r1_sha256")
        const val NGINX_NONE = "<html><head><title>400 No required SSL certificate was sent</title></head><body></body></html>"
        const val NGINX_REFUSED = "<html><head><title>400 The SSL certificate error</title></head><body></body></html>"
    }
}
