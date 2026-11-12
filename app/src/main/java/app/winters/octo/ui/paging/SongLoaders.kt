package app.winters.octo.ui.paging

import app.winters.octo.catalog.KeyedTrack
import app.winters.octo.catalog.ListsDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.rawQuery
import app.winters.octo.playback.QUEUE_WINDOW
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder

// A song row's columns, with what its sort takes from beside the song, and
// the part of the list it was read in.
internal fun trackSelect(segment: Int, k0: String = "NULL", k1: String = "NULL", k2: String = "NULL", t0: String = "NULL", t1: String = "NULL") =
    trackSelectBy("$segment", k0, k1, k2, t0, t1)

// The same, the part told by an expression on the row.
internal fun trackSelectBy(segment: String, k0: String = "NULL", k1: String = "NULL", k2: String = "NULL", t0: String = "NULL", t1: String = "NULL") =
    "t.*, $k0 AS k0, $k1 AS k1, $k2 AS k2, $t0 AS t0, $t1 AS t1, $segment AS seg"

// Song rows through the lists' statements.
class TrackReads(private val dao: ListsDao) : PartReads<KeyedTrack> {
    override suspend fun rows(sql: String, args: List<SqlValue>) = dao.tracks(rawQuery(sql, args))

    override suspend fun count(sql: String, args: List<SqlValue>) = dao.count(rawQuery(sql, args))

    override suspend fun ids(sql: String, args: List<SqlValue>) = dao.ids(rawQuery(sql, args))
}

// A key column's value in a song row.
internal fun valueOf(row: KeyedTrack, column: String): SqlValue {
    val t = row.track
    return when (column) {
        "t.sortKey" -> SqlValue.Text(t.sortKey)
        "t.artistSortKey" -> SqlValue.Text(t.artistSortKey)
        "t.albumSortKey" -> SqlValue.Text(t.albumSortKey)
        "t.albumId" -> SqlValue.Text(t.albumId)
        "t.albumOrder" -> SqlValue.Int64(t.albumOrder.toLong())
        "t.addedAt" -> SqlValue.Int64(t.addedAt)
        "t.year" -> t.year?.let { SqlValue.Int64(it.toLong()) } ?: SqlValue.Null
        "t.durationMs" -> SqlValue.Int64(t.durationMs)
        "t.rating" -> SqlValue.Int64(t.rating.toLong())
        "t.id", "p.trackId" -> SqlValue.Text(t.id)
        "p.plays" -> SqlValue.Int64(row.k0 ?: 0)
        "p.lastPlayedAt" -> SqlValue.Int64(row.k1 ?: 0)
        "p.sortKey" -> SqlValue.Text(row.t0.orEmpty())
        "p.artistSortKey" -> SqlValue.Text(row.t1.orEmpty())
        else -> error("no key column $column")
    }
}

// A song row's key: its segment, every key column's value but the last,
// then its id.
internal fun keyBy(segment: Int, columns: List<String>): (KeyedTrack) -> RowKey =
    { row -> RowKey(segment, columns.dropLast(1).map { valueOf(row, it) }, row.track.id) }

// A song is liked.
internal const val LIKED = "EXISTS (SELECT 1 FROM liked_track l WHERE l.trackId = t.id)"

// The sort orders' key columns: a lead that runs the list's way, then ties
// that always run ascending, as SortQueries orders each list.
private val TITLE_TIES = listOf("t.artistSortKey", "t.id")
private val IN_ALBUM = listOf("t.albumId", "t.albumOrder", "t.sortKey", "t.id")
private val BY_ARTIST_TIES = listOf("t.albumSortKey") + IN_ALBUM
private val BY_ALBUM_TIES = listOf("t.artistSortKey") + IN_ALBUM
private val YEAR_TIES = listOf("t.albumSortKey") + IN_ALBUM
private val NAME_TIES = listOf("t.sortKey", "t.id")

// Orders keys segment by segment, each with its own count of lead values
// that run the list's way.
internal fun bySegment(descending: Boolean, leads: (Int) -> Int): Comparator<RowKey> =
    Comparator { a, b -> SqlOrder.rows(leads(minOf(a.segment, b.segment)), descending).compare(a, b) }

// Every song list the library shows: all songs, the liked ones or a
// genre's, in any song order, each a list of parts on the order's own
// index (3.1 to 3.2 of the lists' design).
class SongLists(private val dao: ListsDao) {
    private val reads = TrackReads(dao)

    fun loader(scope: SongScope, order: SortOrder): PartsLoader<KeyedTrack, TrackEntity> {
        val sort = order.by as? SongSort ?: SongSort.Title
        val down = order.descending
        return when (sort) {
            SongSort.Title, SongSort.FolderOrder -> plain(scope, down, listOf("t.sortKey"), TITLE_TIES, "t.sortKey", sort)
            SongSort.Artist -> plain(scope, down, listOf("t.artistSortKey"), BY_ARTIST_TIES, "t.artistSortKey", sort)
            SongSort.Album -> plain(scope, down, listOf("t.albumSortKey"), BY_ALBUM_TIES, "t.albumSortKey", sort)
            SongSort.RecentlyAdded -> plain(scope, down, listOf("t.addedAt"), IN_ALBUM, null, sort)
            SongSort.Length -> plain(scope, down, listOf("t.durationMs"), NAME_TIES, null, sort)
            SongSort.Year -> year(scope, down)
            SongSort.Rating -> rating(scope, down)
            SongSort.Liked -> liked(scope, down)
            SongSort.MostPlayed -> listening(scope, down, most = true)
            SongSort.RecentlyPlayed -> listening(scope, down, most = false)
            SongSort.DateLiked -> dateLiked(down)
        }
    }

    // The scope's filter. Only the Title order of a genre reads the genre
    // index; every other order keeps its own index, the genre a filter.
    private fun scopeOf(scope: SongScope, onGenreIndex: Boolean = false): Pair<List<String>, List<SqlValue>> = when (scope) {
        SongScope.All -> emptyList<String>() to emptyList()
        SongScope.Liked -> listOf(LIKED) to emptyList()
        is SongScope.Genre -> listOf(if (onGenreIndex) "t.genre = ?" else "+t.genre = ?") to listOf(SqlValue.Text(scope.name))
    }

    // A list's small check: the scope's songs read once, at most
    // QUEUE_WINDOW + 1, on the scope's own rows (every song in table order,
    // a genre's index, the likes), each row's part and key columns given by
    // `select` from its own columns. A part with few songs is never walked
    // for apart from the rest. `totals` joins each song's play totals as p.
    private fun smallRead(scope: SongScope, select: String, totals: Boolean = false): Pair<String, List<SqlValue>> {
        val (from, filter) = when (scope) {
            SongScope.All -> "track t" to ""
            SongScope.Liked -> "liked_track lk CROSS JOIN track t ON t.id = lk.trackId" to ""
            is SongScope.Genre -> "track t" to " WHERE " + scopeOf(scope, onGenreIndex = true).first.joinToString(" AND ")
        }
        val joined = if (totals) "$from LEFT JOIN $PLAY_TOTAL p ON p.trackId = t.id" else from
        return "SELECT $select FROM $joined$filter LIMIT ${QUEUE_WINDOW + 1}" to scopeOf(scope).second
    }

    private fun tables(scope: SongScope, sort: SongSort, totals: Boolean = false): Array<String> {
        val likes = scope == SongScope.Liked || sort == SongSort.Rating || sort == SongSort.Liked || sort == SongSort.DateLiked
        return listOfNotNull("track", if (likes) "liked_track" else null, if (totals) PLAY_TOTAL else null).toTypedArray()
    }

    private fun loader(
        parts: suspend () -> List<Part<KeyedTrack>>,
        tables: Array<String>,
        order: Comparator<RowKey>,
        keys: (KeyedTrack) -> RowKey,
        rail: Boolean? = null,
        smallArms: List<Pair<String, List<SqlValue>>>? = null,
    ) = PartsLoader(
        parts,
        tables,
        order,
        songsOnce = true,
        rowOf = { it.track },
        idOf = { it.id },
        railDescending = rail,
        smallRows = { sql, args -> dao.tracks(rawQuery(sql, args)) },
        smallKey = keys,
        smallArms = smallArms,
    )

    private fun plain(scope: SongScope, down: Boolean, lead: List<String>, ties: List<String>, heading: String?, sort: SongSort): PartsLoader<KeyedTrack, TrackEntity> {
        val (where, args) = scopeOf(scope, onGenreIndex = sort == SongSort.Title || sort == SongSort.FolderOrder)
        val columns = lead + ties
        val keys = keyBy(0, columns)
        val part = IndexPart(0, "track t", where, args, lead, ties, down, trackSelect(0), reads, keys, heading = heading, wholeWhere = scopeOf(scope, true).first)
        val rail = if (scope == SongScope.All && heading != null) down else null
        return loader({ listOf(part) }, tables(scope, sort), SqlOrder.rows(lead.size, down), keys, rail, smallArms = listOf(smallRead(scope, trackSelect(0))))
    }

    private fun year(scope: SongScope, down: Boolean): PartsLoader<KeyedTrack, TrackEntity> {
        val (where, args) = scopeOf(scope)
        val indexed = scopeOf(scope, true).first
        val dated = keyBy(0, listOf("t.year") + YEAR_TIES)
        val undated = keyBy(1, YEAR_TIES)
        val parts = listOf(
            IndexPart(
                0, "track t", where + "t.year IS NOT NULL", args, listOf("t.year"), YEAR_TIES, down, trackSelect(0), reads, dated,
                wholeWhere = indexed + "t.year IS NOT NULL",
            ),
            IndexPart(
                1, "track t", where + "t.year IS NULL", args, emptyList(), YEAR_TIES, false, trackSelect(1), reads, undated,
                wholeWhere = indexed + "t.year IS NULL",
            ),
        )
        val small = smallRead(scope, trackSelectBy("CASE WHEN t.year IS NULL THEN 1 ELSE 0 END"))
        return loader({ parts }, tables(scope, SongSort.Year), bySegment(down) { if (it == 0) 1 else 0 }, { if (it.seg == 1L) undated(it) else dated(it) }, smallArms = listOf(small))
    }

    private fun liked(scope: SongScope, down: Boolean): PartsLoader<KeyedTrack, TrackEntity> {
        val (where, args) = scopeOf(scope)
        // Liked songs first when the list runs high to low.
        val firstLiked = down
        val parts = (0..1).map { segment ->
            val liked = (segment == 0) == firstLiked
            IndexPart(
                segment, "track t", where + (if (liked) LIKED else "NOT $LIKED"), args, emptyList(), NAME_TIES, false, trackSelect(segment), reads, keyBy(segment, NAME_TIES),
            )
        }
        val likedSegment = if (firstLiked) 0 else 1
        val small = smallRead(scope, trackSelectBy("CASE WHEN $LIKED THEN $likedSegment ELSE ${1 - likedSegment} END"))
        return loader({ parts }, tables(scope, SongSort.Liked), SqlOrder.rows(), { keyBy(it.seg?.toInt() ?: 0, NAME_TIES)(it) }, smallArms = listOf(small))
    }

    // Rated songs by their rating the list's way, then unrated ones; within
    // each rating liked songs first, then by title and id. Each rating's
    // liked and unliked songs are a part on the rating index. The ratings
    // the library holds are found by a skip over that index at each read.
    private fun rating(scope: SongScope, down: Boolean): PartsLoader<KeyedTrack, TrackEntity> {
        val (where, args) = scopeOf(scope)
        val order = ratingOrder(down)
        fun keyOf(row: KeyedTrack): RowKey {
            val segment = if (row.track.rating == 0) 1 else 0
            return RowKey(segment, listOf(SqlValue.Int64(row.track.rating.toLong()), SqlValue.Int64(row.k0 ?: 0), SqlValue.Text(row.track.sortKey)), row.track.id)
        }
        suspend fun parts(): List<Part<KeyedTrack>> {
            val ratings = ArrayList<Int>()
            var after = 0L
            while (true) {
                val next = reads.count(
                    "SELECT IFNULL(MIN(t.rating), -1) FROM track t WHERE " + (where + "t.rating > ?").joinToString(" AND "),
                    args + SqlValue.Int64(after),
                )
                if (next < 0) break
                ratings += next
                after = next.toLong()
            }
            val rated = if (down) ratings.asReversed() else ratings
            return (rated + 0).flatMap { rating ->
                val segment = if (rating == 0) 1 else 0
                listOf(true, false).map { liked ->
                    val group = RowKey(segment, listOf(SqlValue.Int64(rating.toLong()), SqlValue.Int64(if (liked) 1 else 0)), "")
                    IndexPart(
                        segment,
                        "track t",
                        where + "t.rating = ?" + (if (liked) LIKED else "NOT $LIKED"),
                        args + SqlValue.Int64(rating.toLong()),
                        emptyList(),
                        NAME_TIES,
                        false,
                        trackSelect(segment, k0 = if (liked) "1" else "0"),
                        reads,
                        ::keyOf,
                        fixed = 2,
                        placeOf = { key -> groupOrder(down).compare(group, key) },
                    )
                }
            }
        }
        // The small check reads the scope's rows with their liked state
        // rather than finding the ratings first.
        val small = smallRead(scope, trackSelect(0, k0 = if (scope == SongScope.Liked) "1" else LIKED))
        return loader(::parts, tables(scope, SongSort.Rating), order, { row ->
            RowKey(if (row.track.rating == 0) 1 else 0, listOf(SqlValue.Int64(row.track.rating.toLong()), SqlValue.Int64(row.k0 ?: 0), SqlValue.Text(row.track.sortKey)), row.track.id)
        }, smallArms = listOf(small))
    }

    // Songs played first when the list runs high to low (last when low to
    // high), by plays and their last play, then by title the list's way;
    // the rest by title, the list's way. A song with no plays counts as
    // unplayed for both orders, as today's listening map has only played
    // songs.
    private fun listening(scope: SongScope, down: Boolean, most: Boolean): PartsLoader<KeyedTrack, TrackEntity> {
        val (where, args) = scopeOf(scope)
        val playedSeg = if (down) 0 else 1
        val unplayedSeg = 1 - playedSeg
        val played = if (most) listOf("+p.plays > 0") else listOf("+p.plays > 0", "+p.lastPlayedAt > 0")
        val lead = if (most) listOf("p.plays", "p.lastPlayedAt", "p.sortKey") else listOf("p.lastPlayedAt", "p.plays", "p.sortKey")
        val ties = listOf("p.artistSortKey", "p.trackId")
        val playedKey = keyBy(playedSeg, lead + ties)
        val unplayedKey = keyBy(unplayedSeg, listOf("t.sortKey") + TITLE_TIES)
        val isPlayed = played.joinToString(" AND ") { it.removePrefix("+") }
        val heard = "EXISTS (SELECT 1 FROM play_total p WHERE p.trackId = t.id AND $isPlayed)"
        val parts = listOf(
            IndexPart(
                playedSeg,
                "play_total p CROSS JOIN track t ON t.id = p.trackId",
                played + where,
                args,
                lead,
                ties,
                down,
                trackSelect(playedSeg, k0 = "p.plays", k1 = "p.lastPlayedAt", t0 = "p.sortKey", t1 = "p.artistSortKey"),
                reads,
                playedKey,
            ),
            IndexPart(
                unplayedSeg, "track t", where + "NOT $heard", args, listOf("t.sortKey"), TITLE_TIES, down, trackSelect(unplayedSeg), reads, unplayedKey,
            ),
        ).sortedBy { it.segment }
        val small = smallRead(
            scope,
            trackSelectBy("CASE WHEN $isPlayed THEN $playedSeg ELSE $unplayedSeg END", k0 = "p.plays", k1 = "p.lastPlayedAt", t0 = "p.sortKey", t1 = "p.artistSortKey"),
            totals = true,
        )
        return loader(
            { parts },
            tables(scope, if (most) SongSort.MostPlayed else SongSort.RecentlyPlayed, totals = true),
            bySegment(down) { if (it == playedSeg) 3 else 1 },
            { if (it.seg?.toInt() == playedSeg) playedKey(it) else unplayedKey(it) },
            smallArms = listOf(small),
        )
    }

    // The liked songs by when they were liked, the list's way, those liked
    // at one moment by title and id.
    private fun dateLiked(down: Boolean): PartsLoader<KeyedTrack, TrackEntity> {
        val select = trackSelect(0, k0 = "l.likedAt")
        val keys: (KeyedTrack) -> RowKey = { row -> RowKey(0, listOf(SqlValue.Int64(row.k0 ?: 0), SqlValue.Text(row.track.sortKey)), row.track.id) }
        val ties = Comparator<RowKey> { a, b -> SqlOrder.compare(a.values[1], b.values[1]).let { if (it != 0) it else SqlOrder.text(a.id, b.id) } }
        val inGroup = "EXISTS (SELECT 1 FROM liked_track l WHERE l.trackId = t.id AND l.likedAt = ?)"
        val part = TieGroupPart(
            0,
            "liked_track l CROSS JOIN track t ON t.id = l.trackId",
            emptyList(),
            emptyList(),
            listOf("l.likedAt"),
            listOf("l.trackId"),
            "l.trackId",
            down,
            select,
            reads,
            keys,
            ties,
            groupWalk = { group, from, forward, limit ->
                val cond = if (from == null) "" else " AND (t.sortKey, t.id) ${if (forward) ">" else "<"} (?, ?)"
                val values = if (from == null) emptyList() else listOf(from.values[1], SqlValue.Text(from.id))
                "SELECT ${trackSelect(0, k0 = "?")} FROM track t WHERE $inGroup$cond ORDER BY ${orderBy(NAME_TIES, !forward)} LIMIT $limit" to
                    (group + group + values)
            },
            groupCount = { group, before ->
                "SELECT COUNT(*) FROM track t WHERE $inGroup AND (t.sortKey, t.id) < (?, ?)" to (group + before.values[1] + SqlValue.Text(before.id))
            },
        )
        return loader({ listOf(part) }, arrayOf("track", "liked_track"), SqlOrder.rows(1, down), keys)
    }
}

// The Rating order of song keys: the segment (rated, then unrated), the
// rating the list's way, liked first, then title and id.
internal fun ratingOrder(down: Boolean): Comparator<RowKey> = Comparator { a, b ->
    groupOrder(down).compare(a, b).let { if (it != 0) return@Comparator it }
    SqlOrder.compare(a.values[2], b.values[2]).let { if (it != 0) return@Comparator it }
    SqlOrder.text(a.id, b.id)
}

// The Rating order of a rating's liked or unliked group: segment, rating,
// liked first.
private fun groupOrder(down: Boolean): Comparator<RowKey> = Comparator { a, b ->
    a.segment.compareTo(b.segment).let { if (it != 0) return@Comparator it }
    if (a.segment == 0) SqlOrder.compare(a.values[0], b.values[0]).let { if (it != 0) return@Comparator if (down) -it else it }
    SqlOrder.compare(b.values[1], a.values[1])
}
