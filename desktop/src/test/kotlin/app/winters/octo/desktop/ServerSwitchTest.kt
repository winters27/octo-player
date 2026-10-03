package app.winters.octo.desktop

import app.winters.octo.desktop.livelists.LiveListStore
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.search.commandsFor
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.FakeSecrets
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.name
import app.winters.octo.desktop.listening.listeningFolder
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.LiveListsJson
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import androidx.compose.runtime.snapshots.Snapshot

// Switching servers in the window: the library, queue, live lists and
// playlists follow the server in use, and what was playing stops plainly.
class ServerSwitchTest {
    @get:Rule val temp = TemporaryFolder()

    private val home = FakeServer()
    private val work = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val player = SilentPlayer()

    @After
    fun stop() {
        scope.cancel()
        home.close()
        work.close()
    }

    private fun song(id: String, title: String) = songJson(id, title)

    private fun library(server: FakeServer, songs: List<Pair<String, String>>, playlist: String) {
        server.answer("ping", type = "octo")
        server.answer("getAlbumList2", """"albumList2":{}""")
        server.answer("getArtists", """"artists":{}""")
        server.answer("search3", """"searchResult3":{"song":[${songs.joinToString(",") { (id, title) -> song(id, title) }}]}""")
        server.answer("getPlaylists", """"playlists":{"playlist":[{"id":"p1","name":"$playlist","owner":"winters","songCount":1}]}""")
    }

    private lateinit var accounts: Accounts
    private lateinit var workId: String

    private fun app(): AppState {
        library(home, listOf("h1" to "Home one", "h2" to "Home two"), "Home mix")
        library(work, listOf("w1" to "Work one"), "Work mix")
        val settings = SettingsStore(File(temp.root, "settings.json"), 0)
        val http = OkHttpClient()
        accounts = Accounts(settings, FakeSecrets(), http)
        val first = runBlocking { accounts.signIn(home.address, "winters", "pw") } as SignInOutcome.Done
        workId = (runBlocking { accounts.add(SignInRequest(work.address, "brandon", "pw")) } as SignInOutcome.Saved).server.id
        val app = AppState(settings, accounts, http, scope, DesktopOs.Windows, player, OnlineLyrics(http, home.address.toHttpUrl()), restored = first.connection, listeningRoot = temp.root)
        waitFor { app.library?.index != null && app.playlists.isNotEmpty() }
        return app
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("waited too long", what())
    }

    private fun AppState.onWork() = connection?.server?.id == workId

    @Test
    fun switchingBringsTheOtherServersLibraryAndPlaylists() {
        val app = app()
        assertEquals(listOf("h1", "h2"), app.library!!.index!!.songs.map { it.id })
        app.switchTo(workId)
        waitFor { app.onWork() && app.library?.index != null && app.playlists.firstOrNull()?.name == "Work mix" }
        assertEquals(listOf("w1"), app.library!!.index!!.songs.map { it.id })
        assertEquals("brandon", app.connection!!.client.username)
        assertEquals("Now on ${work.address.substringAfter("://").substringBefore(":")}.", app.notice)
    }

    @Test
    fun whatWasPlayingStopsAndSaysSoAndEachServerKeepsItsOwnQueue() {
        val app = app()
        app.play(app.library!!.index!!.songs, 1)
        assertTrue(player.state.value.playing)
        app.switchTo(workId)
        waitFor { app.onWork() }
        assertNull("the old server's song no longer plays", player.state.value.current)
        assertTrue(app.notice!!, app.notice!!.contains("stopped, and its queue is kept for when you come back"))
        // Home's queue was put away in its own folder.
        val homeQueue = File(listeningFolder(temp.root, "winters", home.address), "queue.json")
        assertTrue(homeQueue.readText().contains("Home two"))
        assertFalse(File(listeningFolder(temp.root, accounts.find(workId)!!), "queue.json").exists())
        // Back on home, its queue returns, paused where it was.
        val homeId = accounts.servers.first().id
        app.switchTo(homeId)
        waitFor { app.connection?.server?.id == homeId && player.state.value.current != null }
        assertEquals("h2", player.state.value.current!!.song.id)
        assertFalse(player.state.value.playing)
        assertTrue("nothing was playing on work", app.notice!!.startsWith("Now on"))
        assertFalse(app.notice!!.contains("stopped"))
    }

    // Another thread (or a frame) looking at the window at any moment of a
    // switch sees the old server, or the new one with its notice; never the
    // new one before its notice is there.
    @Test
    fun theNewServerNeverShowsWithoutItsNotice() {
        val app = app()
        val homeId = accounts.servers.first().id
        val wrong = CopyOnWriteArrayList<String>()
        val watching = AtomicBoolean(true)
        val watcher = thread {
            var switched = false
            while (watching.get()) {
                val snapshot = Snapshot.takeSnapshot()
                try {
                    snapshot.enter {
                        val on = app.connection?.server
                        val words = app.notice
                        if (on?.id == workId) switched = true
                        if (switched && on != null && words?.startsWith("Now on ${on.name}.") != true) wrong += "${on.name}: $words"
                    }
                } finally {
                    snapshot.dispose()
                }
            }
        }
        repeat(6) { i ->
            val to = if (i % 2 == 0) workId else homeId
            app.switchTo(to)
            waitFor { app.connection?.server?.id == to && app.switching == null }
        }
        watching.set(false)
        watcher.join()
        assertEquals(emptyList<String>(), wrong.distinct())
    }

    @Test
    fun eachServerKeepsItsOwnLiveLists() {
        val app = app()
        app.liveLists.save(LiveList.new("Home favorites", LibraryQuery(listOf(FilterPresets.Favourites)), 0, "hl"))
        app.switchTo(workId)
        waitFor { app.onWork() }
        assertTrue("work has none of home's", app.liveLists.lists.value.isEmpty())
        val homeFile = File(listeningFolder(temp.root, "winters", home.address), LiveListStore.FILE_NAME)
        waitFor { homeFile.exists() }
        assertEquals(listOf("Home favorites"), LiveListsJson.decode(homeFile.readText()).map { it.name })
        app.switchTo(accounts.servers.first().id)
        waitFor { !app.onWork() && app.liveLists.lists.value.isNotEmpty() }
        assertEquals(listOf("Home favorites"), app.liveLists.lists.value.map { it.name })
    }

    @Test
    fun aServerOutOfReachLeavesTheMusicPlaying() {
        val app = app()
        app.play(app.library!!.index!!.songs, 0)
        work.close()
        var answered = false
        app.switchTo(workId) { answered = true }
        waitFor { answered }
        assertFalse(app.onWork())
        assertTrue(player.state.value.playing)
        assertTrue(app.notice!!, app.notice!!.startsWith("Couldn't switch to"))
    }

    @Test
    fun switchingFromSettingsStaysOnSettings() {
        val app = app()
        app.navigator.go(Page.Settings)
        app.switchTo(workId, Page.Settings)
        waitFor { app.onWork() }
        assertEquals(Page.Settings, app.navigator.current.page)
    }

    @Test
    fun theCommandSearchOffersTheOtherServers() {
        val app = app()
        val workName = accounts.find(workId)!!.name
        val titles = commandsFor(app).map { it.title }
        assertTrue(titles.contains("Switch to $workName"))
        assertTrue(titles.contains("Add a server"))
        assertFalse("not the one in use", titles.any { it == "Switch to ${app.connection!!.server.name}" && workName != app.connection!!.server.name })
        commandsFor(app).first { it.title == "Switch to $workName" }.run()
        waitFor { app.onWork() }
    }

    @Test
    fun signingOutOfTheOneInUseKeepsTheOthersToOpen() {
        val app = app()
        app.signOut()
        assertNull(app.connection)
        assertEquals(2, accounts.servers.size)
        app.switchTo(workId)
        waitFor { app.onWork() }
        assertEquals(null, app.notice)
    }

    @Test
    fun removingTheOneInUseCanForgetWhatWasKeptHere() {
        val app = app()
        app.play(app.library!!.index!!.songs, 0)
        val homeId = accounts.servers.first().id
        val folder = listeningFolder(temp.root, accounts.find(homeId)!!)
        app.removeServer(homeId, forgetHere = true)
        assertNull(app.connection)
        waitFor { !folder.exists() }
        assertEquals(listOf(workId), accounts.servers.map { it.id })
    }

    @Test
    fun removingKeepsWhatWasKeptHereUnlessAsked() {
        val app = app()
        app.play(app.library!!.index!!.songs, 0)
        val homeId = accounts.servers.first().id
        val folder = listeningFolder(temp.root, accounts.find(homeId)!!)
        app.removeServer(homeId, forgetHere = false)
        waitFor { accounts.servers.size == 1 }
        assertTrue(File(folder, "queue.json").exists())
    }
}
