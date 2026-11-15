package app.winters.octo.data

import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.MemoryPrefs
import app.winters.octo.connection.encodeConnection
import app.winters.octo.connection.sealed
import app.winters.octo.connection.startHome
import app.winters.octo.server.PasswordChange
import app.winters.octo.server.passwordChangeWords
import app.winters.octo.ui.common.Feedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

// A change of password runs where nothing on screen can cut it: its caller
// may go (a pop-up closed, a page left) once it is sent, and the phone still
// keeps the new password the server took; one on its way refuses a second;
// what it came to goes to its form while that form is drawn, and to the
// app's notice once it is gone. Both forms change through it, and their
// pop-up and sheet stay while it runs.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasswordChangesTest {
    private val server = MockWebServer()
    private val answers = HeldChange()
    private val feedback = Feedback()
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var vault: CredentialVault
    private lateinit var sessions: SessionRepository
    private lateinit var changes: PasswordChanges

    // Server "a" kept and in use at the test server, its password "pw"; the
    // main thread an executor of its own, so a wait on the test thread never
    // blocks the answer's hand-off to the form.
    @Before
    fun start() {
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        installTestKeystore()
        val address = server.startHome()
        server.dispatcher = answers
        vault = CredentialVault()
        val store = SessionStore(MemoryPrefs())
        val kept = StoredServer(
            serverUrl = address.toString(),
            username = "alice",
            passwordSealed = vault.seal("pw"),
            connection = encodeConnection(ConnectionSettings().sealed(vault::seal)),
            id = "a",
        )
        runBlocking { store.update { KeptServers(listOf(kept), active = "a") } }
        sessions = testSessions(store, vault)
        assertTrue(sessions.state.value is SessionState.SignedIn)
        changes = PasswordChanges(sessions, feedback)
    }

    // The server's close releases what it holds; a failed close never leaks
    // the main thread.
    @After
    fun stop() {
        try {
            server.close()
        } finally {
            Dispatchers.resetMain()
            main.close()
        }
    }

    private val words: (PasswordChange) -> String = { passwordChangeWords(it) }

    // The password kept for server "a", opened.
    private fun keptPassword(): String? {
        val kept = sessions.servers.value.find("a") ?: throw AssertionError("Server a is no longer kept")
        return vault.open(kept.passwordSealed)
    }

    // Waits until no change is on its way (at most 10 s; the row fails when
    // one never ends).
    private fun awaitNoChange() = runBlocking {
        withTimeoutOrNull(10.seconds) { changes.changing.first { !it } } ?: throw AssertionError("The change never ended")
    }

    // A change started from `caller`, as a form's own scope starts it, whose
    // form says it is gone.
    private fun startFrom(caller: CoroutineScope, new: String) =
        caller.launch { changes.change("pw", new, family = false, words = words) { _, _ -> false } }

    // The pop-up closes (its scope is cancelled) while the server holds the
    // change; once the server answers, the phone keeps the new password and
    // the notice says it changed. Red when the change runs in its caller: the
    // phone keeps the old password, and its next sign-in is refused.
    @Test
    fun aChangeWhoseCallerGoesKeepsTheNewPasswordAndSaysSo() {
        val caller = CoroutineScope(Dispatchers.Default + Job())
        startFrom(caller, "a new password")
        answers.awaitChange()
        runBlocking { caller.coroutineContext[Job]!!.cancelAndJoin() }
        answers.release()
        awaitNoChange()
        assertEquals("a new password", keptPassword())
        assertEquals(passwordChangeWords(PasswordChange.Changed), feedback.message.value?.text)
    }

    // Red when a second change goes out while one is on its way (Done tapped
    // twice, or another page's form): the server and the phone could end on
    // different passwords.
    @Test
    fun aSecondChangeWhileOneIsOnItsWaySendsNothing() {
        val caller = CoroutineScope(Dispatchers.Default + Job())
        startFrom(caller, "a new password")
        answers.awaitChange()
        assertTrue(changes.changing.value)
        val second = runBlocking {
            withTimeoutOrNull(2.seconds) { changes.change("pw", "another password", family = false, words = words) { _, _ -> true } }
        }
        assertEquals("A second change was sent", false, second)
        answers.release()
        awaitNoChange()
        assertEquals(1, answers.changes.get())
        assertEquals("a new password", keptPassword())
    }

    // What a change came to goes to its form while that form is drawn, and
    // to the notice once it is gone. Red when a refusal is lost with its
    // form, or said in the notice over a form that shows it.
    @Test
    fun aRefusalGoesToItsFormWhileItIsDrawnAndToTheNoticeOnceItIsGone() {
        answers.wrongCurrent = true
        val shown = mutableListOf<String>()
        runBlocking { changes.change("not it", "a new password", family = false, words = words) { _, said -> shown.add(said) } }
        assertEquals(listOf(passwordChangeWords(PasswordChange.WrongCurrent)), shown)
        assertNull(feedback.message.value)
        runBlocking { changes.change("not it", "a new password", family = false, words = words) { _, _ -> false } }
        assertEquals(passwordChangeWords(PasswordChange.WrongCurrent), feedback.message.value?.text)
        assertEquals(0, answers.changes.get())
        assertEquals("pw", keptPassword())
    }

    // Both forms change through the one change, which runs on its own scope
    // and refuses a second; their pop-up and sheet refuse to close while it
    // runs; a refusal reaches a form only while it is drawn; and nothing else
    // in the app asks the sessions for a new password. Red when a form runs
    // the change itself, its pop-up or sheet closes under it, or Done sends
    // a second.
    @Test
    fun bothFormsChangeThroughTheOneChangeOnItsOwnScope() {
        val own = source("data/PasswordChanges.kt")
        assertTrue(own.contains("if (!_changing.compareAndSet(false, true)) return false\n        scope.async {\n            try {\n                val result = sessions.changePassword(current, new, family)"))
        assertTrue(own.contains("            } finally {\n                _changing.value = false\n            }\n        }.await()"))
        val page = source("ui/settings/ServerPage.kt")
        listOf(
            "private val passwords: PasswordChanges,",
            "passwords.change(current, new, family = family.model.me?.managed == true, words = { passwordChangeWords(it) }, answered = answered)",
            "onDismiss = { if (!vm.changingPassword.value) changing = false },",
            "onBack = { vm.changingPassword.value },",
            "if (vm.changingPassword.value) return\n        passwordDraftProblem(draft)?.let { problem = it; return }",
            "scope.launch(start = CoroutineStart.UNDISPATCHED) {\n            vm.changePassword(sent.current, sent.new) { result, words ->\n                if (!drawn()) return@changePassword false",
            "val busy by vm.changingPassword.collectAsStateWithLifecycle()",
        ).forEach { assertTrue(it, page.contains(it)) }
        val family = source("ui/family/FamilyScreen.kt")
        listOf(
            "class FamilyShellViewModel @Inject constructor(val hub: FamilyHub, val passwords: PasswordChanges) : ViewModel()",
            "onDismiss = { if (!passwords.changing.value) changingPassword = false }",
            "passwords.change(current, new, family = model.me?.managed == true, words = { passwordChangeWords(it, family = true) }, answered = answered)",
        ).forEach { assertTrue(it, family.contains(it)) }
        val sheet = source("ui/family/FamilySheets.kt")
        listOf(
            "val leave = { if (!changing.value) close() }",
            "if (changing.value) return\n        passwordDraftProblem(draft)?.let { problem = it; return }",
            "scope.launch(start = CoroutineStart.UNDISPATCHED) {\n            change(sent.current, sent.new) answer@{ result, words ->\n                if (!drawn()) return@answer false",
            "close = leave, avatar = false)",
            "GlazeButton(\"Cancel\", leave,",
        ).forEach { assertTrue(it, sheet.contains(it)) }
        // The sign-in kept for the client takes the new password only while
        // its server is still the one in use.
        assertTrue(source("data/SessionRepository.kt").contains("if (this.current?.id == session.id) credentials = credentials?.let { Credentials(it.username, new, it.mode) }"))
        val callers = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "sessions.changePassword(" in it.readText() }
            .map { it.name }
            .toList()
        assertEquals(listOf("PasswordChanges.kt"), callers)
    }

    private fun source(path: String) = File("src/main/java/app/winters/octo/$path").readText().replace("\r\n", "\n")
}

// Answers every call ok, holding the change of password itself until
// release() (at most 10 s) and counting each one; with `wrongCurrent` its
// ping turns the typed current password away. Its own close releases it,
// since the server waits only 5 s for its connections as it closes.
private class HeldChange : Dispatcher() {
    private val gate = CountDownLatch(1)
    private val arrived = CountDownLatch(1)
    val changes = AtomicInteger()

    @Volatile var wrongCurrent = false

    override fun dispatch(request: RecordedRequest): MockResponse {
        when (request.url.encodedPath) {
            "/rest/changePassword" -> {
                changes.incrementAndGet()
                arrived.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
            "/rest/ping" -> if (wrongCurrent) return answer("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}""")
        }
        return answer("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""")
    }

    private fun answer(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    // Waits until the change has reached the server (at most 10 s; the row
    // fails when it never does).
    fun awaitChange() {
        if (!arrived.await(10, TimeUnit.SECONDS)) throw AssertionError("The change never reached the server")
    }

    fun release() = gate.countDown()

    override fun close() = gate.countDown()
}
