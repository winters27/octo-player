package app.winters.octo.desktop.player

import app.winters.octo.playback.insertIntoShuffle
import app.winters.octo.playback.removeFromShuffle
import app.winters.octo.playback.shuffleRankFor
import app.winters.octo.subsonic.Song
import kotlin.random.Random

// One place in the queue. The key tells two plays of the same song apart.
data class QueueEntry(val key: Long, val song: Song)

enum class RepeatMode { Off, All, One }

// The queue as two lists, the way the phone app keeps it: the songs in
// queue order, and `order`, the queue positions in the order they play.
// Unshuffled the two agree. Songs put in land by the shared ShuffleQueue
// rules, so "Play next" plays next and "Add to queue" plays after the
// listener's own songs, shuffled or not. Not thread safe; the player that
// owns it keeps calls in order.
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
    fun replace(songs: List<Song>, start: Int, shuffle: Boolean) {
        entries.clear()
        songs.forEach { entries += QueueEntry(nextKey++, it) }
        current = if (entries.isEmpty()) -1 else start.coerceIn(0, entries.lastIndex)
        shuffled = shuffle
        order = if (shuffle) shuffledAround(current) else IntArray(entries.size) { it }
    }

    fun setShuffle(on: Boolean) {
        if (on == shuffled) return
        shuffled = on
        order = if (on) shuffledAround(current) else IntArray(entries.size) { it }
    }

    // The current song first, the rest in a random order.
    private fun shuffledAround(first: Int): IntArray {
        val rest = entries.indices.filter { it != first }.shuffled(random)
        return (if (first >= 0) listOf(first) + rest else rest).toIntArray()
    }

    // Puts songs right after the current one, in both orders.
    fun playNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        val at = if (current < 0) entries.size else current + 1
        insert(at, songs, playNext = true)
    }

    // Puts songs at the end of the queue; shuffled, they play after
    // everything still to come.
    fun add(songs: List<Song>) {
        if (songs.isEmpty()) return
        insert(entries.size, songs, playNext = false)
    }

    private fun insert(at: Int, songs: List<Song>, playNext: Boolean) {
        val placed = shuffleRankFor(order, at, entries.size, current, playNext = playNext)
        order = insertIntoShuffle(order, at, songs.size, placed)
        entries.addAll(at, songs.map { QueueEntry(nextKey++, it) })
        if (current >= at) current += songs.size
        // Nothing was playing: the first song put in is up.
        if (current < 0 && entries.isNotEmpty()) current = order.first()
    }

    // Takes an entry out. When it is the one playing, the next in the play
    // order takes its place (or the one before, at the end). Answers
    // whether the current entry changed.
    fun remove(key: Long): Boolean {
        val position = entries.indexOfFirst { it.key == key }
        if (position < 0) return false
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
        return wasCurrent
    }

    // Moves one of the songs still to come, counted from the first one
    // after the current song, to another place among them. Unshuffled that
    // is a move in the queue itself; shuffled it changes only the play order,
    // which is the order the listener sees and drags in.
    fun moveUpcoming(from: Int, to: Int) {
        val base = if (current < 0) 0 else rank + 1
        val a = base + from
        val b = base + to
        if (a !in order.indices || b !in order.indices || a == b) return
        if (!shuffled) {
            val moved = entries.removeAt(a)
            entries.add(b, moved)
            // Unshuffled, the play order is the queue order.
            order = IntArray(entries.size) { it }
            // The current song is before every upcoming one, so it stays put.
        } else {
            val list = order.toMutableList()
            val position = list.removeAt(a)
            list.add(b, position)
            order = list.toIntArray()
        }
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
        entries.clear()
        order = IntArray(0)
        current = -1
    }
}
