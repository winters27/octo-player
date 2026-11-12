package app.winters.octo.ui.paging

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.PlaylistRowView
import app.winters.octo.catalog.marks
import app.winters.octo.catalog.rawQuery
import app.winters.octo.playback.QUEUE_WINDOW

// One playlist's songs as a play's start reads them: with no pin and no
// write, each statement finding the set the playlist shows inside itself
// (the playlist joined to its rows on its itemSet), so each statement is one
// state of the list and no set read by one is carried into the next. The
// start's reads are built for a list that moves between them: its first row
// is one statement, a tapped row is the screen's own key, a shuffled place
// that emptied is drawn again, and a song the list no longer holds plays
// alone. The play's build pins its own set after the player has the song,
// and finds the start there by its key. `before` hears each statement's
// number, from 0, before it runs.
class PlaylistStartLoader(
    db: CatalogDatabase,
    private val playlistId: String,
    private val before: suspend (Int) -> Unit = {},
) : ListLoader<PlaylistRowView> {
    private val dao = db.listsDao()
    private var statements = 0

    override val tables = arrayOf("playlist", "playlist_item", "track", "online_song")

    override val order: Comparator<RowKey> = PLAYLIST_ORDER

    // A playlist may hold a song in several rows.
    override val songsOnce: Boolean = false

    private suspend fun next() = before(statements++)

    // The rows of the set shown now, each with its song, a row whose song
    // is gone from the library and from the finds left out, as a pinned walk
    // leaves it out. The set's own check is kept off the join (+), so the
    // planner never turns it into a range over every set of the playlist.
    private fun rows(where: String) =
        "SELECT i.ord AS ord, i.itemKey AS itemKey, i.id AS itemId, t.id IS NOT NULL AS inLibrary, ${songColumns()} $SHOWN " +
            "LEFT JOIN track t ON t.id = i.trackId LEFT JOIN online_song o ON o.id = i.trackId " +
            "WHERE p.id = ? AND +p.itemSet >= 0 AND (t.id IS NOT NULL OR o.id IS NOT NULL) $where"

    private suspend fun page(where: String, args: List<SqlValue>, forward: Boolean, limit: Int, offset: Int = 0): List<Keyed<PlaylistRowView>> {
        next()
        val dir = if (forward) "" else " DESC"
        val sql = rows(where) + " ORDER BY i.ord$dir, i.itemKey$dir LIMIT $limit" + (if (offset > 0) " OFFSET $offset" else "")
        return dao.playlistRows(rawQuery(sql, listOf(SqlValue.Text(playlistId)) + args)).map { Keyed(playlistKey(it.ord, it.itemKey), it) }
    }

    private fun cursor(forward: Boolean) =
        if (forward) "AND i.ord >= ? AND CASE WHEN i.ord = ? THEN i.itemKey > ? ELSE 1 END" else "AND i.ord <= ? AND CASE WHEN i.ord = ? THEN i.itemKey < ? ELSE 1 END"

    private fun cursorArgs(key: RowKey): List<SqlValue> = listOf(key.values[0], key.values[0], SqlValue.Int64(key.id.toLong()))

    private suspend fun countWhere(where: String, args: List<SqlValue>): Int {
        next()
        val sql = "SELECT COUNT(*) $SHOWN LEFT JOIN track t ON t.id = i.trackId LEFT JOIN online_song o ON o.id = i.trackId " +
            "WHERE p.id = ? AND +p.itemSet >= 0 AND (t.id IS NOT NULL OR o.id IS NOT NULL) $where"
        return dao.count(rawQuery(sql, listOf(SqlValue.Text(playlistId)) + args))
    }

    override suspend fun count(): Int = countWhere("", emptyList())

    override suspend fun survey(anchor: RowKey?): Survey =
        Survey(count(), null, anchor?.let { countWhere("AND i.ord <= ? AND CASE WHEN i.ord = ? THEN i.itemKey < ? ELSE 1 END", cursorArgs(it)) })

    override suspend fun after(key: RowKey?, limit: Int) =
        if (key == null) page("", emptyList(), forward = true, limit) else page(cursor(true), cursorArgs(key), forward = true, limit)

    override suspend fun before(key: RowKey, limit: Int) = page(cursor(false), cursorArgs(key), forward = false, limit)

    override suspend fun keyAt(position: Int): RowKey? =
        if (position < 0) null else page("", emptyList(), forward = true, limit = 1, offset = position).firstOrNull()?.key

    // The first row holding the song in the set shown now.
    override suspend fun keyOf(trackId: String): RowKey? = page("AND i.trackId = ?", listOf(SqlValue.Text(trackId)), forward = true, limit = 1).firstOrNull()?.key

    // The row with the same key in the set shown now, wherever it stands.
    override suspend fun rekey(key: RowKey): RowKey? {
        val itemKey = key.id.toLongOrNull() ?: return null
        return page("AND i.itemKey = ?", listOf(SqlValue.Int64(itemKey)), forward = true, limit = 1).firstOrNull()?.key
    }

    override suspend fun present(ids: List<String>): Set<String> {
        val keys = ids.mapNotNull { it.toLongOrNull() }
        if (keys.isEmpty()) return emptySet()
        return page("AND i.itemKey IN (${marks(keys.size)})", keys.map { SqlValue.Int64(it) }, forward = true, limit = keys.size).mapTo(HashSet()) { it.key.id }
    }

    // The whole list when it holds at most QUEUE_WINDOW rows, in play order,
    // in one statement that reads at most QUEUE_WINDOW rows: its rows come
    // only when its set's rows, counted inside the same statement up to
    // QUEUE_WINDOW + 1 (a row whose song is gone counted too, so it can only
    // say big too soon), are few enough; the count names the playlist by its
    // own argument, so it is counted once, not once a row. Null for a bigger
    // list, and for an empty one, whose first row the start then finds
    // missing.
    override suspend fun small(): List<Keyed<PlaylistRowView>>? {
        val few = "AND (SELECT COUNT(*) FROM (SELECT 1 $SHOWN_AGAIN WHERE p2.id = ? AND +p2.itemSet >= 0 LIMIT ${QUEUE_WINDOW + 1})) <= $QUEUE_WINDOW"
        return page(few, listOf(SqlValue.Text(playlistId)), forward = true, limit = QUEUE_WINDOW).ifEmpty { null }
    }

    override fun trackIdOf(row: PlaylistRowView): String = row.track.id
}

// A playlist's shown set: the playlist and the rows of the set it shows.
private const val SHOWN = "FROM playlist p CROSS JOIN playlist_item i INDEXED BY index_playlist_item_set ON i.playlistId = p.id AND i.itemSet = p.itemSet"

// The same, under other names, for a subquery beside it.
private const val SHOWN_AGAIN = "FROM playlist p2 CROSS JOIN playlist_item i2 INDEXED BY index_playlist_item_set ON i2.playlistId = p2.id AND i2.itemSet = p2.itemSet"
