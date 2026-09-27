package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

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

// The play order for a queue under shuffle, kept by the rules in
// ShuffleQueue.kt (shared core). Only a queue built from nothing is
// shuffled at random.
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
