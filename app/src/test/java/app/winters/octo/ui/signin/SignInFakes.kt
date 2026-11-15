package app.winters.octo.ui.signin

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.connection.ConnectionSecurity
import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.JoinClients
import app.winters.octo.connection.MemoryPrefs
import app.winters.octo.connection.ServerClients
import app.winters.octo.connection.ServerHeader
import app.winters.octo.connection.encodeConnection
import app.winters.octo.connection.sealed
import app.winters.octo.connection.startHome
import app.winters.octo.connection.testDevices
import app.winters.octo.data.CredentialVault
import app.winters.octo.data.KeptServers
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionStore
import app.winters.octo.data.SignInError
import app.winters.octo.data.SignInRequest
import app.winters.octo.data.StoredServer
import app.winters.octo.data.installTestKeystore
import app.winters.octo.data.testSessions
import app.winters.octo.playback.SignInSwitch
import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.family.HandOverBox
import app.winters.octo.ui.family.HandOverSignIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

// One sign-in as the page asked the switch for it.
data class RecordedSignIn(val request: SignInRequest, val keptId: String?)

// The switch's sign-in, recording every call as it arrives. An unarmed call
// answers signed in at once; hold(username) arms only the next call for that
// username, which then waits until release(username, answer). A call moves
// its armed gate to held before it publishes its record, under one lock, so
// a row that has seen the call can always release it.
class GatedSignIns : SignInSwitch {
    private val lock = Any()
    private val armed = HashMap<String, ArrayDeque<CompletableDeferred<Pair<SignInError?, String?>>>>()
    private val held = HashMap<String, ArrayDeque<CompletableDeferred<Pair<SignInError?, String?>>>>()
    val calls = MutableStateFlow<List<RecordedSignIn>>(emptyList())

    fun hold(username: String) {
        synchronized(lock) { armed.getOrPut(username) { ArrayDeque() }.addLast(CompletableDeferred()) }
    }

    // Lets the oldest held call for `username` answer; the row fails when
    // none is held.
    fun release(username: String, answer: Pair<SignInError?, String?> = null to null) {
        val gate = synchronized(lock) { held[username]?.removeFirstOrNull() } ?: throw AssertionError("No sign-in for $username is held")
        gate.complete(answer)
    }

    override suspend fun signIn(request: SignInRequest, keptId: String?): Pair<SignInError?, String?> {
        val gate = synchronized(lock) {
            val next = armed[request.username]?.removeFirstOrNull()
            if (next != null) held.getOrPut(request.username) { ArrayDeque() }.addLast(next)
            calls.update { it + RecordedSignIn(request, keptId) }
            next
        }
        return gate?.await() ?: (null to null)
    }

    // The `nth` call for `username`, once it has arrived (at most 10 s; the
    // row fails when it never does).
    fun awaitCall(username: String, nth: Int = 1): RecordedSignIn = runBlocking {
        val all = withTimeoutOrNull(10.seconds) { calls.first { all -> all.count { it.request.username == username } >= nth } }
            ?: throw AssertionError("Sign-in $nth for $username never came")
        all.filter { it.request.username == username }[nth - 1]
    }

    fun callsFor(username: String): List<RecordedSignIn> = calls.value.filter { it.request.username == username }
}

// The app's own join clients, recording the connection each was made with.
// Each client is the real one, so the test server and Trust see a real
// client.
class RecordingJoins(context: Context) : JoinClients {
    private val real = ServerClients(context, testDevices())
    val made = MutableStateFlow<List<ConnectionSettings>>(emptyList())

    override fun forJoin(main: HttpUrl, settings: ConnectionSettings, seconds: Long): Pair<OkHttpClient, ConnectionSecurity> {
        made.update { it + settings }
        return real.forJoin(main, settings, seconds)
    }
}

// The family page's and the redeem's paths on the test server.
const val JOIN = "/api/family/join"
const val REDEEM = "/rest/redeemFamilySignIn"
const val POLL = "/rest/getFamilySignInRedeem"

fun subsonic(body: String): MockResponse = MockResponse.Builder()
    .addHeader("Content-Type", "application/json")
    .body("""{"subsonic-response":{"status":"ok","version":"1.16.1",$body}}""")
    .build()

const val REDEEMED = """"familySignInRedeemed":{"id":"r_1"}"""
const val EXPIRED = """"familySignInRedeem":{"state":"Expired"}"""
const val DENIED = """"familySignInRedeem":{"state":"Denied"}"""

fun allowed(box: String) = """"familySignInRedeem":{"state":"Allowed","box":"$box"}"""

// The family page's answer to an invite taken up.
fun joined(username: String): MockResponse = MockResponse.Builder()
    .addHeader("Content-Type", "application/json")
    .body("""{"username":"$username","role":"member"}""")
    .build()

// Authentik in front of the server, turning every app away: 401 and its page.
fun authentikPage(): MockResponse = MockResponse.Builder().code(401)
    .addHeader("Content-Type", "text/html; charset=utf-8")
    .body("<html><body aria-label=\"authentik\"></body></html>").build()

// Answers by path (404 for any other), holding `holding`'s answer until
// release(), at most 10 s. Its own close releases it, since the server waits
// only 5 s for its connections as it closes.
class PathAnswers(private val answers: Map<String, () -> MockResponse>, private val holding: String? = null) : Dispatcher() {
    private val gate = CountDownLatch(1)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        if (path == holding) gate.await(10, TimeUnit.SECONDS)
        return answers[path]?.invoke() ?: MockResponse.Builder().code(404).build()
    }

    fun release() = gate.countDown()

    override fun close() = gate.countDown()
}

// Every request answered by Authentik's page.
class AuthentikAnswers : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse = authentikPage()
}

// A kept server as the app keeps it: its password "pw" and its connection
// sealed in the vault.
data class Kept(
    val id: String,
    val address: String,
    val username: String = "alice",
    val home: String? = null,
    val headers: List<ServerHeader> = emptyList(),
) {
    fun stored(vault: CredentialVault) = StoredServer(
        serverUrl = address,
        username = username,
        passwordSealed = vault.seal("pw"),
        connection = encodeConnection(ConnectionSettings(home = home?.toHttpUrl(), headers = headers).sealed(vault::seal)),
        id = id,
    )
}

// What every family-link row runs on: a test server, the main thread as an
// executor of its own (so a wait on the test thread never blocks the view
// model's work), view models made through a store that stop() clears while
// Main is still that executor, the app's set of open sign-ins, and the
// recording fakes.
class JoinHarness {
    val server = MockWebServer()
    val context: Context = ApplicationProvider.getApplicationContext()
    val running = SignInRunning()
    val signIns = GatedSignIns()
    val joins = RecordingJoins(context)
    private val store = ViewModelStore()
    private var made = 0
    private lateinit var main: ExecutorCoroutineDispatcher
    lateinit var address: HttpUrl

    fun start() {
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        installTestKeystore()
        address = server.startHome()
    }

    // The server's close releases what it holds; the store's clear then
    // cancels every view model's work and runs its onCleared while Main is
    // still the executor; a failed close never leaks either.
    fun stop() {
        try {
            server.close()
        } finally {
            store.clear()
            Dispatchers.resetMain()
            main.close()
        }
    }

    // Clears every view model made so far.
    fun clear() = store.clear()

    // The test server's address as a link names it.
    val link: String get() = address.toString().removeSuffix("/")

    // A sign-in view model as the app makes it, over `sessions`: the switch
    // never built (only an edit or an add would reach it), the recording
    // sign-in and join fakes, and this harness's open set.
    fun form(sessions: SessionRepository = testSessions()): SignInViewModel =
        ViewModelProvider(store, viewModelFactory { initializer { SignInViewModel(sessions, notBuilt(), signIns, joins, testDevices(), running) } })
            .get("form-${made++}", SignInViewModel::class.java)

    // Sessions keeping `servers`, `active` the one in use.
    fun sessionsWith(vararg servers: Kept, active: String? = null): SessionRepository {
        val vault = CredentialVault()
        val kept = SessionStore(MemoryPrefs())
        runBlocking { kept.update { KeptServers(servers.map { it.stored(vault) }, active) } }
        return testSessions(kept, vault)
    }

    // A hand-over from another device of `signIn`, redeemed at `base`, and
    // the sealed box the test server hands over once allowed.
    fun handOver(
        signIn: HandOverSignIn,
        base: String = link,
        server: String = base,
        home: String? = null,
        username: String? = null,
        token: String = "handover-token",
    ): Pair<FamilyHandOverLink, String> {
        val key = HandOverBox.newKey()
        return FamilyHandOverLink(base = base, token = token, key = key, server = server, home = home, username = username) to HandOverBox.seal(signIn, key)
    }

    // The test server answering a hand-over at once: redeemed, then allowed.
    fun allowAtOnce(box: String): PathAnswers =
        PathAnswers(mapOf(REDEEM to { subsonic(REDEEMED) }, POLL to { subsonic(allowed(box)) })).also { server.dispatcher = it }

    // Waits for what `vm` started to finish (at most 10 s; the row fails when
    // it never does), leaving out its open-set marker, which never ends.
    fun settle(vm: SignInViewModel) = runBlocking {
        withTimeoutOrNull(10.seconds) { vm.viewModelScope.coroutineContext.job.children.filter { it !== vm.marking }.toList().joinAll() }
            ?: throw AssertionError("The form's request never finished")
    }

    // Waits until `vm` is in the open set, or out of it (at most 5 s; the row
    // fails when it never is). The set changes on the view model's thread, so
    // it is waited for, never read at once; a write made here reaches the
    // marker only once the snapshot is applied.
    fun awaitOpen(vm: SignInViewModel) {
        Snapshot.sendApplyNotifications()
        runBlocking { withTimeoutOrNull(5.seconds) { running.open.first { vm in it } } ?: throw AssertionError("The form never joined the open set") }
    }

    fun awaitClosed(vm: SignInViewModel) {
        Snapshot.sendApplyNotifications()
        runBlocking { withTimeoutOrNull(5.seconds) { running.open.first { vm !in it } } ?: throw AssertionError("The form never left the open set") }
    }

    // An invite's name and password, filled in.
    fun fillInvite(vm: SignInViewModel) {
        vm.inviteName = "Alice"
        vm.invitePassword = "a long password"
        vm.inviteAgain = "a long password"
    }

    // A form holding a finished sign-up whose sign-in step failed: the invite
    // taken, signed up as "alice-new", and the switch saying the password was
    // wrong.
    fun heldSignUp(vm: SignInViewModel) {
        server.dispatcher = PathAnswers(mapOf(JOIN to { joined("alice-new") }))
        vm.startJoin(FamilyInviteLink(server = link, token = "invite-token"))
        fillInvite(vm)
        signIns.hold("alice-new")
        vm.join()
        signIns.awaitCall("alice-new")
        signIns.release("alice-new", SignInError.Failed(SubsonicException.WrongCredentials("no")) to null)
        settle(vm)
    }
}
