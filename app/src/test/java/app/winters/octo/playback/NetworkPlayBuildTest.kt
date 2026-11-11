package app.winters.octo.playback

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.findId
import app.winters.octo.data.Drains
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.asTrack
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.serverReads
import app.winters.octo.discovery.today.todayResolve
import app.winters.octo.listening.PlayTotals
import app.winters.octo.livelists.LivePicks
import app.winters.octo.output.OutputSwitch
import app.winters.octo.output.QueueState
import app.winters.octo.output.RemotePlayer
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.library.health.HealthBuild
import app.winters.octo.ui.paging.ListLoaders
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.Files

// A big list the server answers for plays through the queue end to end:
// its first song plays from the play's own copy, the build walks that copy
// with no second request, the whole list joins in order with every song
// once, and the copy is let go with the build. A server folder's play
// answers with its cut.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkPlayBuildTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = openTestCatalog()
    private val songs = (0 until 1_500).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist ${it % 3}") }
    private val server = ListServer(songs)
    private val scope = MainScope()
    private val merge = db.testMerge(SERVER_SOURCE)
    private val owners = WorkOwners()
    private val totals = PlayTotals(db, merge, { Long.MAX_VALUE }, MutableStateFlow("home"), {}, 500)
    private val reads = db.serverReads(server, merge = merge, owners = owners)
    private val live = LivePicks(db, merge, totals, owners, { Long.MAX_VALUE }, { System.currentTimeMillis() }, 500)
    private val health = HealthBuild(db, merge, owners, { Long.MAX_VALUE }, 500, 100)
    private val loaders = ListLoaders(
        db, merge, { Long.MAX_VALUE }, PlaylistSets(db), owners, Drains(Files.createTempDirectory("drain").toFile()), totals, { null },
        { error("no downloads") }, { error("no sorted library") }, { health }, { live }, server = { SERVER_SOURCE }, servers = { reads },
    )
    private val store = QueueStore(db)
    private val builds = QueueBuilds(store, { loaders }, { live }, { health }, merge, totals, { Long.MAX_VALUE }, owners, { error("no radio") }).also { it.pageSize = 100 }
    private val starts = QueueStarts(db, { loaders }, { live }, { health }, owners, { reads })
    private val finds = FindMap(db.onlineDao(), RealLengths(), scope)
    private val local = OctoPlayer(context, ExoPlayer.Builder(context).build(), ExoPlayer.Builder(context).build())
    private val remote = RemotePlayer(QuietDevice(), AnyMedia(), scope) {}
    private lateinit var window: QueueWindow
    private val player = OutputSwitch(local) { if (::window.isInitialized) window else null }

    init {
        remote.start(QueueState(emptyList(), 0, 0, playing = false))
        player.useRemote(remote)
        window = QueueWindow(
            player, { player.active }, store, builds, starts, ::items, { "home" }, PlayGuard({ player.replacements }, { "home" }), scope,
            WindowSizes(window = 20, ahead = 12, topUpAt = 6, behind = 4, trimAt = 8), finds = finds,
        )
    }

    @After
    fun close() {
        window.release()
        player.release()
        scope.cancel()
        store.scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        server.close()
        db.close()
    }

    // Songs the player can play: library songs and finds alike, a find from
    // the find map until its row is written.
    private suspend fun items(ids: List<String>): List<MediaItem?> = ids.map { id ->
        val track: TrackEntity? = db.queueDao().track(id) ?: (finds[id] ?: db.onlineDao().byIds(listOf(id)).firstOrNull())?.asTrack()
        track?.let { t ->
            MediaItem.Builder().setMediaId(t.id).setUri("http://server/${t.id}")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setDurationMs(t.durationMs).build()).build()
        }
    }

    private fun settle() = repeat(4) {
        shadowOf(Looper.getMainLooper()).idle()
        runBlocking { store.inTurn { } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun until(what: String, done: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (!done()) {
            settle()
            if (System.nanoTime() > end) throw AssertionError("never: $what")
            Thread.sleep(5)
        }
    }

    private fun play(request: PlayRequest): PlayAnswer {
        var answer: PlayAnswer? = null
        val job = scope.launch { answer = window.play(request) }
        until("the play answers") { answer != null || job.isCompleted }
        return answer!!
    }

    @Test
    fun aBigServerListJoinsFromItsCopy() {
        db.putLibrary((0 until 1_500 step 2).map { libraryTrack("t$it", "Title $it", "Artist ${it % 3}") }) { "srv${it.id.removePrefix("t")}" }
        val entries = (0 until 1_500).map { "srv$it" } + (0 until 100).map { "srv${it * 3}" }
        server.lists["L"] = entries
        val today = todayResolve(db, SERVER_SOURCE, entries.map { songs.getValue(it) }).map { it.id }
        val answer = play(PlayRequest(ListSpec.Online(OnlineKind.List, "L")))
        assertTrue(answer.started)
        assertEquals(today.first(), player.currentMediaItem?.mediaId)
        until("the list joined") { runBlocking { store.inTurn { store.dao.listsOf(window.queueId) } }.all { it.fillState == QueueBuilds.JOINED } }
        val queued = runBlocking { store.inTurn { store.page(window.queueId, QueueOrder.Play, null, true, Int.MAX_VALUE, Floors.NONE) } }.map { it.trackId }
        assertEquals(today, queued)
        assertEquals(1, server.calls.count { it == "getPlaylist" })
        assertEquals(0, db.query("SELECT COUNT(*) FROM server_entry", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, db.query("SELECT COUNT(*) FROM server_head", null).use { it.moveToFirst(); it.getInt(0) })
        assertTrue(findId("srv1") in queued)
    }

    @Test
    fun aServerFolderPlayAnswersWithItsCut() {
        server.folders["f"] = emptyList<String>() to (0 until 1_500).map { "srv$it" } + (0 until 600).map { "srv$it" }
        val answer = play(PlayRequest(ListSpec.ServerFolder("f"), shuffle = true))
        assertTrue(answer.started)
        assertTrue(answer.cut)
        // A folder gone from the server is refused with today's words.
        assertEquals(PlayAnswer(false, refusal = "That isn't on the server any more."), play(PlayRequest(ListSpec.ServerFolder("gone"))))
    }
}
