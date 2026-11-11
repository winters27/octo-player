package app.winters.octo.ui.paging

import app.winters.octo.catalog.KeyedTrack
import app.winters.octo.catalog.ListsDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.rawQuery
import app.winters.octo.playback.ArtistOrder
import app.winters.octo.playback.QUEUE_WINDOW
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// One album of an artist's songs, by the artist page's base order: the
// album's year (none counts as 0) newest first, then its name with no name
// first, then its id; `tuple` is that order as key values.
class AlbumGroup(val albumId: String, val tuple: List<SqlValue>)

// An artist's albums in the page's base order, read once per loader: the
// artist's album ids by a skip over the artist's songs index, each with
// its album row (artist-album sized).
private suspend fun artistAlbumGroups(dao: ListsDao, artistId: String): List<AlbumGroup> {
    val ids = ArrayList<String>()
    var after = ""
    while (true) {
        // No row past the last album, so the walk ends there.
        val next = dao.ids(
            rawQuery(
                "SELECT t.albumId FROM track t WHERE t.artistId = ? AND t.albumId > ? ORDER BY t.albumId LIMIT 1",
                listOf(SqlValue.Text(artistId), SqlValue.Text(after)),
            ),
        ).firstOrNull() ?: break
        ids += next
        after = next
    }
    val albums = ids.chunked(900).flatMap { chunk ->
        dao.albums(rawQuery("SELECT * FROM album WHERE id IN ${marks(chunk.size)}", chunk.map { SqlValue.Text(it) }))
    }.associateBy { it.id }
    return ids.map { id ->
        val album = albums[id]
        AlbumGroup(
            id,
            listOf(
                SqlValue.Int64(-(album?.year ?: 0).toLong()),
                SqlValue.Int64(if (album == null) 0 else 1),
                SqlValue.Text(album?.sortKey.orEmpty()),
                SqlValue.Text(id),
            ),
        )
    }.sortedWith { a, b -> compareValues(a.tuple, b.tuple) }
}

internal fun compareValues(a: List<SqlValue>, b: List<SqlValue>): Int {
    for (i in 0 until minOf(a.size, b.size)) SqlOrder.compare(a[i], b[i]).let { if (it != 0) return it }
    return a.size.compareTo(b.size)
}

// The base key of a song of an artist's album group.
private fun baseKey(segment: Int, group: AlbumGroup, row: TrackEntity) =
    RowKey(segment, group.tuple + SqlValue.Int64(row.albumOrder.toLong()), row.id)

// Each album group's songs as a part, its songs by their place in the
// album, on the artist's songs index; `filter` narrows them.
private fun groupParts(
    dao: ListsDao,
    segment: Int,
    artistId: String,
    groups: List<AlbumGroup>,
    filter: String?,
    keyOf: (AlbumGroup, KeyedTrack) -> RowKey = { group, row -> baseKey(segment, group, row.track) },
    prefix: List<SqlValue> = emptyList(),
): List<Part<KeyedTrack>> = groups.map { group ->
    val fixedValues = prefix + group.tuple
    IndexPart(
        segment,
        "track t",
        listOfNotNull("t.artistId = ?", "t.albumId = ?", filter),
        listOf(SqlValue.Text(artistId), SqlValue.Text(group.albumId)),
        emptyList(),
        listOf("t.albumOrder", "t.id"),
        false,
        trackSelect(segment),
        TrackReads(dao),
        { keyOf(group, it) },
        fixed = fixedValues.size,
        placeOf = { key ->
            val segments = segment.compareTo(key.segment)
            if (segments != 0) segments else compareValues(fixedValues, key.values.take(fixedValues.size))
        },
    )
}

// An artist's songs as their page lists them (`Page`): once any is played,
// the played ones first, most played first, of two played as often the
// one played last, ties in the base order; then the rest in the base
// order, album by album. Or album by album (`Albums`): the albums the
// artist made, newest first, each with its songs in the album's order, as
// the collection menu plays them.
class ArtistLoaderSource(private val dao: ListsDao) {
    fun loader(artistId: String, order: ArtistOrder): ListLoader<TrackEntity> = when (order) {
        ArtistOrder.Page -> pageLoader(artistId)
        ArtistOrder.Albums -> albumsLoader(artistId)
    }

    private fun pageLoader(artistId: String): ListLoader<TrackEntity> {
        val lock = Mutex()
        var cached: List<AlbumGroup>? = null
        suspend fun groups() = cached ?: lock.withLock { cached ?: artistAlbumGroups(dao, artistId).also { cached = it } }
        val unplayed = "NOT EXISTS (SELECT 1 FROM play_total p WHERE p.trackId = t.id AND p.plays > 0)"
        val playedKey: (KeyedTrack) -> RowKey = { row ->
            val t = row.track
            RowKey(
                0,
                listOf(
                    SqlValue.Int64(row.k0 ?: 0), SqlValue.Int64(row.k1 ?: 0),
                    SqlValue.Int64(-(row.k2 ?: 0)), SqlValue.Int64(if (row.t0 == null) 0 else 1), SqlValue.Text(row.t0.orEmpty()),
                    SqlValue.Text(t.albumId), SqlValue.Int64(t.albumOrder.toLong()),
                ),
                t.id,
            )
        }
        val baseColumns = listOf("-COALESCE(a.year, 0)", "CASE WHEN a.id IS NULL THEN 0 ELSE 1 END", "IFNULL(a.sortKey, '')", "t.albumId", "t.albumOrder", "t.id")
        val baseOrder = baseColumns.joinToString(", ")
        val inGroup = "EXISTS (SELECT 1 FROM play_total q WHERE q.trackId = t.id AND q.plays = ? AND q.lastPlayedAt = ?)"
        val played = TieGroupPart(
            0,
            "play_total p CROSS JOIN track t ON t.id = p.trackId LEFT JOIN album a ON a.id = t.albumId",
            listOf("p.artistId = ?", "p.plays > 0"),
            listOf(SqlValue.Text(artistId)),
            listOf("p.plays", "p.lastPlayedAt"),
            listOf("p.trackId"),
            "p.trackId",
            true,
            trackSelect(0, k0 = "p.plays", k1 = "p.lastPlayedAt", k2 = "COALESCE(a.year, 0)", t0 = "a.sortKey"),
            TrackReads(dao),
            playedKey,
            Comparator { a, b -> compareValues(a.values.drop(2), b.values.drop(2)).let { if (it != 0) it else SqlOrder.text(a.id, b.id) } },
            groupWalk = { group, from, forward, limit ->
                val cursor = if (from == null) "" else " AND ($baseOrder) ${if (forward) ">" else "<"} (?, ?, ?, ?, ?, ?)"
                val values = if (from == null) emptyList() else from.values.drop(2) + SqlValue.Text(from.id)
                "SELECT ${trackSelect(0, k0 = "?", k1 = "?", k2 = "COALESCE(a.year, 0)", t0 = "a.sortKey")} FROM track t LEFT JOIN album a ON a.id = t.albumId " +
                    "WHERE t.artistId = ? AND $inGroup$cursor ORDER BY ${orderBy(baseColumns, !forward)} LIMIT $limit" to
                    (group + SqlValue.Text(artistId) + group + values)
            },
            groupCount = { group, before ->
                "SELECT COUNT(*) FROM track t LEFT JOIN album a ON a.id = t.albumId WHERE t.artistId = ? AND $inGroup AND ($baseOrder) < (?, ?, ?, ?, ?, ?)" to
                    (listOf(SqlValue.Text(artistId)) + group + before.values.drop(2) + SqlValue.Text(before.id))
            },
            blockOrder = { forward -> if (forward) "p.plays DESC, p.lastPlayedAt DESC, p.trackId" else "p.plays, p.lastPlayedAt, p.trackId DESC" },
        )
        val order = Comparator<RowKey> { a, b ->
            a.segment.compareTo(b.segment).let { if (it != 0) return@Comparator it }
            if (a.segment == 0) {
                SqlOrder.compare(b.values[0], a.values[0]).let { if (it != 0) return@Comparator it }
                SqlOrder.compare(b.values[1], a.values[1]).let { if (it != 0) return@Comparator it }
                return@Comparator compareValues(a.values.drop(2), b.values.drop(2)).let { if (it != 0) it else SqlOrder.text(a.id, b.id) }
            }
            compareValues(a.values, b.values).let { if (it != 0) it else SqlOrder.text(a.id, b.id) }
        }
        // The small check: the artist's songs with their plays and their
        // album's year and name in one statement, each keyed from its own
        // columns as its part keys it.
        val small = "SELECT t.*, IFNULL(p.plays, 0) AS k0, IFNULL(p.lastPlayedAt, 0) AS k1, COALESCE(a.year, 0) AS k2, a.sortKey AS t0, NULL AS t1, " +
            "CASE WHEN p.plays > 0 THEN 0 ELSE 1 END AS seg FROM track t LEFT JOIN album a ON a.id = t.albumId LEFT JOIN play_total p ON p.trackId = t.id " +
            "WHERE t.artistId = ? LIMIT ${QUEUE_WINDOW + 1}"
        val unplayedKey: (KeyedTrack) -> RowKey = { row ->
            val t = row.track
            RowKey(
                1,
                listOf(
                    SqlValue.Int64(-(row.k2 ?: 0)), SqlValue.Int64(if (row.t0 == null) 0 else 1), SqlValue.Text(row.t0.orEmpty()),
                    SqlValue.Text(t.albumId), SqlValue.Int64(t.albumOrder.toLong()),
                ),
                t.id,
            )
        }
        return PartsLoader(
            { listOf<Part<KeyedTrack>>(played) + groupParts(dao, 1, artistId, groups(), unplayed) },
            arrayOf("track", "album", PLAY_TOTAL),
            order,
            songsOnce = true,
            rowOf = { it.track },
            idOf = { it.id },
            smallRows = { sql, args -> dao.tracks(rawQuery(sql, args)) },
            smallKey = { row -> if (row.seg == 0L) playedKey(row) else unplayedKey(row) },
            smallArms = listOf(small to listOf(SqlValue.Text(artistId))),
        )
    }

    // The albums whose artist this is, newest first (no year last), by
    // name, then id; each album's songs by their place in it.
    private fun albumsLoader(artistId: String): ListLoader<TrackEntity> {
        val lock = Mutex()
        var cached: List<AlbumGroup>? = null
        suspend fun groups(): List<AlbumGroup> = cached ?: lock.withLock {
            cached ?: dao.albums(rawQuery("SELECT * FROM album WHERE artistId = ?", listOf(SqlValue.Text(artistId)))).map { album ->
                AlbumGroup(
                    album.id,
                    listOf(
                        SqlValue.Int64(album.year?.let { -it.toLong() } ?: Long.MAX_VALUE),
                        SqlValue.Text(album.sortKey),
                        SqlValue.Text(album.id),
                    ),
                )
            }.sortedWith { a, b -> compareValues(a.tuple, b.tuple) }.also { cached = it }
        }
        suspend fun parts(): List<Part<KeyedTrack>> = groups().map { group ->
            IndexPart(
                0,
                "track t",
                listOf("t.albumId = ?"),
                listOf(SqlValue.Text(group.albumId)),
                emptyList(),
                listOf("t.albumOrder", "t.id"),
                false,
                trackSelect(0),
                TrackReads(dao),
                { row -> RowKey(0, group.tuple + SqlValue.Int64(row.track.albumOrder.toLong()), row.track.id) },
                fixed = group.tuple.size,
                placeOf = { key -> compareValues(group.tuple, key.values.take(group.tuple.size)) },
            )
        }
        // The small check: the songs of the artist's albums with each
        // album's year and name, in one statement.
        val small = "SELECT ${trackSelect(0, k2 = "a.year", t0 = "a.sortKey", t1 = "a.id")} FROM album a CROSS JOIN track t ON t.albumId = a.id " +
            "WHERE a.artistId = ? LIMIT ${QUEUE_WINDOW + 1}"
        return PartsLoader(
            ::parts,
            arrayOf("track", "album"),
            SqlOrder.rows(),
            songsOnce = true,
            rowOf = { it.track },
            idOf = { it.id },
            smallRows = { sql, args -> dao.tracks(rawQuery(sql, args)) },
            smallKey = { row ->
                val values = listOf(
                    SqlValue.Int64(row.k2?.let { -it } ?: Long.MAX_VALUE), SqlValue.Text(row.t0.orEmpty()), SqlValue.Text(row.t1.orEmpty()),
                    SqlValue.Int64(row.track.albumOrder.toLong()),
                )
                RowKey(0, values, row.track.id)
            },
            smallArms = listOf(small to listOf(SqlValue.Text(artistId))),
        )
    }
}

// Every album or every artist in the order its page shows them, each one's
// songs in turn: an album's by their place in it, an artist's in the
// artist page's base order. The groups are read once, album or artist
// sized, and a song's key leads with its group's place among them.
class GroupsLoader(
    private val dao: ListsDao,
    private val artists: Boolean,
    private val groupIds: suspend () -> List<String>,
    private val tablesRead: Array<String>,
) : ListLoader<TrackEntity> {
    private val lock = Mutex()
    private var parts: List<Part<KeyedTrack>>? = null

    private suspend fun parts(): List<Part<KeyedTrack>> = parts ?: lock.withLock {
        parts ?: groupIds().withIndex().flatMap { (rank, id) ->
            val prefix = listOf(SqlValue.Int64(rank.toLong()))
            if (artists) {
                groupParts(dao, 0, id, artistAlbumGroups(dao, id), null, { group, row -> RowKey(0, prefix + group.tuple + SqlValue.Int64(row.track.albumOrder.toLong()), row.track.id) }, prefix)
            } else {
                listOf(
                    IndexPart(
                        0, "track t", listOf("t.albumId = ?"), listOf(SqlValue.Text(id)), emptyList(), listOf("t.albumOrder", "t.id"), false,
                        trackSelect(0), TrackReads(dao),
                        { row -> RowKey(0, prefix + SqlValue.Int64(row.track.albumOrder.toLong()), row.track.id) },
                        fixed = 1,
                        placeOf = { key -> compareValues(prefix, key.values.take(1)) },
                    ),
                )
            }
        }.also { parts = it }
    }

    private val inner = PartsLoader(::parts, tablesRead, SqlOrder.rows(), songsOnce = true, rowOf = { it.track }, idOf = { it.id })

    override val tables: Array<String> get() = tablesRead
    override val order: Comparator<RowKey> get() = inner.order
    override val songsOnce: Boolean get() = true

    // Every song is in one album and has one artist.
    override suspend fun count(): Int = dao.count(rawQuery("SELECT COUNT(*) FROM track", emptyList()))

    override suspend fun survey(anchor: RowKey?): Survey =
        Survey(count(), null, anchor?.let { inner.survey(it).anchorAt })

    override suspend fun after(key: RowKey?, limit: Int) = inner.after(key, limit)
    override suspend fun before(key: RowKey, limit: Int) = inner.before(key, limit)
    override suspend fun keyAt(position: Int) = inner.keyAt(position)
    override suspend fun keyOf(trackId: String) = inner.keyOf(trackId)
    override suspend fun rekey(key: RowKey): RowKey? = key
    override suspend fun present(ids: List<String>) = inner.present(ids)
    override fun trackIdOf(row: TrackEntity): String = row.id

    // The small check: the library's songs with their album's year and name
    // in one statement; at most QUEUE_WINDOW are the whole list, put in the
    // groups' order (album or artist sized) and each group's own here.
    override suspend fun small(): List<Keyed<TrackEntity>>? {
        val rows = dao.tracks(
            rawQuery(
                "SELECT ${trackSelect(0, k2 = "COALESCE(a.year, 0)", t0 = "a.sortKey")} FROM track t LEFT JOIN album a ON a.id = t.albumId LIMIT ${QUEUE_WINDOW + 1}",
                emptyList(),
            ),
        )
        if (rows.size > QUEUE_WINDOW) return null
        val ranks = groupIds().withIndex().associate { (rank, id) -> id to rank.toLong() }
        return rows.mapNotNull { row ->
            val t = row.track
            val rank = SqlValue.Int64(ranks[if (artists) t.artistId else t.albumId] ?: return@mapNotNull null)
            val inGroup = if (artists) {
                listOf(SqlValue.Int64(-(row.k2 ?: 0)), SqlValue.Int64(if (row.t0 == null) 0 else 1), SqlValue.Text(row.t0.orEmpty()), SqlValue.Text(t.albumId))
            } else {
                emptyList()
            }
            Keyed(RowKey(0, listOf(rank) + inGroup + SqlValue.Int64(t.albumOrder.toLong()), t.id), t)
        }.sortedWith { a, b -> order.compare(a.key, b.key) }
    }
}
