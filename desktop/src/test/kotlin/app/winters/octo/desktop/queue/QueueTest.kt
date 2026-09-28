package app.winters.octo.desktop.queue

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.player.PlayerState
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.desktop.player.RepeatMode
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.songJson
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
import kotlin.random.Random

class QueueTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 0L
    private val songs = (1..5).map { Song("s$it", "Song $it", duration = 100) }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    // ---- The queue on this computer ----

    @Test
    fun aShortQueueIsSavedWholeWithItsPlayOrder() {
        val player = SilentPlayer(clock = { now }, random = Random(4))
        player.play(songs, 1, shuffle = true)
        val saved = queueFileOf(player.state.value)!!
        assertEquals(songs.map { it.id }, saved.songs.map { it.id })
        assertEquals(1, saved.index)
        assertEquals(1, saved.order.first())
        assertTrue(saved.shuffle)
    }

    @Test
    fun aVeryLongQueueKeepsAStretchAroundTheSongPlaying() {
        val many = (1..100).map { QueueEntry(it.toLong(), Song("s$it")) }
        val state = PlayerState(queue = many, played = many.take(59), current = many[59], upcoming = many.drop(60))
        val saved = queueFileOf(state, max = 20)!!
        assertEquals(20, saved.songs.size)
        assertEquals("s60", saved.songs[saved.index].id)
        assertEquals(saved.songs.indices.toList(), saved.order)
    }

    @Test
    fun theQueueComesBackAfterARestartPausedWhereItWas() = runBlocking {
        val folder = File(temp.root, "account")
        val first = SilentPlayer(clock = { now })
        first.play(songs, 2)
        first.setRepeat(RepeatMode.All)
        now += 42_000
        QueueKeeper(first, scope, Dispatchers.Unconfined).apply { this.folder = folder }.saveNow()

        val second = SilentPlayer(clock = { now })
        val keeper = QueueKeeper(second, scope, Dispatchers.Unconfined).apply { this.folder = folder }
        assertNotNull(keeper.restore())
        val state = second.state.value
        assertEquals("s3", state.current?.song?.id)
        assertFalse(state.playing)
        assertEquals(RepeatMode.All, state.repeat)
        assertEquals(42_000, second.positionMs())
    }

    @Test
    fun aPlayerThatAlreadyHasMusicIsLeftAlone() = runBlocking {
        val folder = File(temp.root, "account")
        val first = SilentPlayer(clock = { now })
        first.play(songs)
        QueueKeeper(first, scope, Dispatchers.Unconfined).apply { this.folder = folder }.saveNow()
        val second = SilentPlayer(clock = { now })
        second.play(listOf(Song("x", "X", duration = 10)))
        assertNull(QueueKeeper(second, scope, Dispatchers.Unconfined).apply { this.folder = folder }.restore())
        assertEquals("x", second.state.value.current?.song?.id)
    }

    // ---- The queue on the server ----

    @Test
    fun songsOpenedFromFilesAreLeftOutOfTheServersQueue() {
        val entries = listOf(QueueEntry(1, Song("s1")), QueueEntry(2, Song("file:abc")), QueueEntry(3, Song("s3")))
        val onServer = serverQueueOf(PlayerState(queue = entries, played = entries.take(1), current = entries[1], upcoming = entries.drop(2)), 30_000)!!
        assertEquals(listOf("s1", "s3"), onServer.ids)
        assertEquals("the next server song takes the place, from its start", 1, onServer.index)
        assertEquals(0, onServer.positionMs)
    }

    private fun sync(player: SilentPlayer, extensions: List<String> = emptyList()): ServerQueueSync {
        val connection = server.connection(extensions)
        val settings = SettingsStore(File(temp.root, "settings.json"), 0)
        return ServerQueueSync(player, settings, { connection }, scope, Dispatchers.Unconfined, saveAfterMs = 0, now = { 1_000_000 }).apply {
            folder = File(temp.root, "account")
        }
    }

    @Test
    fun aChangedQueueIsSavedOnTheServer() {
        server.answer("savePlayQueueByIndex")
        server.answer("getPlayQueueByIndex")
        val player = SilentPlayer(clock = { now })
        sync(player, listOf("indexBasedQueue:1")).start()
        player.play(songs, 1)
        waitFor { "savePlayQueueByIndex" in server.endpoints() }
        val saved = server.calls.first { it.url.pathSegments.last() == "savePlayQueueByIndex" }.url
        assertEquals(songs.map { it.id }, saved.queryParameterValues("id"))
        assertEquals("1", saved.queryParameter("currentIndex"))
    }

    @Test
    fun aQueueFromAnotherDeviceIsOfferedAndTakenPaused() {
        server.answer(
            "getPlayQueue",
            """"playQueue":{"entry":[${songJson("p1", "Phone one")},${songJson("p2", "Phone two")}],"current":"p2","position":61000,"changed":"2026-09-28T10:00:00Z","changedBy":"Pixel"}""",
        )
        val player = SilentPlayer(clock = { now })
        val sync = sync(player)
        sync.check(force = true)
        waitFor { sync.offer != null }
        val offer = sync.offer!!
        assertEquals("Pixel", offer.device)
        assertEquals("Phone two", offer.title)
        sync.take(offer)
        assertEquals("p2", player.state.value.current?.song?.id)
        assertFalse(player.state.value.playing)
        assertEquals(61_000, player.positionMs())
        assertNull(sync.offer)

        // Once answered, the same queue is not offered again.
        sync.check(force = true)
        Thread.sleep(200)
        assertNull(sync.offer)
    }

    @Test
    fun anotherOctoIsNotNamedOctoOnTheCard() {
        server.answer(
            "getPlayQueue",
            """"playQueue":{"entry":[${songJson("p1", "One")}],"current":"p1","changed":"2026-09-28T10:00:00Z","changedBy":"Octo"}""",
        )
        val sync = sync(SilentPlayer(clock = { now }))
        sync.check(force = true)
        waitFor { sync.offer != null }
        assertNull(sync.offer!!.device)
    }
}
