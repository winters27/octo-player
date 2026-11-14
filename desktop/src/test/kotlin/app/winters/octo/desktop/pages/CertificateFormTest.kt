package app.winters.octo.desktop.pages

import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.CertFixtures
import app.winters.octo.desktop.server.CertFixtures.PASSWORD
import app.winters.octo.desktop.server.CertSecret
import app.winters.octo.desktop.server.CertStore
import app.winters.octo.desktop.server.CertificateChoice
import app.winters.octo.desktop.server.CertificateDraft
import app.winters.octo.desktop.server.CertificateLoad
import app.winters.octo.desktop.server.CertificateOpened
import app.winters.octo.desktop.server.CertificateShown
import app.winters.octo.desktop.server.CertificateTry
import app.winters.octo.desktop.server.CertificateWords
import app.winters.octo.desktop.server.ClientAuthServer
import app.winters.octo.desktop.server.ClientAuthServer.Auth
import app.winters.octo.desktop.server.PickedCertificate
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.server.SwitchOutcome
import app.winters.octo.desktop.server.readCapped
import app.winters.octo.desktop.server.tlsParts
import app.winters.octo.desktop.settings.CertFile
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.server.Reach
import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.ui.family.HandOverBox
import app.winters.octo.ui.family.HandOverSignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLEncoder
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class CertificateFormTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<AutoCloseable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val secrets = CertStore()
    private val settingsFile get() = File(folder.root, "settings.json")
    private val reads = CopyOnWriteArrayList<Path>()
    private val a by lazy { CertFixtures.copy("rsa2048.p12", folder.root) }
    private val rsa get() = CertFixtures.leaf("rsa2048.p12")
    private val fileA get() = CertFile(a.path, "rsa2048.p12")

    @After
    fun stop() {
        scope.cancel()
        opened.forEach { runCatching { it.close() } }
    }

    private fun accounts(): Accounts = tlsParts(SettingsStore(settingsFile), secrets, read = { reads.add(it); readCapped(it) }).first

    private fun serve(auth: Auth = Auth.Required) = ClientAuthServer(auth).also { opened += it }

    // A plain http server, answering like a Subsonic one.
    private fun plain() = FakeServer().also { server ->
        opened += server
        server.answer("ping", type = "octo")
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[]""", type = "octo")
    }

    private suspend fun signIn(accounts: Accounts, address: String, username: String = "winters", certificate: CertificateDraft = CertificateDraft.Use(a.path, PASSWORD), home: String = ""): SavedServer =
        (accounts.signIn(SignInRequest(address, username, "pw", home = home, certificate = certificate)) as SignInOutcome.Done).connection.server

    private fun shown(accounts: Accounts, form: SignInForm) = accounts.certificateShown(form.certificateDraft, form.signsInAgainTo, form.url, form.homeUrl)

    private fun inviteLink(address: String, home: String? = null) =
        "octo://join?server=${URLEncoder.encode(address, Charsets.UTF_8)}&invite=tok_1" + (home?.let { "&home=${URLEncoder.encode(it, Charsets.UTF_8)}" } ?: "")

    private fun fillInvite(form: SignInForm) {
        form.inviteName = "Alex"
        form.invitePassword = "long enough"
        form.inviteAgain = "long enough"
    }

    private fun answerJoin(server: ClientAuthServer) = server.answer("join") { ClientAuthServer.body("""{"username":"alex"}""") }

    private fun handOver(server: ClientAuthServer, allow: HandOverSignIn?): FamilyHandOverLink {
        val key = HandOverBox.newKey()
        server.answer("redeemFamilySignIn") { ClientAuthServer.ok(""""familySignInRedeemed":{"id":"r1"}""") }
        server.answer("getFamilySignInRedeem") {
            if (allow == null) ClientAuthServer.ok(""""familySignInRedeem":{"state":"Denied"}""") else ClientAuthServer.ok(""""familySignInRedeem":{"state":"Allowed","box":"${HandOverBox.seal(allow, key)}"}""")
        }
        return FamilyHandOverLink(base = server.address, token = "t1", key = key, server = server.address)
    }

    private suspend fun loads(accounts: Accounts, server: SavedServer) =
        accounts.loadChoice(CertificateChoice.Saved(server.id, server.certificate!!))

    @Test
    fun aFormForAKeptServerStartsWithItsCertificateAndAFreshOneWithNone() {
        val k = SavedServer("https://music.test/", "winters", id = "k", certificate = CertFile("C:/k.p12", "k.p12"))
        assertEquals(CertificateDraft.Keep("k"), SignInForm(k).certificateDraft)
        assertEquals(CertificateDraft.None, SignInForm().certificateDraft)
        assertEquals(CertificateDraft.Keep("k"), SignInForm(certificateOf = "k").certificateDraft)
    }

    @Test
    fun removeOnAFileJustChosenTakesOffWhatTheFormStartedWith() = runTest {
        val accounts = accounts()
        val picked = PickedCertificate(a.path, PASSWORD, (accounts.openPicked(a.path, PASSWORD) as CertificateOpened.Found).certificate)
        val k = SavedServer("https://music.test/", "winters", id = "k", certificate = CertFile("C:/k.p12", "k.p12"))
        val kept = SignInForm(k)
        kept.useCertificate(picked)
        assertEquals(CertificateDraft.Use(a.path, PASSWORD), kept.certificateDraft)
        kept.removeCertificate()
        assertEquals(CertificateDraft.Remove("k"), kept.certificateDraft)
        val fresh = SignInForm()
        fresh.useCertificate(picked)
        fresh.removeCertificate()
        assertEquals(CertificateDraft.None, fresh.certificateDraft)
    }

    @Test
    fun choosingOrRemovingACertificateIsAChangeToSave() = runTest {
        val accounts = accounts()
        val k = SavedServer("https://music.test/", "winters", id = "k", certificate = CertFile("C:/k.p12", "k.p12"))
        val form = SignInForm(k, keepsSecret = true)
        assertFalse(form.changesConnection(k))
        form.removeCertificate()
        assertTrue(form.changesConnection(k))
        val chosen = SignInForm(k, keepsSecret = true)
        chosen.useCertificate(PickedCertificate(a.path, PASSWORD, (accounts.openPicked(a.path, PASSWORD) as CertificateOpened.Found).certificate))
        assertTrue(chosen.changesConnection(k))
    }

    @Test
    fun aLinkToAnotherServerLeavesAFileChosenForTheOldOneBehind() = runTest {
        val accounts = accounts()
        val k = SavedServer("https://music.test/", "winters", id = "k")
        val form = SignInForm(k)
        form.useCertificate(PickedCertificate(a.path, PASSWORD, (accounts.openPicked(a.path, PASSWORD) as CertificateOpened.Found).certificate))
        assertTrue(form.takeJoinLink(inviteLink("https://elsewhere.test")))
        // A link to another server starts the form over: no certificate.
        assertEquals(CertificateDraft.None, form.certificateDraft)
        assertNull(form.certificateLoad)
    }

    @Test
    fun anotherHostTypedOnThePageShowsAndSendsNone() = runTest {
        val server = serve(Auth.Wanted)
        val accounts = accounts()
        signIn(accounts, server.address)
        accounts.signOut()
        val form = SignInForm(accounts.last)
        form.typeAddress(server.url("localhost").toString())
        form.password = "pw"
        assertEquals(CertificateShown.None, shown(accounts, form))
        val made = (accounts.signIn(form.request()) as SignInOutcome.Done).connection.server
        assertNull(server.lastLeaf)
        assertNull(made.certificate)
    }

    @Test
    fun theSameHostWithAnotherUsernameShowsAndSendsTheLastOneAndKeepsIt() = runTest {
        val server = serve(Auth.Wanted)
        val accounts = accounts()
        val k = signIn(accounts, server.address)
        accounts.signOut()
        val form = SignInForm(accounts.last)
        form.username = "brandon"
        form.password = "pw2"
        assertEquals(CertificateShown.Sends(CertificateChoice.Saved(k.id, fileA)), shown(accounts, form))
        val made = (accounts.signIn(form.request()) as SignInOutcome.Done).connection.server
        assertEquals(rsa, server.lastLeaf)
        assertNotEquals(k.id, made.id)
        assertEquals(fileA, made.certificate)
        assertEquals(CertSecret(a.path, PASSWORD), secrets.item(made.id))
        val later = accounts()
        assertTrue(loads(later, made) is CertificateLoad.Found)
        assertTrue("k keeps its own", loads(later, k) is CertificateLoad.Found)
    }

    @Test
    fun aSignOutThenAPageSignInToAServerThatRequiresTheCertificateWorks() = runTest {
        val server = serve()
        val accounts = accounts()
        signIn(accounts, server.address)
        accounts.signOut()
        val form = SignInForm(accounts.last)
        form.password = "pw"
        assertTrue(accounts.signIn(form.request()) is SignInOutcome.Done)
        assertEquals(rsa, server.lastLeaf)
    }

    // An invite always makes a new account, so on the page after a sign-out
    // it starts the form over: the last server's certificate goes nowhere.
    @Test
    fun aPageInviteToTheLastHostCarriesNothingOfIt() = runTest {
        val server = serve(Auth.Wanted)
        answerJoin(server)
        val accounts = accounts()
        val k = signIn(accounts, server.address)
        accounts.signOut()
        val form = SignInForm(accounts.last)
        assertTrue(form.takeJoinLink(inviteLink(server.address)))
        assertEquals(CertificateDraft.None, form.certificateDraft)
        fillInvite(form)
        server.leaves.clear()
        val made = signUpFromForm(accounts, form)!!.connection.server
        assertEquals("alex", made.username)
        assertNull(made.certificate)
        assertTrue(server.leaves.isNotEmpty() && server.leaves.all { it == null })
        assertNotEquals(k.id, made.id)
    }

    // After the invite started the form over, a certificate chosen on the
    // page goes with the join to a server that requires one, and the new
    // server keeps it across a restart.
    @Test
    fun aCertificateChosenOnThePageAfterAnInviteGoesWithTheJoinAndStays() = runTest {
        val server = serve()
        answerJoin(server)
        val accounts = accounts()
        val k = signIn(accounts, server.address)
        accounts.signOut()
        val form = SignInForm(accounts.last)
        assertTrue(form.takeJoinLink(inviteLink(server.address)))
        assertEquals(CertificateDraft.None, form.certificateDraft)
        form.useCertificate(PickedCertificate(a.path, PASSWORD, (accounts.openPicked(a.path, PASSWORD) as CertificateOpened.Found).certificate))
        assertEquals(CertificateDraft.Use(a.path, PASSWORD), form.certificateDraft)
        fillInvite(form)
        server.leaves.clear()
        val made = signUpFromForm(accounts, form)!!.connection.server
        assertEquals("alex", made.username)
        assertNotEquals(k.id, made.id)
        assertEquals(fileA, made.certificate)
        assertTrue(server.leaves.isNotEmpty() && server.leaves.all { it == rsa })
        val later = accounts()
        assertEquals(fileA, later.find(made.id)?.certificate)
        assertTrue(loads(later, made) is CertificateLoad.Found)
        assertEquals(CertSecret(a.path, PASSWORD), secrets.item(made.id))
    }

    // The usual http LAN home never stops k's certificate at its https main.
    @Test
    fun anHttpHomeDoesNotStopTheCertificateAfterASignOut() = runTest {
        val server = serve()
        val accounts = accounts()
        signIn(accounts, server.address, home = "http://127.0.0.1:1")
        accounts.signOut()
        val form = SignInForm(accounts.last)
        assertEquals("http://127.0.0.1:1", form.home)
        form.password = "pw"
        assertTrue(accounts.signIn(form.request()) is SignInOutcome.Done)
        assertEquals(rsa, server.lastLeaf)
    }

    @Test
    fun anInviteLinkWithAnHttpHomeToTheSameOriginSendsNone() = runTest {
        val server = serve(Auth.Wanted)
        answerJoin(server)
        val accounts = accounts()
        signIn(accounts, server.address)
        accounts.signOut()
        val form = SignInForm(accounts.last)
        assertTrue(form.takeJoinLink(inviteLink(server.address, home = "http://127.0.0.1:1")))
        fillInvite(form)
        server.leaves.clear()
        assertTrue(signUpFromForm(accounts, form) != null)
        assertTrue(server.leaves.isNotEmpty() && server.leaves.all { it == null })
    }

    // A link's https home on another host becomes part of the saved server;
    // the last server's certificate goes to neither address.
    @Test
    fun anInviteLinkWithAnHttpsHomeOnAnotherHostKeepsThatHomeAndNoCertificate() = runTest {
        val main = serve(Auth.Wanted)
        val home = serve(Auth.Wanted)
        answerJoin(main)
        val accounts = accounts()
        signIn(accounts, main.address)
        accounts.signOut()
        val form = SignInForm(accounts.last)
        val homeAddress = home.url("localhost").toString()
        assertTrue(form.takeJoinLink(inviteLink(main.address, home = homeAddress)))
        fillInvite(form)
        main.leaves.clear()
        val made = signUpFromForm(accounts, form)!!.connection.server
        assertNull(made.certificate)
        assertEquals(homeAddress.removeSuffix("/"), made.home?.removeSuffix("/"))
        assertTrue(main.leaves.all { it == null })
        assertTrue(home.leaves.all { it == null })
    }

    @Test
    fun anHttpMainWithAnHttpsHomeSendsTheCertificateAtTheHome() = runTest {
        val home = serve()
        val accounts = accounts()
        signIn(accounts, "http://127.0.0.1:1", home = home.address)
        accounts.signOut()
        home.leaves.clear()
        val form = SignInForm(accounts.last)
        form.password = "pw"
        assertTrue(accounts.signIn(form.request()) is SignInOutcome.Done)
        assertEquals(rsa, home.lastLeaf)
    }

    // Red when the row keeps the earlier drive words after a try that found
    // the file.
    @Test
    fun theRowTakesWhatTheTryFound() = runTest {
        val server = serve(Auth.Wanted)
        val accounts = accounts()
        val k = signIn(accounts, server.address)
        accounts.signOut()
        val form = SignInForm(accounts.last)
        val choice = CertificateChoice.Saved(k.id, fileA)
        form.certificateLoad = CertificateTry(choice, CertificateLoad.Unreadable(CertificateWords.notFound("rsa2048.p12"), again = false))
        server.answer("ping") { ClientAuthServer.body("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}""") }
        form.password = "wrong"
        val failed = accounts.signIn(form.request()) as SignInOutcome.Failed
        form.tried(failed.tried)
        assertEquals(choice, form.certificateLoad!!.choice)
        assertTrue(form.certificateLoad!!.load is CertificateLoad.Found)
    }

    // T with an http main, an https home and its file gone; signed in to at
    // its main with an http home: nothing is read and nothing said of it.
    @Test
    fun aRecordThatCannotSendItsCertificateIsNeverRead() = runTest {
        val main = plain()
        val home = serve(Auth.Wanted)
        val accounts = accounts()
        val t = signIn(accounts, main.address, home = home.address)
        assertEquals(fileA, t.certificate)
        assertTrue(a.delete())
        accounts.signOut()
        reads.clear()
        val done = accounts.signIn(SignInRequest(main.address, "winters", "pw", home = "http://127.0.0.1:1", certificate = CertificateDraft.Keep(t.id))) as SignInOutcome.Done
        assertTrue(reads.isEmpty())
        assertFalse(done.note.orEmpty().contains("rsa2048.p12"))
        assertEquals(fileA, done.connection.server.certificate)
    }

    // The join sheet opened while signed in: nothing of the server in use
    // but its certificate, which goes to that server's own origin and on to
    // the new server.
    @Test
    fun theSignedInJoinSheetCarriesTheCertificateOfTheServerInUse() = runTest {
        val server = serve()
        answerJoin(server)
        val accounts = accounts()
        val k = signIn(accounts, server.address)
        val form = SignInForm(certificateOf = k.id).also { assertTrue(it.takeJoinLink(inviteLink(server.address))) }
        assertEquals("", form.username)
        assertEquals("", form.label)
        assertTrue(form.headers.isEmpty())
        assertEquals(CertificateDraft.Keep(k.id), form.certificateDraft)
        fillInvite(form)
        val made = signUpFromForm(accounts, form)!!.connection.server
        assertTrue(server.leaves.all { it == rsa })
        assertTrue(loads(accounts(), made) is CertificateLoad.Found)
    }

    @Test
    fun anHttpKeptServerTypedWithHttpShowsTheNameAndReadsNothing() = runTest {
        val main = plain()
        val accounts = accounts()
        val k = signIn(accounts, main.address)
        assertEquals("a Use on http is still saved", fileA, k.certificate)
        assertEquals(CertSecret(a.path, PASSWORD), secrets.item(k.id))
        reads.clear()
        val form = SignInForm(k).also { it.keptId = k.id }
        form.password = "pw"
        assertEquals(CertificateShown.HttpsOnly("rsa2048.p12"), shown(accounts, form))
        assertTrue(accounts.signInAgain(k.id, form.request(), sameAccount = true) is SignInOutcome.Done)
        assertTrue(reads.isEmpty())
    }

    @Test
    fun afterASignOutAnotherHostWithHttpShowsNoneAndTheNewServerHasNone() = runTest {
        val main = plain()
        val accounts = accounts()
        signIn(accounts, main.address)
        accounts.signOut()
        val form = SignInForm(accounts.last)
        form.typeAddress(main.address.replace("localhost", "127.0.0.1"))
        form.password = "pw"
        assertEquals(CertificateShown.None, shown(accounts, form))
        assertNull((accounts.signIn(form.request()) as SignInOutcome.Done).connection.server.certificate)
    }

    @Test
    fun aFileChosenOnAnHttpServerIsSavedAndSentOnceItsAddressIsHttps() = runTest {
        val main = plain()
        val server = serve()
        val accounts = accounts()
        val k = signIn(accounts, main.address, certificate = CertificateDraft.None)
        accounts.edit(k.id, SignInRequest(main.address, "winters", "", certificate = CertificateDraft.Use(a.path, PASSWORD))) as SignInOutcome.Done
        val later = accounts()
        assertEquals(fileA, later.find(k.id)!!.certificate)
        assertEquals(CertSecret(a.path, PASSWORD), secrets.item(k.id))
        later.edit(k.id, SignInRequest(server.address, "winters", "", certificate = CertificateDraft.Keep(k.id))) as SignInOutcome.Done
        assertEquals(rsa, server.lastLeaf)
    }

    // k moved to http with its file gone: the start, a switch and a glance
    // read nothing, and nothing asks.
    @Test
    fun aServerMovedToHttpIsNeverReadForItsCertificate() = runTest {
        val main = plain()
        val server = serve()
        val accounts = accounts()
        val other = signIn(accounts, main.address, username = "brandon", certificate = CertificateDraft.None)
        val k = signIn(accounts, server.address)
        accounts.edit(k.id, SignInRequest(main.address, "winters", "", certificate = CertificateDraft.Keep(k.id))) as SignInOutcome.Done
        assertTrue(a.delete())
        reads.clear()
        val later = accounts()
        assertTrue(later.restore() != null)
        assertNull(later.takeStartAsk())
        assertTrue(later.switchTo(other.id) is SwitchOutcome.Done)
        val asked = main.calls.size
        assertEquals(Reach.Answers, later.check(k.id).reach)
        assertTrue(main.calls.size > asked)
        assertTrue(later.switchTo(k.id) is SwitchOutcome.Done)
        assertTrue(reads.isEmpty())
        val form = SignInForm(later.find(k.id)).also { it.keptId = k.id }
        assertEquals(CertificateShown.HttpsOnly("rsa2048.p12"), shown(later, form))
        assertTrue(reads.isEmpty())
    }

    @Test
    fun theSameHttpAddressAfterASignOutLandsOnItselfAndKeepsItsFile() = runTest {
        val main = plain()
        val server = serve()
        val accounts = accounts()
        val k = signIn(accounts, server.address)
        accounts.edit(k.id, SignInRequest(main.address, "winters", "", certificate = CertificateDraft.Keep(k.id))) as SignInOutcome.Done
        accounts.signOut()
        val form = SignInForm(accounts.last)
        form.password = "pw"
        assertEquals(CertificateShown.None, shown(accounts, form))
        val done = accounts.signIn(form.request()) as SignInOutcome.Done
        assertEquals(k.id, done.connection.server.id)
        assertEquals(fileA, done.connection.server.certificate)
        assertEquals(CertSecret(a.path, PASSWORD), secrets.item(k.id))
    }

    @Test
    fun theSameHostOnAnotherPortShowsAndSendsNone() = runTest {
        val server = serve()
        val otherPort = serve(Auth.Wanted)
        val accounts = accounts()
        signIn(accounts, server.address)
        accounts.signOut()
        val form = SignInForm(accounts.last)
        form.typeAddress(otherPort.address)
        form.password = "pw"
        assertEquals(CertificateShown.None, shown(accounts, form))
        accounts.signIn(form.request()) as SignInOutcome.Done
        assertNull(otherPort.lastLeaf)
    }

    @Test
    fun aHandOverToAnotherPortOfTheHostInUseSendsNone() = runTest {
        val server = serve()
        val otherPort = serve(Auth.Wanted)
        val accounts = accounts()
        val k = signIn(accounts, server.address)
        val form = SignInForm(certificateOf = k.id).also { it.take(handOver(otherPort, allow = null)) }
        assertNull(receiveFromForm(accounts, FamilyPlatform.Windows, form))
        assertTrue(otherPort.leaves.isNotEmpty() && otherPort.leaves.all { it == null })
    }

    // A sealed sign-in for another account at the last server: the page
    // starts over and adopts it, so the last server's certificate stays home.
    // The redeem, at k's own origin, still goes with k's certificate; the
    // sign-in after it goes with none.
    @Test
    fun aHandOverFromThePageToANewAccountAtTheLastOriginCarriesNothingOfIt() = runTest {
        val server = serve(Auth.Wanted)
        val accounts = accounts()
        val k = signIn(accounts, server.address)
        accounts.signOut()
        val form = SignInForm(accounts.last).also { it.take(handOver(server, HandOverSignIn("ana", "pw3", server = server.address))) }
        val made = receiveFromForm(accounts, FamilyPlatform.Windows, form)!!.connection.server
        assertNotEquals(k.id, made.id)
        assertEquals(CertificateDraft.None, form.certificateDraft)
        assertNull(made.certificate)
        assertNull(secrets.item(made.id))
        assertNull(server.lastLeaf)
        assertEquals("k keeps its own", fileA, accounts.find(k.id)?.certificate)
    }

    // The page row with keptId set and http typed on k's host shows what
    // signInAgain(k) sends: k's file kept, nothing sent.
    @Test
    fun thePageRowShowsWhatSignInAgainSends() = runTest {
        val server = serve()
        val accounts = accounts()
        val k = signIn(accounts, server.address)
        val form = SignInForm(k).also { it.keptId = k.id }
        form.typeAddress("http://127.0.0.1:${server.port}/")
        assertEquals(k.id, form.signsInAgainTo)
        assertEquals(CertificateShown.HttpsOnly("rsa2048.p12"), shown(accounts, form))
        assertEquals(CertificateChoice.None, accounts.certificateFor(form.certificateDraft, form.signsInAgainTo, form.url, form.homeUrl))
    }

    @Test
    fun anEditThatChangesTheHostKeepsAndSendsTheCertificate() = runTest {
        val server = serve()
        val accounts = accounts()
        val k = signIn(accounts, server.address)
        server.leaves.clear()
        val done = accounts.edit(k.id, SignInRequest(server.url("localhost").toString(), "winters", "", certificate = CertificateDraft.Keep(k.id))) as SignInOutcome.Done
        assertEquals(rsa, server.lastLeaf)
        assertEquals(fileA, done.connection.server.certificate)
    }

    // The start asks for a certificate it could not read: the page opens on
    // it for that server, keeping the saved password.
    @Test
    fun theStartOpensThePageOnACertificateItCouldNotRead() = runTest {
        val server = serve()
        val accounts = accounts()
        val k = signIn(accounts, server.address)
        assertTrue(a.delete())
        val settings = SettingsStore(settingsFile)
        val later = tlsParts(settings, secrets).first
        assertNull(later.restore())
        val http = OkHttpClient()
        val app = AppState(settings, later, http, scope, DesktopOs.Windows, lyricsLibrary = OnlineLyrics(http, "http://127.0.0.1:9/".toHttpUrl()), restored = null)
        val form = app.signInForm
        assertEquals(k.id, form.keptId)
        assertTrue(form.keepsSecret)
        assertTrue(form.advancedOpen && form.certificatePending)
        assertEquals(false to CertificateWords.notFound("rsa2048.p12"), form.result)
    }

    // Every SignInRequest the app itself makes says what it sends for the
    // certificate; only the two shortcuts Accounts keeps for tests and
    // scripts take the default.
    @Test
    fun everyRequestTheAppMakesNamesItsCertificate() {
        val main = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "desktop/src/main/kotlin") }.first(File::isDirectory)
        var seen = 0
        main.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText().replace("\r\n", "\n")
            var at = text.indexOf("SignInRequest(")
            while (at >= 0) {
                val before = text.substring(maxOf(0, at - 20), at)
                val declares = before.endsWith("class ")
                val shortcut = before.endsWith("test(") || before.endsWith("signIn(")
                val quoted = before.endsWith("\"")
                if (!declares && !shortcut && !quoted) {
                    seen++
                    assertTrue("${file.name}: ${text.substring(at, minOf(text.length, at + 80))}", call(text, at).contains("certificate ="))
                }
                at = text.indexOf("SignInRequest(", at + 1)
            }
        }
        assertEquals("SignInForm twice, JoinFlow once", 3, seen)
    }

    // The text of the call starting at `at`, to its closing bracket.
    private fun call(text: String, at: Int): String {
        var depth = 0
        for (i in at until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(at, i + 1)
            }
        }
        return text.substring(at)
    }
}
