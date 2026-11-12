package app.winters.octo.ui.paging

import app.winters.octo.catalog.ListsDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.rawQuery
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The songs a search found, as its screen ran it: songs whose title holds
// the words first, then those whose artist does, each by title, then id;
// the list ends at its `cap`-th row, a key read once and then kept, so a
// walk from any row stops where the screen's list did. Each page is a scan
// with a page-sized sort, as today's search is.
class SearchLoader(private val dao: ListsDao, private val q: String, private val cap: Int) : ListLoader<TrackEntity> {
    private val reads = TrackReads(dao)
    private val lock = Mutex()
    private var end: RowKey? = null
    private var endKnown = false

    override val tables = arrayOf("track")

    override val order: Comparator<RowKey> = Comparator { a, b ->
        SqlOrder.compare(b.values[0], a.values[0]).let { if (it != 0) return@Comparator it }
        SqlOrder.compare(a.values[1], b.values[1]).let { if (it != 0) return@Comparator it }
        SqlOrder.text(a.id, b.id)
    }

    override val songsOnce = true

    private val words = listOf(SqlValue.Text(q))
    private val matches = "(t.searchKey LIKE '%' || ? || '%' OR lower(t.artist) LIKE '%' || ? || '%')"
    private val m = "(t.searchKey LIKE '%' || ? || '%')"

    private fun key(row: app.winters.octo.catalog.KeyedTrack) = RowKey(0, listOf(SqlValue.Int64(row.k0 ?: 0), SqlValue.Text(row.track.sortKey)), row.track.id)

    private fun select() = trackSelect(0, k0 = m)

    // Rows after a key in list order, or before it.
    private fun past(forward: Boolean) = if (forward) "($m < ? OR ($m = ? AND (t.sortKey, t.id) > (?, ?)))" else "($m > ? OR ($m = ? AND (t.sortKey, t.id) < (?, ?)))"

    private fun pastArgs(key: RowKey) = words + key.values[0] + words + key.values[0] + key.values[1] + SqlValue.Text(key.id)

    // Rows at or before the end key.
    private val within = "($m > ? OR ($m = ? AND (t.sortKey, t.id) <= (?, ?)))"

    private suspend fun end(): RowKey? = lock.withLock {
        if (!endKnown) {
            end = probe(cap - 1)
            endKnown = true
        }
        end
    }

    private suspend fun rows(conditions: List<String>, args: List<SqlValue>, forward: Boolean, limit: String): List<Keyed<TrackEntity>> {
        val sql = "SELECT ${select()} FROM track t WHERE ${(listOf(matches) + conditions).joinToString(" AND ")} " +
            "ORDER BY $m ${if (forward) "DESC" else ""}, t.sortKey ${if (forward) "" else "DESC"}, t.id ${if (forward) "" else "DESC"} $limit"
        // The select's mark comes first, then the filter's, then the rest,
        // then the order's.
        val all = words + words + words + args + words
        return reads.rows(sql, all).map { Keyed(key(it), it.track) }
    }

    private suspend fun probe(position: Int): RowKey? = if (position < 0) null else rows(emptyList(), emptyList(), true, "LIMIT 1 OFFSET $position").firstOrNull()?.key

    private suspend fun bounded(): Pair<List<String>, List<SqlValue>> {
        val last = end() ?: return emptyList<String>() to emptyList()
        return listOf(within) to (words + last.values[0] + words + last.values[0] + last.values[1] + SqlValue.Text(last.id))
    }

    override suspend fun count(): Int {
        val (c, a) = bounded()
        return dao.count(rawQuery("SELECT COUNT(*) FROM track t WHERE ${(listOf(matches) + c).joinToString(" AND ")}", words + words + a))
    }

    override suspend fun survey(anchor: RowKey?): Survey {
        end()
        val before = anchor?.let {
            val (c, a) = bounded()
            dao.count(rawQuery("SELECT COUNT(*) FROM track t WHERE ${(listOf(matches, past(false)) + c).joinToString(" AND ")}", words + words + pastArgs(it) + a))
        }
        return Survey(count(), null, before)
    }

    override suspend fun after(key: RowKey?, limit: Int): List<Keyed<TrackEntity>> {
        val (c, a) = bounded()
        val cursor = if (key == null) emptyList() else listOf(past(true))
        return rows(cursor + c, (if (key == null) emptyList() else pastArgs(key)) + a, true, "LIMIT $limit")
    }

    override suspend fun before(key: RowKey, limit: Int): List<Keyed<TrackEntity>> = rows(listOf(past(false)), pastArgs(key), false, "LIMIT $limit")

    override suspend fun keyAt(position: Int): RowKey? = if (position >= cap) null else probe(position)

    override suspend fun keyOf(trackId: String): RowKey? {
        val (c, a) = bounded()
        return rows(listOf("t.id = ?") + c, listOf(SqlValue.Text(trackId)) + a, true, "LIMIT 1").firstOrNull()?.key
    }

    override suspend fun rekey(key: RowKey): RowKey? = key

    override suspend fun present(ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val (c, a) = bounded()
        return rows(listOf("t.id IN ${marks(ids.size)}") + c, ids.map { SqlValue.Text(it) } + a, true, "LIMIT ${ids.size}").mapTo(HashSet()) { it.row.id }
    }

    override fun trackIdOf(row: TrackEntity): String = row.id
}

// The rows picked on another list's page, by the ids that page picks them
// by, in the page's order as it stands now: each found by its key in the
// list at the time it is read (tap-sized). A pick no longer in the list is
// left out. It lists a song at most once exactly when its list does.
class SelectionLoader<T : Any>(
    private val base: ListLoader<T>,
    private val ids: List<String>,
    // A pick's key in the list now, by the id it was picked by.
    private val keyOfPick: suspend (String) -> RowKey?,
) : ListLoader<T> {
    private val lock = Mutex()
    private var rows: List<Keyed<T>>? = null

    private suspend fun rows(): List<Keyed<T>> = rows ?: lock.withLock {
        rows ?: ids.distinct().mapNotNull { id ->
            val key = keyOfPick(id) ?: return@mapNotNull null
            val before = base.before(key, 1).firstOrNull()?.key
            base.after(before, 1).firstOrNull()?.takeIf { base.order.compare(it.key, key) == 0 }
        }.sortedWith { a, b -> base.order.compare(a.key, b.key) }.also { rows = it }
    }

    override val tables: Array<String> get() = base.tables
    override val order: Comparator<RowKey> get() = base.order
    override val songsOnce: Boolean get() = base.songsOnce

    override suspend fun count(): Int = rows().size
    override suspend fun survey(anchor: RowKey?): Survey = rows().let { all -> Survey(all.size, null, anchor?.let { a -> all.count { order.compare(it.key, a) < 0 } }) }
    override suspend fun after(key: RowKey?, limit: Int) = rows().filter { key == null || order.compare(it.key, key) > 0 }.take(limit)
    override suspend fun before(key: RowKey, limit: Int) = rows().filter { order.compare(it.key, key) < 0 }.asReversed().take(limit)
    override suspend fun keyAt(position: Int) = rows().getOrNull(position)?.key
    override suspend fun keyOf(trackId: String) = rows().firstOrNull { base.trackIdOf(it.row) == trackId }?.key
    override suspend fun rekey(key: RowKey) = base.rekey(key)
    override suspend fun present(ids: List<String>): Set<String> = base.present(ids)
    override suspend fun small(): List<Keyed<T>>? = rows().takeIf { it.size <= app.winters.octo.playback.QUEUE_WINDOW }
    override fun trackIdOf(row: T): String? = base.trackIdOf(row)
}
