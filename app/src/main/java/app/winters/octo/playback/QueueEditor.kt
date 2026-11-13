package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
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

    // Runs the app's own queue upkeep `write` (a refill, an Autoplay add, a
    // window top-up), never a person's edit: at once, or, while a blend is
    // under way or being set up, once it is over. `write` checks its own
    // guards again when it runs.
    fun upkeep(write: () -> Unit) = write()
}

// A queue edit that can be taken back.
class QueueUndo internal constructor(internal val undo: () -> Unit)

// Edits to the queue that the app offers to undo: taking out one song,
// clearing all but the song that is on, taking out the songs already
// played, and taking out songs whose files are going. They work on the
// whole queue, past the window the player holds too, through the queue the
// service attaches while it runs, and answer their undo once made.
@OptIn(UnstableApi::class)
@Singleton
class QueueEditor @Inject constructor() {
    private var player: EditableQueue? = null
    private var window: QueueWindow? = null
    private val scope = MainScope()

    fun attach(player: EditableQueue) {
        this.player = player
    }

    fun attachWindow(window: QueueWindow) {
        this.window = window
    }

    fun detach() {
        player = null
        window = null
    }

    // Takes one song out by its key; `done` hears the undo, which puts it
    // back where it was.
    fun remove(key: String, done: (QueueUndo) -> Unit) {
        val window = window ?: return
        val entry = entryOfKey(key) ?: return
        scope.launch {
            val undo = window.takeOut(listOf(entry)) ?: return@launch
            done(QueueUndo(undo))
        }
    }

    // Takes out everything but the song that is on.
    fun clear(done: (QueueUndo) -> Unit) {
        val window = window ?: return
        scope.launch { window.clear()?.let { done(QueueUndo(it)) } }
    }

    // Takes these songs out wherever they are, for files about to be
    // deleted. When the song that is on goes, the next one plays.
    fun removeSongs(trackIds: Set<String>, done: (QueueUndo) -> Unit = {}) {
        val window = window ?: return
        scope.launch { window.removeSongs(trackIds)?.let { done(QueueUndo(it)) } }
    }

    // Takes out the songs played before the one that is on.
    fun removePlayed(done: (QueueUndo) -> Unit) {
        val window = window ?: return
        scope.launch { window.removePlayed()?.let { done(QueueUndo(it)) } }
    }

    // Remove played's rows go for good once `offerEnds` returns: its Undo
    // is no longer offered.
    fun expireFloorsWhen(offerEnds: suspend () -> Unit) {
        scope.launch {
            offerEnds()
            window?.expireFloors()
        }
    }

    // Moves a song already in the queue to play right after the one that is
    // on, shuffle or not. Answers whether it was there to move.
    fun playNext(key: String): Boolean {
        val player = player ?: return false
        val from = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).entryId == key } ?: return false
        if (from == player.currentMediaItemIndex) return false
        val item = player.getMediaItemAt(from)
        player.removeRuns(listOf(from..from))
        player.addNext(listOf(item))
        return true
    }

    // Puts the songs back as they were.
    fun undo(undo: QueueUndo): Boolean {
        undo.undo()
        return true
    }
}
