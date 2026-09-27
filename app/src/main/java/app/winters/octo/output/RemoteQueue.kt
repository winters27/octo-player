package app.winters.octo.output

import app.winters.octo.playback.insertIntoShuffle
import app.winters.octo.playback.moveInShuffle
import app.winters.octo.playback.ownSongsAt
import app.winters.octo.playback.removeFromShuffle
import app.winters.octo.playback.shuffleRankFor

// Repeat modes, as Media3's Player numbers them.
const val REPEAT_OFF = 0
const val REPEAT_ONE = 1
const val REPEAT_ALL = 2

// A queue as it moves between the phone and another device: the songs,
// which one is on and how far into it, whether it was playing, how it
// repeats, and under shuffle the order the songs play in (queue positions,
// first to last).
data class QueueState<T>(
    val items: List<T>,
    val index: Int,
    val positionMs: Long,
    val playing: Boolean,
    val repeatMode: Int = REPEAT_OFF,
    val shuffle: Boolean = false,
    val order: List<Int> = items.indices.toList(),
)

// The queue while music plays on another device. The phone keeps it and
// moves through it, so Cast devices and media renderers behave the same,
// and edits follow the same rules as on the phone: see ShuffleQueue.kt.
class RemoteQueue<T>(state: QueueState<T>, private val isAutoplay: (T) -> Boolean = { false }) {
    private val songs = state.items.toMutableList()
    private var playOrder: List<Int> = state.order.takeIf { it.sorted() == songs.indices.toList() } ?: songs.indices.toList()

    val items: List<T> get() = songs
    val size: Int get() = songs.size

    var index: Int = if (songs.isEmpty()) 0 else state.index.coerceIn(songs.indices)
        private set

    var repeatMode: Int = state.repeatMode
    var shuffle: Boolean = state.shuffle
    val order: List<Int> get() = playOrder

    val current: T? get() = songs.getOrNull(index)

    // The queue positions in the order they play now.
    fun sequence(): List<Int> = if (shuffle) playOrder else songs.indices.toList()

    // The song after `from` in play order under `repeat`, or null at the end.
    fun next(from: Int = index, repeat: Int = repeatMode): Int? {
        if (songs.isEmpty()) return null
        if (repeat == REPEAT_ONE) return from
        val seq = sequence()
        val rank = seq.indexOf(from)
        return seq.getOrNull(rank + 1) ?: if (repeat == REPEAT_ALL) seq.first() else null
    }

    fun previous(from: Int = index, repeat: Int = repeatMode): Int? {
        if (songs.isEmpty()) return null
        if (repeat == REPEAT_ONE) return from
        val seq = sequence()
        val rank = seq.indexOf(from)
        return (if (rank > 0) seq[rank - 1] else null) ?: if (repeat == REPEAT_ALL) seq.last() else null
    }

    fun first(): Int? = sequence().firstOrNull()

    // The queue positions after the current song, in play order.
    fun upcoming(): List<Int> {
        val seq = sequence()
        return seq.drop(seq.indexOf(index) + 1)
    }

    fun moveTo(position: Int) {
        if (position in songs.indices) index = position
    }

    // A new queue. Under shuffle it gets a new random order, as on the phone.
    fun replaceAll(items: List<T>, start: Int?, shuffled: (Int) -> List<Int> = { n -> (0 until n).shuffled() }) {
        songs.clear()
        songs += items
        playOrder = shuffled(items.size)
        index = when {
            songs.isEmpty() -> 0
            start != null && start in songs.indices -> start
            else -> first() ?: 0
        }
    }

    // Songs put in at queue position `at`. Added at the end, the
    // listener's songs go before any Autoplay songs still to come; "Play
    // next" puts them right after the current song in play order too.
    fun add(at: Int, items: List<T>, playNext: Boolean = false): Int {
        if (items.isEmpty()) return at
        if (songs.isEmpty()) {
            replaceAll(items, 0)
            return 0
        }
        val size = songs.size
        val adding = items.all(isAutoplay)
        val autoplay = songs.map(isAutoplay)
        val requested = at.coerceIn(0, size)
        val place = if (adding || playNext) requested else ownSongsAt(requested, upcoming(), autoplay)
        val beforeAutoplay = !adding && autoplay.getOrElse(place) { false }
        val rank = shuffleRankFor(playOrder.toIntArray(), place, size, index, playNext = playNext, beforeAutoplay = beforeAutoplay)
        playOrder = insertIntoShuffle(playOrder.toIntArray(), place, items.size, rank).toList()
        songs.addAll(place, items)
        if (index >= place) index += items.size
        return place
    }

    // Songs put back exactly at `at`, as for an undo; the play order is
    // set right afterwards with setOrder.
    fun insertAt(at: Int, items: List<T>) {
        if (items.isEmpty()) return
        val place = at.coerceIn(0, songs.size)
        playOrder = insertIntoShuffle(playOrder.toIntArray(), place, items.size, playOrder.size).toList()
        songs.addAll(place, items)
        if (songs.size > items.size && index >= place) index += items.size
    }

    // Takes out queue positions `from` until `to`. When the current song
    // goes, the next one still there in play order is on, as on the phone.
    fun remove(from: Int, to: Int): Removal {
        val start = from.coerceIn(0, songs.size)
        val end = to.coerceIn(start, songs.size)
        if (start == end) return Removal.Kept
        val count = end - start
        val gone = start until end
        val removedCurrent = index in gone
        val before = sequence()
        val successor = if (removedCurrent) before.drop(before.indexOf(index) + 1).firstOrNull { it !in gone } else null
        playOrder = removeFromShuffle(playOrder.toIntArray(), start, end).toList()
        repeat(count) { songs.removeAt(start) }
        fun shifted(position: Int) = if (position >= end) position - count else position
        index = when {
            songs.isEmpty() -> 0
            !removedCurrent -> shifted(index)
            successor != null -> shifted(successor)
            else -> first() ?: 0
        }
        return when {
            !removedCurrent -> Removal.Kept
            successor != null -> Removal.MovedOn
            else -> Removal.Ended
        }
    }

    fun move(from: Int, to: Int, newFrom: Int) {
        val count = to - from
        if (count <= 0 || from == newFrom) return
        playOrder = moveInShuffle(playOrder.toIntArray(), from, to, newFrom).toList()
        val moved = songs.subList(from, to).toList()
        repeat(count) { songs.removeAt(from) }
        songs.addAll(newFrom, moved)
        index = when (index) {
            in from until to -> newFrom + (index - from)
            else -> {
                // Where the current song sits once the run is out, then back in.
                val without = if (index >= to) index - count else index
                if (without >= newFrom) without + count else without
            }
        }
    }

    fun replace(from: Int, to: Int, items: List<T>) {
        if (to - from == items.size) {
            items.forEachIndexed { i, item -> songs[from + i] = item }
            return
        }
        val wasOn = index in from until to
        remove(from, to)
        add(from, items)
        if (wasOn && items.isNotEmpty()) index = from.coerceAtMost(songs.lastIndex)
    }

    // Sets the play order, as when songs go back for an undo.
    fun setOrder(order: List<Int>) {
        if (order.sorted() == songs.indices.toList()) playOrder = order
    }

    fun state(positionMs: Long, playing: Boolean): QueueState<T> =
        QueueState(songs.toList(), index, positionMs, playing, repeatMode, shuffle, playOrder)
}

// What taking songs out did to the song that is on.
enum class Removal {
    // It is still there.
    Kept,

    // It went, and the next one is on now.
    MovedOn,

    // It went, and nothing was left to play after it.
    Ended,
}

// Whether music carries on on the phone when casting ends. Picking the
// phone moves the music across as it was. When casting ends any other way
// (the device went away, or was stopped from elsewhere), the music pauses
// unless the listener asked for it to keep playing.
fun playsOnReturn(wasPlaying: Boolean, chosen: Boolean, keepPlaying: Boolean): Boolean =
    wasPlaying && (chosen || keepPlaying)
