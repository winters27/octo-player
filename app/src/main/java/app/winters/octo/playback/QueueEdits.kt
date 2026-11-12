package app.winters.octo.playback

import androidx.room.withTransaction
import app.winters.octo.catalog.QueueEntryEntity
import app.winters.octo.catalog.QueueListEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// A new row an edit puts in: its number, its song and the list it heads
// under, and its listed length.
@Serializable
data class NewRow(val entry: Long, val trackId: String, val listId: Long, val durationMs: Long)

// Where a run goes in one order: right after `after`, or right before
// `before` when it went first; `behind` names the entries before that
// neighbor as the edit saw them, nearest first, and `key` its key then, so
// a run whose neighbor has gone since still lands where it was put.
@Serializable
data class Spot(val after: Long? = null, val before: Long? = null, val behind: List<Long> = emptyList(), val key: Long? = null) {
    companion object {
        val END = Spot()
    }
}

// One edit of a queue, by entry numbers, as the worker applies it: at once,
// or at the join of a list being written when one of its queue is.
@Serializable
sealed interface QueueEdit {
    // Rows put in as one run, in their own order, at a spot in each order;
    // with `list` the new list they make. A spot of END goes last.
    @Serializable
    @SerialName("insert")
    data class Insert(val rows: List<NewRow>, val play: Spot, val shuffle: Spot, val list: StoredList? = null) : QueueEdit

    @Serializable
    @SerialName("remove")
    data class Remove(val entries: List<Long>) : QueueEdit

    // A row moved in play order; its shuffled key stays.
    @Serializable
    @SerialName("move")
    data class Move(val entry: Long, val play: Spot) : QueueEdit

    // Rows put back exactly as they were, keys and all.
    @Serializable
    @SerialName("putBack")
    data class PutBack(val rows: List<StoredRow>) : QueueEdit

    // Every row of these songs goes to a generation held for their Undo.
    @Serializable
    @SerialName("removeSongs")
    data class RemoveSongs(val trackIds: List<String>, val holding: Long) : QueueEdit

    // The rows under Remove played's floors go, and the floors with them.
    @Serializable
    @SerialName("floorsExpire")
    data class FloorsExpire(val account: String) : QueueEdit

    // A big add that waited for another list: it is placed now and builds.
    @Serializable
    @SerialName("addList")
    data class AddList(val listId: Long, val play: Spot, val shuffle: Spot) : QueueEdit
}

// A queue row as an edit carries it.
@Serializable
data class StoredRow(val entry: Long, val trackId: String, val listId: Long, val qk: Long, val sk: Long, val durationMs: Long, val skBefore: Long? = null) {
    fun row(queueId: Long) = QueueEntryEntity(queueId, entry, trackId, listId, qk, sk, durationMs, skBefore)

    companion object {
        fun of(row: QueueEntryEntity) = StoredRow(row.entry, row.trackId, row.listId, row.qk, row.sk, row.durationMs, row.skBefore)
    }
}

// A list's row as an edit carries it.
@Serializable
data class StoredList(val listId: Long, val source: String?, val autoplay: Boolean, val songsOnce: Boolean = false) {
    fun row(queueId: Long, swapCount: Long) =
        QueueListEntity(listId, queueId, source, autoplay, null, 0, QueueBuilds.JOINED, null, null, null, 0, 0, null, null, swapCount, 0, null, null, null, null, null, songsOnce)
}

internal val EditJson = Json { ignoreUnknownKeys = true }

fun QueueEdit.toJson(): String = EditJson.encodeToString(QueueEdit.serializer(), this)

fun queueEditOf(json: String): QueueEdit = EditJson.decodeFromString(QueueEdit.serializer(), json)

// Applies edits to a generation in Room, on the worker: each in its own
// transaction, keys computed from the rows as they are then, every song
// followed to the library song it is now.
class QueueApply(private val store: QueueStore) {
    private val db = store.db
    private val dao = store.dao

    suspend fun apply(queueId: Long, edit: QueueEdit) {
        when (edit) {
            is QueueEdit.Insert -> insert(queueId, edit)
            is QueueEdit.Remove -> edit.entries.chunked(900).forEach { db.withTransaction { dao.deleteEntries(queueId, it) } }
            is QueueEdit.Move -> move(queueId, edit)
            is QueueEdit.PutBack -> db.withTransaction { edit.rows.chunked(QUEUE_PAGE).forEach { rows -> dao.putEntries(rows.map { it.row(queueId) }) } }
            is QueueEdit.RemoveSongs -> removeSongs(queueId, edit)
            is QueueEdit.FloorsExpire -> expire(queueId, edit.account)
            is QueueEdit.AddList -> Unit
        }
    }

    // Where a spot lands in Room now: the row to go after, or before, in
    // that order. A neighbor gone since is stood in for by the nearest
    // entry before it that is still there, then by the row with the
    // greatest key below its key, then by the end.
    suspend fun resolve(queueId: Long, order: QueueOrder, spot: Spot): Pair<QueueEntryEntity?, QueueEntryEntity?> {
        spot.after?.let { dao.entry(queueId, it) }?.let { return it to null }
        spot.before?.let { dao.entry(queueId, it) }?.let { return null to it }
        if (spot.after == null && spot.before == null) return null to null
        for (e in spot.behind) dao.entry(queueId, e)?.let { return it to null }
        spot.key?.let { key ->
            store.page(queueId, order, keyed(order, key), forward = false, 1, Floors.NONE).firstOrNull()?.let { return it to null }
        }
        return null to null
    }

    private fun keyed(order: QueueOrder, key: Long) =
        QueueEntryEntity(0, Long.MIN_VALUE, "", 0, if (order == QueueOrder.Play) key else 0, if (order == QueueOrder.Shuffle) key else 0, 0)

    private suspend fun insert(queueId: Long, edit: QueueEdit.Insert) {
        if (edit.rows.isEmpty()) return
        val (afterQ, beforeQ) = resolve(queueId, QueueOrder.Play, edit.play)
        val (afterS, beforeS) = resolve(queueId, QueueOrder.Shuffle, edit.shuffle)
        val qks = store.runKeys(queueId, QueueOrder.Play, afterQ, beforeQ, edit.rows.size)
        val sks = store.runKeys(queueId, QueueOrder.Shuffle, afterS, beforeS, edit.rows.size)
        val followed = edit.rows.map { store.followed(it.trackId) }
        val swapCount = store.swapCount()
        db.withTransaction {
            edit.list?.let { if (dao.list(it.listId) == null) dao.putList(it.row(queueId, swapCount)) }
            dao.putEntries(edit.rows.mapIndexed { i, r -> QueueEntryEntity(queueId, r.entry, followed[i], r.listId, qks[i], sks[i], r.durationMs) })
        }
    }

    private suspend fun move(queueId: Long, edit: QueueEdit.Move) {
        val row = dao.entry(queueId, edit.entry) ?: return
        val (after, before) = resolve(queueId, QueueOrder.Play, edit.play)
        if (after?.entry == row.entry || before?.entry == row.entry) return
        // The row's own key is out of the way while its new one is found.
        val lo = after?.qk ?: before?.qk?.let { b -> neighbor(queueId, b, row.entry, after = false) }
        val hi = when {
            after != null -> neighbor(queueId, after.qk, row.entry, after = true)
            else -> before?.qk
        }
        val key = spacedKeys(lo, hi, 1)?.first() ?: store.runKeys(queueId, QueueOrder.Play, after, before, 1).first()
        db.withTransaction { dao.setQk(queueId, row.entry, key) }
    }

    // The key next to `key` in play order, passing over `skip`.
    private suspend fun neighbor(queueId: Long, key: Long, skip: Long, after: Boolean): Long? {
        val near = store.page(queueId, QueueOrder.Play, keyed(QueueOrder.Play, key).copy(entry = if (after) Long.MAX_VALUE else Long.MIN_VALUE), after, 2, Floors.NONE)
        return near.firstOrNull { it.entry != skip }?.qk
    }

    private suspend fun removeSongs(queueId: Long, edit: QueueEdit.RemoveSongs) {
        edit.trackIds.chunked(900).forEach { chunk ->
            val rows = dao.entriesOfSongs(queueId, chunk)
            rows.map { it.entry }.chunked(900).forEach { entries -> db.withTransaction { dao.moveEntries(queueId, edit.holding, entries) } }
        }
    }

    // The rows below Remove played's floors go a page at a time, then the
    // floors are cleared.
    private suspend fun expire(queueId: Long, account: String) {
        val floors = Floors.of(dao.account(account)?.takeIf { it.queueId == queueId })
        if (floors == Floors.NONE) return
        var from: QueueEntryEntity? = null
        while (true) {
            val page = store.page(queueId, QueueOrder.Play, from, forward = true, QUEUE_PAGE, Floors.NONE)
            val below = page.filter { it.qk < floors.q || it.sk < floors.s }
            if (below.isNotEmpty()) db.withTransaction { dao.deleteEntries(queueId, below.map { it.entry }) }
            if (page.size < QUEUE_PAGE) break
            from = page.last()
        }
        dao.setFloors(account, queueId, null, null)
    }
}
