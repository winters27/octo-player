package app.winters.octo.desktop

import app.winters.octo.data.HeaderDraft
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.FakeSecrets
import app.winters.octo.desktop.server.ServerSecurity
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.server.SwitchOutcome
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.server.PasswordChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// The window and the accounts agree on the server in use whatever order
// changes finish in: an edit or switch begun before a later switch or
// sign-out never comes back after it. And every change of connection
// (edit, password, home route) fetches the queue afresh.
class ConnectionOrderTest {
    @get:Rule val temp = TemporaryFolder()

    private val home = FakeServer()
    private val work = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val player = CountingPlayer()
    private val elsewhere = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Holds a slow check until let go.
    private val release = CountDownLatch(1)

    private lateinit var settings: SettingsStore
    private lateinit var accounts: Accounts
    private lateinit var homeId: String
    private lateinit var workId: String

    @After
    fun stop() {
        release.countDown()
        scope.cancel()
        elsewhere.cancel()
        home.close()
        work.close()
    }

    // A player that counts how often it was asked to fetch its queue again.
    private class CountingPlayer(private val inner: SilentPlayer = SilentPlayer()) : DesktopPlayer by inner {
        @Volatile var reconnects = 0

        override fun reconnect() {
            reconnects++
        }
    }

    private fun library(server: FakeServer) {
        server.answerBy("ping") { request ->
            // An edit's new header value is checked slowly, until let go.
            if (request.headers["X-Token"] == "edited") release.await(5, TimeUnit.SECONDS)
            server.ok(type = "octo")
        }
        server.answer("getAlbumList2", """"albumList2":{}""")
        server.answer("getArtists", """"artists":{}""")
        server.answer("search3", """"searchResult3":{"song":[]}""")
        server.answer("getPlaylists", """"playlists":{"playlist":[]}""")
    }

    private fun app(): AppState {
        library(home)
        library(work)
        settings = SettingsStore(File(temp.root, "settings.json"), 0)
        val security = ServerSecurity(settings)
        val http = security.install(OkHttpClient.Builder()).build()
        accounts = Accounts(settings, FakeSecrets(), http, security)
        val first = runBlocking { accounts.signIn(SignInRequest(home.address, "winters", "pw", headers = listOf(HeaderDraft("X-Token", "home")))) } as SignInOutcome.Done
        homeId = first.connection.server.id
        workId = (runBlocking { accounts.add(SignInRequest(work.address, "brandon", "pw", headers = listOf(HeaderDraft("X-Token", "work")))) } as SignInOutcome.Saved).server.id
        val app = AppState(settings, accounts, http, scope, DesktopOs.Windows, player, OnlineLyrics(http, home.address.toHttpUrl()), restored = first.connection, listeningRoot = temp.root)
        waitFor { app.library?.index != null }
        return app
    }

    // The app on work, with `home` as its home address: home answers like
    // the server only while `homeUp` says so, and work takes password
    // changes. The home route is the one activate made.
    private fun routedApp(homeUp: AtomicBoolean): AppState {
        library(work)
        work.answer("changePassword")
        home.respond("ping") { if (homeUp.get()) home.okResponse(type = "octo") else MockResponse.Builder().code(503).build() }
        settings = SettingsStore(File(temp.root, "settings.json"), 0)
        val security = ServerSecurity(settings)
        val http = security.install(OkHttpClient.Builder()).build()
        accounts = Accounts(settings, FakeSecrets(), http, security)
        val first = runBlocking { accounts.signIn(SignInRequest(work.address, "winters", "pw", home = home.address)) } as SignInOutcome.Done
        workId = first.connection.server.id
        val app = AppState(settings, accounts, http, scope, DesktopOs.Windows, player, OnlineLyrics(http, work.address.toHttpUrl()), restored = first.connection, listeningRoot = temp.root)
        waitFor { app.library?.index != null }
        return app
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("waited too long", what())
    }

    // An edit of the home server with a new header value, its ticket taken
    // now; its check waits until let go, holding the accounts.
    private fun startEdit(): Deferred<SignInOutcome> {
        val ticket = accounts.ticket()
        val edit = elsewhere.async { accounts.edit(homeId, SignInRequest(home.address, "winters", "", headers = listOf(HeaderDraft("X-Token", "edited"))), generation = ticket) }
        waitFor { home.calls.any { it.headers["X-Token"] == "edited" } }
        return edit
    }

    // T16: an edit of home finishing after a switch to work leaves work in
    // use in the accounts, the shared client and the window. Red with no
    // lock in Accounts, or no generation check in reconnected.
    @Test
    fun anEditFinishingAfterASwitchLeavesTheOtherServerInUse() {
        val app = app()
        val edit = startEdit()
        app.switchTo(workId)
        release.countDown()
        val done = runBlocking { edit.await() } as SignInOutcome.Done
        waitFor { app.connection?.server?.id == workId }
        app.reconnected(done.connection)
        assertEquals(workId, app.connection!!.server.id)
        assertEquals(workId, accounts.active!!.id)
        work.calls.clear()
        runBlocking { app.connection!!.client.ping() }
        // The window reads work's library meanwhile, so every call there,
        // the ping among them, carries work's header.
        assertEquals(setOf("work"), work.calls.map { it.headers["X-Token"] }.toSet())
    }

    // Whether `what` comes true within a second and a half.
    private fun soon(what: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 1_500
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        return what()
    }

    // T16: a switch asked for while an edit holds the accounts waits its
    // turn. Work is not asked anything until the edit is done, then the
    // switch goes ahead and work is the one in use. Red when switchTo skips
    // the writer lock.
    @Test
    fun aSwitchWaitsForAnEditThatHoldsTheAccounts() {
        app()
        val edit = startEdit()
        val asked = work.calls.size
        val switched = elsewhere.async { accounts.switchTo(workId) }
        // A switch that went ahead would ask work within moments.
        assertFalse("The switch asked work while the edit held the accounts", soon { work.calls.size > asked })
        assertFalse(switched.isCompleted)
        release.countDown()
        assertTrue(runBlocking { edit.await() } is SignInOutcome.Done)
        assertTrue(runBlocking { switched.await() } is SwitchOutcome.Done)
        assertTrue(work.calls.size > asked)
        assertEquals(workId, accounts.active!!.id)
    }

    // An edit finishing after a sign-out stays signed out: saved, not made
    // the one in use, and still signed out. Red when Accounts.keep makes it
    // the one in use after a newer stop ticket, or clears a sign-out asked
    // for after the edit began.
    @Test
    fun anEditFinishingAfterASignOutStaysSignedOut() {
        val app = app()
        val edit = startEdit()
        app.signOut()
        release.countDown()
        val saved = runBlocking { edit.await() } as SignInOutcome.Saved
        assertTrue(saved.server.signedOut)
        assertNull(app.connection)
        assertNull(settings.current.activeServer)
        assertTrue(accounts.find(homeId)!!.signedOut)
    }

    // A switch begun before a sign-out finishes after it: signed out in the
    // accounts and the window. Red with no generation check in arrive, or
    // when a switch overtaken by a sign-out still makes its server the one
    // in use.
    @Test
    fun aSwitchFinishingAfterASignOutStaysSignedOut() {
        val app = app()
        val asked = CountDownLatch(1)
        work.answerBy("ping") {
            asked.countDown()
            release.await(5, TimeUnit.SECONDS)
            work.ok(type = "octo")
        }
        app.switchTo(workId)
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        app.signOut()
        release.countDown()
        waitFor { app.switching == null && accounts.active == null }
        assertNull(app.connection)
        assertNull(settings.current.activeServer)
    }

    // A sign-out from the window signs out the server it shows, not one an
    // earlier change made the one in use before the window moved to it;
    // that one is let go but keeps its password. Red when AppState.signOut
    // signs out whatever the accounts have in use.
    @Test
    fun aSignOutSignsOutTheServerTheWindowShows() {
        val app = app()
        assertTrue(runBlocking { accounts.switchTo(workId) } is SwitchOutcome.Done)
        app.signOut()
        waitFor { accounts.find(homeId)!!.signedOut }
        assertFalse(accounts.find(workId)!!.signedOut)
        assertNull(settings.current.activeServer)
    }

    // An edit of the server in use fetches the queue afresh. Red when
    // reconnected stops calling for it.
    @Test
    fun anEditOfTheServerInUseFetchesTheQueueAgain() {
        val app = app()
        release.countDown()
        val done = runBlocking { accounts.edit(homeId, SignInRequest(home.address, "winters", "", headers = listOf(HeaderDraft("X-Token", "edited")))) } as SignInOutcome.Done
        app.reconnected(done.connection)
        assertEquals(1, player.reconnects)
        assertSame(done.connection, app.connection)
    }

    // A password change through the app, and a real move of the
    // home route activate made, each fetch the queue afresh. The home
    // address starts out not answering, then answers, and a request that
    // cannot reach the main address asks the route to look again. Red when
    // AppState.changePassword stops calling for it, when activate's route no
    // longer tells onRouteMoved, or when AppState stops listening to it.
    @Test
    fun aPasswordChangeAndAMoveOfTheHomeRouteFetchTheQueueAgain() {
        val homeUp = AtomicBoolean(false)
        val app = routedApp(homeUp)
        // The sign-in's own look at the home address and the route's first
        // check have both been answered "not here".
        waitFor { home.calls.count { it.url.pathSegments.last() == "ping" } >= 2 }
        val before = player.reconnects
        val changed = runBlocking { app.changePassword(app.connection!!, "pw", "new") }
        assertEquals(PasswordChange.Changed, changed.result)
        assertEquals(before + 1, player.reconnects)
        homeUp.set(true)
        work.close()
        runCatching { runBlocking { app.connection!!.client.ping() } }
        waitFor { player.reconnects == before + 2 }
        assertEquals(home.address, app.connection!!.client.baseUrl.toString())
    }
}
