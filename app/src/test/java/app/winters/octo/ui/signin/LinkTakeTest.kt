package app.winters.octo.ui.signin

import app.winters.octo.connection.ServerHeader
import app.winters.octo.connection.SignInWords
import app.winters.octo.data.HeaderDraft
import app.winters.octo.data.SignInError
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.FamilySignInPrefill
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.gateway.basicCredentials
import app.winters.octo.ui.family.HandOverSignIn
import app.winters.octo.ui.nav.ServerForm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

// A family link taken into a form carries nothing of the last server to
// another one: a fresh form that held an address forgets every detail, a
// form for a kept server refuses another server or account (or, after its
// address was edited, any link), and a link that comes while the form is
// busy is refused with words under the address and nothing else changed. A
// hand-over's sealed sign-in is checked the same way before anything is
// sent with it. What a link may keep is kept: what was typed for its own
// server, a fitting link's saved details, a home certificate trusted. A held
// sign-up or received sign-in is never dropped or written over by a link, and
// a link note outlives the app's own writes.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkTakeTest {
    private val harness = JoinHarness()

    @Before
    fun start() = harness.start()

    @After
    fun stop() = harness.stop()

    private val wrong = SignInError.Failed(SubsonicException.WrongCredentials("no")) to null

    // Server A, kept with a header of its own, at the test server.
    private fun keptA() = Kept("a", harness.link, headers = listOf(ServerHeader("X-Token", "t")))

    // A form for kept server A of each kind: signing in again, editing it,
    // and editing the connection of the server in use.
    private fun keptForms(): List<Pair<String, SignInViewModel>> {
        val signIn = harness.form(harness.sessionsWith(keptA())).apply { start(ServerForm.SignIn, "a", null) }
        val edit = harness.form(harness.sessionsWith(keptA())).apply { start(ServerForm.Edit, "a", null) }
        val connection = harness.form(harness.sessionsWith(keptA(), active = "a")).apply { startEditing() }
        return listOf("sign in again" to signIn, "edit" to edit, "edit connection" to connection)
    }

    // A form holding a pin for `host`: its sign-in met an untrusted
    // certificate there, the person trusted it, and the sign-in failed after.
    private fun formWithPin(address: String, host: String): SignInViewModel {
        val vm = harness.form()
        vm.typeAddress(address)
        vm.username = "alice"
        vm.password = "pw"
        harness.signIns.hold("alice")
        vm.submit()
        harness.signIns.awaitCall("alice")
        harness.signIns.release("alice", SignInError.Untrusted(host, "ab12") to null)
        harness.settle(vm)
        harness.signIns.hold("alice")
        vm.trust()
        harness.signIns.awaitCall("alice", 2)
        harness.signIns.release("alice", wrong)
        harness.settle(vm)
        return vm
    }

    // Red when any per-server detail the join reads survives a link to
    // another server, or the join's connection still carries one.
    @Test
    fun aLinkToAnotherServerForgetsEveryDetailOfTheLast() {
        harness.server.dispatcher = PathAnswers(mapOf(JOIN to { authentikPage() }))
        val vm = harness.form()
        vm.typeAddress("https://a.example.com")
        vm.username = "carol"
        vm.password = "secret"
        vm.pickMethod(AuthMode.LegacyPassword)
        vm.apiKey = "key"
        vm.home = "192.168.1.20:4533"
        val row = vm.addHeader()
        vm.setHeaderName(row, "Authorization")
        vm.setHeaderValue(row, "Bearer a")
        vm.pickGateway(GatewayKind.Password)
        vm.gatewayFirst = "a-user"
        vm.gatewaySecond = "a-pass"
        vm.clientCert = "a-cert"
        vm.inviteName = "Old"
        vm.invitePassword = "old password"
        vm.inviteAgain = "old password"
        vm.startJoin(FamilyInviteLink(server = harness.link, token = "invite-token"))
        assertEquals(emptyList<HeaderDraft>(), vm.headers)
        assertEquals(GatewayKind.None, vm.gatewayKind)
        assertEquals(GatewayKind.None, vm.gatewaySaved)
        assertEquals("", vm.gatewayFirst)
        assertEquals("", vm.gatewaySecond)
        assertFalse(vm.legacyPassword)
        assertFalse(vm.useApiKey)
        assertEquals("", vm.apiKey)
        assertEquals("", vm.password)
        assertEquals("", vm.inviteName)
        assertEquals("", vm.invitePassword)
        assertEquals("", vm.inviteAgain)
        assertEquals("", vm.username)
        assertNull(vm.takesApiKeys)
        assertNull(vm.clientCert)
        assertEquals("", vm.home)
        assertEquals(harness.address, vm.url)
        harness.fillInvite(vm)
        vm.join()
        harness.settle(vm)
        val sent = harness.joins.made.value.single()
        assertEquals(emptyList<ServerHeader>(), sent.headers)
        assertNull(sent.clientCertAlias)
        assertEquals(emptyMap<String, String>(), sent.pins)
        assertNull(sent.home)
        // The sign-in page met at the new server asks for its own fields:
        // Try again sends those, and nothing of the last server.
        assertEquals(GatewayKind.Password, vm.gatewayKind)
        vm.gatewayFirst = "b-user"
        vm.gatewaySecond = "b-pass"
        vm.join()
        harness.settle(vm)
        val withFields = sent.copy(headers = listOf(ServerHeader("Authorization", basicCredentials("b-user", "b-pass"))))
        assertEquals(listOf(sent, withFields), harness.joins.made.value)
    }

    // Red when a form that never held an address forgets what was typed for
    // the server it is about to add.
    @Test
    fun aFormThatNeverHeldAnAddressKeepsWhatWasTyped() {
        val vm = harness.form()
        vm.home = "192.168.1.20:4533"
        val row = vm.addHeader()
        vm.setHeaderName(row, "X-Token")
        vm.setHeaderValue(row, "t")
        vm.startJoin(FamilyInviteLink(server = harness.link, token = "invite-token"))
        assertEquals("192.168.1.20:4533", vm.home)
        assertEquals(listOf("X-Token"), vm.headers.map { it.name })
    }

    // Red when the same server's link again forgets what was typed for it
    // (a renewed code would lose it), or a new home address keeps it.
    @Test
    fun aSecondLinkToTheSameServerKeepsAndANewHomeForgets() {
        val vm = harness.form()
        val home = "http://192.168.1.20:4533"
        vm.startJoin(FamilyInviteLink(server = harness.link, token = "invite-token", home = home))
        val row = vm.addHeader()
        vm.setHeaderName(row, "X-Token")
        vm.setHeaderValue(row, "t")
        vm.startJoin(FamilyInviteLink(server = harness.link, token = "invite-token", home = home))
        assertEquals(listOf("X-Token"), vm.headers.map { it.name })
        vm.startJoin(FamilyInviteLink(server = harness.link, token = "invite-token", home = "http://192.168.1.21:4533"))
        assertEquals(emptyList<HeaderDraft>(), vm.headers)
        assertEquals("http://192.168.1.21:4533", vm.home)
    }

    // Red when a form for a kept server takes another server's link, an
    // invite, another account's link or any link after its address was
    // edited, or loses its details or address to a link that fits it.
    @Test
    fun aFormForAKeptServerTakesOnlyALinkThatFitsIt() {
        for ((kind, vm) in keptForms()) {
            val address = vm.address
            vm.startJoin(FamilySignInPrefill(server = "https://b.example.com", username = "alice"))
            assertEquals(kind, SignInWords.LINK_FOR_ANOTHER_SERVER, vm.linkNote)
            assertEquals(kind, address, vm.address)
            vm.startJoin(FamilyInviteLink(server = harness.link, token = "invite-token"))
            assertEquals(kind, SignInWords.LINK_FOR_ANOTHER_ACCOUNT, vm.linkNote)
            assertNull(kind, vm.invite)
            vm.startJoin(FamilySignInPrefill(server = harness.link, username = "bob"))
            assertEquals(kind, SignInWords.LINK_FOR_ANOTHER_ACCOUNT, vm.linkNote)
            assertEquals(kind, "alice", vm.username)
            vm.startJoin(FamilySignInPrefill(server = harness.link, username = "alice"))
            assertNull(kind, vm.linkNote)
            assertEquals(kind, address, vm.address)
            assertEquals(kind, listOf("X-Token"), vm.headers.map { it.name })
            vm.typeAddress("https://c.example.com")
            vm.startJoin(FamilySignInPrefill(server = harness.link, username = "alice"))
            assertEquals(kind, SignInWords.LINK_AFTER_ADDRESS_CHANGE, vm.linkNote)
            assertEquals(kind, "c.example.com", vm.address)
        }
    }

    // A link while the form signs in, or while a certificate question waits,
    // is refused with the busy words under the address, and nothing else
    // changes; the next link taken clears the words, and so does an edit of
    // the address. Red when a running request's form is changed by a link,
    // or the words go to the error line.
    @Test
    fun aLinkWhileBusyOrAskingIsRefusedAndChangesNothing() {
        val vm = harness.form()
        vm.typeAddress(harness.link)
        vm.username = "alice"
        vm.password = "pw"
        harness.signIns.hold("alice")
        vm.submit()
        harness.signIns.awaitCall("alice")
        val address = vm.address
        vm.startJoin(FamilySignInPrefill(server = "https://b.example.com", username = "bob"))
        assertEquals(SignInWords.LINK_WHILE_BUSY, vm.linkNote)
        assertEquals(address, vm.address)
        assertEquals("alice", vm.username)
        assertEquals("pw", vm.password)
        assertNull(vm.error)
        harness.signIns.release("alice", SignInError.Untrusted("127.0.0.1", "ab12") to null)
        harness.settle(vm)
        assertNotNull(vm.question)
        vm.startJoin(FamilySignInPrefill(server = "https://b.example.com", username = "bob"))
        assertEquals(SignInWords.LINK_WHILE_BUSY, vm.linkNote)
        assertEquals(address, vm.address)
        assertNull(vm.error)
        vm.distrust()
        vm.startJoin(FamilySignInPrefill(server = harness.link, username = "alice"))
        assertNull(vm.linkNote)
        vm.password = "pw"
        harness.signIns.hold("alice")
        vm.submit()
        harness.signIns.awaitCall("alice", 2)
        vm.startJoin(FamilySignInPrefill(server = "https://b.example.com", username = "bob"))
        assertEquals(SignInWords.LINK_WHILE_BUSY, vm.linkNote)
        harness.signIns.release("alice", wrong)
        harness.settle(vm)
        vm.typeAddress("music.example.com")
        assertNull(vm.linkNote)
    }

    // Red when a pin trusted for the last server reaches another one.
    @Test
    fun aPinForTheLastServerIsGoneAfterALinkToAnother() {
        val vm = formWithPin("https://a.example.com", "a.example.com")
        harness.server.dispatcher = PathAnswers(mapOf(JOIN to { authentikPage() }))
        vm.startJoin(FamilyInviteLink(server = harness.link, token = "invite-token"))
        harness.fillInvite(vm)
        vm.join()
        harness.settle(vm)
        assertEquals(emptyMap<String, String>(), harness.joins.made.value.last().pins)
    }

    // The person typed the server's address and filled in its sign-in page,
    // then took its invite: the fields stay and go with the sign-up. Red when
    // a link to the server typed forgets what was typed for it.
    @Test
    fun aTypedServersSignInPageFieldsGoWithItsInvite() {
        harness.server.dispatcher = AuthentikAnswers()
        val vm = harness.form()
        vm.typeAddress(harness.link)
        vm.pickGateway(GatewayKind.Password)
        vm.gatewayFirst = "alice"
        vm.gatewaySecond = "app-pass"
        vm.startJoin(FamilyInviteLink(server = harness.link, token = "invite-token"))
        assertEquals("alice", vm.gatewayFirst)
        harness.fillInvite(vm)
        vm.join()
        harness.settle(vm)
        assertEquals(basicCredentials("alice", "app-pass"), harness.server.takeRequest(10, TimeUnit.SECONDS)!!.headers["Authorization"])
    }

    // Red when the same invite opened again throws away the sign-up it made
    // (an invite works once; Try again would sign up again and be refused).
    @Test
    fun theSameInviteAgainKeepsItsSignUp() {
        val vm = harness.form()
        harness.heldSignUp(vm)
        assertTrue(vm.holdsSignUp)
        val sent = harness.server.requestCount
        vm.startJoin(FamilyInviteLink(server = harness.link, token = "invite-token"))
        assertTrue(vm.holdsSignUp)
        vm.join()
        harness.signIns.awaitCall("alice-new", 2)
        harness.settle(vm)
        assertEquals(sent, harness.server.requestCount)
        assertTrue(vm.signedIn)
    }

    // While a sign-up is held, an invite with its token for another server
    // is refused with the open sign-in's words, and nothing of the form
    // changes: the address, the home, the invite and the sign-up stay, and
    // Sign up again signs in with it, never signing up again. Red when the
    // link drops the sign-up (an invite works once, so the person is locked
    // out of it) or writes the other server's address over it.
    @Test
    fun theSameTokenForAnotherServerIsRefusedAndKeepsTheSignUp() {
        val vm = harness.form()
        harness.heldSignUp(vm)
        keepsItsSignUpThrough(vm, FamilyInviteLink(server = "https://b.example.com", token = "invite-token"))
    }

    // Red when another invite for the same server drops the held sign-up or
    // takes its place.
    @Test
    fun anotherTokenForTheSameServerIsRefusedAndKeepsTheSignUp() {
        val vm = harness.form()
        harness.heldSignUp(vm)
        keepsItsSignUpThrough(vm, FamilyInviteLink(server = harness.link, token = "another-token"))
    }

    // `link`, refused over the sign-up `vm` holds, which then signs in as it
    // was.
    private fun keepsItsSignUpThrough(vm: SignInViewModel, link: FamilyInviteLink) {
        assertTrue(vm.holdsSignUp)
        val address = vm.address
        val home = vm.home
        val invite = vm.invite
        val sent = harness.server.requestCount
        vm.startJoin(link)
        assertEquals(SignInWords.LINK_WHILE_SIGN_IN_OPEN, vm.linkNote)
        assertTrue(vm.holdsSignUp)
        assertEquals(address, vm.address)
        assertEquals(home, vm.home)
        assertEquals(invite, vm.invite)
        vm.join()
        val call = harness.signIns.awaitCall("alice-new", 2)
        harness.settle(vm)
        assertEquals(harness.address.toString(), call.request.address)
        assertEquals(sent, harness.server.requestCount)
        assertTrue(vm.signedIn)
    }

    // While a received sign-in is held (its sign-in step failed), another
    // hand-over is refused with the open sign-in's words and the held one
    // stays: Try again signs in with it, with no redeem. Red when a link
    // drops it (its token is spent, so the other device would have to allow
    // again) or takes its place.
    @Test
    fun aLinkOverAHeldReceivedSignInIsRefusedAndKeepsIt() {
        val vm = harness.form()
        val (link, box) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = harness.link))
        harness.allowAtOnce(box)
        harness.signIns.hold("alice")
        vm.startJoin(link)
        harness.signIns.awaitCall("alice")
        harness.signIns.release("alice", wrong)
        harness.settle(vm)
        assertTrue(vm.holdsReceived)
        val sent = harness.server.requestCount
        val (other, _) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = harness.link), token = "another-token")
        vm.startJoin(other)
        assertEquals(SignInWords.LINK_WHILE_SIGN_IN_OPEN, vm.linkNote)
        assertTrue(vm.holdsReceived)
        assertEquals(link, vm.handOver)
        vm.receive()
        harness.signIns.awaitCall("alice", 2)
        harness.settle(vm)
        assertEquals(sent, harness.server.requestCount)
        assertTrue(vm.signedIn)
    }

    // A held sign-up signs in at the address it was made for, so the address
    // takes no edit while it is held: neither typing in its field nor the
    // scheme button; once the link is left, both work again. Red when the
    // model takes either (the field would show one address while the sign-in
    // goes to another).
    @Test
    fun aHeldSignUpsAddressTakesNoEdit() {
        val vm = harness.form()
        harness.heldSignUp(vm)
        val address = vm.address
        val scheme = vm.scheme
        vm.typeAddressFromField("https://b.example.com")
        vm.toggleScheme()
        assertEquals(address, vm.address)
        assertEquals(scheme, vm.scheme)
        vm.stopJoin()
        vm.toggleScheme()
        assertNotEquals(scheme, vm.scheme)
        vm.typeAddressFromField("https://b.example.com")
        assertEquals("b.example.com", vm.address)
    }

    // A link refused while a home QR's hand-over waits for Allow says so
    // under the address, and the words stay through the address and home
    // its sealed sign-in then writes in, the sign-in failing after. Red when
    // the app's own writes wipe the note.
    @Test
    fun aNoteSetWhileAHomeQrIsAllowedOutlivesItsAdoption() {
        val vm = harness.form()
        val (link, box) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = "https://music.example.com", home = harness.link), home = harness.link)
        val answers = PathAnswers(mapOf(REDEEM to { subsonic(REDEEMED) }, POLL to { subsonic(allowed(box)) }), holding = POLL)
        harness.server.dispatcher = answers
        harness.signIns.hold("alice")
        vm.startJoin(link)
        takeOne()
        takeOne()
        vm.startJoin(FamilySignInPrefill(server = "https://b.example.com", username = "bob"))
        assertEquals(SignInWords.LINK_WHILE_BUSY, vm.linkNote)
        answers.release()
        harness.signIns.awaitCall("alice")
        assertEquals("music.example.com", vm.address)
        harness.signIns.release("alice", wrong)
        harness.settle(vm)
        assertEquals(SignInWords.LINK_WHILE_BUSY, vm.linkNote)
    }

    // A link refused while a sign-up runs, after its address was edited,
    // keeps its words when the sign-up puts back the address it was made
    // for. Red when that write wipes the note.
    @Test
    fun aNoteSetWhileASignUpRunsOutlivesItsAddressPutBack() {
        val signUp = PathAnswers(mapOf(JOIN to { joined("alice-new") }), holding = JOIN)
        harness.server.dispatcher = signUp
        val vm = harness.form()
        vm.startJoin(FamilyInviteLink(server = harness.link, token = "invite-token"))
        harness.fillInvite(vm)
        harness.signIns.hold("alice-new")
        vm.join()
        takeOne()
        vm.typeAddressFromField("https://c.example.com")
        vm.startJoin(FamilySignInPrefill(server = "https://b.example.com", username = "bob"))
        assertEquals(SignInWords.LINK_WHILE_BUSY, vm.linkNote)
        signUp.release()
        harness.signIns.awaitCall("alice-new")
        harness.signIns.release("alice-new", wrong)
        harness.settle(vm)
        assertEquals(harness.address, vm.url)
        assertEquals(SignInWords.LINK_WHILE_BUSY, vm.linkNote)
    }

    private fun takeOne() = harness.server.takeRequest(10, TimeUnit.SECONDS) ?: throw AssertionError("no request came within 10 s")

    // A hand-over that fits a kept server's form signs in to that server
    // again (by its id, at its own addresses, with its saved details), while
    // the form is busy, from every kind of kept form. Red when the received
    // sign-in is refused as busy, or signs in as a new server.
    @Test
    fun aFittingHandOverSignsInToTheKeptServerAgain() {
        for ((kind, vm) in keptForms()) {
            val (link, box) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = harness.link), username = "alice")
            harness.allowAtOnce(box)
            harness.signIns.hold("alice")
            val before = harness.signIns.callsFor("alice").size
            vm.startJoin(link)
            val call = harness.signIns.awaitCall("alice", before + 1)
            assertTrue(kind, vm.busy)
            assertEquals(kind, "a", call.keptId)
            assertEquals(kind, harness.address.toString(), call.request.address)
            assertEquals(kind, listOf("X-Token"), call.request.headers.map { it.name })
            harness.signIns.release("alice")
            harness.settle(vm)
            assertTrue(kind, vm.signedIn)
        }
    }

    // A sealed sign-in for another server in a kept server's form is
    // refused: no sign-in, the hand-over left, its words under the address.
    // Red when the received check is skipped (A's details and its id would go
    // to the other server).
    @Test
    fun aSealedSignInForAnotherServerMakesNoSignIn() {
        val (_, vm) = keptForms().first()
        val (link, box) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = "https://c.example.com"))
        harness.allowAtOnce(box)
        vm.startJoin(link)
        harness.settle(vm)
        assertEquals(emptyList<RecordedSignIn>(), harness.signIns.callsFor("alice"))
        assertEquals(SignInWords.LINK_FOR_ANOTHER_SERVER, vm.linkNote)
        assertFalse(vm.joining)
        assertNull(vm.handOver)
    }

    // After the address of a kept server's form is edited, Try again with
    // the sign-in already received makes no sign-in, and a hand-over for
    // another server after the edit is refused the same way. Red
    // when an edited kept form forgets and adopts.
    @Test
    fun anEditedKeptFormNeverSignsAReceivedSignInIn() {
        val (_, vm) = keptForms().first()
        val (link, box) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = harness.link), username = "alice")
        harness.allowAtOnce(box)
        harness.signIns.hold("alice")
        vm.startJoin(link)
        harness.signIns.awaitCall("alice")
        harness.signIns.release("alice", wrong)
        harness.settle(vm)
        vm.typeAddress("https://d.example.com")
        vm.receive()
        harness.settle(vm)
        assertEquals(1, harness.signIns.callsFor("alice").size)
        assertEquals(SignInWords.LINK_AFTER_ADDRESS_CHANGE, vm.linkNote)
        val (other, _) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = "https://c.example.com"), base = "https://c.example.com")
        vm.startJoin(other)
        assertEquals(SignInWords.LINK_AFTER_ADDRESS_CHANGE, vm.linkNote)
        assertNull(vm.handOver)
    }

    // A fresh form's sign-in page fields, typed after the redeem met a sign-in
    // page, are not sent with a sealed sign-in for a server the form never
    // went to. Red when adoption keeps them.
    @Test
    fun fieldsTypedForTheRedeemNeverGoToAnotherServer() {
        harness.server.dispatcher = AuthentikAnswers()
        val vm = harness.form()
        val (link, box) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = "https://elsewhere.example.com"))
        vm.startJoin(link)
        harness.settle(vm)
        assertEquals(Gateway.Authentik, vm.gateway)
        vm.gatewayFirst = "alice"
        vm.gatewaySecond = "app-pass"
        harness.allowAtOnce(box)
        vm.receive()
        val call = harness.signIns.awaitCall("alice")
        harness.settle(vm)
        assertEquals("https://elsewhere.example.com", call.request.address)
        assertEquals(GatewayKind.None, call.request.gateway.kind)
        assertEquals("", call.request.gateway.first)
        assertEquals("", call.request.gateway.second)
        assertEquals("elsewhere.example.com", vm.address)
    }

    // A home QR from the person's own device: its link names the home address,
    // its sealed sign-in the outside address and that home. The form adopts
    // the outside server, and the certificate trusted at home stays trusted
    // for it. Red when adoption drops every pin, or the received sign-in is
    // never checked (nothing is adopted and the form keeps showing the home).
    @Test
    fun aHomeQrKeepsTheHomesPin() {
        val vm = formWithPin(harness.link, "127.0.0.1")
        val (link, box) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = "https://music.example.com", home = harness.link), home = harness.link)
        harness.allowAtOnce(box)
        vm.startJoin(link)
        val call = harness.signIns.awaitCall("alice", 3)
        harness.settle(vm)
        assertEquals("https://music.example.com", call.request.address)
        assertEquals(mapOf("127.0.0.1" to "ab12"), call.request.pins)
        assertEquals("music.example.com", vm.address)
        assertEquals(harness.link, vm.home)
    }

    // After adoption, a sign-in step that meets Cloudflare Access opens its
    // fields there; filled in, Try again sends them, and nothing is forgotten
    // again. Red when the attempt is not re-tagged with the adopted address
    // (the page met would be dropped), Try again forgets, or the received
    // sign-in is never checked (nothing is adopted, so the form shows the
    // link's server while the sign-in goes to another).
    @Test
    fun anAdoptedServersSignInPageOpensAndTryAgainSendsItsFields() {
        val vm = harness.form()
        val (link, box) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = "https://music.example.com"))
        harness.allowAtOnce(box)
        harness.signIns.hold("alice")
        vm.startJoin(link)
        harness.signIns.awaitCall("alice")
        harness.signIns.release("alice", SignInError.Failed(SubsonicException.NotSubsonic("HTTP 403 from ping", 403, Gateway.CloudflareAccess, false)) to null)
        harness.settle(vm)
        assertEquals("music.example.com", vm.address)
        assertTrue(vm.advancedOpen)
        assertEquals(GatewayKind.CloudflareToken, vm.gatewayKind)
        vm.gatewayFirst = "id.access"
        vm.gatewaySecond = "s3cret"
        vm.receive()
        val again = harness.signIns.awaitCall("alice", 2)
        harness.settle(vm)
        assertEquals("https://music.example.com", again.request.address)
        assertEquals("id.access", again.request.gateway.first)
        assertEquals("s3cret", again.request.gateway.second)
        assertEquals(GatewayKind.CloudflareToken, vm.gatewayKind)
    }

    // A server kept under its home address only signs in again from its home
    // QR (the sealed sign-in names the outside address and that home). Red
    // when the received check compares only the server it names.
    @Test
    fun aServerKeptUnderItsHomeOnlySignsInAgainFromItsHomeQr() {
        val vm = harness.form(harness.sessionsWith(Kept("a", harness.link)))
        vm.start(ServerForm.SignIn, "a", null)
        val (link, box) = harness.handOver(HandOverSignIn(username = "alice", secret = "pw", server = "https://music.example.com", home = harness.link), home = harness.link)
        harness.allowAtOnce(box)
        vm.startJoin(link)
        val call = harness.signIns.awaitCall("alice")
        harness.settle(vm)
        assertEquals("a", call.keptId)
        assertEquals(harness.address.toString(), call.request.address)
        assertTrue(vm.signedIn)
    }
}
