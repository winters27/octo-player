package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import javax.inject.Inject
import javax.inject.Singleton

// A player whose queue takes the edits the app offers: the phone's own
// player, or the one standing in while music plays on another device.
interface EditableQueue : Player {
    // Songs right after the current one, in the play order too.
    fun addNext(items: List<MediaItem>)

    // Sets the play order for shuffle, as saved or as it was before an edit.
    fun setPlayOrder(order: IntArray)

    // Puts songs back exactly where they were, for an undo. `runs` are
    // queue positions and the songs that go there, lowest first; `order`
    // is the play order once they are all back.
    fun putBack(runs: List<Pair<Int, List<MediaItem>>>, order: IntArray)

    // Takes out runs of songs, last run first.
    fun removeRuns(runs: List<IntRange>)
}

// A queue edit that can be taken back: the queue as it was, its play order
// under shuffle, and the entries left once the edit was made.
class QueueUndo internal constructor(
    internal val items: List<MediaItem>,
    internal val order: IntArray,
    internal val kept: List<String>,
)

// Where songs go back for an undo. `before` is the queue's keys before the
// edit and `now` its keys since. Each run is a queue position and the old
// positions of the songs that go there, lowest first; putting them back in
// that order puts every song at its old position. Nothing when the queue
// has changed some other way since, so an undo never scrambles it.
fun putBackRuns(before: List<String>, now: List<String>): List<Pair<Int, List<Int>>>? {
    val runs = mutableListOf<Pair<Int, MutableList<Int>>>()
    var kept = 0
    before.forEachIndexed { i, key ->
        if (kept < now.size && now[kept] == key) {
            kept++
        } else {
            val last = runs.lastOrNull()
            if (last != null && last.second.last() == i - 1) last.second += i else runs += i to mutableListOf(i)
        }
    }
    return if (kept == now.size && runs.isNotEmpty()) runs else null
}

// Queue positions to take out, as runs from the last to the first, so
// taking out one never moves the next.
fun removalRuns(positions: Collection<Int>): List<IntRange> {
    val runs = mutableListOf<IntRange>()
    positions.toSortedSet().forEach { p ->
        val last = runs.lastOrNull()
        if (last != null && last.last == p - 1) runs[runs.lastIndex] = last.first..p else runs += p..p
    }
    return runs.reversed()
}

// Where these songs sit in the queue, by the queue's song ids, every time
// each one is there.
fun queuePositionsOf(queueTrackIds: List<String>, songs: Set<String>): List<Int> =
    queueTrackIds.indices.filter { queueTrackIds[it] in songs }

// Edits to the queue that the app offers to undo: taking out one song,
// clearing all but the song that is on, and taking out the songs already
// played. It works on the service's player, which attaches while it runs,
// so it acts on the queue as it is this moment and never on a stale copy.
@OptIn(UnstableApi::class)
@Singleton
class QueueEditor @Inject constructor() {
    private var player: EditableQueue? = null

    fun attach(player: EditableQueue) {
        this.player = player
    }

    fun detach() {
        player = null
    }

    // Takes one song out by its key. Answers the undo, or nothing when the
    // song is no longer in the queue.
    fun remove(key: String): QueueUndo? = edit { keys -> listOf(keys.indexOf(key)).filter { it >= 0 } }

    // Takes out everything but the song that is on.
    fun clear(): QueueUndo? = edit { keys -> keys.indices.filter { it != currentMediaItemIndex } }

    // Takes these songs out wherever they are, for files about to be
    // deleted. When the song that is on goes, the next one plays.
    fun removeSongs(trackIds: Set<String>): QueueUndo? = edit {
        queuePositionsOf(List(mediaItemCount) { getMediaItemAt(it).mediaId }, trackIds)
    }

    // Takes out the songs played before the one that is on.
    fun removePlayed(): QueueUndo? = edit { keys ->
        playedOrder(keys, currentMediaItemIndex) { currentTimeline.getPreviousWindowIndex(it, Player.REPEAT_MODE_OFF, shuffleModeEnabled) }
            .map { it.index }
    }

    // Moves a song already in the queue to play right after the one that is
    // on, shuffle or not. Answers whether it was there to move.
    fun playNext(key: String): Boolean {
        val player = player ?: return false
        val from = keysOf(player).indexOf(key)
        if (from < 0 || from == player.currentMediaItemIndex) return false
        val item = player.getMediaItemAt(from)
        player.removeRuns(listOf(from..from))
        player.addNext(listOf(item))
        return true
    }

    // Puts the songs back exactly where they were. Answers whether it could:
    // not once the queue has changed some other way.
    fun undo(undo: QueueUndo): Boolean {
        val player = player ?: return false
        val runs = putBackRuns(undo.items.map { it.entryId.orEmpty() }, keysOf(player)) ?: return false
        if (undo.kept != keysOf(player)) return false
        player.putBack(runs.map { (at, old) -> at to old.map(undo.items::get) }, undo.order)
        return true
    }

    private fun edit(pick: EditableQueue.(List<String>) -> List<Int>): QueueUndo? {
        val player = player ?: return null
        val keys = keysOf(player)
        val positions = player.pick(keys)
        if (positions.isEmpty()) return null
        val items = List(player.mediaItemCount, player::getMediaItemAt)
        val order = shuffleOrderOf(player)
        player.removeRuns(removalRuns(positions))
        return QueueUndo(items, order, keysOf(player))
    }

    private fun keysOf(player: Player): List<String> = List(player.mediaItemCount) { player.getMediaItemAt(it).entryId.orEmpty() }
}
