package app.winters.octo.playback

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.Drains
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.asTrack
import app.winters.octo.discovery.serverFolders
import app.winters.octo.discovery.serverReads
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
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files

// The queue as the service holds it, playing lists a fake server answers
// for: the window over Room, its builders and first reads, the server's
// lists and folder levels read into their owners' rows, with small window
// sizes and build pages, and the words the window says kept. A rig made
// over another's database and server is that service come back.
@OptIn(UnstableApi::class)
internal class NetworkQueueRig(
    songs: Map<String, Song>,
    page: Int = 100,
    readPage: Int = 50,
    val db: CatalogDatabase = openTestCatalog(),
    val server: ListServer = ListServer(songs),
) : AutoCloseable {
    private val context: Context = ApplicationProvider.getApplicationContext()
    val scope = MainScope()
    val merge = db.testMerge(SERVER_SOURCE)
    val owners = WorkOwners()
    // Finds a start plays before their rows are written.
    val finds = FindMap(db.onlineDao(), RealLengths(), scope)
    private val totals = PlayTotals(db, merge, { Long.MAX_VALUE }, MutableStateFlow("home"), {}, 500)
    val folders = db.serverFolders(server, owners, page = readPage)
    val reads = db.serverReads(server, page = readPage, merge = merge, owners = owners, levels = folders)
    private val live = LivePicks(db, merge, totals, owners, { Long.MAX_VALUE }, { System.currentTimeMillis() }, 500)
    private val health = HealthBuild(db, merge, owners, { Long.MAX_VALUE }, 500, 100)
    private val loaders = ListLoaders(
        db, merge, { Long.MAX_VALUE }, PlaylistSets(db), owners, Drains(Files.createTempDirectory("drain").toFile()), totals, { null },
        { error("no downloads") }, { error("no sorted library") }, { health }, { live }, server = { SERVER_SOURCE }, servers = { reads },
    )
    val store = QueueStore(db)
    val builds = QueueBuilds(store, { loaders }, { live }, { health }, merge, totals, { Long.MAX_VALUE }, owners, { error("no radio") })
        .also { it.pageSize = page }
    private val starts = QueueStarts(db, { loaders }, { live }, { health }, owners, { reads })
    private val local = OctoPlayer(context, ExoPlayer.Builder(context).build(), ExoPlayer.Builder(context).build())
    private val remote = RemotePlayer(QuietDevice(), AnyMedia(), scope) {}
    lateinit var window: QueueWindow
    val player = OutputSwitch(local) { if (::window.isInitialized) window else null }
    val words = ArrayList<String>()

    init {
        remote.start(QueueState(emptyList(), 0, 0, playing = false))
        player.useRemote(remote)
        window = QueueWindow(
            player, { player.active }, store, builds, starts, ::items, { "home" }, PlayGuard({ player.replacements }, { "home" }), scope,
            WindowSizes(window = 20, ahead = 12, topUpAt = 6, behind = 4, trimAt = 8), { words += it }, finds = finds,
        )
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

    fun settle() = repeat(4) {
        shadowOf(Looper.getMainLooper()).idle()
        runBlocking { store.inTurn { } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    // Waits on the work, settling the main looper and the worker, until
    // `done`; fails after a few seconds.
    fun until(what: String, done: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (!done()) {
            settle()
            if (System.nanoTime() > end) throw AssertionError("never: $what")
            Thread.sleep(5)
        }
    }

    fun play(request: PlayRequest): PlayAnswer {
        var answer: PlayAnswer? = null
        val job = scope.launch { answer = window.play(request) }
        until("the play answers") { answer != null || job.isCompleted }
        return answer!!
    }

    fun current(): String? = player.currentMediaItem?.mediaId

    // Every list of the queue in use joined.
    fun joined() = until("every list joined") {
        runBlocking { store.inTurn { store.dao.listsOf(window.queueId) } }.all { it.fillState == QueueBuilds.JOINED } && window.building.value.isEmpty()
    }

    // The whole queue's songs in play order.
    fun songs(): List<String> = runBlocking { store.inTurn { store.page(window.queueId, QueueOrder.Play, null, true, Int.MAX_VALUE, Floors.NONE) } }.map { it.trackId }

    fun count(sql: String, vararg args: Any?): Int = db.query(sql, args).use { it.moveToFirst(); it.getInt(0) }

    override fun close() {
        window.release()
        player.release()
        scope.cancel()
        store.scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        server.close()
        db.close()
    }
}
