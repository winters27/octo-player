package app.winters.octo.desktop.pages

import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.ResolvedHeaders
import app.winters.octo.connection.SignInWords
import app.winters.octo.data.HeaderDraft
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SignInPages
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.CertFixtures
import app.winters.octo.desktop.server.CertStore
import app.winters.octo.desktop.server.CertificateChoice
import app.winters.octo.desktop.server.CertificateDraft
import app.winters.octo.desktop.server.CertificateLoad
import app.winters.octo.desktop.server.CertificateQuestion
import app.winters.octo.desktop.server.ClientAuthServer
import app.winters.octo.desktop.server.FakeSecrets
import app.winters.octo.desktop.server.SavedGateway
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.server.tlsParts
import app.winters.octo.desktop.settings.CertFile
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.subsonic.FamilySignInPrefill
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.gateway.basicCredentials
import app.winters.octo.ui.family.HANDOVER_DENIED
import app.winters.octo.ui.family.HandOverBox
import app.winters.octo.ui.family.HandOverSignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

// A family link into a desktop form carries nothing of another server: a
// fitting link keeps what the form holds, any other starts it over (rows,
// sign-in page, saved secret, Method, certificate, everything typed), a late
// fill for the old server changes nothing, a link while a sign-in runs is
// refused in the link note, a hand-over's sealed sign-in is checked before
// anything goes to it, a sign-up or a sealed sign-in is used once and, while
// held, refuses every link but its own, and a waiting hand-over can be left
// while a committing request cannot.
class LinkFormTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<AutoCloseable>()
    private val gates = mutableListOf<CountDownLatch>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() {
        gates.forEach { it.countDown() }
        scope.cancel()
        opened.forEach { runCatching { it.close() } }
    }

    private fun server(): FakeServer = FakeServer().also { opened += it }

    private fun gate(): CountDownLatch = CountDownLatch(1).also { gates += it }

    private fun CountDownLatch.hold() = check(await(10, TimeUnit.SECONDS)) { "held too long" }

    private fun accounts(): Accounts = Accounts(SettingsStore(File(folder.root, "settings.json")), FakeSecrets(), OkHttpClient())

    // Waits for work another thread does, at most 10 s.
    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!what()) {
            check(System.currentTimeMillis() < until) { "waited too long" }
            Thread.sleep(20)
        }
    }

    private fun FakeServer.serves() {
        answer("ping", type = "octo")
        answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[]""", type = "octo")
    }

    private fun FakeServer.redeems(state: () -> String) {
        answerBy("redeemFamilySignIn") { ok(""""familySignInRedeemed":{"id":"r1"}""", type = "octo") }
        answerBy("getFamilySignInRedeem") { ok(""""familySignInRedeem":${state()}""", type = "octo") }
    }

    private fun allowed(signIn: HandOverSignIn, key: String) = """{"state":"Allowed","box":"${HandOverBox.seal(signIn, key)}"}"""

    private fun FakeServer.count(endpoint: String) = endpoints().count { it == endpoint }

    private val kept = SavedServer(
        "https://music.example.com/",
        "winters",
        headerNames = listOf("X-Token", "Authorization"),
        id = "w1",
        certificate = CertFile("C:/w.p12", "w.p12"),
    )
    private val authorization = SavedGateway(GatewayKind.Password, setOf("Authorization"))

    @Test
    fun aFittingSignInLinkKeepsWhatTheFormHolds() {
        val form = SignInForm(kept)
        form.knowSavedGateway(kept, authorization)
        form.take(FamilySignInPrefill("https://music.example.com"))
        assertEquals(listOf("X-Token"), form.headers.map { it.name })
        assertEquals(GatewayKind.Password, form.request().gateway.saved)
        assertEquals("w1", form.filledFrom)
        assertEquals(CertificateDraft.Keep("w1"), form.certificateDraft)
        assertNull(form.linkNote)
    }

    // Red when the check reads the address box: here it holds the same
    // server again, typed after an edit, which no longer ties the form to it.
    @Test
    fun anEditedAddressThenALinkForTheSameServerForgets() {
        val form = SignInForm(kept)
        form.typeAddress("https://other.example.com")
        form.take(FamilySignInPrefill("https://music.example.com", "winters"))
        assertTrue(form.headers.isEmpty())
        assertEquals(GatewayKind.None, form.savedGateway)
        assertNull(form.keptId)
        assertNull(form.filledFrom)
        assertEquals(CertificateDraft.None, form.certificateDraft)
        assertEquals("https://music.example.com/", form.url.toString())
        assertEquals("the link names the username", "winters", form.username)
    }

    // An invite always makes a new account, so even to the same server it
    // starts the form over, the certificate included.
    @Test
    fun anInviteToTheHeldServerStartsTheFormOver() {
        val form = SignInForm(kept)
        assertEquals(CertificateDraft.Keep("w1"), form.certificateDraft)
        form.take(FamilyInviteLink("https://music.example.com", "t1"))
        assertTrue(form.headers.isEmpty())
        assertEquals("", form.username)
        assertEquals(CertificateDraft.None, form.certificateDraft)
        assertNull(form.certificateLoad)
        assertNull(form.filledFrom)
        assertEquals("t1", form.invite?.token)
        // Remove on a file chosen after the forget never names the old server.
        form.removeCertificate()
        assertEquals(CertificateDraft.None, form.certificateDraft)
    }

    // Red when anything typed for the old server survives the forget.
    @Test
    fun aForgetLeavesNothingTypedForTheOldServer() {
        val form = SignInForm(kept, keepsSecret = true)
        form.label = "Home"
        form.password = "old"
        form.pickMethod(app.winters.octo.subsonic.AuthMode.LegacyPassword)
        form.apiKey = "k"
        form.rememberPassword = false
        form.home = "http://192.168.1.20:4533"
        form.pickGateway(GatewayKind.Password)
        form.typeGatewayFirst("alice")
        form.typeGatewaySecond("pw")
        assertTrue(form.keepsPassword)
        form.take(FamilyInviteLink("https://other.example.com", "t1"))
        assertEquals("", form.password)
        assertEquals("", form.apiKey)
        assertFalse(form.legacyPassword || form.useApiKey)
        assertTrue(form.rememberPassword)
        assertEquals("", form.home)
        assertEquals(GatewayKind.None, form.gatewayKind)
        assertEquals("", form.gatewayFirst)
        assertEquals("", form.gatewaySecond)
        assertFalse("no saved secret stands in any more", form.keepsPassword)
        assertEquals("the name stays", "Home", form.label)
    }

    // A file opening on the row for the old server (being read, or asking
    // for its password) ends with a link to another server, so its answer
    // lands nowhere and Choose is ready; a fitting link leaves it. Red when
    // the forget leaves the opening's turn, which lets the old server's file
    // onto the new server's form.
    @Test
    fun aLinkToAnotherServerEndsAFileOpeningForTheOldOne() {
        val fitting = SignInForm(kept)
        val turn = Any()
        fitting.certificateTurn = turn
        fitting.certificateOpening = true
        fitting.take(FamilySignInPrefill("https://music.example.com"))
        assertSame("a fitting link keeps the opening", turn, fitting.certificateTurn)
        assertTrue(fitting.certificateOpening)

        val form = SignInForm(kept)
        form.certificateTurn = Any()
        form.certificateOpening = true
        form.certificateProblem = "That file isn't a certificate."
        form.take(FamilyInviteLink("https://other.example.com", "t1"))
        assertEquals("t1", form.invite?.token)
        assertNull("the opening's turn ends", form.certificateTurn)
        assertFalse("Choose is ready", form.certificateOpening)
        assertNull(form.certificateProblem)
    }

    // The late saved sign-in page and the late certificate read land on the
    // form's own server, never after a link to another started it over.
    @Test
    fun lateFillsLandOnlyWhileTheFormIsStillItsServers() {
        val file = CertFile("C:/w.p12", "w.p12")
        val saved = CertificateChoice.Saved("w1", file)
        val read = CertificateLoad.Unreadable("words", again = true)

        val plain = SignInForm(kept)
        plain.knowSavedGateway(kept, authorization)
        plain.knowCertificate(saved, read)
        assertEquals(GatewayKind.Password, plain.savedGateway)
        assertEquals(saved, plain.certificateLoad?.choice)

        val fitting = SignInForm(kept)
        fitting.take(FamilySignInPrefill("https://music.example.com"))
        fitting.knowSavedGateway(kept, authorization)
        fitting.knowCertificate(saved, read)
        assertEquals(GatewayKind.Password, fitting.savedGateway)
        assertEquals(saved, fitting.certificateLoad?.choice)

        val forgot = SignInForm(kept)
        forgot.take(FamilyInviteLink("https://other.example.com", "t1"))
        forgot.knowSavedGateway(kept, authorization)
        forgot.knowCertificate(saved, read)
        assertEquals(GatewayKind.None, forgot.savedGateway)
        assertEquals(GatewayKind.None, forgot.request().gateway.saved)
        assertNull(forgot.certificateLoad)
    }

    // The join after a forget sends none of the old server's saved headers.
    @Test
    fun aJoinAfterAForgetSendsNoSavedHeader() = runTest {
        val a = server().apply { serves() }
        val accounts = accounts()
        accounts.signIn(SignInRequest(a.address, "winters", "pw", headers = listOf(HeaderDraft("X-Token", "a-secret")))) as SignInOutcome.Done
        accounts.signOut()
        val form = SignInForm(accounts.last)
        assertEquals(listOf("X-Token"), form.headers.map { it.name })
        form.take(FamilyInviteLink("https://other.example.com", "t1"))
        val headers = accounts.joinHeaders(form.request()) as ResolvedHeaders.Ready
        assertTrue(headers.headers.isEmpty())
    }

    // Red when a link changes a running sign-in, or its words go to the
    // result line.
    @Test
    fun aLinkWhileASignInRunsIsRefusedInTheLinkNote() {
        val form = SignInForm()
        form.typeAddress("https://music.example.com")
        form.username = "u"
        form.busy = true
        form.result = false to "Wrong username or password."
        assertTrue(form.takeJoinLink("https://other.example.com/family/join#invite=tok_1"))
        assertEquals(SignInWords.LINK_WHILE_BUSY, form.linkNote)
        assertEquals(false to "Wrong username or password.", form.result)
        assertEquals("music.example.com", form.address)
        assertEquals("u", form.username)
        assertNull(form.invite)
        form.busy = false
        form.question = CertificateQuestion("music.example.com", "ab")
        assertTrue(form.takeJoinLink("https://other.example.com/family/join#invite=tok_1"))
        assertEquals(SignInWords.LINK_WHILE_BUSY, form.linkNote)
        assertNull(form.invite)
        form.question = null
        // A switch on its way counts the same.
        assertTrue(form.takeJoinLink("https://other.example.com/family/join#invite=tok_1", switching = true))
        assertNull(form.invite)
        // The next link taken clears the note.
        form.take(FamilyInviteLink("https://music.example.com", "t2"))
        assertNull(form.linkNote)
        assertEquals("t2", form.invite?.token)
        // An edit of the address clears it too.
        form.noteLink(SignInWords.LINK_WHILE_SIGN_IN_OPEN)
        form.typeAddress("music.example.org")
        assertNull(form.linkNote)
    }

    // A second link to the server and home typed keeps what was typed; a new
    // home starts over.
    @Test
    fun aSecondLinkToTheSameServerAndHomeKeepsWhatWasTyped() {
        val form = SignInForm()
        form.typeAddress("https://music.example.com")
        form.home = "http://192.168.1.20:4533"
        val id = form.addHeader()
        form.setHeaderName(id, "X-Token")
        form.setHeaderValue(id, "t")
        form.take(FamilyInviteLink("https://music.example.com", "t1", home = "http://192.168.1.20:4533"))
        assertEquals(listOf("X-Token"), form.headers.map { it.name })
        form.take(FamilyInviteLink("https://music.example.com", "t1", home = "http://192.168.1.20:4533"))
        assertEquals(listOf("X-Token"), form.headers.map { it.name })
        form.take(FamilyInviteLink("https://music.example.com", "t1", home = "http://192.168.1.30:4533"))
        assertTrue(form.headers.isEmpty())
        assertEquals("http://192.168.1.30:4533", form.home)
    }

    // The server typed, its headers, then its invite: the headers go along.
    @Test
    fun aTypedServersHeadersGoWithItsInvite() = runTest {
        val s = server()
        s.answerBy("join") { """{"username":"alex"}""" }
        s.serves()
        val form = SignInForm()
        form.typeAddress(s.address)
        val id = form.addHeader()
        form.setHeaderName(id, "X-Token")
        form.setHeaderValue(id, "typed")
        assertTrue(form.takeJoinLink("octo://join?server=${java.net.URLEncoder.encode(s.address, Charsets.UTF_8)}&invite=tok_1"))
        assertEquals(listOf("X-Token"), form.headers.map { it.name })
        form.inviteName = "Alex"
        form.invitePassword = "long enough"
        form.inviteAgain = "long enough"
        assertNotNull(signUpFromForm(accounts(), form))
        assertEquals("typed", s.calls.first { it.url.pathSegments.last() == "join" }.headers["X-Token"])
    }

    // A sign-up whose sign-in step failed is held: the same invite again keeps
    // it, its address cannot change, Try again signs in without signing up
    // again, at the address it was made at, and leaving the link puts its
    // username in the field.
    @Test
    fun aSignUpIsMadeOnceAndSignedInToWhereItWasMade() = runTest {
        val s = server()
        s.answerBy("join") { """{"username":"alex"}""" }
        var pings = 0
        s.answerBy("ping") { if (pings++ == 0) s.failed(40, "Wrong username or password") else s.ok(type = "octo") }
        s.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[]""", type = "octo")
        val link = "octo://join?server=${java.net.URLEncoder.encode(s.address, Charsets.UTF_8)}&invite=tok_1"
        val accounts = accounts()
        val form = SignInForm()
        assertTrue(form.takeJoinLink(link))
        form.inviteName = "Alex"
        form.invitePassword = "long enough"
        form.inviteAgain = "long enough"
        assertNull(signUpFromForm(accounts, form))
        assertEquals("alex", form.signedUp?.username)
        assertEquals(s.address, form.signedUp?.main)
        assertTrue(form.addressLocked)
        form.typeAddress("https://elsewhere.example")
        assertEquals(s.address, form.url.toString())
        assertTrue(form.takeJoinLink(link))
        assertEquals("the same invite keeps its sign-up", "alex", form.signedUp?.username)
        assertNotNull(signUpFromForm(accounts, form))
        assertEquals(1, s.count("join"))
        assertEquals("alex", s.calls.last { it.url.pathSegments.last() == "ping" }.url.queryParameter("u"))
        assertNull(form.signedUp)
    }

    // An invite to `server` taken, signed up, and its sign-in step failed:
    // the sign-up is held.
    private suspend fun heldSignUp(s: FakeServer): SignInForm {
        s.answerBy("join") { """{"username":"alex"}""" }
        s.fail("ping", 40, "Wrong username or password")
        val form = SignInForm()
        assertTrue(form.takeJoinLink("octo://join?server=${java.net.URLEncoder.encode(s.address, Charsets.UTF_8)}&invite=tok_1"))
        form.inviteName = "Alex"
        form.invitePassword = "long enough"
        form.inviteAgain = "long enough"
        assertNull(signUpFromForm(accounts(), form))
        assertNotNull(form.signedUp)
        return form
    }

    // A held sign-up is a sign-in that is open: another invite to its own
    // server is refused with the open sign-in's words and changes nothing,
    // the held sign-up, its invite, the address and the home included; so is
    // a sign-in link. Leaving the link then puts the sign-up's username in
    // the field. Red when another token drops the held sign-up.
    @Test
    fun anotherTokenOnTheHeldServerIsRefusedAndKeepsTheSignUp() = runTest {
        val s = server()
        val form = heldSignUp(s)
        val held = form.signedUp
        val address = form.url.toString()
        val home = form.home
        assertTrue(form.takeJoinLink("octo://join?server=${java.net.URLEncoder.encode(s.address, Charsets.UTF_8)}&invite=tok_2"))
        assertEquals(SignInWords.LINK_WHILE_SIGN_IN_OPEN, form.linkNote)
        assertSame("the held sign-up stays", held, form.signedUp)
        assertEquals("tok_1", form.invite?.token)
        assertTrue(form.addressLocked)
        assertEquals(address, form.url.toString())
        assertEquals(home, form.home)
        form.take(FamilySignInPrefill(s.address, "ana"))
        assertEquals(SignInWords.LINK_WHILE_SIGN_IN_OPEN, form.linkNote)
        assertSame(held, form.signedUp)
        assertEquals("tok_1", form.invite?.token)
        form.leaveLink()
        assertEquals("alex", form.username)
        assertNull(form.signedUp)
        assertNull(form.invite)
    }

    // The same invite token for another server is never the same invite:
    // refused with the open sign-in's words, and the held sign-up, the
    // address (still the server it was made at) and the home stay. Its own
    // invite again is taken and keeps the sign-up. Red when a link to
    // another server drops the held sign-up or moves the address.
    @Test
    fun theSameTokenForAnotherServerIsRefusedAndKeepsTheSignUp() = runTest {
        val s = server()
        val form = heldSignUp(s)
        val held = form.signedUp
        val home = form.home
        assertTrue(form.takeJoinLink("octo://join?server=${java.net.URLEncoder.encode("https://other.example.com", Charsets.UTF_8)}&invite=tok_1"))
        assertEquals(SignInWords.LINK_WHILE_SIGN_IN_OPEN, form.linkNote)
        assertSame("the held sign-up stays", held, form.signedUp)
        assertTrue(form.addressLocked)
        assertEquals(s.address, form.url.toString())
        assertEquals(home, form.home)
        assertTrue(form.takeJoinLink("octo://join?server=${java.net.URLEncoder.encode(s.address, Charsets.UTF_8)}&invite=tok_1"))
        assertNull("its own invite again is taken", form.linkNote)
        assertSame(held, form.signedUp)
    }

    // A sealed sign-in held (its sign-in step failed) is open too: another
    // hand-over to its server, an invite, a sign-in link, and its own
    // hand-over's token for another server are each refused and keep it,
    // its address and its hand-over; its own hand-over again is taken and
    // keeps it, so the code is never redeemed twice.
    @Test
    fun aHeldSealedSignInRefusesEveryLinkButItsOwn() = runTest {
        val s = server()
        val key = HandOverBox.newKey()
        s.redeems { allowed(HandOverSignIn("winters", "pw", server = s.address), key) }
        s.fail("ping", 40, "Wrong username or password")
        val form = SignInForm()
        val own = FamilyHandOverLink(base = s.address, token = "t1", key = key, server = s.address)
        form.take(own)
        assertNull(receiveFromForm(accounts(), FamilyPlatform.Windows, form))
        val held = form.received ?: throw AssertionError("the sealed sign-in is not held")
        for (other in listOf(
            FamilyHandOverLink(base = s.address, token = "t2", key = key, server = s.address),
            FamilyInviteLink(s.address, "t1"),
            FamilySignInPrefill(s.address, "winters"),
            FamilyHandOverLink(base = "https://other.example.com", token = "t1", key = key, server = "https://other.example.com"),
        )) {
            form.take(other)
            assertEquals("$other", SignInWords.LINK_WHILE_SIGN_IN_OPEN, form.linkNote)
            assertSame("$other", held, form.received)
            assertEquals("t1", form.handOver?.token)
            assertNull(form.invite)
            assertEquals(s.address, form.url.toString())
        }
        form.take(own)
        assertNull("its own hand-over again is taken", form.linkNote)
        assertSame(held, form.received)
        assertEquals(1, s.count("redeemFamilySignIn"))
    }

    // A sealed sign-in that failed its sign-in step is used again by Try
    // again: the code is never redeemed twice.
    @Test
    fun aSealedSignInIsReceivedOnce() = runTest {
        val s = server()
        val key = HandOverBox.newKey()
        s.redeems { allowed(HandOverSignIn("winters", "pw", server = s.address), key) }
        var pings = 0
        s.answerBy("ping") { if (pings++ == 0) s.failed(40, "Wrong username or password") else s.ok(type = "octo") }
        s.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[]""", type = "octo")
        val accounts = accounts()
        val form = SignInForm()
        form.take(FamilyHandOverLink(base = s.address, token = "t1", key = key, server = s.address))
        assertNull(receiveFromForm(accounts, FamilyPlatform.Windows, form))
        assertNotNull(form.received)
        assertNotNull(receiveFromForm(accounts, FamilyPlatform.Windows, form))
        assertEquals(1, s.count("redeemFamilySignIn"))
        assertNull(form.received)
    }

    // The page after a sign-out of A, a hand-over from A's own device whose
    // sealed sign-in names A's outside address and A's address as its home:
    // it fits, and signs in at A's own addresses with A's saved header, while
    // the page is busy. Red when the sealed server is used (it is not
    // reachable), or the received step is refused as busy.
    @Test
    fun aMatchingHandOverSignsInAtTheHeldServerWithItsSavedHeaders() = runTest {
        val a = server()
        a.serves()
        val accounts = accounts()
        val made = (accounts.signIn(SignInRequest(a.address, "winters", "pw", headers = listOf(HeaderDraft("X-Token", "a-secret")))) as SignInOutcome.Done).connection.server
        accounts.signOut()
        val key = HandOverBox.newKey()
        a.redeems { allowed(HandOverSignIn("winters", "pw", server = "https://music.example.com", home = a.address), key) }
        val form = SignInForm(accounts.last)
        form.take(FamilyHandOverLink(base = a.address, token = "t1", key = key, server = a.address, home = a.address))
        assertEquals("a fitting link keeps the rows", listOf("X-Token"), form.headers.map { it.name })
        form.busy = true
        val done = receiveFromForm(accounts, FamilyPlatform.Windows, form)
        assertNotNull(done)
        assertEquals(made.id, done!!.connection.server.id)
        assertEquals(a.address, done.connection.server.address)
        assertEquals("a-secret", a.calls.last { it.url.pathSegments.last() == "ping" }.headers["X-Token"])
        assertEquals(listOf("X-Token"), form.headers.map { it.name })
    }

    // A fresh form that held another address, a hand-over whose sealed
    // sign-in names a server C outside what was taken: the form starts over,
    // adopts C, and a sign-in page met at C opens its fields; typed in, Try
    // again sends them, and nothing is redeemed or forgotten again.
    @Test
    fun aSealedSignInForAnotherServerIsAdoptedAndItsSignInPageOpens() = runTest {
        val s = server()
        val c = server()
        val key = HandOverBox.newKey()
        s.redeems { allowed(HandOverSignIn("ana", "pw", server = c.address), key) }
        fun guarded(endpoint: String, answer: (RecordedRequest) -> MockResponse) =
            c.respond(endpoint) { request -> if (request.headers["Authorization"] == basicCredentials("alice", "app-pw")) answer(request) else SignInPages.authelia() }
        guarded("ping") { c.okResponse(type = "octo") }
        guarded("getOpenSubsonicExtensions") { c.okResponse(""""openSubsonicExtensions":[]""", type = "octo") }
        val accounts = accounts()
        val form = SignInForm()
        form.typeAddress("https://typed.example.com")
        form.take(FamilyHandOverLink(base = s.address, token = "t1", key = key, server = s.address))
        assertNull(receiveFromForm(accounts, FamilyPlatform.Windows, form))
        assertEquals(c.address, form.url.toString())
        assertTrue(form.advancedOpen)
        assertEquals(GatewayKind.Password, form.gatewayKind)
        form.typeGatewayFirst("alice")
        form.typeGatewaySecond("app-pw")
        val done = receiveFromForm(accounts, FamilyPlatform.Windows, form)
        assertNotNull(done)
        assertEquals(c.address, done!!.connection.server.address)
        assertEquals(1, s.count("redeemFamilySignIn"))
        assertEquals(basicCredentials("alice", "app-pw"), c.calls.last { it.url.pathSegments.last() == "ping" }.headers["Authorization"])
    }

    // The page after a sign-out of A (https, a certificate, a header), an
    // invite to B: B's join and sign-in get neither, B is saved without them,
    // and A is as it was.
    @Test
    fun aJoinToAnotherServerCarriesNothingOfTheLastOne() = runTest {
        val a = ClientAuthServer(ClientAuthServer.Auth.Required).also { opened += it }
        val b = ClientAuthServer(ClientAuthServer.Auth.Wanted).also { opened += it }
        b.answer("join") { ClientAuthServer.body("""{"username":"alex"}""") }
        val file = CertFixtures.copy("rsa2048.p12", folder.root)
        val accounts = tlsParts(SettingsStore(File(folder.root, "settings.json")), CertStore()).first
        val kept = (accounts.signIn(SignInRequest(a.address, "winters", "pw", headers = listOf(HeaderDraft("X-Token", "a-secret")), certificate = CertificateDraft.Use(file.path, CertFixtures.PASSWORD))) as SignInOutcome.Done).connection.server
        accounts.signOut()
        val before = accounts.find(kept.id)
        val form = SignInForm(accounts.last)
        assertTrue(form.takeJoinLink("octo://join?server=${java.net.URLEncoder.encode(b.address, Charsets.UTF_8)}&invite=tok_1"))
        form.inviteName = "Alex"
        form.invitePassword = "long enough"
        form.inviteAgain = "long enough"
        val made = signUpFromForm(accounts, form)!!.connection.server
        assertTrue(b.calls.isNotEmpty())
        assertTrue(b.calls.none { it.headers["X-Token"] != null })
        assertTrue("no certificate was offered to B", b.leaves.all { it == null })
        assertNull(made.certificate)
        assertTrue(made.headerNames.isEmpty())
        assertEquals(before, accounts.find(kept.id))
    }

    // Runs a link request the way the page's go() does: as the form's own job.
    private fun go(form: SignInForm, work: suspend () -> Unit): kotlinx.coroutines.Job {
        form.busy = true
        form.waiting = form.handOver != null && form.received == null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                work()
            } finally {
                if (form.running === coroutineContext.job) {
                    form.busy = false
                    form.waiting = false
                    form.running = null
                }
            }
        }
        form.running = job
        job.start()
        return job
    }

    // While a hand-over waits for the other device, leaving cancels it at
    // once, and an Allow that comes later signs nothing in.
    @Test
    fun aWaitingHandOverLeftIsCancelledAndNeverSignsIn() {
        val s = server()
        s.serves()
        val key = HandOverBox.newKey()
        val state = java.util.concurrent.atomic.AtomicReference("""{"state":"Waiting"}""")
        s.redeems { state.get() }
        val form = SignInForm()
        form.take(FamilyHandOverLink(base = s.address, token = "t1", key = key, server = s.address))
        val job = go(form) { receiveFromForm(accounts(), FamilyPlatform.Windows, form) }
        waitFor { s.count("getFamilySignInRedeem") > 0 }
        assertTrue(form.waiting)
        assertFalse(form.committing)
        form.leaveLink()
        assertFalse(form.busy)
        assertFalse(form.waiting)
        assertNull(form.running)
        assertNull(form.handOver)
        runBlocking { withTimeout(10.seconds) { job.join() } }
        assertTrue(job.isCancelled)
        state.set(allowed(HandOverSignIn("winters", "pw", server = s.address), key))
        assertEquals(0, s.count("ping"))
        // A link is taken again at once.
        form.take(FamilyInviteLink(s.address, "t2"))
        assertEquals("t2", form.invite?.token)
    }

    // A sign-up that is running commits: leaving does nothing until it ends.
    @Test
    fun aRunningSignUpCannotBeLeft() {
        val s = server()
        s.serves()
        val held = gate()
        s.answerBy("join") {
            held.hold()
            """{"username":"alex"}"""
        }
        val accounts = accounts()
        val form = SignInForm()
        assertTrue(form.takeJoinLink("octo://join?server=${java.net.URLEncoder.encode(s.address, Charsets.UTF_8)}&invite=tok_1"))
        form.inviteName = "Alex"
        form.invitePassword = "long enough"
        form.inviteAgain = "long enough"
        var done: SignInOutcome.Done? = null
        val job = go(form) { done = signUpFromForm(accounts, form) }
        waitFor { s.count("join") > 0 }
        assertTrue(form.committing)
        form.leaveLink()
        assertTrue("still running", form.busy)
        assertNotNull(form.invite)
        held.countDown()
        runBlocking { withTimeout(10.seconds) { job.join() } }
        assertNotNull(done)
        assertFalse(form.busy)
    }

    // Allowed, then the sign-in step held: the request commits, and leaving
    // does nothing; it signs in once released.
    @Test
    fun aHandOversSignInStepCommits() {
        val s = server()
        val key = HandOverBox.newKey()
        s.redeems { allowed(HandOverSignIn("winters", "pw", server = s.address), key) }
        val held = gate()
        s.answerBy("ping") {
            held.hold()
            s.ok(type = "octo")
        }
        s.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[]""", type = "octo")
        val form = SignInForm()
        form.take(FamilyHandOverLink(base = s.address, token = "t1", key = key, server = s.address))
        var done: SignInOutcome.Done? = null
        val job = go(form) { done = receiveFromForm(accounts(), FamilyPlatform.Windows, form) }
        waitFor { s.count("ping") > 0 }
        assertFalse(form.waiting)
        assertTrue(form.committing)
        form.leaveLink()
        assertNotNull(form.handOver)
        held.countDown()
        runBlocking { withTimeout(10.seconds) { job.join() } }
        assertNotNull(done)
    }

    // Every end of the redeem ends the wait: a refusal, and a header problem.
    @Test
    fun aRefusedHandOverOrAHeaderProblemEndsTheWait() = runTest {
        val s = server()
        s.redeems { """{"state":"Denied"}""" }
        val form = SignInForm()
        form.take(FamilyHandOverLink(base = s.address, token = "t1", key = HandOverBox.newKey(), server = s.address))
        form.busy = true
        form.waiting = true
        assertNull(receiveFromForm(accounts(), FamilyPlatform.Windows, form))
        assertFalse(form.waiting)
        assertEquals(HANDOVER_DENIED, form.result?.second)
        val problem = SignInForm()
        problem.take(FamilyHandOverLink(base = s.address, token = "t2", key = HandOverBox.newKey(), server = s.address))
        val id = problem.addHeader()
        problem.setHeaderName(id, "Not a name")
        problem.setHeaderValue(id, "v")
        problem.busy = true
        problem.waiting = true
        assertNull(receiveFromForm(accounts(), FamilyPlatform.Windows, problem))
        assertFalse(problem.waiting)
        assertNotNull(problem.result)
    }
}
