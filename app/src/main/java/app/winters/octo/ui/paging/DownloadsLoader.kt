package app.winters.octo.ui.paging

import app.winters.octo.catalog.DownloadRowView
import app.winters.octo.catalog.ListsDao
import app.winters.octo.catalog.rawQuery
import app.winters.octo.offline.DownloadRow
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.sort.DownloadSort
import app.winters.octo.sort.SortOrder
import okio.ByteString.Companion.toByteString

// A download row through the lists' statements.
class DownloadReads(private val dao: ListsDao) : PartReads<DownloadRowView> {
    override suspend fun rows(sql: String, args: List<SqlValue>) = dao.downloadRows(rawQuery(sql, args))

    override suspend fun count(sql: String, args: List<SqlValue>) = dao.count(rawQuery(sql, args))

    override suspend fun ids(sql: String, args: List<SqlValue>) = dao.ids(rawQuery(sql, args))
}

fun DownloadRowView.toRow() = DownloadRow(
    trackId = trackId,
    sourceId = sourceId,
    serverId = serverId,
    title = title,
    artist = artist,
    artwork = artwork,
    sizeBytes = sizeBytes,
    state = runCatching { DownloadStatus.valueOf(state) }.getOrDefault(DownloadStatus.Queued),
    progress = progress,
    reason = reason,
    addedAt = addedAt,
    explicit = explicit,
)

private fun downloadSelect(segment: Int) =
    "d.trackId AS trackId, d.sourceId AS sourceId, d.serverId AS serverId, d.title AS title, d.artist AS artist, " +
        "t.artwork AS artwork, d.sizeBytes AS sizeBytes, d.state AS state, d.progress AS progress, d.reason AS reason, " +
        "d.addedAt AS addedAt, t.explicit AS explicit, d.titleKey AS titleKey, $segment AS seg"

// The segment done downloads are in, after the three on their way.
const val DONE_SEGMENT = 3

// The key of a finished download, on the page and in a sort copy alike:
// its order's lead (0 for Title), its title key, its song, its source, then
// its server id, so no two rows share a key.
fun doneKey(lead: Long, titleKey: ByteArray, trackId: String, sourceId: String, serverId: String) =
    RowKey(DONE_SEGMENT, listOf(SqlValue.Int64(lead), SqlValue.Blob(titleKey.toByteString()), SqlValue.Text(trackId), SqlValue.Text(sourceId)), serverId)

// The lead of a done download in an order.
fun doneLead(sort: DownloadSort, sizeBytes: Long, addedAt: Long): Long = when (sort) {
    DownloadSort.Title -> 0
    DownloadSort.Size -> sizeBytes
    DownloadSort.RecentlyDownloaded -> addedAt
}

// The Downloads page of one server (`sourceId`, the one in use): the
// downloads on their way, downloading, waiting, then failed, each newest
// first, then the finished ones in the chosen order, each on its own index
// ending in the row's key.
class DownloadsLists(private val dao: ListsDao) {
    private val reads = DownloadReads(dao)

    // With `doneOnly`, the finished ones alone, as the car lists them.
    fun loader(sourceId: String?, order: SortOrder, doneOnly: Boolean = false): PartsLoader<DownloadRowView, DownloadRow> {
        val sort = order.by as? DownloadSort ?: DownloadSort.RecentlyDownloaded
        val source = SqlValue.Text(sourceId.orEmpty())
        val from = "download d LEFT JOIN track t ON t.id = d.trackId"
        val active = listOf(DownloadStatus.Downloading, DownloadStatus.Queued, DownloadStatus.Failed).mapIndexed { segment, state ->
            IndexPart(
                segment,
                from,
                listOf("d.sourceId = ?", "d.state = '${state.name}'"),
                listOf(source),
                listOf("d.addedAt"),
                listOf("d.title", "d.serverId"),
                true,
                downloadSelect(segment),
                reads,
                { row -> RowKey(segment, listOf(SqlValue.Int64(row.addedAt), SqlValue.Text(row.title)), row.serverId) },
            )
        }
        val lead = when (sort) {
            DownloadSort.Title -> emptyList()
            DownloadSort.Size -> listOf("d.sizeBytes")
            DownloadSort.RecentlyDownloaded -> listOf("d.addedAt")
        }
        val titleLead = sort == DownloadSort.Title
        val done = IndexPart(
            DONE_SEGMENT,
            from,
            listOf("d.sourceId = ?", "d.state = 'Done'"),
            listOf(source),
            if (titleLead) listOf("d.titleKey") else lead,
            if (titleLead) listOf("d.trackId", "d.serverId") else listOf("d.titleKey", "d.trackId", "d.serverId"),
            order.descending,
            downloadSelect(DONE_SEGMENT),
            reads,
            { row -> doneKey(doneLead(sort, row.sizeBytes, row.addedAt), row.titleKey, row.trackId, row.sourceId, row.serverId) },
            idColumn = "d.trackId",
            columnsOf = { key ->
                val ties = listOf(key.values[1], key.values[2], SqlValue.Text(key.id))
                if (titleLead) ties else listOf(key.values[0]) + ties
            },
        )
        val parts: List<Part<DownloadRowView>> = when {
            sourceId == null -> emptyList()
            doneOnly -> listOf(done)
            else -> active + done
        }
        return PartsLoader(
            { parts },
            arrayOf("download", "track"),
            downloadsOrder(sort, order.descending),
            songsOnce = false,
            rowOf = { it.toRow() },
            idOf = { it.trackId },
            smallRows = { sql, args -> dao.downloadRows(rawQuery(sql, args)) },
            smallKey = { row ->
                val segment = row.seg?.toInt() ?: DONE_SEGMENT
                if (segment == DONE_SEGMENT) {
                    doneKey(doneLead(sort, row.sizeBytes, row.addedAt), row.titleKey, row.trackId, row.sourceId, row.serverId)
                } else {
                    RowKey(segment, listOf(SqlValue.Int64(row.addedAt), SqlValue.Text(row.title)), row.serverId)
                }
            },
        )
    }

    // How many downloads of a server are finished and how much room they
    // take, counted on the done index.
    suspend fun doneTotals(sourceId: String?): Pair<Int, Long> {
        if (sourceId == null) return 0 to 0L
        val q = "SELECT COUNT(*) FROM download WHERE sourceId = ? AND state = 'Done'"
        val size = "SELECT IFNULL(SUM(sizeBytes), 0) FROM download WHERE sourceId = ? AND state = 'Done'"
        val args = listOf(SqlValue.Text(sourceId))
        return dao.count(rawQuery(q, args)) to (dao.numbers(rawQuery(size, args)).firstOrNull() ?: 0L)
    }

    // Whether a server has any download at all, on its way or done.
    suspend fun any(sourceId: String?): Boolean =
        sourceId != null && dao.count(rawQuery("SELECT EXISTS(SELECT 1 FROM download WHERE sourceId = ?)", listOf(SqlValue.Text(sourceId)))) == 1
}

// The page's order of download keys: the three segments on their way,
// each newest first then by title and id, then the done ones by their
// lead the list's way, then the title key, song, source and id.
internal fun downloadsOrder(sort: DownloadSort, down: Boolean): Comparator<RowKey> = Comparator { a, b ->
    a.segment.compareTo(b.segment).let { if (it != 0) return@Comparator it }
    if (a.segment < DONE_SEGMENT) return@Comparator SqlOrder.rows(1, true).compare(a, b)
    val lead = SqlOrder.compare(a.values[0], b.values[0]).let { if (sort == DownloadSort.Title) 0 else if (down) -it else it }
    if (lead != 0) return@Comparator lead
    val titles = SqlOrder.compare(a.values[1], b.values[1]).let { if (sort == DownloadSort.Title && down) -it else it }
    if (titles != 0) return@Comparator titles
    for (i in 2 until a.values.size) SqlOrder.compare(a.values[i], b.values[i]).let { if (it != 0) return@Comparator it }
    SqlOrder.text(a.id, b.id)
}
