package app.winters.octo.desktop

import app.winters.octo.data.HeaderDraft
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.FakeSecrets
import app.winters.octo.desktop.server.ServerSecurity
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.lyrics.OnlineLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        assertEquals("work", work.calls.single().headers["X-Token"])
    }

    // T16: an edit finishing after a sign-out stays signed out. Red with no
    // generation check in reconnected.
    @Test
    fun anEditFinishingAfterASignOutStaysSignedOut() {
        val app = app()
        val edit = startEdit()
        app.signOut()
        release.countDown()
        val done = runBlocking { edit.await() } as SignInOutcome.Done
        waitFor { accounts.active == null }
        app.reconnected(done.connection)
        assertNull(app.connection)
        assertNull(settings.current.activeServer)
    }

    // T16: a switch begun before a sign-out finishes after it: signed out in
    // the accounts and the window. Red with no generation check in arrive,
    // or when a sign-out does not let go of a server made in use since.
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

    // T17: an edit of the server in use, a password change and a move of
    // the home route each fetch the queue afresh. Red when any of the three
    // calls goes.
    @Test
    fun aChangedConnectionPasswordOrRouteFetchesTheQueueAgain() {
        val app = app()
        release.countDown()
        val done = runBlocking { accounts.edit(homeId, SignInRequest(home.address, "winters", "", headers = listOf(HeaderDraft("X-Token", "edited")))) } as SignInOutcome.Done
        app.reconnected(done.connection)
        assertEquals(1, player.reconnects)
        assertSame(done.connection, app.connection)
        app.passwordChanged()
        assertEquals(2, player.reconnects)
        accounts.onRouteMoved!!.invoke()
        assertEquals(3, player.reconnects)
    }
}
