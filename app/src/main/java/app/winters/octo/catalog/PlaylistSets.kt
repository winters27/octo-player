package app.winters.octo.catalog

import app.winters.octo.playlists.PlaylistFolderTree

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// How many rows one transaction of a bulk playlist write or delete takes.
const val SET_PAGE = 500

// A walk's hold on the item set a playlist showed when the walk began, with
// the baselines its pages read through: the edit log's last seq and the
// next free item key at that moment.
data class PinnedSet(val playlistId: String, val itemSet: Long, val seq0: Long, val nextKey0: Long)

// A row as a pinned walk reads it.
data class SetItem(val ord: Long, val itemKey: Long, val trackId: String, val relinkKey: String, val serverSongId: String?)

// A row a bulk write puts in a new set, in play order. One that keeps its
// key (a row kept from the set before) names it; a new one takes a fresh one.
data class NewItem(val trackId: String, val relinkKey: String, val serverSongId: String? = null, val itemKey: Long? = null)

// A row's place in a set.
data class RowAt(val ord: Long, val itemKey: Long)

// A playlist's shown item and base sets.
data class SetsOf(val id: String, val itemSet: Long, val baseSet: Long)

// A walk's pin of a playlist, by its owner; and a set the edit log holds.
data class PinRow(val owner: String, val playlistId: String)

data class LogSet(val playlistId: String, val itemSet: Long)

// What the edit log keeps of each edit.
object EditOp {
    const val MOVE = 0
    const val REMOVE = 1
    const val RESTORE = 2
}

// The sets of one playlist a set mutex holds in a coroutine's context, so a
// call that takes one already held runs at once.
private class HeldSets(val ids: Set<String>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HeldSets>
}

// Points in the set writes where a test can step in.
internal open class SetHooks {
    // A page of a new set was written.
    open suspend fun wrotePage(playlistId: String, itemSet: Long, page: Int) {}

    // A page of an old set was deleted.
    open suspend fun deletedPage(playlistId: String, itemSet: Long) {}

    // A page of a set's edit log was deleted at its last pin.
    open suspend fun clearedLogPage(playlistId: String, itemSet: Long) {}
}

// A playlist's songs live in numbered sets: the playlist's itemSet names the
// rows it shows, its baseSet the base its family sends are worked out from
// (playlist_synced_song). A whole new list of songs (a sync, a new playlist
// from a list, a restore) is written as a new set, a page of rows to a
// transaction, then shown in one short compare-and-set of the playlist row,
// and the old set is deleted a page at a time; a reader sees the old rows
// until that step and the new ones after it, at any size. A playlist being
// deleted shows set -1, which every list of playlists leaves out, until its
// rows are gone.
//
// Each playlist has a set mutex, the last area mutex in the lock order:
// every bulk write of a playlist and every single edit of it take it, so
// two writers of one playlist never share a set. A new set's number is one
// past every set the playlist's rows use and past the one it shows.
//
// A walk of a playlist that spans transactions pins the set it began on
// (`pin`), so the set outlives a flip until the walk lets it go
// (`releasePin`, the only code that deletes a pin). Single edits write the
// shown set in place; while any walk pins it, each move, removal and
// putting back also writes the row as it was into the set's edit log, in
// the edit's own transaction, and a walk reads every page through `page`,
// which joins the set's untouched rows with each edited row's first log row
// after the walk's baseline: the set exactly as it stood when it was pinned.
@Singleton
class PlaylistSets internal constructor(
    private val db: CatalogDatabase,
    private val scope: CoroutineScope,
) {
    @Inject constructor(db: CatalogDatabase) : this(db, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val dao = db.playlistSetDao()
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val settling = ArrayList<Job>()

    internal var hooks = SetHooks()

    // Runs `block` holding the playlist's set mutex; at once when this
    // coroutine holds it already.
    suspend fun <T> withSet(playlistId: String, block: suspend () -> T): T {
        val held = currentCoroutineContext()[HeldSets]
        if (held != null && playlistId in held.ids) return block()
        val mutex = mutexes.computeIfAbsent(playlistId) { Mutex() }
        return mutex.withLock { withContext(HeldSets((held?.ids ?: emptySet()) + playlistId)) { block() } }
    }

    // Whether this coroutine holds the playlist's set mutex.
    suspend fun holdsSet(playlistId: String): Boolean = currentCoroutineContext()[HeldSets]?.ids?.contains(playlistId) == true

    // A new item set for a playlist: one past every set its rows use and
    // past the one it shows. Null when it is gone or being deleted. Under
    // its set mutex.
    internal suspend fun newItemSet(playlistId: String): Long? {
        check(holdsSet(playlistId)) { "an item set is numbered under its playlist's set mutex" }
        val shown = dao.itemSetOf(playlistId)?.takeIf { it >= 0 } ?: return null
        return maxOf(dao.maxItemSet(playlistId) ?: -1, shown) + 1
    }

    // The same for a base set. Null when the playlist is gone.
    internal suspend fun newBaseSet(playlistId: String): Long? {
        check(holdsSet(playlistId)) { "a base set is numbered under its playlist's set mutex" }
        val shown = dao.baseSetOf(playlistId) ?: return null
        return maxOf(dao.maxBaseSet(playlistId) ?: -1, shown) + 1
    }

    // Writes one page of a new set, in one transaction: the rows go in play
    // order from rank `fromRank`, ORD_STEP apart, and the rows with no key
    // take one block of fresh keys. At most SET_PAGE rows.
    internal suspend fun writeRows(playlistId: String, itemSet: Long, fromRank: Long, rows: List<NewItem>) {
        require(rows.size <= SET_PAGE)
        if (rows.isEmpty()) return
        db.withTransaction {
            val fresh = rows.count { it.itemKey == null }
            var next = 0L
            if (fresh > 0) {
                dao.raiseNextItemKey(fresh)
                next = dao.nextItemKey() - fresh
            }
            dao.insertItems(
                rows.mapIndexed { i, row ->
                    PlaylistItemEntity(
                        playlistId = playlistId,
                        itemSet = itemSet,
                        trackId = row.trackId,
                        relinkKey = row.relinkKey,
                        ord = (fromRank + i) * ORD_STEP,
                        itemKey = row.itemKey ?: next++,
                        serverSongId = row.serverSongId,
                    )
                },
            )
        }
    }

    // Writes a whole new set from `pages` of rows in play order, a page of
    // at most SET_PAGE to a transaction, and answers its number; null when
    // the playlist is gone. A write that fails or is cancelled leaves no
    // rows behind. Under the playlist's set mutex.
    internal suspend fun writeSet(playlistId: String, pages: suspend (suspend (List<NewItem>) -> Unit) -> Unit): Long? {
        val set = newItemSet(playlistId) ?: return null
        var done = false
        try {
            var rank = 0L
            var page = 0
            pages { rows ->
                for (chunk in rows.chunked(SET_PAGE)) {
                    writeRows(playlistId, set, rank, chunk)
                    rank += chunk.size
                    hooks.wrotePage(playlistId, set, ++page)
                }
            }
            done = true
            return set
        } finally {
            if (!done) withContext(NonCancellable) { deleteSet(playlistId, set) }
        }
    }

    // A new playlist, `row`, with the songs `pages` give in play order: the
    // playlist goes in empty, its songs go into a new set, and it shows them
    // in one step, so a reader never sees part of them. Answers false when
    // it was deleted meanwhile.
    suspend fun create(row: PlaylistEntity, pages: suspend (suspend (List<NewItem>) -> Unit) -> Unit): Boolean {
        db.userDao().insertPlaylist(row.copy(itemSet = 0, baseSet = 0))
        return withSet(row.id) {
            val set = writeSet(row.id, pages) ?: return@withSet false
            val shown = db.withTransaction { dao.flipItems(row.id, 0, set) == 1 }
            if (!shown) deleteSet(row.id, set)
            shown
        }
    }

    // Deletes a set of a playlist's rows a page at a time, each page its own
    // transaction that first checks that no walk pins the set and that the
    // playlist does not show it. Answers whether the set is gone; one a walk
    // pins goes when its last pin does.
    internal suspend fun deleteSet(playlistId: String, itemSet: Long): Boolean {
        while (true) {
            val deleted = db.withTransaction {
                if (dao.setPinned(playlistId, itemSet) || dao.itemSetOf(playlistId) == itemSet) return@withTransaction null
                val ids = dao.setRowIds(playlistId, itemSet, SET_PAGE)
                if (ids.isNotEmpty()) dao.deleteItems(ids)
                ids.size
            } ?: return false
            if (deleted > 0) hooks.deletedPage(playlistId, itemSet)
            if (deleted < SET_PAGE) return true
        }
    }

    // Deletes a base set a page at a time; nothing walks a base.
    internal suspend fun deleteBaseSet(playlistId: String, baseSet: Long) {
        while (true) {
            val deleted = db.withTransaction {
                if (dao.baseSetOf(playlistId) == baseSet) return@withTransaction 0
                val songs = dao.baseSongs(playlistId, baseSet, SET_PAGE)
                if (songs.isNotEmpty()) dao.deleteBase(playlistId, baseSet, songs)
                songs.size
            }
            if (deleted < SET_PAGE) return
        }
    }

    // A new playlist shows the set written for it, unless it was deleted
    // or shows another meanwhile.
    internal suspend fun flipNew(playlistId: String, itemSet: Long): Boolean = db.withTransaction { dao.flipItems(playlistId, 0, itemSet) == 1 }

    // Shows a new base, kept from now on, when the playlist still shows `from`.
    internal suspend fun flipBase(playlistId: String, from: Long, to: Long): Boolean = db.withTransaction { dao.flipBase(playlistId, from, to) == 1 }

    // Deletes a playlist: it shows set -1 at once, so it leaves every list
    // of playlists, then its rows go a page at a time, and the playlist row
    // once none is left. A set a walk pins stays until that walk lets it go,
    // and the row with it. With `updatedAt`, only while it is unchanged
    // since then. Answers whether it was deleted.
    suspend fun deletePlaylist(playlistId: String, updatedAt: Long? = null): Boolean = withSet(playlistId) {
        val base = newBaseSet(playlistId) ?: return@withSet false
        val marked = db.withTransaction { dao.markDeleted(playlistId, base, updatedAt == null, updatedAt ?: 0) == 1 }
        if (marked) sweepOne(playlistId)
        marked
    }

    // At start, once: the work rows older launches left go (the library's
    // open deletes none): each of their pins is let go as releasePin lets
    // one go, which takes its set's edit log and lets an old set go; an edit
    // log no pin holds goes; and every other table's rows of their owners
    // (sweepOlderLaunches). Then the sets no playlist shows and no walk
    // holds, left by writes the app's end cut off, go a page at a time, and
    // the rows and row of a playlist whose delete was cut off.
    suspend fun sweep() {
        releaseOlderPins()
        clearLooseLogs()
        db.sweepOlderLaunches(SWEPT_WORK_TABLES - PIN_TABLE)
        for (sets in dao.playlistSets()) withSet(sets.id) { sweepOne(sets.id) }
    }

    // Every pin an older launch's owner holds, below this launch's range and
    // above it, a page of OWNER_PAGE at a time, each let go.
    private suspend fun releaseOlderPins() {
        val mark = LaunchMark.prefix
        for (below in listOf(true, false)) {
            while (true) {
                val pins = if (below) dao.pinsBelow(mark, OWNER_PAGE) else dao.pinsFrom(prefixEnd(mark), OWNER_PAGE)
                for (pin in pins) releasePin(pin.owner, pin.playlistId)
                if (pins.size < OWNER_PAGE) break
            }
        }
    }

    // An edit log no pin holds (its last pin's release was cut off before
    // its log went) goes, set by set, in its playlist's key order.
    private suspend fun clearLooseLogs() {
        var after: LogSet? = dao.firstLogSet() ?: return
        while (after != null) {
            if (!dao.setPinned(after.playlistId, after.itemSet)) clearLog(after.playlistId, after.itemSet)
            after = dao.logSetAfter(after.playlistId, after.itemSet)
        }
    }

    private suspend fun sweepOne(playlistId: String) {
        val shown = dao.itemSetOf(playlistId) ?: return
        var after = Long.MIN_VALUE
        while (true) {
            val set = dao.nextItemSet(playlistId, after) ?: break
            if (set != shown) deleteSet(playlistId, set)
            after = set
        }
        val base = if (shown < 0) null else dao.baseSetOf(playlistId)
        var afterBase = Long.MIN_VALUE
        while (true) {
            val set = dao.nextBaseSet(playlistId, afterBase) ?: break
            if (set != base) deleteBaseSet(playlistId, set)
            afterBase = set
        }
        if (shown < 0) finishDelete(playlistId)
    }

    // The row of a playlist being deleted goes once no rows of it are left.
    private suspend fun finishDelete(playlistId: String) {
        db.withTransaction { if (!dao.hasItems(playlistId) && !dao.hasBase(playlistId)) dao.deleteMarked(playlistId) }
    }

    // Pins the item set a playlist shows for `owner`'s walk, in one
    // transaction that also notes the edit log's baselines and runs
    // `inPin`, which reads the set as the pin has it. Null when the
    // playlist is gone or being deleted.
    suspend fun <T> pin(owner: String, playlistId: String, inPin: suspend (PinnedSet) -> T): Pair<PinnedSet, T>? = db.withTransaction {
        val shown = dao.itemSetOf(playlistId)?.takeIf { it >= 0 } ?: return@withTransaction null
        dao.insertPin(owner, playlistId, shown)
        val set = PinnedSet(playlistId, shown, dao.lastSeq(), dao.nextItemKey())
        set to inPin(set)
    }

    suspend fun pin(owner: String, playlistId: String): PinnedSet? = pin(owner, playlistId) { }?.first

    // Lets `owner`'s pin of a playlist go: the only code that deletes a pin.
    // At the last pin on that set, its edit log goes a page at a time, each
    // page stopping if a walk pinned the set meanwhile, and a set the
    // playlist no longer shows is deleted in the background, a page at a
    // time under the playlist's set mutex, then the playlist row when it is
    // being deleted and nothing of it is left.
    suspend fun releasePin(owner: String, playlistId: String) {
        val set = db.withTransaction {
            val set = dao.pinOf(owner, playlistId) ?: return@withTransaction null
            dao.deletePin(owner, playlistId)
            set
        } ?: return
        if (dao.setPinned(playlistId, set)) return
        clearLog(playlistId, set)
        if (dao.itemSetOf(playlistId) != set) settle(playlistId, set)
    }

    // Lets every pin `owner` holds go, each as releasePin lets one go.
    suspend fun releasePins(owner: String) {
        for (playlistId in dao.pinnedBy(owner)) releasePin(owner, playlistId)
    }

    private suspend fun clearLog(playlistId: String, itemSet: Long) {
        while (true) {
            val deleted = db.withTransaction {
                if (dao.setPinned(playlistId, itemSet)) return@withTransaction 0
                val seqs = dao.logSeqs(playlistId, itemSet, SET_PAGE)
                if (seqs.isNotEmpty()) dao.deleteLog(seqs)
                seqs.size
            }
            if (deleted < SET_PAGE) return
            hooks.clearedLogPage(playlistId, itemSet)
        }
    }

    private fun settle(playlistId: String, itemSet: Long) {
        val job = scope.launch {
            withSet(playlistId) {
                deleteSet(playlistId, itemSet)
                if (dao.itemSetOf(playlistId) == -1L) sweepOne(playlistId)
            }
        }
        synchronized(settling) {
            settling.removeAll { !it.isActive }
            settling += job
        }
    }

    // Waits for every set deletion a released pin started.
    internal suspend fun idle() {
        while (true) {
            val jobs = synchronized(settling) { settling.filter { it.isActive } }
            if (jobs.isEmpty()) return
            jobs.joinAll()
        }
    }

    // A page of a pinned set as it stood when it was pinned, in play order,
    // from just past (`afterOrd`, `afterKey`): the rows no edit touched
    // since, and each edited row as its first log row after the pin has it,
    // in one statement, so a page is one read of one state.
    suspend fun page(set: PinnedSet, afterOrd: Long, afterKey: Long, limit: Int): List<SetItem> =
        dao.baselinePage(set.playlistId, set.itemSet, set.seq0, set.nextKey0, afterOrd, afterKey, limit)

    // Walks a pinned set as it stood, a page at a time.
    suspend fun walk(set: PinnedSet, pageSize: Int = SET_PAGE, each: suspend (List<SetItem>) -> Unit) {
        var afterOrd = Long.MIN_VALUE
        var afterKey = Long.MIN_VALUE
        while (true) {
            val rows = page(set, afterOrd, afterKey, pageSize)
            if (rows.isEmpty()) return
            each(rows)
            if (rows.size < pageSize) return
            afterOrd = rows.last().ord
            afterKey = rows.last().itemKey
        }
    }

    // Inside a pin's transaction, where the set's rows are as it pins them:
    // the place of the row with this key, or null when the set has none.
    suspend fun rekey(set: PinnedSet, itemKey: Long): RowAt? = dao.rowAt(set.playlistId, set.itemSet, itemKey)

    // Inside a pin's transaction: the place of a song's first row in the
    // set, or null when the set does not hold it.
    suspend fun keyOf(set: PinnedSet, trackId: String): RowAt? {
        val ord = dao.firstOrdOf(set.playlistId, set.itemSet, trackId) ?: return null
        return dao.firstKeyAt(set.playlistId, set.itemSet, ord)?.let { RowAt(ord, it) }
    }

    // One single edit of a playlist: under its set mutex, in one
    // transaction, with the set it shows; null, running nothing, when it is
    // gone or being deleted. The edit writes its log rows (logRows,
    // logRestore) in `block` before it changes the rows.
    suspend fun <T> edit(playlistId: String, block: suspend (itemSet: Long) -> T): T? = withSet(playlistId) {
        db.withTransaction {
            val shown = dao.itemSetOf(playlistId)?.takeIf { it >= 0 } ?: return@withTransaction null
            block(shown)
        }
    }

    // Inside a single edit's transaction, before it moves or removes these
    // rows: while any walk pins the set, each row as it is now goes into the
    // set's edit log. At most SET_PAGE keys.
    suspend fun logRows(op: Int, playlistId: String, itemSet: Long, keys: List<Long>) {
        require(op == EditOp.MOVE || op == EditOp.REMOVE)
        require(keys.size <= SET_PAGE)
        if (keys.isNotEmpty()) dao.logRows(op, playlistId, itemSet, keys)
    }

    // Inside a single edit's transaction that puts a row back: while any
    // walk pins the set, the row as it goes back goes into the edit log.
    suspend fun logRestore(playlistId: String, itemSet: Long, row: PlaylistItemEntity) =
        dao.logRestore(playlistId, itemSet, row.itemKey, row.ord, row.trackId, row.relinkKey, row.serverSongId)

    // A sync's new rows and base shown with the playlist's new details from
    // `updated`, in one transaction that first checks no swap ran since the
    // sync read the library (`swaps` against `swapCount`), and then that the
    // playlist is still as `expected` says: not edited, unlinked or deleted,
    // and showing the same sets. Shown, or why not.
    internal suspend fun flipSync(
        expected: PlaylistEntity,
        updated: PlaylistEntity,
        itemSet: Long,
        baseSet: Long,
        swapCount: Long,
        swaps: suspend () -> Long,
    ): Flip = db.withTransaction {
        if (swaps() != swapCount) return@withTransaction Flip.Swapped
        val changed = dao.flipSync(
            expected.id, itemSet, baseSet, updated.name, updated.updatedAt, updated.syncedAt, updated.syncedName, updated.serverStamp,
            expected.updatedAt, expected.serverId, expected.sourceId, expected.itemSet, expected.baseSet,
        )
        if (changed == 1) Flip.Shown else Flip.Changed
    }

    // A base the base step seeded, shown in one transaction only while the
    // playlist is as the step read it: not edited (updatedAt and syncedAt
    // both as read), linked to the same server playlist of the same server,
    // not a family playlist, and showing the same base.
    internal suspend fun flipSeeded(expected: PlaylistEntity, baseSet: Long): Boolean = db.withTransaction {
        dao.flipSeeded(expected.id, expected.baseSet, baseSet, expected.updatedAt, expected.syncedAt, expected.serverId, expected.sourceId) == 1
    }

    // A phone playlist found to be a server playlist's twin: linked to it,
    // marked a family playlist or not (with what the listener may do, when
    // it is one), and its base the new set `baseSet`, in one transaction.
    internal suspend fun flipTwin(
        playlistId: String,
        fromBase: Long,
        baseSet: Long,
        serverId: String,
        sourceId: String,
        syncedAt: Long,
        name: String,
        stamp: String,
        family: Boolean,
        rights: FamilyRights?,
    ): Boolean = db.withTransaction {
        dao.flipTwin(
            playlistId, fromBase, baseSet, serverId, sourceId, syncedAt, name, stamp, family, rights != null,
            rights?.add == true, rights?.remove == true, rights?.rename == true, rights?.delete == true,
        ) == 1
    }

    // Turns playlists back into ones only on the phone: unlinkPlaylist's
    // rule for one, unlinkServer's for every one kept with `sourceId`, and
    // unlinkServersExcept's for every one kept with a server not in `kept`.
    // Each also shows a fresh empty base under its set mutex, and its old
    // base goes a page at a time.
    suspend fun unlinkPlaylist(playlistId: String) = unlink(playlistId) { base -> dao.unlinkPlaylist(playlistId, base) }

    suspend fun unlinkServer(sourceId: String) {
        for (id in dao.keptWith(sourceId)) unlink(id) { base -> dao.unlinkServer(id, sourceId, base) }
    }

    suspend fun unlinkServersExcept(kept: List<String>) {
        for (id in dao.keptWithout(kept)) unlink(id) { base -> dao.unlinkServerNotIn(id, kept, base) }
    }

    private suspend fun unlink(playlistId: String, write: suspend (base: Long) -> Int) = withSet(playlistId) {
        val old = dao.baseSetOf(playlistId) ?: return@withSet
        val base = newBaseSet(playlistId) ?: return@withSet
        val serverId = dao.serverIdOf(playlistId)
        val unlinked = db.withTransaction {
            val done = write(base)
            // Its folder placement stays, as the phone-only playlist's.
            if (done == 1 && serverId != null) db.playlistFolderDao().turnLocal(serverId, PlaylistFolderTree.localKey(playlistId))
            done
        }
        if (unlinked == 1) deleteBaseSet(playlistId, old)
    }
}

// How a sync's flip went: shown, refused because a swap ran since the sync
// read the library, or refused because the playlist changed.
enum class Flip { Shown, Swapped, Changed }

// What the listener may do with a family playlist.
data class FamilyRights(val add: Boolean, val remove: Boolean, val rename: Boolean, val delete: Boolean)

// A page of a pinned set as it stood at its baseline (`seq0`, `nextKey0`),
// in (ord, itemKey) order from just past the cursor, as one statement over
// two arms. Arm A is the set's rows that existed then and that no edit has
// logged since. Arm B is, for each such row an edit logged since, its first
// log row after the baseline when that is a move or a removal: the row at
// its place and with its song at the baseline. A first log row that puts a
// row back means the row was not in the set then, so neither arm has it.
internal const val BASELINE_PAGE_SQL = """
    SELECT i.ord AS ord, i.itemKey AS itemKey, i.trackId AS trackId, i.relinkKey AS relinkKey, i.serverSongId AS serverSongId
    FROM playlist_item i
    WHERE i.playlistId = :playlistId AND i.itemSet = :itemSet AND i.itemKey < :nextKey0
      AND i.ord >= :afterOrd AND CASE WHEN i.ord = :afterOrd THEN i.itemKey > :afterKey ELSE 1 END
      AND NOT EXISTS (
          SELECT 1 FROM playlist_edit_log f
          WHERE f.playlistId = :playlistId AND f.itemSet = :itemSet AND f.itemKey = i.itemKey AND f.seq > :seq0
      )
    UNION ALL
    SELECT e.ord, e.itemKey, e.trackId, e.relinkKey, e.serverSongId
    FROM playlist_edit_log e
    WHERE e.playlistId = :playlistId AND e.itemSet = :itemSet AND e.itemKey < :nextKey0 AND e.op IN (0, 1)
      AND e.ord >= :afterOrd AND CASE WHEN e.ord = :afterOrd THEN e.itemKey > :afterKey ELSE 1 END
      AND e.seq = (
          SELECT MIN(f.seq) FROM playlist_edit_log f
          WHERE f.playlistId = :playlistId AND f.itemSet = :itemSet AND f.itemKey = e.itemKey AND f.seq > :seq0
      )
    ORDER BY ord, itemKey LIMIT :limit
"""

// A single edit's log rows for moved or removed rows, written only while a
// walk pins the set, as the rows are before the edit.
internal const val LOG_ROWS_SQL = """
    INSERT INTO playlist_edit_log (playlistId, itemSet, op, itemKey, ord, trackId, relinkKey, serverSongId)
    SELECT i.playlistId, i.itemSet, :op, i.itemKey, i.ord, i.trackId, i.relinkKey, i.serverSongId
    FROM playlist_item i
    WHERE i.playlistId = :playlistId AND i.itemSet = :itemSet AND i.itemKey IN (:keys)
      AND EXISTS (SELECT 1 FROM playlist_set_pin p WHERE p.playlistId = :playlistId AND p.itemSet = :itemSet)
"""

internal const val LOG_RESTORE_SQL = """
    INSERT INTO playlist_edit_log (playlistId, itemSet, op, itemKey, ord, trackId, relinkKey, serverSongId)
    SELECT :playlistId, :itemSet, 2, :itemKey, :ord, :trackId, :relinkKey, :serverSongId
    WHERE EXISTS (SELECT 1 FROM playlist_set_pin p WHERE p.playlistId = :playlistId AND p.itemSet = :itemSet)
"""

// The statements of PlaylistSets.
@Dao
interface PlaylistSetDao {
    @Query("SELECT itemSet FROM playlist WHERE id = :playlistId")
    suspend fun itemSetOf(playlistId: String): Long?

    @Query("SELECT baseSet FROM playlist WHERE id = :playlistId")
    suspend fun baseSetOf(playlistId: String): Long?

    @Query("SELECT MAX(itemSet) FROM playlist_item WHERE playlistId = :playlistId")
    suspend fun maxItemSet(playlistId: String): Long?

    @Query("SELECT MAX(baseSet) FROM playlist_synced_song WHERE playlistId = :playlistId")
    suspend fun maxBaseSet(playlistId: String): Long?

    @Query("SELECT MIN(itemSet) FROM playlist_item WHERE playlistId = :playlistId AND itemSet > :after")
    suspend fun nextItemSet(playlistId: String, after: Long): Long?

    @Query("SELECT MIN(baseSet) FROM playlist_synced_song WHERE playlistId = :playlistId AND baseSet > :after")
    suspend fun nextBaseSet(playlistId: String, after: Long): Long?

    @Query("SELECT id, itemSet, baseSet FROM playlist")
    suspend fun playlistSets(): List<SetsOf>

    @Query("UPDATE playlist_meta SET nextItemKey = nextItemKey + :n WHERE id = 0")
    suspend fun raiseNextItemKey(n: Int)

    @Query("SELECT nextItemKey FROM playlist_meta WHERE id = 0")
    suspend fun nextItemKey(): Long

    @Insert
    suspend fun insertItems(rows: List<PlaylistItemEntity>)

    @Query("INSERT OR REPLACE INTO playlist_set_pin (owner, playlistId, itemSet) VALUES (:owner, :playlistId, :itemSet)")
    suspend fun insertPin(owner: String, playlistId: String, itemSet: Long)

    @Query("SELECT itemSet FROM playlist_set_pin WHERE owner = :owner AND playlistId = :playlistId")
    suspend fun pinOf(owner: String, playlistId: String): Long?

    // The playlists an owner pins a set of, by the pins' key.
    @Query("SELECT playlistId FROM playlist_set_pin WHERE owner = :owner")
    suspend fun pinnedBy(owner: String): List<String>

    // Pins of owners below `mark`, and from `end` on, by the pins' key.
    @Query("SELECT owner, playlistId FROM playlist_set_pin WHERE owner < :mark ORDER BY owner, playlistId LIMIT :limit")
    suspend fun pinsBelow(mark: String, limit: Int): List<PinRow>

    @Query("SELECT owner, playlistId FROM playlist_set_pin WHERE owner >= :end ORDER BY owner, playlistId LIMIT :limit")
    suspend fun pinsFrom(end: String, limit: Int): List<PinRow>

    // The edit log's sets in key order: the first, and the next after one.
    @Query("SELECT playlistId, itemSet FROM playlist_edit_log ORDER BY playlistId, itemSet LIMIT 1")
    suspend fun firstLogSet(): LogSet?

    @Query(
        "SELECT playlistId, itemSet FROM playlist_edit_log WHERE playlistId > :playlistId OR (playlistId = :playlistId AND itemSet > :itemSet) " +
            "ORDER BY playlistId, itemSet LIMIT 1",
    )
    suspend fun logSetAfter(playlistId: String, itemSet: Long): LogSet?

    @Query("DELETE FROM playlist_set_pin WHERE owner = :owner AND playlistId = :playlistId")
    suspend fun deletePin(owner: String, playlistId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_set_pin WHERE playlistId = :playlistId AND itemSet = :itemSet)")
    suspend fun setPinned(playlistId: String, itemSet: Long): Boolean

    // The edit log's last seq: past every row of every set's log.
    @Query("SELECT IFNULL(MAX(seq), 0) FROM playlist_edit_log")
    suspend fun lastSeq(): Long

    @Query("SELECT id FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet LIMIT :limit")
    suspend fun setRowIds(playlistId: String, itemSet: Long, limit: Int): List<Long>

    @Query("DELETE FROM playlist_item WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<Long>)

    @Query("SELECT songId FROM playlist_synced_song WHERE playlistId = :playlistId AND baseSet = :baseSet LIMIT :limit")
    suspend fun baseSongs(playlistId: String, baseSet: Long, limit: Int): List<String>

    @Query("DELETE FROM playlist_synced_song WHERE playlistId = :playlistId AND baseSet = :baseSet AND songId IN (:songIds)")
    suspend fun deleteBase(playlistId: String, baseSet: Long, songIds: List<String>)

    @Query("SELECT seq FROM playlist_edit_log WHERE playlistId = :playlistId AND itemSet = :itemSet LIMIT :limit")
    suspend fun logSeqs(playlistId: String, itemSet: Long, limit: Int): List<Long>

    @Query("DELETE FROM playlist_edit_log WHERE seq IN (:seqs)")
    suspend fun deleteLog(seqs: List<Long>)

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_item WHERE playlistId = :playlistId)")
    suspend fun hasItems(playlistId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_synced_song WHERE playlistId = :playlistId)")
    suspend fun hasBase(playlistId: String): Boolean

    @Query("DELETE FROM playlist WHERE id = :playlistId AND itemSet = -1")
    suspend fun deleteMarked(playlistId: String): Int

    @Query("UPDATE playlist SET itemSet = :to WHERE id = :playlistId AND itemSet = :from")
    suspend fun flipItems(playlistId: String, from: Long, to: Long): Int

    @Query("UPDATE playlist SET baseSet = :to, syncedSongsKept = 1, syncedSongs = NULL WHERE id = :playlistId AND baseSet = :from AND itemSet >= 0")
    suspend fun flipBase(playlistId: String, from: Long, to: Long): Int

    @Query(
        """
        UPDATE playlist SET baseSet = :to, syncedSongsKept = 1, syncedSongs = NULL
        WHERE id = :playlistId AND baseSet = :from AND itemSet >= 0 AND family = 0
          AND updatedAt = :updatedAt AND syncedAt IS :syncedAt AND serverId IS :serverId AND sourceId IS :sourceId
        """,
    )
    suspend fun flipSeeded(playlistId: String, from: Long, to: Long, updatedAt: Long, syncedAt: Long?, serverId: String?, sourceId: String?): Int

    @Query("SELECT serverId FROM playlist WHERE id = :playlistId")
    suspend fun serverIdOf(playlistId: String): String?

    @Query(
        "UPDATE playlist SET itemSet = -1, baseSet = :baseSet " +
            "WHERE id = :playlistId AND itemSet >= 0 AND (:anyChange OR updatedAt = :updatedAt)",
    )
    suspend fun markDeleted(playlistId: String, baseSet: Long, anyChange: Boolean, updatedAt: Long): Int

    @Query(BASELINE_PAGE_SQL)
    suspend fun baselinePage(playlistId: String, itemSet: Long, seq0: Long, nextKey0: Long, afterOrd: Long, afterKey: Long, limit: Int): List<SetItem>

    @Query("SELECT ord, itemKey FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet AND itemKey = :itemKey")
    suspend fun rowAt(playlistId: String, itemSet: Long, itemKey: Long): RowAt?

    @Query("SELECT MIN(ord) FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet AND trackId = :trackId")
    suspend fun firstOrdOf(playlistId: String, itemSet: Long, trackId: String): Long?

    @Query("SELECT MIN(itemKey) FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet AND ord = :ord")
    suspend fun firstKeyAt(playlistId: String, itemSet: Long, ord: Long): Long?

    @Query(LOG_ROWS_SQL)
    suspend fun logRows(op: Int, playlistId: String, itemSet: Long, keys: List<Long>)

    @Query(LOG_RESTORE_SQL)
    suspend fun logRestore(playlistId: String, itemSet: Long, itemKey: Long, ord: Long, trackId: String, relinkKey: String, serverSongId: String?)

    @Query(
        """
        UPDATE playlist SET itemSet = :itemSet, baseSet = :baseSet, syncedSongsKept = 1, syncedSongs = NULL,
            name = :name, updatedAt = :newUpdatedAt, syncedAt = :syncedAt, syncedName = :syncedName, serverStamp = :serverStamp
        WHERE id = :playlistId AND updatedAt = :updatedAt AND serverId IS :serverId AND sourceId IS :sourceId
          AND itemSet = :fromItemSet AND baseSet = :fromBaseSet
        """,
    )
    suspend fun flipSync(
        playlistId: String,
        itemSet: Long,
        baseSet: Long,
        name: String,
        newUpdatedAt: Long,
        syncedAt: Long?,
        syncedName: String?,
        serverStamp: String?,
        updatedAt: Long,
        serverId: String?,
        sourceId: String?,
        fromItemSet: Long,
        fromBaseSet: Long,
    ): Int

    @Query(
        """
        UPDATE playlist SET serverId = :serverId, sourceId = :sourceId, syncedAt = :syncedAt, syncedName = :name, serverStamp = :stamp,
            family = :family,
            familyMayAdd = CASE WHEN :rights THEN :add ELSE familyMayAdd END,
            familyMayRemove = CASE WHEN :rights THEN :remove ELSE familyMayRemove END,
            familyMayRename = CASE WHEN :rights THEN :rename ELSE familyMayRename END,
            familyMayDelete = CASE WHEN :rights THEN :delete ELSE familyMayDelete END,
            baseSet = :baseSet, syncedSongsKept = 1, syncedSongs = NULL
        WHERE id = :playlistId AND baseSet = :fromBase AND itemSet >= 0
        """,
    )
    suspend fun flipTwin(
        playlistId: String,
        fromBase: Long,
        baseSet: Long,
        serverId: String,
        sourceId: String,
        syncedAt: Long,
        name: String,
        stamp: String,
        family: Boolean,
        rights: Boolean,
        add: Boolean,
        remove: Boolean,
        rename: Boolean,
        delete: Boolean,
    ): Int

    @Query("SELECT id FROM playlist WHERE sourceId = :sourceId")
    suspend fun keptWith(sourceId: String): List<String>

    @Query("SELECT id FROM playlist WHERE sourceId IS NOT NULL AND sourceId NOT IN (:kept)")
    suspend fun keptWithout(kept: List<String>): List<String>

    @Query(
        """
        UPDATE playlist SET serverId = NULL, sourceId = NULL, syncedAt = NULL, syncedName = NULL, serverStamp = NULL,
            octoNotice = NULL, syncedSongs = NULL, syncedSongsKept = 0, family = 0,
            familyMayAdd = 0, familyMayRemove = 0, familyMayRename = 0, familyMayDelete = 0, baseSet = :baseSet
        WHERE id = :playlistId
        """,
    )
    suspend fun unlinkPlaylist(playlistId: String, baseSet: Long): Int

    @Query(
        """
        UPDATE playlist SET serverId = NULL, sourceId = NULL, syncedAt = NULL, syncedName = NULL, serverStamp = NULL,
            syncedSongs = NULL, syncedSongsKept = 0, family = 0,
            familyMayAdd = 0, familyMayRemove = 0, familyMayRename = 0, familyMayDelete = 0, baseSet = :baseSet
        WHERE id = :playlistId AND sourceId = :sourceId
        """,
    )
    suspend fun unlinkServer(playlistId: String, sourceId: String, baseSet: Long): Int

    @Query(
        """
        UPDATE playlist SET serverId = NULL, sourceId = NULL, syncedAt = NULL, syncedName = NULL, serverStamp = NULL,
            syncedSongs = NULL, syncedSongsKept = 0, family = 0,
            familyMayAdd = 0, familyMayRemove = 0, familyMayRename = 0, familyMayDelete = 0, baseSet = :baseSet
        WHERE id = :playlistId AND sourceId IS NOT NULL AND sourceId NOT IN (:kept)
        """,
    )
    suspend fun unlinkServerNotIn(playlistId: String, kept: List<String>, baseSet: Long): Int
}
