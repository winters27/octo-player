package app.winters.octo.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

// How many rows one transaction of an edit touches.
private const val EDIT_PAGE = 500

// A song to add to a playlist, as its row will name it.
data class SongToAdd(val trackId: String, val relinkKey: String)

// What an add wrote: how many rows, and the range of row keys it took
// (after `before`, up to `last`), to take exactly those rows back out.
data class AddedRows(val count: Int, val before: Long, val last: Long)

// One playlist's songs changed one edit at a time, each writing only the
// rows it changes: a removal deletes its row, putting it back inserts it
// again with its own key and place, a move gives the row a place between
// its new neighbors, and an add appends after the last row. Rows are named
// by their own key, never by their place, so a pick, an Undo or a screen's
// place outlives any other edit or a sync. Every edit takes only its own
// playlist's set mutex, so it waits for nothing but that playlist's bulk
// writes, and runs in one transaction with the set it shows; while any walk
// pins that set, the rows a move, a removal or a putting back touches go
// into the set's edit log in the same transaction. Each edit moves the
// playlist's change time, so a sync sending it back sees it.
@Singleton
class PlaylistEdits @Inject constructor(private val db: CatalogDatabase, private val sets: PlaylistSets) {
    private val dao = db.playlistEditDao()
    private val setDao = db.playlistSetDao()
    private val users = db.userDao()

    // Takes a song out. Answers the row as it was, to put back; null when
    // it was gone already.
    suspend fun remove(playlistId: String, itemKey: Long, now: Long): PlaylistItemEntity? = sets.edit(playlistId) { set ->
        val row = dao.rowByKey(playlistId, set, itemKey) ?: return@edit null
        sets.logRows(EditOp.REMOVE, playlistId, set, listOf(itemKey))
        dao.deleteByKey(playlistId, set, itemKey)
        users.touchPlaylist(playlistId, now)
        row
    }

    // Takes several songs out, a page to a transaction. Answers the rows as
    // they were.
    suspend fun removeAll(playlistId: String, itemKeys: List<Long>, now: Long): List<PlaylistItemEntity> {
        val out = ArrayList<PlaylistItemEntity>()
        for (page in itemKeys.distinct().chunked(EDIT_PAGE)) {
            out += sets.edit(playlistId) { set ->
                val rows = dao.rowsByKeys(playlistId, set, page)
                if (rows.isNotEmpty()) {
                    sets.logRows(EditOp.REMOVE, playlistId, set, rows.map { it.itemKey })
                    dao.deleteByKeys(playlistId, set, rows.map { it.itemKey })
                    users.touchPlaylist(playlistId, now)
                }
                rows
            } ?: break
        }
        return out
    }

    // Puts a row taken out back, with its own key and place, unless the
    // playlist is gone or the row is there already. Rows sharing a place
    // stand by their keys, so it lands between its neighbors of before.
    suspend fun restore(playlistId: String, row: PlaylistItemEntity, now: Long): Boolean = restoreAll(playlistId, listOf(row), now) > 0

    suspend fun restoreAll(playlistId: String, rows: List<PlaylistItemEntity>, now: Long): Int {
        var restored = 0
        for (page in rows.chunked(EDIT_PAGE)) {
            restored += sets.edit(playlistId) { set ->
                var n = 0
                for (row in page) {
                    if (dao.rowByKey(playlistId, set, row.itemKey) != null) continue
                    val back = row.copy(id = 0, playlistId = playlistId, itemSet = set)
                    dao.insert(back)
                    sets.logRestore(playlistId, set, back)
                    n++
                }
                if (n > 0) users.touchPlaylist(playlistId, now)
                n
            } ?: break
        }
        return restored
    }

    // Drops the row `itemKey` on the row `targetKey`: after it when moving
    // down, before it when moving up. It takes a place between the target
    // and the target's neighbor that way; when none is left between them,
    // the playlist is spaced out again first (keeping every key), then the
    // move runs on the new set.
    suspend fun move(playlistId: String, itemKey: Long, targetKey: Long, now: Long) = sets.withSet(playlistId) {
        repeat(2) {
            val crowded = sets.edit(playlistId) { set ->
                val row = dao.rowByKey(playlistId, set, itemKey) ?: return@edit false
                val target = dao.rowByKey(playlistId, set, targetKey) ?: return@edit false
                if (row.itemKey == target.itemKey) return@edit false
                val down = compareValuesBy(row, target, { it.ord }, { it.itemKey }) < 0
                val neighbor = if (down) {
                    dao.next(playlistId, set, target.ord, target.itemKey, itemKey)
                } else {
                    dao.previous(playlistId, set, target.ord, target.itemKey, itemKey)
                }
                val ord = when {
                    neighbor == null -> if (down) target.ord + ORD_STEP else target.ord - ORD_STEP
                    down && neighbor.ord - target.ord >= 2 -> target.ord + (neighbor.ord - target.ord) / 2
                    !down && target.ord - neighbor.ord >= 2 -> neighbor.ord + (target.ord - neighbor.ord) / 2
                    else -> return@edit true
                }
                sets.logRows(EditOp.MOVE, playlistId, set, listOf(itemKey))
                dao.setOrd(row.id, ord)
                users.touchPlaylist(playlistId, now)
                false
            } ?: return@withSet
            if (!crowded) return@withSet
            respace(playlistId)
        }
    }

    // A new set of the playlist's rows, in the same order with the same
    // keys, ORD_STEP apart, shown in one step; walks pinning the old set
    // keep reading it.
    internal suspend fun respace(playlistId: String) = sets.withSet(playlistId) {
        val from = setDao.itemSetOf(playlistId)?.takeIf { it >= 0 } ?: return@withSet
        val to = sets.writeSet(playlistId) { emit ->
            var afterOrd = Long.MIN_VALUE
            var afterKey = Long.MIN_VALUE
            while (true) {
                val page = dao.setPage(playlistId, from, afterOrd, afterKey, EDIT_PAGE)
                if (page.isEmpty()) break
                emit(page.map { NewItem(it.trackId, it.relinkKey, it.serverSongId, it.itemKey) })
                if (page.size < EDIT_PAGE) break
                afterOrd = page.last().ord
                afterKey = page.last().itemKey
            }
        } ?: return@withSet
        if (db.withTransaction { setDao.flipItems(playlistId, from, to) } == 1) sets.deleteSet(playlistId, from) else sets.deleteSet(playlistId, to)
    }

    // Appends songs in their order, after the last row, a page to a
    // transaction, holding the playlist's set mutex across pages so no
    // other write lands between them. With `distinct` a song goes in only
    // the first time this add brings it; with `newOnly` only when the
    // playlist does not hold it yet. Answers what it wrote; null for
    // nothing.
    suspend fun add(
        playlistId: String,
        pages: suspend (suspend (List<SongToAdd>) -> Unit) -> Unit,
        distinct: Boolean,
        newOnly: Boolean,
        now: Long,
    ): AddedRows? = sets.withSet(playlistId) {
        var before: Long? = null
        var last = 0L
        var count = 0
        var nextOrd: Long? = null
        val beforeId = dao.maxId() ?: 0L
        var gone = false
        pages { refs ->
            for (chunk in refs.chunked(EDIT_PAGE)) {
                if (gone) return@pages
                val wrote = sets.edit(playlistId) { set ->
                    val seen = HashSet<String>()
                    val keep = chunk.filter { ref ->
                        when {
                            newOnly -> seen.add(ref.trackId) && !dao.holds(playlistId, set, ref.trackId)
                            distinct -> seen.add(ref.trackId) && !dao.holdsSince(playlistId, set, ref.trackId, beforeId)
                            else -> true
                        }
                    }
                    if (keep.isEmpty()) return@edit true
                    val start = nextOrd ?: ((dao.maxOrd(playlistId, set) ?: -ORD_STEP) + ORD_STEP)
                    setDao.raiseNextItemKey(keep.size)
                    val first = setDao.nextItemKey() - keep.size
                    setDao.insertItems(
                        keep.mapIndexed { i, ref ->
                            PlaylistItemEntity(playlistId = playlistId, itemSet = set, trackId = ref.trackId, relinkKey = ref.relinkKey, ord = start + i * ORD_STEP, itemKey = first + i)
                        },
                    )
                    if (before == null) before = first - 1
                    last = first + keep.size - 1
                    count += keep.size
                    nextOrd = start + keep.size * ORD_STEP
                    users.touchPlaylist(playlistId, now)
                    true
                }
                if (wrote == null) gone = true
            }
        }
        before?.let { AddedRows(count, it, last) }
    }

    // Whether any set of a playlist holds the song: for a new playlist
    // being written, its one set.
    suspend fun holdsAny(playlistId: String, trackId: String): Boolean = dao.holdsAny(playlistId, trackId)

    // Takes back an add: its rows go, a page at a time, wherever they have
    // moved since. A sync since then that gave the rows new keys leaves
    // nothing to take back.
    suspend fun undoAdd(playlistId: String, added: AddedRows, now: Long) = sets.withSet(playlistId) {
        while (true) {
            val n = sets.edit(playlistId) { set ->
                val keys = dao.addedKeys(playlistId, set, added.before, added.last, EDIT_PAGE)
                if (keys.isNotEmpty()) {
                    sets.logRows(EditOp.REMOVE, playlistId, set, keys)
                    dao.deleteByKeys(playlistId, set, keys)
                    users.touchPlaylist(playlistId, now)
                }
                keys.size
            } ?: return@withSet
            if (n < EDIT_PAGE) return@withSet
        }
    }
}

// The statements of single playlist edits, each on the set's indexes.
@Dao
interface PlaylistEditDao {
    @Query("SELECT * FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet AND itemKey = :itemKey")
    suspend fun rowByKey(playlistId: String, itemSet: Long, itemKey: Long): PlaylistItemEntity?

    @Query("SELECT * FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet AND itemKey IN (:keys)")
    suspend fun rowsByKeys(playlistId: String, itemSet: Long, keys: List<Long>): List<PlaylistItemEntity>

    @Query("DELETE FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet AND itemKey = :itemKey")
    suspend fun deleteByKey(playlistId: String, itemSet: Long, itemKey: Long)

    @Query("DELETE FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet AND itemKey IN (:keys)")
    suspend fun deleteByKeys(playlistId: String, itemSet: Long, keys: List<Long>)

    @Insert
    suspend fun insert(row: PlaylistItemEntity): Long

    @Query("UPDATE playlist_item SET ord = :ord WHERE id = :id")
    suspend fun setOrd(id: Long, ord: Long)

    @Query("SELECT MAX(ord) FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet")
    suspend fun maxOrd(playlistId: String, itemSet: Long): Long?

    @Query("SELECT MAX(id) FROM playlist_item")
    suspend fun maxId(): Long?

    // The row after (or before) a place in play order, leaving one row out.
    @Query(
        "SELECT ord, itemKey FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet " +
            "AND ord >= :ord AND CASE WHEN ord = :ord THEN itemKey > :itemKey ELSE 1 END AND itemKey != :skip " +
            "ORDER BY ord, itemKey LIMIT 1",
    )
    suspend fun next(playlistId: String, itemSet: Long, ord: Long, itemKey: Long, skip: Long): RowAt?

    @Query(
        "SELECT ord, itemKey FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet " +
            "AND ord <= :ord AND CASE WHEN ord = :ord THEN itemKey < :itemKey ELSE 1 END AND itemKey != :skip " +
            "ORDER BY ord DESC, itemKey DESC LIMIT 1",
    )
    suspend fun previous(playlistId: String, itemSet: Long, ord: Long, itemKey: Long, skip: Long): RowAt?

    // Whether the set holds the song, at all or in a row written after
    // the row with id `afterId`.
    @Query("SELECT EXISTS(SELECT 1 FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet AND trackId = :trackId)")
    suspend fun holds(playlistId: String, itemSet: Long, trackId: String): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet AND trackId = :trackId AND id > :afterId)",
    )
    suspend fun holdsSince(playlistId: String, itemSet: Long, trackId: String, afterId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_item WHERE trackId = :trackId AND playlistId = :playlistId)")
    suspend fun holdsAny(playlistId: String, trackId: String): Boolean

    @Query(
        "SELECT itemKey FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet AND itemKey > :before AND itemKey <= :last " +
            "ORDER BY itemKey LIMIT :limit",
    )
    suspend fun addedKeys(playlistId: String, itemSet: Long, before: Long, last: Long, limit: Int): List<Long>

    @Query(
        "SELECT * FROM playlist_item WHERE playlistId = :playlistId AND itemSet = :itemSet " +
            "AND ord >= :afterOrd AND CASE WHEN ord = :afterOrd THEN itemKey > :afterKey ELSE 1 END ORDER BY ord, itemKey LIMIT :limit",
    )
    suspend fun setPage(playlistId: String, itemSet: Long, afterOrd: Long, afterKey: Long, limit: Int): List<PlaylistItemEntity>
}
