package app.winters.octo.desktop.player

import app.winters.octo.playback.NoSource
import app.winters.octo.playback.QueueSource
import app.winters.octo.playback.insertIntoShuffle
import app.winters.octo.playback.movedSource
import app.winters.octo.playback.removeFromShuffle
import app.winters.octo.playback.shuffleRankFor
import app.winters.octo.subsonic.Song
import kotlin.random.Random

// One place in the queue. The key tells two plays of the same song apart;
// `source` says where it came from: the listener's own pick, the list it
// was played from, or Autoplay.
data class QueueEntry(val key: Long, val song: Song, val source: QueueSource = NoSource)

enum class RepeatMode { Off, All, One }

// How many queue edits can be taken back, most recent first.
const val UNDO_DEPTH = 20

// The queue as two lists, the way the phone app keeps it: the songs in
// queue order, and `order`, the queue positions in the order they play.
// Unshuffled the two agree. Songs put in land by the shared ShuffleQueue
// rules, so "Play next" plays next and "Add to queue" plays after the
// listener's own songs, shuffled or not. Edits the listener makes can be
// taken back (undo), one at a time, as long as nothing else changed the
// queue since. Not thread safe; the player that owns it keeps calls in order.
class PlayQueue(private val random: Random = Random.Default) {
    private val entries = ArrayList<QueueEntry>()
    private var order = IntArray(0)
    private var nextKey = 1L

    // The queue position playing, or -1 for none.
    var current: Int = -1
        private set

    var shuffled: Boolean = false
        private set

    val songs: List<QueueEntry> get() = entries.toList()

    val size: Int get() = entries.size

    // Queue positions in the order they play.
    val playOrder: List<Int> get() = order.toList()

    val currentEntry: QueueEntry? get() = entries.getOrNull(current)

    // Where the current song is in the play order.
    private val rank: Int get() = order.indexOf(current)

    // The entries still to play after the current one, in play order.
    val upcoming: List<QueueEntry>
        get() = if (current < 0) order.map { entries[it] } else order.drop(rank + 1).map { entries[it] }

    // The entries already played before the current one, in play order.
    val played: List<QueueEntry> get() = if (current < 0) emptyList() else order.take(rank).map { entries[it] }

    // Starts afresh with these songs, playing from `start`. Shuffled, the
    // chosen song plays first and the rest follow in a random order.
    fun replace(songs: List<Song>, start: Int, shuffle: Boolean, source: QueueSource = NoSource) {
        changedElsewhere()
        entries.clear()
        songs.forEach { entries += QueueEntry(nextKey++, it, source) }
        current = if (entries.isEmpty()) -1 else start.coerceIn(0, entries.lastIndex)
        shuffled = shuffle
        order = if (shuffle) shuffledAround(current) else IntArray(entries.size) { it }
    }

    // Puts back a queue saved earlier: the songs in queue order, the play
    // order, and the current position. Unshuffled, or with a play order
    // that does not fit the songs, they play in queue order. `sources` go
    // with the songs when they were saved too.
    fun restore(songs: List<Song>, playOrder: List<Int>, start: Int, shuffle: Boolean, sources: List<QueueSource>? = null) {
        changedElsewhere()
        entries.clear()
        songs.forEachIndexed { i, song -> entries += QueueEntry(nextKey++, song, sources?.getOrNull(i) ?: NoSource) }
        current = if (entries.isEmpty()) -1 else start.coerceIn(0, entries.lastIndex)
        val fits = playOrder.size == entries.size && playOrder.sorted() == entries.indices.toList()
        shuffled = shuffle && fits
        order = if (shuffled) playOrder.toIntArray() else IntArray(entries.size) { it }
    }

    fun setShuffle(on: Boolean) {
        if (on == shuffled) return
        changedElsewhere()
        shuffled = on
        order = if (on) shuffledAround(current) else IntArray(entries.size) { it }
    }

    // The current song first, the rest in a random order.
    private fun shuffledAround(first: Int): IntArray {
        val rest = entries.indices.filter { it != first }.shuffled(random)
        return (if (first >= 0) listOf(first) + rest else rest).toIntArray()
    }

    // Puts songs right after the current one, in both orders.
    fun playNext(songs: List<Song>, source: QueueSource = QueueSource.You) {
        if (songs.isEmpty()) return
        edit(undoable = source == QueueSource.You) {
            val at = if (current < 0) entries.size else current + 1
            insert(at, songs, source, shuffleRankFor(order, at, entries.size, current, playNext = true))
        }
    }

    // Puts songs at the end of the queue; shuffled, they play after
    // everything still to come. The listener's own can be taken back;
    // songs a radio or Autoplay adds cannot.
    fun add(songs: List<Song>, source: QueueSource = QueueSource.You) {
        if (songs.isEmpty()) return
        edit(undoable = source == QueueSource.You) {
            insert(entries.size, songs, source, order.size)
        }
    }

    // Puts the listener's songs among those to come, just before the entry
    // `before` (at the end for null or an entry not to come), as a drop
    // does. Wherever they land, they are the listener's own.
    fun insertBefore(songs: List<Song>, before: Long?) {
        if (songs.isEmpty()) return
        edit(undoable = true) {
            val target = upcoming.firstOrNull { it.key == before }
            val position = target?.let { t -> entries.indexOfFirst { it.key == t.key } } ?: entries.size
            val placed = if (target != null) order.indexOf(position) else order.size
            insert(position, songs, QueueSource.You, placed)
        }
    }

    // Songs go in at queue position `at`, after the first `placed` entries
    // of the play order.
    private fun insert(at: Int, songs: List<Song>, source: QueueSource, placed: Int) {
        order = insertIntoShuffle(order, at, songs.size, placed)
        entries.addAll(at, songs.map { QueueEntry(nextKey++, it, source) })
        if (current >= at) current += songs.size
        // Nothing was playing: the first song put in is up.
        if (current < 0 && entries.isNotEmpty()) current = order.first()
    }

    // Takes an entry out. When it is the one playing, the next in the play
    // order takes its place (or the one before, at the end). Answers
    // whether the current entry changed.
    fun remove(key: Long): Boolean = remove(listOf(key))

    // Takes these entries out, as one edit that can be taken back. Answers
    // whether the current entry changed.
    fun remove(keys: Collection<Long>): Boolean {
        val wanted = keys.toSet()
        if (entries.none { it.key in wanted }) return false
        val playingKey = currentEntry?.key
        edit(undoable = true) { wanted.forEach(::takeOut) }
        return currentEntry?.key != playingKey
    }

    // Takes out every song still to come; the one playing plays on.
    fun clearUpcoming(): Boolean {
        val coming = upcoming.map { it.key }
        if (current < 0 || coming.isEmpty()) return false
        edit(undoable = true) { coming.forEach(::takeOut) }
        return true
    }

    // Takes out the songs played before the current one.
    fun removePlayed(): Boolean {
        val gone = played.map { it.key }
        if (gone.isEmpty()) return false
        edit(undoable = true) { gone.forEach(::takeOut) }
        return true
    }

    private fun takeOut(key: Long) {
        val position = entries.indexOfFirst { it.key == key }
        if (position < 0) return
        val wasCurrent = position == current
        val followingKey = if (wasCurrent) {
            val r = rank
            (order.getOrNull(r + 1) ?: order.getOrNull(r - 1))?.let { entries[it].key }
        } else {
            null
        }
        entries.removeAt(position)
        order = removeFromShuffle(order, position, position + 1)
        current = when {
            wasCurrent -> followingKey?.let { k -> entries.indexOfFirst { it.key == k } } ?: -1
            position < current -> current - 1
            else -> current
        }
    }

    // Moves one of the songs still to come, counted from the first one
    // after the current song, to another place among them. Unshuffled that
    // is a move in the queue itself; shuffled it changes only the play order,
    // which is the order the listener sees and drags in.
    fun moveUpcoming(from: Int, to: Int) {
        val coming = upcoming
        val moving = coming.getOrNull(from) ?: return
        if (to !in coming.indices || from == to) return
        val rest = coming.filter { it.key != moving.key }
        move(listOf(moving.key), rest.getOrNull(to)?.key, adopt = false)
    }

    // Moves entries (any but the one playing, songs already played too) to
    // play just before the entry `before`, or last for null. They keep their
    // order among themselves and, landing inside a run of one source, join
    // it (movedSource), so a song dragged in among the listener's own
    // becomes theirs. `before` must be a song still to come. Unshuffled
    // the queue itself changes; shuffled only the play order. Answers
    // whether anything moved.
    fun move(keys: Collection<Long>, before: Long?, adopt: Boolean = true): Boolean {
        val playingKey = currentEntry?.key
        val wanted = keys.toSet() - setOfNotNull(playingKey, before)
        val sequence = order.map { entries[it] }
        val moving = sequence.filter { it.key in wanted }
        if (moving.isEmpty()) return false
        val rest = sequence.filter { it.key !in wanted }
        // The first place after the song playing.
        val firstFree = if (playingKey == null) 0 else rest.indexOfFirst { it.key == playingKey } + 1
        val at = if (before == null) rest.size else rest.indexOfFirst { it.key == before }.takeIf { it >= firstFree } ?: return false
        val beforeSource = rest.getOrNull(at - 1)?.takeIf { at - 1 >= firstFree }?.source
        val afterSource = rest.getOrNull(at)?.source
        val placed = moving.map { if (adopt) it.copy(source = movedSource(it.source, beforeSource, afterSource)) else it }
        val result = rest.subList(0, at) + placed + rest.subList(at, rest.size)
        if (result == sequence) return false
        edit(undoable = true) {
            val byKey = placed.associateBy { it.key }
            if (!shuffled) {
                entries.clear()
                entries += result
                order = IntArray(entries.size) { it }
            } else {
                entries.replaceAll { byKey[it.key] ?: it }
                val position = entries.withIndex().associate { (i, e) -> e.key to i }
                order = result.map { position.getValue(it.key) }.toIntArray()
            }
            current = playingKey?.let { k -> entries.indexOfFirst { it.key == k } } ?: -1
        }
        return true
    }

    // Plays the entry with this key next, straight away.
    fun jumpTo(key: Long): Boolean {
        val position = entries.indexOfFirst { it.key == key }
        if (position < 0) return false
        current = position
        return true
    }

    // The next queue position by the play order, or null at the end. With
    // repeat all the end wraps round; repeat one is the player's business.
    fun nextPosition(repeat: RepeatMode): Int? {
        if (entries.isEmpty()) return null
        val r = rank
        return when {
            r + 1 < order.size -> order[r + 1]
            repeat == RepeatMode.All -> order.first()
            else -> null
        }
    }

    fun previousPosition(repeat: RepeatMode): Int? {
        if (entries.isEmpty()) return null
        val r = rank
        return when {
            r > 0 -> order[r - 1]
            repeat == RepeatMode.All -> order.last()
            else -> null
        }
    }

    fun moveTo(position: Int) {
        if (position in entries.indices) current = position
    }

    fun clear() {
        changedElsewhere()
        entries.clear()
        order = IntArray(0)
        current = -1
    }

    // Gives every entry of one song a new copy of it (with its real length,
    // say), the queue's own and the ones an undo would bring back. Keys,
    // order and what can be undone stay as they are. Answers whether any
    // entry changed.
    fun updateSong(id: String, change: (Song) -> Song): Boolean {
        fun List<QueueEntry>.updated() = map { if (it.song.id == id) it.copy(song = change(it.song)) else it }
        val now = entries.updated()
        if (now == entries) return false
        entries.clear()
        entries += now
        val kept = undos.map { Undo(Snapshot(it.before.entries.updated(), it.before.order, it.before.shuffled, it.before.playing), it.stampBefore, it.stampAfter) }
        undos.clear()
        undos += kept
        return true
    }

    // ---- Undo ----

    // The queue as it was before an edit: its entries, play order and
    // shuffle, and the entry that was playing.
    private class Snapshot(val entries: List<QueueEntry>, val order: IntArray, val shuffled: Boolean, val playing: Long?)

    // An edit that can be taken back, and the queue's stamp just after it,
    // so an undo never acts on a queue something else has changed since.
    private class Undo(val before: Snapshot, val stampBefore: Long, val stampAfter: Long)

    // Changes every time the queue itself changes (not when a song ends
    // and the next starts), and never goes back to a number used before.
    private var stamps = 0L
    private var stamp = 0L
    private val undos = ArrayDeque<Undo>()

    // Whether the most recent edit can be taken back now.
    val canUndo: Boolean get() = undos.lastOrNull()?.stampAfter == stamp

    private fun edit(undoable: Boolean, change: () -> Unit) {
        val before = Snapshot(entries.toList(), order.copyOf(), shuffled, currentEntry?.key)
        val stampBefore = stamp
        change()
        stamp = ++stamps
        if (!undoable) {
            undos.clear()
            return
        }
        undos.addLast(Undo(before, stampBefore, stamp))
        while (undos.size > UNDO_DEPTH) undos.removeFirst()
    }

    // A change that cannot be taken back, like a new queue or shuffle: the
    // edits before it cannot be either.
    private fun changedElsewhere() {
        stamp = ++stamps
        undos.clear()
    }

    // Takes back the most recent edit, putting every song back where it was.
    // The song playing now plays on, wherever it lands; if the edit had put
    // it in, the one playing before comes back instead. Answers null when
    // there is nothing to take back, else whether the current entry changed.
    fun undo(): Boolean? {
        val last = undos.lastOrNull()
        if (last == null || last.stampAfter != stamp) {
            undos.clear()
            return null
        }
        undos.removeLast()
        val playingKey = currentEntry?.key
        val before = last.before
        entries.clear()
        entries += before.entries
        order = before.order.copyOf()
        shuffled = before.shuffled
        val keep = playingKey?.let { k -> entries.indexOfFirst { it.key == k } }?.takeIf { it >= 0 }
        current = keep ?: before.playing?.let { k -> entries.indexOfFirst { it.key == k } } ?: -1
        stamp = last.stampBefore
        return currentEntry?.key != playingKey
    }
}
