package app.winters.octo.desktop.queue

import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.PlayerState
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.desktop.player.RepeatMode
import app.winters.octo.desktop.player.SavedQueue
import app.winters.octo.playback.QueueSource
import app.winters.octo.playback.encoded
import app.winters.octo.playback.queueSourceOf
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

// The most songs saved with the queue. A longer queue (a whole library
// played from the Songs page) keeps a few played songs and the rest of
// what is to come, in the order they play.
const val SAVED_QUEUE_MAX = 2_000

// How many played songs a cut-down queue keeps.
private const val KEEP_PLAYED = 50

// How often the place in the song is saved while playing.
private const val SPOT_EVERY_MS = 15_000L

// The queue as it is written to disk.
@Serializable
data class QueueFile(
    val songs: List<Song>,
    val order: List<Int>,
    val index: Int,
    val shuffle: Boolean = false,
    val repeat: String = RepeatMode.Off.name,
    // Where each song came from ("you", "list:OK Computer"), for the
    // queue's headings; missing in files saved before there were any.
    val sources: List<String>? = null,
)

// Where in the current song playback was, kept apart from the queue so
// the place can be saved often without writing every song again.
@Serializable
data class QueueSpot(val songId: String, val positionMs: Long)

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

// The player's queue as a queue to save: the whole queue in its own order
// with its play order, or, when it is too long, a stretch around the
// current song in the order it plays. Null for an empty queue.
fun queueFileOf(state: PlayerState, max: Int = SAVED_QUEUE_MAX): QueueFile? {
    val current = state.current ?: return null
    val repeat = state.repeat.name
    if (state.queue.size <= max) {
        val position = state.queue.withIndex().associate { (i, entry) -> entry.key to i }
        val order = (state.played + current + state.upcoming).mapNotNull { position[it.key] }
        return QueueFile(state.queue.map(QueueEntry::song), order, position[current.key] ?: 0, state.shuffle, repeat, state.queue.map { it.source.encoded() })
    }
    val before = state.played.takeLast(minOf(KEEP_PLAYED, max / 4))
    val after = state.upcoming.take(max - before.size - 1)
    val kept = before + current + after
    return QueueFile(kept.map(QueueEntry::song), kept.indices.toList(), before.size, state.shuffle, repeat, kept.map { it.source.encoded() })
}

fun QueueFile.saved(spot: QueueSpot?): SavedQueue {
    val position = spot?.takeIf { it.songId == songs.getOrNull(index)?.id }?.positionMs ?: 0
    val mode = RepeatMode.entries.firstOrNull { it.name == repeat } ?: RepeatMode.Off
    return SavedQueue(songs, order, index, position, shuffle, mode, sources?.takeIf { it.size == songs.size }?.map(::queueSourceOf))
}

// Keeps the queue on this computer, so Octo opens where it was left:
// the songs, the order, shuffle and repeat, and the place in the song,
// paused. Saved a moment after each change, at each pause, now and then
// while playing, and on the way out.
class QueueKeeper(
    private val player: DesktopPlayer,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    // The signed-in account's folder, or null while signed out.
    @Volatile var folder: File? = null

    private val queueFile get() = folder?.let { File(it, "queue.json") }
    private val spotFile get() = folder?.let { File(it, "queue-spot.json") }

    @OptIn(FlowPreview::class)
    fun start(): Job = scope.launch {
        // The queue itself, once it settles.
        launch {
            player.state
                .map { Shape((it.played + listOfNotNull(it.current) + it.upcoming).map { e -> e.key to e.source }, it.current?.key, it.shuffle, it.repeat) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(1_000)
                .collect { saveQueue() }
        }
        // The place, at each pause.
        launch {
            player.state.map { it.playing }.distinctUntilChanged().drop(1).collect { playing -> if (!playing) saveSpot() }
        }
        // And now and then while playing.
        while (isActive) {
            delay(SPOT_EVERY_MS)
            if (player.state.value.playing) saveSpot()
        }
    }

    // Brings back the queue saved for this account, when the player has
    // none. Answers what was put back, if anything.
    suspend fun restore(): SavedQueue? {
        if (player.state.value.current != null) return null
        val saved = withContext(io) { read() } ?: return null
        if (player.state.value.current != null) return null
        player.restore(saved)
        return saved
    }

    // Saves everything at once, for quitting.
    fun saveNow() {
        runCatching { writeQueue(player.state.value) }
        runCatching { writeSpot(player.state.value) }
    }

    private fun saveQueue() {
        val state = player.state.value
        scope.launch(io) {
            runCatching { writeQueue(state) }
            runCatching { writeSpot(state) }
        }
    }

    private fun saveSpot() {
        val state = player.state.value
        scope.launch(io) { runCatching { writeSpot(state) } }
    }

    @Synchronized
    private fun writeQueue(state: PlayerState) {
        val file = queueFile ?: return
        val saving = queueFileOf(state)
        if (saving == null) {
            file.delete()
            return
        }
        replace(file, json.encodeToString(QueueFile.serializer(), saving))
    }

    @Synchronized
    private fun writeSpot(state: PlayerState) {
        val file = spotFile ?: return
        val song = state.current?.song ?: return
        replace(file, json.encodeToString(QueueSpot.serializer(), QueueSpot(song.id, player.positionMs())))
    }

    @Synchronized
    private fun read(): SavedQueue? {
        val file = queueFile?.takeIf(File::exists) ?: return null
        val queue = runCatching { json.decodeFromString(QueueFile.serializer(), file.readText()) }.getOrNull() ?: return null
        if (queue.songs.isEmpty()) return null
        val spot = spotFile?.takeIf(File::exists)?.let { runCatching { json.decodeFromString(QueueSpot.serializer(), it.readText()) }.getOrNull() }
        return queue.saved(spot)
    }

    // What makes a queue different enough to save again: its entries (and
    // where each came from) in the order they play, the current one,
    // shuffle and repeat.
    private data class Shape(val playOrder: List<Pair<Long, QueueSource>>, val current: Long?, val shuffle: Boolean, val repeat: RepeatMode)
}

// Writes a file whole, through a new file moved into place, so a crash
// part way leaves the old one.
internal fun replace(file: File, text: String) {
    file.parentFile?.mkdirs()
    val next = File(file.parentFile, file.name + ".new")
    next.writeText(text)
    if (!next.renameTo(file)) {
        file.delete()
        next.renameTo(file)
    }
}
