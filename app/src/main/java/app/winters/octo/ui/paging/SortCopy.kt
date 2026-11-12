package app.winters.octo.ui.paging

import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.ListsSortCopyEntity
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.SortCopyRow
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.deleteOwned
import app.winters.octo.catalog.rawQuery
import app.winters.octo.sort.DownloadSort
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import okio.ByteString.Companion.toByteString

private const val FILL_PAGE = 500

// The disk one row of a sort copy (lists_sort_copy) takes in the file with
// its keys and indexes, under an owner led by the launch's mark, with a
// server's long ids (ListsDiskConstantsTest: 436). A copy asks for twice
// this a row, the write-ahead log's share the second half.
const val SORT_COPY_ROW_BYTES = 455L

// Which lists build their queue from a sort copy: every order that reads
// what the listener changes outside a merge (a like, a rating), and the
// finished downloads, whose sizes a finish rewrites.
fun readsUserData(scope: SongScope, order: SortOrder): Boolean {
    val sort = order.by as? SongSort ?: return false
    return sort == SongSort.Rating || sort == SongSort.Liked || (scope == SongScope.Liked && sort == SongSort.DateLiked)
}

// A list's sort copy: its rows written with the values of its order as
// they were when each was read, walked in an order no like, rating or
// finish moves, so each row is read once whatever happens to it meanwhile.
// The copy's key is the list's order, ascending in every column, and each
// row's key is the one the list's page gives the same row.
class SortCopies(
    private val db: CatalogDatabase,
    private val merge: CatalogMerge,
    private val freeSpace: FreeSpace,
) {
    private val dao = db.listsDao()

    // The rows of a song list in one page of its fill: each song with its
    // rating and like as read now.
    private fun songRow(owner: String, sort: SongSort, down: Boolean, t: TrackEntity, liked: Boolean, likedAt: Long?): ListsSortCopyEntity {
        val segment: Int
        val lead: Long
        var tie = 0
        when (sort) {
            SongSort.Rating -> {
                segment = if (t.rating == 0) 1 else 0
                lead = t.rating.toLong()
                tie = if (liked) 0 else 1
            }
            SongSort.Liked -> {
                segment = if (liked == down) 0 else 1
                lead = 0
            }
            else -> {
                segment = 0
                lead = likedAt ?: 0
            }
        }
        val leadKey = if (down && segment == 0) -lead else lead
        return ListsSortCopyEntity(owner, segment, lead, leadKey, liked, tie, t.sortKey, t.id, ByteArray(0), "", "")
    }

    // Fills `owner`'s copy of a song list in `order`, its songs passing
    // `keep`: the scope walked by (sortKey, id) for all songs and a genre,
    // the liked songs by their key; a page to a transaction. A swap during
    // it starts it again, twice at most; the third runs inside the merge
    // lock. Answers the swap count the copy's rows were read at.
    suspend fun fillSongs(owner: String, scope: SongScope, order: SortOrder, keep: suspend (List<TrackEntity>) -> List<TrackEntity>): Long {
        val sort = order.by as SongSort
        return fill(owner, sizeOf(scope)) { baseline ->
            if (scope == SongScope.Liked) {
                var after = ""
                while (true) {
                    val page = dao.tracks(
                        rawQuery(
                            "SELECT ${trackSelect(0, k0 = "l.likedAt")} FROM liked_track l CROSS JOIN track t ON t.id = l.trackId " +
                                "WHERE l.trackId > ? ORDER BY l.trackId LIMIT $FILL_PAGE",
                            listOf(SqlValue.Text(after)),
                        ),
                    )
                    if (page.isEmpty()) return@fill true
                    val kept = keep(page.map { it.track }).mapTo(HashSet()) { it.id }
                    val rows = page.filter { it.track.id in kept }.map { songRow(owner, sort, order.descending, it.track, true, it.k0) }
                    if (!write(rows, baseline)) return@fill false
                    if (page.size < FILL_PAGE) return@fill true
                    after = page.last().track.id
                }
            }
            val genre = (scope as? SongScope.Genre)?.name
            var afterKey = ""
            var afterId = ""
            while (true) {
                val conditions = listOfNotNull(if (genre != null) "+t.genre = ?" else null, "(t.sortKey, t.id) > (?, ?)")
                val args = listOfNotNull(genre?.let { SqlValue.Text(it) }) + SqlValue.Text(afterKey) + SqlValue.Text(afterId)
                val page = dao.tracks(
                    rawQuery(
                        "SELECT ${trackSelect(0, k0 = LIKED)} FROM track t WHERE ${conditions.joinToString(" AND ")} ORDER BY t.sortKey, t.id LIMIT $FILL_PAGE",
                        args,
                    ),
                )
                if (page.isEmpty()) return@fill true
                val kept = keep(page.map { it.track }).mapTo(HashSet()) { it.id }
                val rows = page.filter { it.track.id in kept }.map { songRow(owner, sort, order.descending, it.track, it.k0 == 1L, null) }
                if (!write(rows, baseline)) return@fill false
                if (page.size < FILL_PAGE) return@fill true
                afterKey = page.last().track.sortKey
                afterId = page.last().track.id
            }
            @Suppress("UNREACHABLE_CODE")
            true
        }
    }

    // Fills `owner`'s copy of a server's finished downloads in `order`,
    // walking the downloads by their own key, which no write moves.
    suspend fun fillDownloads(owner: String, sourceId: String, order: SortOrder): Long {
        val sort = order.by as? DownloadSort ?: DownloadSort.RecentlyDownloaded
        return fill(owner, 0) { baseline ->
            var after = ""
            while (true) {
                val page = dao.downloadRows(
                    rawQuery(
                        "SELECT d.trackId AS trackId, d.sourceId AS sourceId, d.serverId AS serverId, d.title AS title, d.artist AS artist, " +
                            "NULL AS artwork, d.sizeBytes AS sizeBytes, d.state AS state, d.progress AS progress, d.reason AS reason, " +
                            "d.addedAt AS addedAt, NULL AS explicit, d.titleKey AS titleKey, $DONE_SEGMENT AS seg " +
                            "FROM download d WHERE d.sourceId = ? AND d.serverId > ? AND d.state = 'Done' ORDER BY d.serverId LIMIT $FILL_PAGE",
                        listOf(SqlValue.Text(sourceId), SqlValue.Text(after)),
                    ),
                )
                if (page.isEmpty()) return@fill true
                val rows = page.map { row ->
                    val lead = doneLead(sort, row.sizeBytes, row.addedAt)
                    ListsSortCopyEntity(
                        owner, DONE_SEGMENT, lead, if (order.descending) -lead else lead, false,
                        0, "", row.trackId, if (order.descending && sort == DownloadSort.Title) turned(row.titleKey) else row.titleKey,
                        row.sourceId, row.serverId,
                    )
                }
                if (!write(rows, baseline)) return@fill false
                if (page.size < FILL_PAGE) return@fill true
                after = page.last().serverId
            }
            @Suppress("UNREACHABLE_CODE")
            true
        }
    }

    private fun turned(bytes: ByteArray) = ByteArray(bytes.size + 1) { i -> if (i < bytes.size) (255 - (bytes[i].toInt() and 0xff)).toByte() else -1 }

    private suspend fun sizeOf(scope: SongScope): Long = when (scope) {
        SongScope.Liked -> dao.count(rawQuery("SELECT COUNT(*) FROM liked_track", emptyList())).toLong()
        else -> dao.count(rawQuery("SELECT COUNT(*) FROM track", emptyList())).toLong()
    }

    // One page of a fill, in one transaction that first checks no swap ran
    // since the fill began.
    private suspend fun write(rows: List<ListsSortCopyEntity>, baseline: Long): Boolean = try {
        db.withTransaction {
            if (db.workDao().swapCount() != baseline) return@withTransaction false
            if (rows.isNotEmpty()) dao.insertSortCopyRows(rows)
            true
        }
    } catch (e: SQLiteFullException) {
        throw NotEnoughSpace(null, SpaceFor.List)
    }

    private suspend fun fill(owner: String, rows: Long, attempt: suspend (baseline: Long) -> Boolean): Long {
        repeat(3) { n ->
            val need = rows * SORT_COPY_ROW_BYTES * 2
            if (freeSpace.bytes() < need) throw NotEnoughSpace(need, SpaceFor.List)
            db.deleteOwned("lists_sort_copy", owner)
            var baseline = 0L
            val done = if (n < 2) {
                baseline = db.withTransaction { db.workDao().swapCount() }
                attempt(baseline)
            } else {
                merge.betweenRebuilds(HoldKind.Walk) {
                    baseline = db.withTransaction { db.workDao().swapCount() }
                    attempt(baseline)
                }
            }
            if (done) return baseline
        }
        return db.withTransaction { db.workDao().swapCount() }
    }

    // A finished copy walked as a frozen source, its keys as the page gives
    // the same rows: by the copy's own key, ascending.
    fun loader(owner: String, kind: SongSort?, downloads: Boolean, down: Boolean, titles: Boolean = false): ListLoader<SortCopyRow> {
        fun raw(titleKey: ByteArray) = if (downloads && titles && down) unturned(titleKey) else titleKey
        val reads = object : PartReads<SortCopyRow> {
            override suspend fun rows(sql: String, args: List<SqlValue>) = dao.sortCopyRows(rawQuery(sql, args))
            override suspend fun count(sql: String, args: List<SqlValue>) = dao.count(rawQuery(sql, args))
            override suspend fun ids(sql: String, args: List<SqlValue>) = dao.ids(rawQuery(sql, args))
        }
        val columns = listOf("c.segment", "c.leadKey", "c.tieKey", "c.sortKey", "c.titleKey", "c.trackId", "c.sourceId", "c.serverId")
        val keyOf: (SortCopyRow) -> RowKey = { row ->
            when {
                downloads -> doneKey(row.lead, raw(row.titleKey), row.trackId, row.sourceId, row.serverId)
                kind == SongSort.Rating -> RowKey(row.segment, listOf(SqlValue.Int64(row.lead), SqlValue.Int64(if (row.liked) 1 else 0), SqlValue.Text(row.sortKey)), row.trackId)
                kind == SongSort.Liked -> RowKey(row.segment, listOf(SqlValue.Text(row.sortKey)), row.trackId)
                else -> RowKey(0, listOf(SqlValue.Int64(row.lead), SqlValue.Text(row.sortKey)), row.trackId)
            }
        }
        val part = IndexPart(
            0, "lists_sort_copy c", listOf("c.owner = ?"), listOf(SqlValue.Text(owner)), emptyList(), columns, false,
            "c.*", reads, keyOf, idColumn = "c.trackId",
            placeOf = { 0 },
            columnsOf = { key -> copyColumns(key, kind, downloads, down, titles) },
        )
        // The copy's key is the list's order, so its own order compares keys
        // as the copy's columns hold them.
        val order = Comparator<RowKey> { a, b -> compareValues(copyColumns(a, kind, downloads, down, titles), copyColumns(b, kind, downloads, down, titles)) }
        return PartsLoader({ listOf(part) }, arrayOf("lists_sort_copy"), order, songsOnce = !downloads, rowOf = { it }, idOf = { it.trackId })
    }

    private fun unturned(bytes: ByteArray) = ByteArray((bytes.size - 1).coerceAtLeast(0)) { i -> (255 - (bytes[i].toInt() and 0xff)).toByte() }

    // The copy's columns for a page's key, so a key from the page finds its
    // place in the copy.
    private fun copyColumns(key: RowKey, kind: SongSort?, downloads: Boolean, down: Boolean, titles: Boolean): List<SqlValue> {
        val empty = SqlValue.Blob(ByteArray(0).toByteString())
        fun directed(lead: SqlValue, segment: Int) = if (down && segment == 0 && lead is SqlValue.Int64) SqlValue.Int64(-lead.v) else lead
        return when {
            downloads -> {
                val lead = key.values[0].let { if (down && it is SqlValue.Int64) SqlValue.Int64(-it.v) else it }
                val title = key.values[1].let { if (titles && down && it is SqlValue.Blob) SqlValue.Blob(turned(it.v.toByteArray()).toByteString()) else it }
                listOf(SqlValue.Int64(DONE_SEGMENT.toLong()), lead, SqlValue.Int64(0), SqlValue.Text(""), title, key.values[2], key.values[3], SqlValue.Text(key.id))
            }
            kind == SongSort.Rating -> {
                val liked = (key.values[1] as? SqlValue.Int64)?.v ?: 0
                listOf(SqlValue.Int64(key.segment.toLong()), directed(key.values[0], key.segment), SqlValue.Int64(1 - liked), key.values[2], empty, SqlValue.Text(key.id), SqlValue.Text(""), SqlValue.Text(""))
            }
            kind == SongSort.Liked -> listOf(SqlValue.Int64(key.segment.toLong()), SqlValue.Int64(0), SqlValue.Int64(0), key.values[0], empty, SqlValue.Text(key.id), SqlValue.Text(""), SqlValue.Text(""))
            else -> listOf(SqlValue.Int64(0), directed(key.values[0], 0), SqlValue.Int64(0), key.values[1], empty, SqlValue.Text(key.id), SqlValue.Text(""), SqlValue.Text(""))
        }
    }
}
