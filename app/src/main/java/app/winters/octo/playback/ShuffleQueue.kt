package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

// A shuffled queue is two lists: the songs in queue order, and `order`, the
// queue positions in the order they play. These keep the second in step
// with the first as songs come and go, so a song put in never lands at a
// random spot.

// Songs put in at queue positions `at` until `at + count` play, in queue
// order, after the first `rank` entries of the shuffled order. Every song
// already at `at` or later moves along by `count`.
fun insertIntoShuffle(order: IntArray, at: Int, count: Int, rank: Int): IntArray {
    val shifted = order.map { if (it >= at) it + count else it }
    val place = rank.coerceIn(0, shifted.size)
    return (shifted.subList(0, place) + (at until at + count) + shifted.subList(place, shifted.size)).toIntArray()
}

// How many songs of the shuffled order play before songs put in at queue
// position `at` of a queue `size` long, while `current` is on:
// - "Play next" (`playNext`): right after the song that is on;
// - at the end of the queue ("Add to queue"): after all of them;
// - right after the song that is on in the queue: right after it;
// - just before a song Autoplay added: just before it, so the listener's
//   own songs play first;
// - anywhere else: right after the song before them in the queue.
fun shuffleRankFor(order: IntArray, at: Int, size: Int, current: Int, playNext: Boolean = false, beforeAutoplay: Boolean = false): Int {
    fun after(position: Int) = order.indexOf(position).let { if (it < 0) order.size else it + 1 }
    return when {
        playNext && current >= 0 -> after(current)
        at >= size -> order.size
        current >= 0 && at == current + 1 -> after(current)
        beforeAutoplay -> order.indexOf(at).let { if (it < 0) order.size else it }
        at == 0 -> 0
        else -> after(at - 1)
    }
}

// Where songs the listener adds at queue position `at` go. Added at the end,
// they go before the first Autoplay song still to come (`upcoming` is the
// queue positions after the current song, in play order), so the
// listener's own songs play before Autoplay's. Anywhere else, at `at`.
fun ownSongsAt(at: Int, upcoming: List<Int>, autoplay: List<Boolean>): Int {
    if (at < autoplay.size) return at
    return upcoming.firstOrNull { autoplay.getOrElse(it) { false } } ?: at
}

// The queue positions after the current song, in the order they will play.
internal fun upcoming(player: Player): List<Int> {
    val timeline = player.currentTimeline
    val shuffle = player.shuffleModeEnabled
    return buildList {
        var i = player.currentMediaItemIndex
        while (size < timeline.windowCount) {
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
            if (i == C.INDEX_UNSET) break
            add(i)
        }
    }
}

// The shuffled order once queue positions `from` until `to` are taken out:
// the rest keep playing in the same order.
fun removeFromShuffle(order: IntArray, from: Int, to: Int): IntArray {
    val count = to - from
    return order.filter { it < from || it >= to }.map { if (it >= to) it - count else it }.toIntArray()
}

// The shuffled order once queue positions `from` until `to` move to start
// at `newFrom`: each song keeps its place in the play order.
fun moveInShuffle(order: IntArray, from: Int, to: Int, newFrom: Int): IntArray {
    val size = order.size
    val moved = (from until to).toList()
    val rest = (0 until size).filter { it < from || it >= to }
    val queue = rest.subList(0, newFrom) + moved + rest.subList(newFrom, rest.size)
    // Where each old queue position ends up.
    val newPosition = IntArray(size)
    queue.forEachIndexed { position, old -> newPosition[old] = position }
    return order.map { newPosition[it] }.toIntArray()
}

// The play order for a queue under shuffle, kept by the rules above. Only a
// queue built from nothing is shuffled at random.
@OptIn(UnstableApi::class)
class QueueShuffleOrder(private val shuffled: IntArray, private val random: Random = Random.Default) : ShuffleOrder {
    // Where each queue position sits in the play order.
    private val rankOf = IntArray(shuffled.size).also { ranks -> shuffled.forEachIndexed { rank, position -> ranks[position] = rank } }

    // The play order, first to last.
    val playOrder: IntArray get() = shuffled.copyOf()

    override fun getLength(): Int = shuffled.size

    override fun getNextIndex(index: Int): Int = shuffled.getOrNull(rankOf[index] + 1) ?: C.INDEX_UNSET

    override fun getPreviousIndex(index: Int): Int = shuffled.getOrNull(rankOf[index] - 1) ?: C.INDEX_UNSET

    override fun getLastIndex(): Int = shuffled.lastOrNull() ?: C.INDEX_UNSET

    override fun getFirstIndex(): Int = shuffled.firstOrNull() ?: C.INDEX_UNSET

    // A new queue is shuffled at random. Songs added to one go after the
    // song before them in the queue, or last when added at the end; the
    // player moves them to the right place when it knows more.
    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        if (shuffled.isEmpty()) return QueueShuffleOrder((0 until insertionCount).shuffled(random).toIntArray(), random)
        val rank = shuffleRankFor(shuffled, insertionIndex, shuffled.size, current = -1)
        return QueueShuffleOrder(insertIntoShuffle(shuffled, insertionIndex, insertionCount, rank), random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder =
        QueueShuffleOrder(removeFromShuffle(shuffled, indexFrom, indexToExclusive), random)

    override fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): ShuffleOrder =
        QueueShuffleOrder(moveInShuffle(shuffled, indexFrom, indexToExclusive, newIndexFrom), random)

    override fun cloneAndClear(): ShuffleOrder = QueueShuffleOrder(IntArray(0), random)
}
