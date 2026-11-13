package app.winters.octo.playback

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.QueueEntryEntity
import app.winters.octo.catalog.QueueListEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.Drains
import app.winters.octo.listening.PlayTotals
import app.winters.octo.livelists.LivePicks
import app.winters.octo.ui.library.health.HealthBuild
import app.winters.octo.output.DeviceShape
import app.winters.octo.output.MediaForDevice
import app.winters.octo.output.OutputDevice
import app.winters.octo.output.OutputFamily
import app.winters.octo.output.OutputSwitch
import app.winters.octo.output.QueueState
import app.winters.octo.output.RemoteMedia
import app.winters.octo.output.RemoteOutput
import app.winters.octo.output.RemotePlayer
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.testing.waitOnWork
import app.winters.octo.ui.paging.ListLoaders
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files

// A device that takes every song and says nothing back, so a queue on it
// moves only as the test moves it.
internal class QuietDevice : RemoteOutput {
    override val device = OutputDevice("tv", "TV", OutputFamily.Cast, DeviceShape.Tv)
    override fun accepts(mimeType: String) = true
    override val takesNext = true
    override fun load(media: RemoteMedia, startMs: Long, play: Boolean, next: RemoteMedia?) = Unit
    override fun setNext(next: RemoteMedia?) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seek(positionMs: Long) = Unit
    override fun stop() = Unit
    override fun setVolume(level: Float) = Unit
    override fun setMuted(muted: Boolean) = Unit
    override fun listen(listener: RemoteOutput.Listener?) = Unit
    override fun close(stopPlaying: Boolean) = Unit
}

internal class AnyMedia : MediaForDevice {
    override fun canPlay(item: MediaItem, output: RemoteOutput) = true
    override suspend fun make(item: MediaItem, output: RemoteOutput) = RemoteMedia("http://device/${item.mediaId}", "audio/mpeg", item.mediaId)
}

// A player whose upkeep waits while `busy`, as the phone's does while a
// blend is under way; `free()` lets what waited run, as the blend's end does.
internal class GatedQueue(private val to: EditableQueue) : EditableQueue by to {
    var busy = false
    val waiting = ArrayList<() -> Unit>()

    override fun upkeep(write: () -> Unit) {
        if (busy) waiting += write else write()
    }

    fun free() {
        busy = false
        val writes = waiting.toList()
        waiting.clear()
        writes.forEach { it() }
    }
}

// The queue as the service holds it, over a test library: the session's
// player (the phone's, swapped for a quiet device's so nothing loads), the
// window over Room, its builders and its first reads, with small window
// sizes and build pages.
@OptIn(UnstableApi::class)
internal class QueueRig(
    val db: CatalogDatabase = openTestCatalog(),
    val sizes: WindowSizes = WindowSizes(window = 20, ahead = 12, topUpAt = 6, behind = 4, trimAt = 8),
    page: Int = 50,
    serverInUse: String? = null,
    // The window's seed source; null leaves the window's own.
    random: java.util.Random? = null,
    // Free bytes on the disk, as the builds see it.
    space: () -> Long = { Long.MAX_VALUE },
    // Play totals that run, with their merge, for lists by listening.
    listening: app.winters.octo.listening.TotalsRig? = null,
    // The library's merge, when the test runs one of its own.
    libraryMerge: CatalogMerge? = null,
    // Whether the window moves the player through a gate that can hold the
    // app's own upkeep back, as a blend under way does.
    gated: Boolean = false,
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val scope = MainScope()
    val merge: CatalogMerge = libraryMerge ?: listening?.merge ?: db.testMerge(serverInUse)
    val owners = WorkOwners()
    val totals = listening?.totals ?: PlayTotals(db, merge, { Long.MAX_VALUE }, MutableStateFlow("home"), {}, 500)
    val sets = PlaylistSets(db)
    val loaders = ListLoaders(
        db, merge, { Long.MAX_VALUE }, sets, owners, Drains(Files.createTempDirectory("drain").toFile()), totals, { null },
        { error("no downloads") }, { error("no sorted library") }, { health }, { live }, server = { serverInUse },
    )
    val store = QueueStore(db)
    val live = LivePicks(db, merge, totals, owners, { space() }, { System.currentTimeMillis() }, 500)
    val health = HealthBuild(db, merge, owners, { space() }, 500, 100)
    val builds = QueueBuilds(store, { loaders }, { live }, { health }, merge, totals, { space() }, owners, { error("no radio") })
        .also { it.pageSize = page }
    val starts = QueueStarts(db, { loaders }, { live }, { health }, owners)
    val local = OctoPlayer(context, ExoPlayer.Builder(context).build(), ExoPlayer.Builder(context).build())
    val remote = RemotePlayer(QuietDevice(), AnyMedia(), scope) {}
    lateinit var window: QueueWindow
    val player = OutputSwitch(local) { if (::window.isInitialized) window else null }
    val words = ArrayList<String>()

    // The gate, when `gated`: the device's player with the upkeep held back
    // while `busy`.
    val gate = GatedQueue(remote)
    private val inner: () -> EditableQueue = if (gated) ({ gate }) else ({ player.active })

    init {
        remote.start(QueueState(emptyList(), 0, 0, playing = false))
        player.useRemote(remote)
        val guard = PlayGuard({ player.replacements }, { "home" })
        window = if (random == null) {
            QueueWindow(player, inner, store, builds, starts, ::items, { "home" }, guard, scope, sizes, { words += it })
        } else {
            QueueWindow(player, inner, store, builds, starts, ::items, { "home" }, guard, scope, sizes, { words += it }, random)
        }
    }

    // Songs the player can play: one for each id the library holds.
    suspend fun items(ids: List<String>): List<MediaItem?> = ids.map { id ->
        db.queueDao().track(id)?.let { t ->
            MediaItem.Builder().setMediaId(t.id).setUri("http://server/${t.id}")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setDurationMs(t.durationMs).build()).build()
        }
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // Runs `block` on the test's thread, the main looper turning meanwhile.
    fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(20_000) { block() } }

    // Lets the worker and the main thread settle: every job posted so far
    // has run, and the main looper has nothing left.
    fun settle() {
        repeat(4) {
            idle()
            run { store.inTurn { } }
            idle()
        }
    }

    // Waits until `done`, the main looper turning: at most 15 s, or with
    // `progress`, for as long as that moves and 15 s once it stands still.
    fun until(what: String, progress: (() -> Any?)? = null, done: () -> Boolean) = waitOnWork(what, 15_000, progress, ::settle, done)

    // The ids the player holds, in queue order.
    fun held(): List<String> = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }

    fun current(): String? = player.currentMediaItem?.mediaId

    // The generation in use's rows in play order, and its lists.
    fun rows(order: QueueOrder = QueueOrder.Play): List<QueueEntryEntity> = run {
        store.inTurn { store.page(window.queueId, order, null, true, Int.MAX_VALUE, Floors.NONE) }
    }

    fun lists(): List<QueueListEntity> = run { store.inTurn { store.dao.listsOf(window.queueId) } }

    // The songs of the whole queue in the order that plays.
    fun songs(order: QueueOrder = QueueOrder.Play): List<String> = rows(order).map { it.trackId }

    fun play(request: PlayRequest): PlayAnswer {
        var answer: PlayAnswer? = null
        val job = scope.launch { answer = window.play(request) }
        until("the play answers") { answer != null || job.isCompleted }
        return answer!!
    }

    // Waits until no list of the queue in use is being written, for as long
    // as their writes move (each page a list writes moves its row).
    fun joined() = until("every list joined", { lists() }) { lists().all { it.fillState == QueueBuilds.JOINED } && window.building.value.isEmpty() }

    // Next as a controller presses it, through the session's player.
    // A Next at the window's edge waits for a read, so this waits for the
    // move unless the queue ends here.
    fun next() {
        val before = current()
        player.seekToNext()
        settle()
        fun ends() = window.atLogicalEnd && player.repeatMode == Player.REPEAT_MODE_OFF
        if (current() == before) {
            try {
                // Waits on the step, not the clock: until the song moved, or
                // nothing is under way and the queue ends here.
                until("Next moved") { current() != before || (!window.stepping && ends()) }
            } catch (e: AssertionError) {
                throw AssertionError("Next never moved from $before: held ${held()}, end ${window.atLogicalEnd}, windowed ${window.windowed}, places ${window.building.value.size}", e)
            }
        }
    }

    fun close() {
        window.release()
        player.release()
        scope.cancel()
        store.scope.cancel()
        idle()
        db.close()
    }
}

// A merge's rename of a library song, as its rows are left after the swap:
// the song under its new id, and the old id's copy pointing at it.
internal suspend fun CatalogDatabase.rename(old: String, new: String) {
    val track = dao().track(old) ?: error("no $old")
    openHelper.writableDatabase.execSQL("DELETE FROM track WHERE id = ?", arrayOf<Any?>(old))
    dao().insertTracks(listOf(track.copy(id = new)))
    sourceDao().putTracks(
        listOf(
            app.winters.octo.catalog.SourceTrackEntity(
                id = old, sourceId = "device", nativeId = old, title = track.title, searchKey = track.searchKey, sortKey = track.sortKey,
                artist = track.artist, artistId = track.artistId, album = track.album, albumId = track.albumId, trackNo = track.trackNo,
                discNo = track.discNo, year = track.year, durationMs = track.durationMs, addedAt = track.addedAt, mimeType = null,
                sizeBytes = null, artwork = null, uri = null, albumOrder = track.albumOrder, relinkKey = "", genre = "", mergedId = new,
            ),
        ),
    )
}

// A merge of one library song into another the library holds already, as
// its rows are left after the swap: the first gone, its copy pointing at
// the second.
internal suspend fun CatalogDatabase.mergeInto(old: String, into: String) {
    val track = dao().track(old) ?: error("no $old")
    check(dao().track(into) != null) { "no $into" }
    openHelper.writableDatabase.execSQL("DELETE FROM track WHERE id = ?", arrayOf<Any?>(old))
    sourceDao().putTracks(
        listOf(
            app.winters.octo.catalog.SourceTrackEntity(
                id = old, sourceId = "device", nativeId = old, title = track.title, searchKey = track.searchKey, sortKey = track.sortKey,
                artist = track.artist, artistId = track.artistId, album = track.album, albumId = track.albumId, trackNo = track.trackNo,
                discNo = track.discNo, year = track.year, durationMs = track.durationMs, addedAt = track.addedAt, mimeType = null,
                sizeBytes = null, artwork = null, uri = null, albumOrder = track.albumOrder, relinkKey = "", genre = "", mergedId = into,
            ),
        ),
    )
}

// Songs named s0, s1, ... in title order, `n` of them, on one album.
internal fun CatalogDatabase.songs(n: Int, album: String = "al", prefix: String = "s", genre: (Int) -> String? = { null }): List<TrackEntity> {
    val tracks = (0 until n).map { i ->
        val id = "$prefix$i"
        val key = "%s%07d".format(prefix, i)
        TrackEntity(
            id = id, sourceId = "device", nativeId = id, title = key, searchKey = key, sortKey = key,
            artist = "ar", artistId = "ar", album = album, albumId = album, trackNo = i, discNo = 1, year = null,
            durationMs = 1_000, addedAt = i.toLong(), mimeType = null, sizeBytes = null, artwork = null, uri = null,
            albumOrder = i, genre = genre(i) ?: "",
        )
    }
    runBlocking { tracks.chunked(500).forEach { dao().insertTracks(it) } }
    return tracks
}
