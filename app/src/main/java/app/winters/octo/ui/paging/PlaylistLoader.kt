package app.winters.octo.ui.paging

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.PinnedSet
import app.winters.octo.catalog.PlaylistRowView
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.rawQuery
import app.winters.octo.playback.QUEUE_WINDOW
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// A song's columns as the library song `t` has them, or, for a song found
// online `o`, shaped the way a find is shaped like a library song. With
// `goneId`, a row that is neither keeps that id and empty words. Each is
// named with `prefix` before it, and is null on a row where `none` holds.
internal fun songColumns(
    trackNo: String = "NULL",
    discNo: String = "NULL",
    goneId: String? = null,
    prefix: String = "",
    none: String? = null,
): String = listOf(
    "id" to "o.id", "sourceId" to "o.sourceId", "nativeId" to "o.nativeId", "title" to "o.title",
    "searchKey" to "LOWER(o.title)", "sortKey" to "LOWER(o.title)", "artist" to "o.artist", "artistId" to "''",
    "album" to "o.album", "albumId" to "''", "trackNo" to trackNo, "discNo" to discNo, "year" to "NULL",
    "durationMs" to "o.durationMs", "addedAt" to "0", "mimeType" to "o.mimeType", "sizeBytes" to "NULL",
    "artwork" to "CASE WHEN o.coverId IS NULL THEN NULL ELSE 'online:' || o.sourceId || '|' || o.coverId END",
    "uri" to "NULL", "albumOrder" to "0", "relinkKey" to "''", "genre" to "''", "onPhone" to "0", "rating" to "0",
    "explicit" to "o.explicit", "artistSortKey" to "''", "albumSortKey" to "''", "lookupKey" to "''", "looseKey" to "''",
).joinToString(", ") { (column, find) ->
    val gone = when {
        goneId == null -> find
        column == "id" -> "COALESCE(o.id, $goneId)"
        column in GONE_TEXT -> "COALESCE($find, '')"
        column == "durationMs" -> "COALESCE($find, 0)"
        else -> find
    }
    val unless = none?.let { "WHEN $it THEN NULL " }.orEmpty()
    "CASE ${unless}WHEN t.id IS NOT NULL THEN t.$column ELSE $gone END AS $prefix$column"
}

// A find's columns that may be empty but never null.
private val GONE_TEXT = setOf("sourceId", "nativeId", "title", "searchKey", "sortKey", "artist", "album")

// A playlist page's key: a row's place in its set, then its own key.
internal fun playlistKey(ord: Long, itemKey: Long) = RowKey(0, listOf(SqlValue.Int64(ord)), itemKey.toString())

// Playlist keys in play order: by place, then by the row's key as a number.
val PLAYLIST_ORDER: Comparator<RowKey> = Comparator { a, b ->
    SqlOrder.compare(a.values[0], b.values[0]).let { if (it != 0) return@Comparator it }
    (a.id.toLongOrNull() ?: 0).compareTo(b.id.toLongOrNull() ?: 0)
}

// A loader that holds something while it is in use (a playlist set's
// pin) and lets it go when its generation ends.
interface Retiring {
    suspend fun retire()
}

// One playlist's songs as one walk sees them: the set the playlist showed
// when the walk first read, pinned under `owner`, read as it stood then
// through the set's edit log, so a move, a removal or a putting back made
// meanwhile changes nothing it reads; an add made meanwhile is not in it.
// Each page is one statement over the set's untouched rows and each edited
// row's first log row after the pin, in play order. A row whose song is
// gone from the library and from the finds drops out, as today.
class PlaylistLoader(
    db: CatalogDatabase,
    private val sets: PlaylistSets,
    private val playlistId: String,
    private val owner: String,
) : ListLoader<PlaylistRowView>, Retiring {
    private val dao = db.listsDao()
    private val lock = Mutex()
    @Volatile private var pinned: PinnedSet? = null
    @Volatile private var tried = false
    @Volatile private var retired = false

    override val tables = arrayOf("playlist", "playlist_item", "track", "online_song")

    override val order: Comparator<RowKey> = PLAYLIST_ORDER

    // A playlist may hold a song in several rows.
    override val songsOnce: Boolean = false

    private suspend fun set(): PinnedSet? {
        pinned?.let { return it }
        if (tried) return null
        return lock.withLock {
            pinned ?: if (tried || retired) {
                null
            } else {
                tried = true
                sets.pin(owner, playlistId).also { pinned = it }
            }
        }
    }

    override suspend fun retire() {
        lock.withLock {
            retired = true
            if (pinned != null || tried) sets.releasePin(owner, playlistId)
        }
    }

    // One arm of a page: the set's rows (`log` false) or the log's rows,
    // after the cursor and filtered, each with its song.
    private fun arm(log: Boolean, cursor: String, extra: String, columns: String): String {
        val (table, alias) = if (log) "playlist_edit_log" to "e" else "playlist_item" to "i"
        val baseline = if (log) {
            "e.op IN (0, 1) AND e.seq = (SELECT MIN(f.seq) FROM playlist_edit_log f " +
                "WHERE f.playlistId = ? AND f.itemSet = ? AND f.itemKey = e.itemKey AND f.seq > ?)"
        } else {
            "NOT EXISTS (SELECT 1 FROM playlist_edit_log f WHERE f.playlistId = ? AND f.itemSet = ? AND f.itemKey = i.itemKey AND f.seq > ?)"
        }
        return "SELECT $columns FROM $table $alias LEFT JOIN track t ON t.id = $alias.trackId LEFT JOIN online_song o ON o.id = $alias.trackId " +
            // The key bound is kept off the keys' index, so each arm walks
            // its order's index even from the top.
            "WHERE $alias.playlistId = ? AND $alias.itemSet = ? AND +$alias.itemKey < ? $cursor $extra AND $baseline " +
            "AND (t.id IS NOT NULL OR o.id IS NOT NULL)"
    }

    private fun rowColumns(log: Boolean): String {
        val a = if (log) "e" else "i"
        return "$a.ord AS ord, $a.itemKey AS itemKey, ${if (log) "0" else "i.id"} AS itemId, t.id IS NOT NULL AS inLibrary, ${songColumns()}"
    }

    // The values of one arm, in the order its text names them.
    private fun armArgs(set: PinnedSet, cursor: List<SqlValue>, extra: List<SqlValue>): List<SqlValue> =
        listOf(SqlValue.Text(set.playlistId), SqlValue.Int64(set.itemSet), SqlValue.Int64(set.nextKey0)) + cursor + extra +
            listOf(SqlValue.Text(set.playlistId), SqlValue.Int64(set.itemSet), SqlValue.Int64(set.seq0))

    private fun cursorText(log: Boolean, forward: Boolean): String {
        val a = if (log) "e" else "i"
        return if (forward) {
            "AND $a.ord >= ? AND CASE WHEN $a.ord = ? THEN $a.itemKey > ? ELSE 1 END"
        } else {
            "AND $a.ord <= ? AND CASE WHEN $a.ord = ? THEN $a.itemKey < ? ELSE 1 END"
        }
    }

    private fun cursorArgs(key: RowKey): List<SqlValue> {
        val ord = key.values[0]
        return listOf(ord, ord, SqlValue.Int64(key.id.toLong()))
    }

    private suspend fun page(key: RowKey?, forward: Boolean, limit: Int, offset: Int = 0, extra: Pair<(Boolean) -> String, List<SqlValue>>? = null): List<Keyed<PlaylistRowView>> {
        val set = set() ?: return emptyList()
        val cursor = if (key == null) emptyList() else cursorArgs(key)
        val (filter, values) = extra ?: ({ _: Boolean -> "" } to emptyList())
        val sql = arm(false, if (key == null) "" else cursorText(false, forward), filter(false), rowColumns(false)) +
            " UNION ALL " +
            arm(true, if (key == null) "" else cursorText(true, forward), filter(true), rowColumns(true)) +
            " ORDER BY ord ${if (forward) "" else "DESC"}, itemKey ${if (forward) "" else "DESC"} LIMIT $limit" +
            (if (offset > 0) " OFFSET $offset" else "")
        val args = armArgs(set, cursor, values) + armArgs(set, cursor, values)
        return dao.playlistRows(rawQuery(sql, args)).map { Keyed(playlistKey(it.ord, it.itemKey), it) }
    }

    private suspend fun countWhere(key: RowKey?, extra: Pair<(Boolean) -> String, List<SqlValue>>? = null): Int {
        val set = set() ?: return 0
        val cursor = if (key == null) emptyList() else cursorArgs(key)
        val (filter, values) = extra ?: ({ _: Boolean -> "" } to emptyList())
        val sql = "SELECT (SELECT COUNT(*) FROM (${arm(false, if (key == null) "" else strictBefore(false), filter(false), "1")})) + " +
            "(SELECT COUNT(*) FROM (${arm(true, if (key == null) "" else strictBefore(true), filter(true), "1")}))"
        val args = armArgs(set, cursor, values) + armArgs(set, cursor, values)
        return dao.count(rawQuery(sql, args))
    }

    // Rows strictly before a cursor.
    private fun strictBefore(log: Boolean): String {
        val a = if (log) "e" else "i"
        return "AND $a.ord <= ? AND CASE WHEN $a.ord = ? THEN $a.itemKey < ? ELSE 1 END"
    }

    override suspend fun count(): Int = countWhere(null)

    override suspend fun survey(anchor: RowKey?): Survey = Survey(count(), null, anchor?.let { countWhere(it) })

    override suspend fun after(key: RowKey?, limit: Int) = page(key, forward = true, limit)

    override suspend fun before(key: RowKey, limit: Int) = page(key, forward = false, limit)

    override suspend fun keyAt(position: Int): RowKey? =
        if (position < 0) null else page(null, forward = true, limit = 1, offset = position).firstOrNull()?.key

    // The first row holding the song, as the pinned set stood.
    override suspend fun keyOf(trackId: String): RowKey? {
        val byTrack: (Boolean) -> String = { log -> "AND ${if (log) "e" else "i"}.trackId = ?" }
        return page(null, forward = true, limit = 1, extra = byTrack to listOf(SqlValue.Text(trackId))).firstOrNull()?.key
    }

    // A key from another generation's set, in this one's: the row with the
    // same key, wherever it stands now; null when this set has none.
    override suspend fun rekey(key: RowKey): RowKey? {
        val itemKey = key.id.toLongOrNull() ?: return null
        val byKey: (Boolean) -> String = { log -> "AND ${if (log) "e" else "i"}.itemKey = ?" }
        return page(null, forward = true, limit = 1, extra = byKey to listOf(SqlValue.Int64(itemKey))).firstOrNull()?.key
    }

    // Which of these rows (by their keys) the set still holds.
    override suspend fun present(ids: List<String>): Set<String> {
        val keys = ids.mapNotNull { it.toLongOrNull() }
        if (keys.isEmpty()) return emptySet()
        val among: (Boolean) -> String = { log -> "AND ${if (log) "e" else "i"}.itemKey IN ${marks(keys.size)}" }
        return page(null, forward = true, limit = keys.size, extra = among to keys.map { SqlValue.Int64(it) }).mapTo(HashSet()) { it.key.id }
    }

    // The set the playlist shows now, with no pin: first its rows are
    // counted on the set's index up to QUEUE_WINDOW + 1, so a big set says
    // so having read no row; then, at QUEUE_WINDOW or fewer, its rows in play
    // order on that index. The count takes in rows whose song is gone too,
    // which the read leaves out, so it can only say big too soon. The set's
    // own check is kept off the join (+), so the planner never turns it into
    // a range over every set of the playlist.
    override suspend fun small(): List<Keyed<PlaylistRowView>>? {
        val set = "FROM playlist p CROSS JOIN playlist_item i INDEXED BY index_playlist_item_set ON i.playlistId = p.id AND i.itemSet = p.itemSet"
        val id = listOf(SqlValue.Text(playlistId))
        val rowsInSet = dao.count(rawQuery("SELECT COUNT(*) FROM (SELECT 1 $set WHERE p.id = ? AND +p.itemSet >= 0 LIMIT ${QUEUE_WINDOW + 1})", id))
        if (rowsInSet > QUEUE_WINDOW) return null
        val sql = "SELECT i.ord AS ord, i.itemKey AS itemKey, i.id AS itemId, t.id IS NOT NULL AS inLibrary, ${songColumns()} " +
            "$set LEFT JOIN track t ON t.id = i.trackId LEFT JOIN online_song o ON o.id = i.trackId " +
            "WHERE p.id = ? AND +p.itemSet >= 0 AND (t.id IS NOT NULL OR o.id IS NOT NULL) ORDER BY i.ord, i.itemKey LIMIT ${QUEUE_WINDOW + 1}"
        val rows = dao.playlistRows(rawQuery(sql, id))
        // An edit between the two may have grown the set.
        if (rows.size > QUEUE_WINDOW) return null
        return rows.map { Keyed(playlistKey(it.ord, it.itemKey), it) }
    }

    override fun trackIdOf(row: PlaylistRowView): String = row.track.id
}
