package app.winters.octo.playback

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.rawQuery
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlOrder
import app.winters.octo.ui.paging.SqlValue
import app.winters.octo.ui.paging.Survey

// A page of one album's songs in album order after a place in it.
const val ALBUM_LIST_PAGE_SQL =
    "SELECT * FROM track WHERE albumId = ?1 AND (albumOrder, id) > (?2, ?3) ORDER BY albumOrder, id LIMIT ?4"
const val ALBUM_LIST_BACK_SQL =
    "SELECT * FROM track WHERE albumId = ?1 AND (albumOrder, id) < (?2, ?3) ORDER BY albumOrder DESC, id DESC LIMIT ?4"
const val ALBUM_LIST_COUNT_SQL = "SELECT COUNT(*) FROM track WHERE albumId = ?1"

// Albums one after another, each in its own order: the "Recently added"
// shortcut's play. Walked album by album in pages on the albums' song
// index, so an album as big as the library is never held whole. A row's
// key is its album's place in the list, its place in the album and its id.
class AlbumListLoader(db: CatalogDatabase, private val albumIds: List<String>) : ListLoader<TrackEntity> {
    private val dao = db.queueDao()
    override val tables = arrayOf("track")
    override val order: Comparator<RowKey> = SqlOrder.rows()
    override val songsOnce = true

    private fun keyOf(segment: Int, t: TrackEntity) = RowKey(segment, listOf(SqlValue.Int64(t.albumOrder.toLong())), t.id)

    private suspend fun page(segment: Int, from: RowKey?, forward: Boolean, limit: Int): List<Keyed<TrackEntity>> {
        val albumId = albumIds[segment]
        val sql = if (forward) ALBUM_LIST_PAGE_SQL else ALBUM_LIST_BACK_SQL
        val (place, id) = when {
            from != null -> ((from.values.first() as SqlValue.Int64).v) to SqlValue.Text(from.id)
            forward -> Long.MIN_VALUE to SqlValue.Text("")
            else -> Long.MAX_VALUE to SqlValue.Text("￿")
        }
        val rows = dao.tracks(rawQuery(sql, listOf(SqlValue.Text(albumId), SqlValue.Int64(place), id, SqlValue.Int64(limit.toLong()))))
        return rows.map { Keyed(keyOf(segment, it), it) }
    }

    private suspend fun countOf(segment: Int): Int = dao.number(app.winters.octo.catalog.queueQuery(ALBUM_LIST_COUNT_SQL, albumIds[segment]))?.toInt() ?: 0

    override suspend fun count(): Int = albumIds.indices.sumOf { countOf(it) }

    override suspend fun survey(anchor: RowKey?): Survey {
        var before: Int? = null
        if (anchor != null) {
            before = (0 until anchor.segment.coerceAtMost(albumIds.size)).sumOf { countOf(it) }
            var at: RowKey? = null
            while (anchor.segment < albumIds.size) {
                val rows = page(anchor.segment, at, true, 500).takeWhile { order.compare(it.key, anchor) < 0 }
                before += rows.size
                if (rows.size < 500) break
                at = rows.last().key
            }
        }
        return Survey(count(), null, before)
    }

    override suspend fun after(key: RowKey?, limit: Int): List<Keyed<TrackEntity>> {
        val out = ArrayList<Keyed<TrackEntity>>()
        var segment = key?.segment ?: 0
        var from = key
        while (out.size < limit && segment < albumIds.size) {
            out += page(segment, from, true, limit - out.size)
            if (out.size < limit) {
                segment++
                from = null
            }
        }
        return out
    }

    override suspend fun before(key: RowKey, limit: Int): List<Keyed<TrackEntity>> {
        val out = ArrayList<Keyed<TrackEntity>>()
        var segment = key.segment.coerceAtMost(albumIds.size - 1)
        var from: RowKey? = key.takeIf { it.segment < albumIds.size }
        while (out.size < limit && segment >= 0) {
            out += page(segment, from, false, limit - out.size)
            if (out.size < limit) {
                segment--
                from = null
            }
        }
        return out
    }

    override suspend fun keyAt(position: Int): RowKey? {
        var left = position
        for (segment in albumIds.indices) {
            val n = countOf(segment)
            if (left < n) {
                var at: RowKey? = null
                while (true) {
                    val rows = page(segment, at, true, minOf(500, left + 1))
                    if (left < rows.size) return rows[left].key
                    left -= rows.size
                    at = rows.lastOrNull()?.key ?: return null
                }
            }
            left -= n
        }
        return null
    }

    override suspend fun keyOf(trackId: String): RowKey? {
        val t = dao.track(trackId) ?: return null
        val segment = albumIds.indexOf(t.albumId).takeIf { it >= 0 } ?: return null
        return keyOf(segment, t)
    }

    override suspend fun rekey(key: RowKey): RowKey? = key

    override suspend fun present(ids: List<String>): Set<String> {
        val wanted = albumIds.toHashSet()
        return ids.chunked(900).flatMap { dao.tracks(rawQuery("SELECT * FROM track WHERE id IN (${it.joinToString(",") { "?" }})", it.map(SqlValue::Text))) }
            .filter { it.albumId in wanted }.mapTo(HashSet()) { it.id }
    }

    override fun trackIdOf(row: TrackEntity): String = row.id
}
