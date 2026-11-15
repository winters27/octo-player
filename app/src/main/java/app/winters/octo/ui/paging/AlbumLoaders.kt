package app.winters.octo.ui.paging

import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import app.winters.octo.catalog.AlbumEntryView
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.ListsAlbumEntryEntity
import app.winters.octo.catalog.ListsDao
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.deleteOwned
import app.winters.octo.catalog.findId
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.rawQuery
import app.winters.octo.data.Drains
import app.winters.octo.discovery.Resolved
import app.winters.octo.discovery.asAdopted
import app.winters.octo.discovery.resolveSongs
import app.winters.octo.discovery.titleKeys
import app.winters.octo.server.serverSourceId
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient

// An album's songs above this many are not held in memory: its page and
// every route that reads it walk it in pages.
const val WHOLE_ALBUM_MAX = 2_000

// One page of an album walk.
private const val ALBUM_PAGE = 500

// The disk one row of an album's copy (lists_album_entry) takes in the file
// with its keys and indexes, under an owner led by the launch's mark, with a
// server's long ids (ListsDiskConstantsTest: 588). A copy asks for twice
// this a row, the write-ahead log's share the second half.
const val ALBUM_ENTRY_ROW_BYTES = 615L

// An album's library songs in its own order: its place in the album, then
// the song's id, on the album's songs index.
fun albumLibraryLoader(dao: ListsDao, albumId: String): PartsLoader<app.winters.octo.catalog.KeyedTrack, TrackEntity> {
    val keys: (app.winters.octo.catalog.KeyedTrack) -> RowKey = { row -> RowKey(0, listOf(SqlValue.Int64(row.track.albumOrder.toLong())), row.track.id) }
    val part = IndexPart(0, "track t", listOf("t.albumId = ?"), listOf(SqlValue.Text(albumId)), emptyList(), listOf("t.albumOrder", "t.id"), false, trackSelect(0), TrackReads(dao), keys)
    return PartsLoader(
        { listOf(part) },
        arrayOf("track"),
        SqlOrder.rows(),
        songsOnce = true,
        rowOf = { it.track },
        idOf = { it.id },
        smallRows = { sql, args -> dao.tracks(rawQuery(sql, args)) },
        smallKey = keys,
    )
}

// An album's server listing in a copy under an owner, as the page shows
// it: each listed song once, the first time it comes (segment 0, by its
// place in the listing), then the album's library songs the listing left
// out (segment 1, by their place in the album). A row's key is the same on
// the page and in a queue's copy whenever the library and the listing are.
// With `keepGone` (a queue's fill), a row whose song left the library since
// the copy was made stays, with its copied id, for the fill to follow.
fun albumCopyLoader(dao: ListsDao, owner: String, keepGone: Boolean = false): PartsLoader<AlbumEntryView, TrackEntity> {
    val reads = object : PartReads<AlbumEntryView> {
        override suspend fun rows(sql: String, args: List<SqlValue>) = dao.albumRows(rawQuery(sql, args))
        override suspend fun count(sql: String, args: List<SqlValue>) = dao.count(rawQuery(sql, args))
        override suspend fun ids(sql: String, args: List<SqlValue>) = dao.ids(rawQuery(sql, args))
    }
    val from = "lists_album_entry e LEFT JOIN track t ON t.id = e.shownId LEFT JOIN online_song o ON o.id = e.shownId"
    val select = "e.position AS position, e.shownId AS shownId, e.isFind AS isFind, e.albumOrder AS entryOrder, " +
        songColumns(trackNo = "e.trackNo", discNo = "e.discNo", goneId = if (keepGone) "e.shownId" else null)
    val exists = if (keepGone) "1" else "(t.id IS NOT NULL OR o.id IS NOT NULL)"
    val owned = SqlValue.Text(owner)
    val listed = IndexPart(
        0, from,
        listOf("e.owner = ?", "e.albumOrder IS NULL", "e.position = (SELECT MIN(f.position) FROM lists_album_entry f WHERE f.owner = e.owner AND f.shownId = e.shownId)", exists),
        listOf(owned), emptyList(), listOf("e.position"), false, select, reads,
        { row -> RowKey(0, listOf(SqlValue.Int64(row.position)), row.shownId) },
        idColumn = "e.shownId",
    )
    val library = IndexPart(
        1, from, listOf("e.owner = ?", "e.albumOrder IS NOT NULL", exists), listOf(owned),
        emptyList(), listOf("e.albumOrder", "e.shownId"), false, select, reads,
        { row -> RowKey(1, listOf(SqlValue.Int64(row.entryOrder ?: 0)), row.shownId) },
    )
    return PartsLoader(
        { listOf(listed, library) },
        arrayOf("lists_album_entry", "track", "online_song"),
        SqlOrder.rows(),
        songsOnce = true,
        rowOf = { it.withNumbers() },
        idOf = { it.id },
        // The small check: both parts' rows in one statement, each keyed by
        // the part it is in (a listing row has no album order).
        smallRows = { sql, args -> dao.albumRows(rawQuery(sql, args)) },
        smallKey = { row ->
            val order = row.entryOrder
            if (order == null) RowKey(0, listOf(SqlValue.Int64(row.position)), row.shownId) else RowKey(1, listOf(SqlValue.Int64(order)), row.shownId)
        },
    )
}

private fun AlbumEntryView.withNumbers(): TrackEntity = track

// The album on the signed-in server that most of a library album's songs
// came from: its songs are walked a page at a time and each page's server
// copies counted by their source album, the counts kept per album (album
// sized). Ties go to the smallest id. `map` turns a copy's album into the
// id voted for, or drops it. A page's copies are found by its songs: `+`
// keeps the planner off the indexes that start with the source, which would
// walk every copy the server has for each page.
suspend fun serverAlbumVote(db: CatalogDatabase, albumId: String, sourceId: String, map: (String) -> String? = { it }): String? {
    val dao = db.listsDao()
    val votes = HashMap<String, Int>()
    var afterOrder = Long.MIN_VALUE
    var afterId = ""
    while (true) {
        val page = dao.tracks(
            rawQuery(
                "SELECT ${trackSelect(0)} FROM track t WHERE t.albumId = ? AND (t.albumOrder, t.id) > (?, ?) ORDER BY t.albumOrder, t.id LIMIT $ALBUM_PAGE",
                listOf(SqlValue.Text(albumId), SqlValue.Int64(afterOrder), SqlValue.Text(afterId)),
            ),
        )
        if (page.isEmpty()) break
        val ids = page.map { it.track.id }
        val rows = dao.albumVotes(
            rawQuery(
                "SELECT s.albumId AS albumId, COUNT(*) AS n FROM source_track s WHERE s.mergedId IN ${marks(ids.size)} AND +s.sourceId = ? GROUP BY s.albumId",
                ids.map { SqlValue.Text(it) } + SqlValue.Text(sourceId),
            ),
        )
        for (row in rows) map(row.albumId)?.let { votes[it] = (votes[it] ?: 0) + row.n }
        if (page.size < ALBUM_PAGE) break
        afterOrder = page.last().track.albumOrder.toLong()
        afterId = page.last().track.id
    }
    return votes.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenComparator { a, b -> SqlOrder.text(a.key, b.key) }).firstOrNull()?.key
}

// Streams a library album's listing on an Octo server into a copy under
// `owner`, resolving each page as today's album page does (a song the
// library has from the server is that song, one with the same title and
// artist is that song, the rest are finds, numbered as the album numbers
// them, and those the server does not mark are its own, handed to `owned`;
// a find already downloaded shows as the song it became), then adds the album's
// library songs the listing left out. The answer is drained to a file
// first and its finds counted over the file; a listing with none writes
// nothing, since the album then shows and plays as the library has it,
// unless `keepLibrary` asks for the library's songs under the owner all the
// same (a queue's fill walks only a copy of its own). The library part is
// written under the three-attempt rule. Answers whether the listing holds a
// find; false when no Octo server is signed in or the album did not come
// from it.
class AlbumListing(
    private val db: CatalogDatabase,
    private val merge: CatalogMerge,
    private val drains: Drains,
    private val freeSpace: FreeSpace,
    private val adoptions: () -> Map<String, String>,
    // Takes the server's own songs the listing named that the copy lacks, on
    // the server of that source id.
    private val owned: (String, List<Song>) -> Unit = { _, _ -> },
) {
    private val dao = db.listsDao()
    private val sources = db.sourceDao()
    private val online = db.onlineDao()
    private val catalog = db.dao()

    suspend fun build(
        owner: String,
        albumId: String,
        client: SubsonicClient?,
        runsOcto: Boolean,
        now: Long = System.currentTimeMillis(),
        keepLibrary: Boolean = true,
    ): Boolean {
        db.deleteOwned("lists_album_entry", owner)
        var finds = false
        var position = 0L
        if (client != null && runsOcto) {
            val sourceId = serverSourceId(client.primaryUrl)
            val row = serverAlbumVote(db, albumId, sourceId)
            val native = row?.let { sources.albumNativeId(it) }
            if (native != null) {
                drains.use("album-${owner.replace(':', '-').replace('/', '-')}.json") { file ->
                    client.drainAlbum(native, file, room = { written -> room(written * 2) })
                    // A page holds a find when it holds a song the server
                    // marks as outside the library, or one of the server's
                    // own the copy lacks.
                    var counted = false
                    client.readAlbum(file) { songs ->
                        if (!counted) counted = resolve(songs, sourceId, albumId, now).any { (_, shown) -> shown.second }
                    }
                    if (counted) {
                        client.readAlbum(file) { songs ->
                            val entries = resolve(songs, sourceId, albumId, now) { mine -> owned(sourceId, mine) }.map { (song, shown) ->
                                ListsAlbumEntryEntity(owner, position++, song.id, shown.first, shown.second, null, shown.third?.first, shown.third?.second)
                            }
                            finds = finds || entries.any { it.isFind }
                            write { dao.insertAlbumEntries(entries) }
                        }
                    }
                }
            }
        }
        // Finds taken in between the count and the writing leave none.
        if (!finds && position > 0) db.deleteOwned("lists_album_entry", owner).also { position = 0 }
        if (!finds && !keepLibrary) return false
        libraryPart(owner, albumId, position)
        return finds
    }

    // Each song of a page as the album page shows it: its shown id, whether
    // that is a find, and a find's track and disc number; songs dropped as
    // outsideAlbumSongs drops them are left out. The server's own songs the
    // copy lacks among them are handed to `onOwned`.
    private suspend fun resolve(
        songs: List<Song>,
        sourceId: String,
        albumId: String,
        now: Long,
        onOwned: ((List<Song>) -> Unit)? = null,
    ): List<Pair<Song, Triple<String, Boolean, Pair<Int?, Int?>?>>> {
        if (songs.isEmpty()) return emptyList()
        val links = songs.map { it.id }.distinct().chunked(900).flatMap { sources.libraryLinks(sourceId, it) }.associate { it.serverId to it.trackId }
        val unlinked = songs.filter { it.id !in links }
        val candidates = unlinked.flatMap { titleKeys(it.title) }.distinct().sorted().chunked(900).flatMap { catalog.tracksWithKeys(it) }
        val resolved = resolveSongs(songs, sourceId, links, candidates, now)
        val kept = online.keep(resolved.filterIsInstance<Resolved.Found>().map { it.song }.distinctBy { it.id }).associateBy { it.id }
        val library = resolved.filterIsInstance<Resolved.InLibrary>().map { it.trackId } +
            kept.values.mapNotNull { it.adoptedId.ifEmpty { null } }
        val present = library.distinct().chunked(900).flatMap { catalog.tracksByIdsUnordered(it) }.associateBy { it.id }
        // A find downloaded since shows as the song it became, when that song
        // is one of this album's.
        val adopted = adoptions().filterKeys { id -> songs.any { findId(it.id) == id } }
        val targets = adopted.values.distinct().chunked(900).flatMap { catalog.tracksByIdsUnordered(it) }
            .filter { it.albumId == albumId }.mapTo(HashSet()) { it.id }
        val mine = ArrayList<Song>()
        val rows = songs.zip(resolved).mapNotNull { (song, r) ->
            when (val each = asAdopted(if (r is Resolved.Found) kept[r.song.id]?.let { r.copy(song = it) } ?: r else r, present.keys)) {
                // A song the library no longer has is left out, as today.
                is Resolved.InLibrary -> if (each.trackId in present) song to Triple(each.trackId, false, null) else null
                is Resolved.Found -> {
                    // A song the server does not mark is the server's own
                    // (Octo marks every song of a library album's listing it
                    // lacks), so it is in its place as a library song that
                    // streams (OwnedFinds) until the copy brings it in.
                    if (!song.isExternal) mine += song
                    val shown = adopted[each.song.id]?.takeIf { it in targets } ?: each.song.id
                    song to Triple(shown, isFind(shown), (song.track?.takeIf { it > 0 } to song.discNumber?.takeIf { it > 0 }))
                }
            }
        }
        if (mine.isNotEmpty()) onOwned?.invoke(mine)
        return rows
    }

    // The album's library songs the listing does not show, after it, by
    // their place in the album, a page at a time.
    private suspend fun libraryPart(owner: String, albumId: String, from: Long) {
        repeat(3) { attempt ->
            val done = if (attempt < 2) writeLibrary(owner, albumId, from) else merge.betweenRebuilds(HoldKind.Walk) { writeLibrary(owner, albumId, from) }
            if (done) return
            while (dao.deleteAlbumLibraryRows(owner) == 500) Unit
        }
    }

    private suspend fun writeLibrary(owner: String, albumId: String, from: Long): Boolean {
        val baseline = db.withTransaction { db.workDao().swapCount() }
        var position = from
        var afterOrder = Long.MIN_VALUE
        var afterId = ""
        var pages = 0
        while (true) {
            val page = dao.tracks(
                rawQuery(
                    "SELECT ${trackSelect(0)} FROM track t WHERE t.albumId = ? AND (t.albumOrder, t.id) > (?, ?) " +
                        "AND NOT EXISTS (SELECT 1 FROM lists_album_entry e WHERE e.owner = ? AND e.shownId = t.id AND e.albumOrder IS NULL) " +
                        "ORDER BY t.albumOrder, t.id LIMIT $ALBUM_PAGE",
                    listOf(SqlValue.Text(albumId), SqlValue.Int64(afterOrder), SqlValue.Text(afterId), SqlValue.Text(owner)),
                ),
            )
            if (page.isEmpty()) return true
            val rows = page.map { ListsAlbumEntryEntity(owner, position++, it.track.id, it.track.id, false, it.track.albumOrder.toLong(), null, null) }
            val kept = write {
                db.withTransaction {
                    if (db.workDao().swapCount() != baseline) return@withTransaction false
                    dao.insertAlbumEntries(rows)
                    true
                }
            }
            if (!kept) return false
            if (++pages % 10 == 0) room(position * ALBUM_ENTRY_ROW_BYTES * 2)
            if (page.size < ALBUM_PAGE) return true
            afterOrder = page.last().track.albumOrder.toLong()
            afterId = page.last().track.id
        }
    }

    private fun room(bytes: Long) {
        if (freeSpace.bytes() < bytes) throw NotEnoughSpace(bytes, SpaceFor.List)
    }

    private suspend fun <T> write(block: suspend () -> T): T = try {
        block()
    } catch (e: SQLiteFullException) {
        throw NotEnoughSpace(null, SpaceFor.List)
    }
}
