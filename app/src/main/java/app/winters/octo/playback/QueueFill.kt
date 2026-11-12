package app.winters.octo.playback

import app.winters.octo.catalog.QueueEntryEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow

// Points a test can watch a big list's fill at, to time it against another
// way of filling. Each does nothing in the app.
open class FillHooks {
    // A play's first song was prepared and asked to play.
    open fun startPlaying(listId: Long) {}

    // Up next holds `count` songs after the current one: called once at 10
    // and once when the window is full, for each play.
    open fun upNextHolds(listId: Long, count: Int) {}

    // A Next at the window's edge waited this long for the song it went to.
    open fun nextWaited(millis: Long) {}

    // A list's fill ended, having written this many rows and about this
    // many bytes.
    open fun filled(listId: Long, rows: Long, bytes: Long) {}
}

// How a list too big to play whole reaches the queue once its first song
// plays: its rows written into Room in the background, keyed in play order
// and in shuffled order, joined to the queue in one step, and read by the
// window from then on. The window and the rest of the queue reach a fill
// only through here, so another way of filling can stand in its place.
//
// What the rest of the queue relies on: rows of a generation are kept in
// Room with a key in each order (qk, sk), so a run inserted later (Play
// next, Add to queue, another app's add) takes keys between two rows' keys,
// and a big add takes a base and a step in its gap (addSpacing).
interface QueueFill {
    // Lists that finished filling, with words when they stopped short.
    val joins: SharedFlow<Joined>

    // Words a radio's pool leaves while its seed plays.
    val radioWords: SharedFlow<String>

    var hooks: FillHooks

    // The keys of a big play's start entry, which plays at once: in play
    // order and in shuffled order (first of all for a shuffled play).
    fun startKeys(seed: Long, firstShuffled: Boolean): Pair<Long, Long>

    // Starts filling `listId` once `opened` (the job writing its row and
    // start entry) is done, unless it is filling already; answers the job.
    fun start(listId: Long, queueId: Long, opened: CompletableDeferred<Unit>? = null): Job

    // Up to `limit` rows of a generation after (or before) `from` in an
    // order, nearest first, as the window reads them.
    suspend fun page(queueId: Long, order: QueueOrder, from: QueueEntryEntity?, forward: Boolean, limit: Int, floors: Floors): List<QueueEntryEntity>

    // Whether `row` lies past the place of a list still filling, where the
    // window's reads stop: every row of a list but a play's start entry.
    fun blocks(listId: Long, play: Boolean, row: QueueEntryEntity): Boolean
}
