package app.winters.octo.catalog

import android.database.Cursor
import androidx.room.RoomRawQuery
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import app.winters.octo.device.DEVICE
import java.io.Closeable

// Two copies of a song count as the same one if their lengths differ by
// no more than this.
private const val SAME_LENGTH_MS = 3_000L

// How many rows the merge reads or places at a time.
internal const val MERGE_PAGE = 500

// Raised whenever the rules below change what a merge makes, so the next
// merge runs even when no source changed.
const val MERGE_RULES_VERSION = 1

// One source as a merge reads it: its place in the order, and whether its
// rows are a copy waiting to be swapped in (stage_*) or its live ones.
internal class MergeSource(val sourceId: String, val rank: Int, val staged: Boolean) {
    val onPhone get() = sourceId == DEVICE
    val trackTable get() = if (staged) "stage_track" else "source_track"
    val albumTable get() = if (staged) "stage_album" else "source_album"
    val artistTable get() = if (staged) "stage_artist" else "source_artist"
}

// Hears how far a merge is: each page of a source's songs placed, then
// once every song is.
internal interface MergeProgress {
    fun page(source: MergeSource, rows: Int) {}

    fun placed() {}

    companion object {
        val None: MergeProgress = object : MergeProgress {}
    }
}

// Merges the sources into one library, so something on the phone and on a
// server shows once. The library comes out in merge_candidate (songs),
// album_next and artist_next, and which library song each copy became in
// merged_id_next; the swap (CatalogMerge) puts them in place.
//
// - Artists match by name, albums by album artist and title, and songs by
//   title and length within an album. Track numbers are not needed, since
//   phone files often lack them. A song's title is read by SongIdentity, so
//   "Song - Remastered 2011" is "Song", but "Song (Live)" stays apart.
// - The first source to have something gives it its id, so give the phone
//   first: its songs keep their ids, and likes and playlists keep working.
// - A merged song or album takes the best of its copies. Copies are asked
//   in source order, the phone's first, and for each field:
//   - added: the earliest real time any copy was added. A server stamps
//     every song with the day it first scanned it, while the phone's time
//     comes from the file, so the earliest is the nearest to when the song
//     was really got. Unknown times (0) are passed over. Albums alike.
//   - year: the first copy's original release year; failing that, the
//     first copy's own year. So a remaster shows the year the song came out.
//   - genre: the first copy's main genre; the song info sheet shows every
//     copy's genres together (songDetails).
//   - artist: the fullest credit, the copy naming the most artists (say,
//     "A, B" over "A"); on equal credits the first copy's stays.
//   - track and disc numbers and the server rating: the first copy's.
//   - explicit or clean: the first copy that says, so a server's mark
//     reaches a phone file whose tags say nothing.
//   - composer, tempo, MusicBrainz ids and the other details the library
//     does not keep: read from the copies when needed, by the same order
//     (songDetails).
// - Anything only one source has is passed through exactly as it was,
//   apart from its year, which is its original year when it has one.
//
// Songs are read a page at a time and placed against the songs placed so
// far, which sit in merge_candidate, so memory grows with albums and
// artists only.
internal class CatalogMerger(
    private val db: CatalogDatabase,
    private val work: WorkDao,
    private val hooks: () -> MergeHooks,
) {
    suspend fun merge(sources: List<MergeSource>, progress: MergeProgress = MergeProgress.None): MergeCounts {
        work.clearMergeWork()

        // Artists and albums match only across sources: two a source keeps
        // apart (say, two albums of the same name) stay apart.
        val artistBases = acrossSources(
            sources,
            { source, after -> work.artistPage(seqPageQuery(source.artistTable, source.sourceId, after)) },
            { it.seq }, { it.id }, { matchKey(it.name) },
        )
        val artistIdFor = artistBases.idFor
        val albumBases = acrossSources(
            sources,
            { source, after -> work.albumPage(seqPageQuery(source.albumTable, source.sourceId, after)) },
            { it.seq }, { it.id }, { matchKey(it.artist) + "|" + matchKey(it.title) },
        )
        val albumIdFor = albumBases.idFor

        val placed = Placed()
        var firstWritten = false
        for (source in sources) {
            // From the third source on, every song is open to it again: none
            // has a copy from it yet. Matches below mark theirs taken.
            if (source.rank > 1) reopenSongs()
            // The first page starts below every row: any id sorts after "".
            var after = PageKey(Int.MIN_VALUE, Long.MIN_VALUE, "")
            while (true) {
                val page = work.trackPage(trackPageQuery(source.trackTable, source.sourceId, after))
                if (page.isEmpty()) break
                // A song that comes in new files under its title's lookup
                // keys, read before the page's transaction takes the lock.
                val lookups = page.map { titleLookup(it.title, it.artist) }
                db.withTransaction {
                    PageWriter(db.openHelper.writableDatabase).use { writer ->
                        page.forEachIndexed { i, track -> place(track, lookups[i], source, writer, placed, albumIdFor, artistIdFor) }
                    }
                }
                if (!firstWritten) {
                    firstWritten = true
                    hooks().firstOutputWritten()
                }
                progress.page(source, page.size)
                val last = page.last()
                after = PageKey(last.albumOrder, last.seq, last.id)
            }
        }
        progress.placed()

        orderMixedAlbums(placed)
        val albumCount = writeAlbums(albumBases, artistIdFor, placed)
        val artistCount = writeArtists(artistBases, sources.firstOrNull()?.sourceId, placed)
        return MergeCounts(placed.total, albumCount, artistCount)
    }

    // What the songs placed so far add up to, by album and artist.
    private class Placed {
        var total = 0
        var nextSeq = 0L
        // The source rank that made each album's first song.
        val albumMadeBy = HashMap<String, Int>()
        // Albums filled from more than one source.
        val mixed = HashSet<String>()
        val albumSongs = HashMap<String, Int>()
        val albumMs = HashMap<String, Long>()
        val artistSongs = HashMap<String, Int>()
        // Library albums per artist, filled while albums are written.
        val albumsByArtist = HashMap<String, Int>()
    }

    private fun place(
        track: SourceTrackEntity,
        lookup: TitleLookup,
        source: MergeSource,
        writer: PageWriter,
        placed: Placed,
        albumIdFor: Map<String, String>,
        artistIdFor: Map<String, String>,
    ) {
        val keys = if (track.keysVersion == SONG_KEYS_VERSION) track else track.withKeys()
        val albumId = albumIdFor[track.albumId] ?: track.albumId
        // First within the matched album. Failing that, the same song by the
        // same artist anywhere: sources often file an album under a
        // different album artist or edition name. The first source has
        // nothing before it to match.
        val match = if (source.rank == 0) {
            null
        } else {
            writer.fit(FIT_IN_ALBUM_SQL, albumId, keys.titleKey, track.durationMs)
                ?: writer.fit(FIT_ANYWHERE_SQL, keys.matchKey, keys.titleKey, track.durationMs)
        }
        if (match != null) {
            writer.add(match, track, source)
            writer.link(track.id, match.id)
            placed.mixed += match.albumId
            return
        }
        val artistId = artistIdFor[track.artistId] ?: track.artistId
        writer.create(track, keys, lookup, source, albumId, artistId, placed.nextSeq++)
        writer.link(track.id, track.id)
        val firstRank = placed.albumMadeBy.getOrPut(albumId) { source.rank }
        if (firstRank != source.rank) placed.mixed += albumId
        placed.albumSongs[albumId] = (placed.albumSongs[albumId] ?: 0) + 1
        placed.albumMs[albumId] = (placed.albumMs[albumId] ?: 0L) + track.durationMs
        placed.artistSongs[artistId] = (placed.artistSongs[artistId] ?: 0) + 1
        placed.total++
    }

    // Album order: an album filled from more than one source is put in
    // track number order when every song has one; otherwise its songs keep
    // the order they came in. Single-source albums keep their own order,
    // which each song brought with it.
    private suspend fun orderMixedAlbums(placed: Placed) {
        val sql = db.openHelper.writableDatabase
        for (albumId in placed.mixed) {
            val numbered = !exists(sql, "SELECT EXISTS(SELECT 1 FROM merge_candidate WHERE albumId = ?1 AND trackNo IS NULL)", albumId)
            var order = 0
            var disc = Int.MIN_VALUE.toLong()
            var number = Int.MIN_VALUE.toLong()
            var seq = Long.MIN_VALUE
            while (true) {
                val ids = ArrayList<String>(MERGE_PAGE)
                if (numbered) {
                    sql.query(BY_TRACK_NUMBER_SQL, arrayOf<Any?>(albumId, disc, number, seq)).use { c ->
                        while (c.moveToNext()) {
                            ids += c.getString(0)
                            disc = c.getLong(1)
                            number = c.getLong(2)
                            seq = c.getLong(3)
                        }
                    }
                } else {
                    sql.query(BY_ARRIVAL_SQL, arrayOf<Any?>(albumId, seq)).use { c ->
                        while (c.moveToNext()) {
                            ids += c.getString(0)
                            seq = c.getLong(1)
                        }
                    }
                }
                if (ids.isEmpty()) break
                db.withTransaction {
                    db.openHelper.writableDatabase.compileStatement("UPDATE merge_candidate SET albumOrder = ?1 WHERE id = ?2").use { update ->
                        for (id in ids) {
                            update.bindAll(order++, id)
                            update.executeUpdateDelete()
                        }
                    }
                }
            }
        }
    }

    // An album whose songs all turned out to be copies of songs already in
    // another album has nothing left, and is left out.
    private suspend fun writeAlbums(bases: Matched<SourceAlbumEntity>, artistIdFor: Map<String, String>, placed: Placed): Int {
        var count = 0
        val batch = ArrayList<AlbumNextEntity>(MERGE_PAGE)
        for (base in bases.bases) {
            val songs = placed.albumSongs[base.id] ?: continue
            val extras = bases.extras[base.id].orEmpty()
            // Counts are only worked out again for albums whose songs changed:
            // made from several sources, or with songs gone to another album.
            val recount = extras.isNotEmpty() || songs != base.songCount
            val album = AlbumEntity(
                id = base.id,
                sourceId = base.sourceId,
                nativeId = base.nativeId,
                title = base.title,
                searchKey = base.searchKey,
                sortKey = base.sortKey,
                artist = base.artist,
                artistId = artistIdFor[base.artistId] ?: base.artistId,
                year = base.year ?: extras.firstNotNullOfOrNull { it.year },
                songCount = if (recount) songs else base.songCount,
                durationMs = if (recount) placed.albumMs[base.id] ?: 0L else base.durationMs,
                addedAt = earliestAdded(listOf(base.addedAt) + extras.map { it.addedAt }),
                artwork = base.artwork ?: extras.firstNotNullOfOrNull { it.artwork },
                releaseTypes = base.releaseTypes.ifEmpty { extras.firstOrNull { it.releaseTypes.isNotEmpty() }?.releaseTypes.orEmpty() },
            )
            placed.albumsByArtist[album.artistId] = (placed.albumsByArtist[album.artistId] ?: 0) + 1
            batch += AlbumNextEntity(album)
            count++
            if (batch.size == MERGE_PAGE) {
                work.insertAlbumNext(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) work.insertAlbumNext(batch)
        return count
    }

    // Artists from a later source with nothing left of their own are left
    // out too; the first source's artists always stay as they were.
    private suspend fun writeArtists(bases: Matched<SourceArtistEntity>, firstSource: String?, placed: Placed): Int {
        var count = 0
        val batch = ArrayList<ArtistNextEntity>(MERGE_PAGE)
        for (base in bases.bases) {
            val several = bases.extras[base.id].orEmpty().isNotEmpty()
            val later = base.sourceId != firstSource
            val albumCount = placed.albumsByArtist[base.id] ?: 0
            val songCount = placed.artistSongs[base.id] ?: 0
            if (later && albumCount == 0 && songCount == 0) continue
            batch += ArtistNextEntity(
                ArtistEntity(
                    id = base.id,
                    sourceId = base.sourceId,
                    name = base.name,
                    searchKey = base.searchKey,
                    sortKey = base.sortKey,
                    albumCount = if (several || later) albumCount else base.albumCount,
                    songCount = if (several || later) songCount else base.songCount,
                    artwork = base.artwork,
                ),
            )
            count++
            if (batch.size == MERGE_PAGE) {
                work.insertArtistNext(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) work.insertArtistNext(batch)
        return count
    }

    // Every song placed so far is open to the next source again, a page of
    // them a transaction in the order they were placed, so no write holds
    // the lock for every song and none runs outside a transaction.
    private suspend fun reopenSongs() {
        var after = Long.MIN_VALUE
        while (true) {
            val end = db.withTransaction {
                val sql = db.openHelper.writableDatabase
                val last = sql.query(REOPEN_PAGE_END_SQL, arrayOf<Any?>(after)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                if (last == null) {
                    sql.execSQL(REOPEN_REST_SQL, arrayOf<Any?>(after))
                } else {
                    sql.execSQL(REOPEN_PAGE_SQL, arrayOf<Any?>(after, last))
                }
                last
            } ?: return
            after = end
        }
    }

    private fun exists(sql: SupportSQLiteDatabase, query: String, vararg args: Any?): Boolean =
        sql.query(query, args).use { it.moveToFirst() && it.getLong(0) != 0L }
}

// Where a page of placed songs ends, by the order they were placed: the
// last of the page's MERGE_PAGE songs, none when fewer are left. Then the
// page, or every song left, opened to the next source.
internal const val REOPEN_PAGE_END_SQL =
    "SELECT seq FROM merge_candidate WHERE seq > ?1 ORDER BY seq LIMIT 1 OFFSET ${MERGE_PAGE - 1}"
internal const val REOPEN_PAGE_SQL =
    "UPDATE merge_candidate SET lastSourceRank = 0 WHERE seq > ?1 AND seq <= ?2 AND lastSourceRank != 0"
internal const val REOPEN_REST_SQL = "UPDATE merge_candidate SET lastSourceRank = 0 WHERE seq > ?1 AND lastSourceRank != 0"

// Where a page of a source's songs ends: its last row's place in album,
// source order and id.
internal class PageKey(val order: Int, val seq: Long, val id: String)

// Things matched across sources: the first copy of each (the base, whose
// id the library uses), the other copies of it, and which base every copy
// belongs to.
internal class Matched<T>(val bases: List<T>, val extras: Map<String, List<T>>, val idFor: Map<String, String>)

private suspend fun <T> acrossSources(
    sources: List<MergeSource>,
    page: suspend (MergeSource, Long) -> List<T>,
    seq: (T) -> Long,
    id: (T) -> String,
    key: (T) -> String,
): Matched<T> {
    val bases = ArrayList<T>()
    val extras = HashMap<String, MutableList<T>>()
    val idFor = HashMap<String, String>()
    // Bases from earlier sources, by key, that no copy from the current
    // source has claimed yet.
    val earlier = HashMap<String, ArrayDeque<T>>()
    for (source in sources) {
        val added = HashMap<String, ArrayDeque<T>>()
        var after = Long.MIN_VALUE
        while (true) {
            val rows = page(source, after)
            if (rows.isEmpty()) break
            for (item in rows) {
                val k = key(item)
                val base = earlier[k]?.removeFirstOrNull()
                if (base != null) {
                    idFor[id(item)] = id(base)
                    extras.getOrPut(id(base)) { mutableListOf() } += item
                } else {
                    bases += item
                    idFor[id(item)] = id(item)
                    added.getOrPut(k) { ArrayDeque() } += item
                }
            }
            after = seq(rows.last())
        }
        for ((k, list) in added) earlier.getOrPut(k) { ArrayDeque() } += list
    }
    return Matched(bases, extras, idFor)
}

// A library song a copy matched: its id, the album it is in, its title and
// how many artists its credit names.
private class Fit(val id: String, val albumId: String, val title: String, val credits: Int)

// The statements one page of placing songs runs, compiled once a page.
private class PageWriter(private val sql: SupportSQLiteDatabase) : Closeable {
    private val insert = sql.compileStatement(INSERT_CANDIDATE_SQL)
    private val addCopy = sql.compileStatement(ADD_COPY_SQL)
    private val linkCopy = sql.compileStatement(LINK_SQL)

    fun fit(query: String, key: String, titleKey: String, durationMs: Long): Fit? =
        sql.query(query, arrayOf<Any?>(key, titleKey, durationMs - SAME_LENGTH_MS, durationMs + SAME_LENGTH_MS)).use { c: Cursor ->
            if (c.moveToFirst()) Fit(c.getString(0), c.getString(1), c.getString(2), c.getInt(3)) else null
        }

    // A copy joins a library song: today's rules for each field. A copy
    // whose credit names more artists gives the song its artist, and with
    // it the title's lookup keys read with that artist; the statement
    // takes them on the same rule, reading the row it changes.
    fun add(match: Fit, copy: SourceTrackEntity, source: MergeSource) {
        val credits = splitLines(copy.artists).size
        val keys = if (credits > match.credits && copy.artist.isNotBlank()) titleLookup(match.title, copy.artist) else null
        addCopy.bindAll(
            copy.trackNo, copy.discNo, copy.year, copy.originalYear, copy.genre, copy.rating, copy.explicit,
            copy.addedAt, credits, copy.artist.isNotBlank(), copy.artist, source.onPhone,
            source.rank, match.id, keys?.lookupKey, keys?.looseKey,
        )
        addCopy.executeUpdateDelete()
    }

    // A copy no song matched becomes a library song of its own, filed under
    // its title's lookup keys (`lookup`, read with its artist).
    fun create(
        base: SourceTrackEntity,
        keys: SourceTrackEntity,
        lookup: TitleLookup,
        source: MergeSource,
        albumId: String,
        artistId: String,
        seq: Long,
    ) {
        insert.bindAll(
            base.id, base.sourceId, base.nativeId, base.title, base.searchKey, base.sortKey, base.artist, artistId,
            base.album, albumId, base.trackNo, base.discNo, base.year, base.durationMs, base.addedAt, base.mimeType,
            base.sizeBytes, base.artwork, base.uri, base.albumOrder, base.relinkKey, base.genre, source.onPhone,
            base.rating?.takeIf { it in 1..5 } ?: 0, base.explicit, "", "", lookup.lookupKey, lookup.looseKey,
            seq, keys.titleKey, keys.matchKey, base.originalYear, splitLines(base.artists).size, base.discNo ?: 1,
            source.rank, source.rank, false,
        )
        insert.executeInsert()
    }

    fun link(sourceRowId: String, mergedId: String) {
        linkCopy.bindAll(sourceRowId, mergedId)
        linkCopy.executeInsert()
    }

    override fun close() {
        insert.close()
        addCopy.close()
        linkCopy.close()
    }
}

internal fun SupportSQLiteStatement.bindAll(vararg args: Any?) {
    clearBindings()
    args.forEachIndexed { index, value ->
        val at = index + 1
        when (value) {
            null -> bindNull(at)
            is String -> bindString(at, value)
            is Boolean -> bindLong(at, if (value) 1 else 0)
            is Int -> bindLong(at, value.toLong())
            is Long -> bindLong(at, value)
            is Float -> bindDouble(at, value.toDouble())
            is Double -> bindDouble(at, value)
            else -> error("cannot bind ${value::class.simpleName}")
        }
    }
}

// A page of a source's songs in the order the merge reads them: by place
// in album, then source order, then id.
internal fun trackPageSql(table: String) =
    "SELECT * FROM `$table` WHERE sourceId = ?1 AND (albumOrder, seq, id) > (?2, ?3, ?4) " +
        "ORDER BY albumOrder, seq, id LIMIT $MERGE_PAGE"

internal fun seqPageSql(table: String) =
    "SELECT * FROM `$table` WHERE sourceId = ?1 AND seq > ?2 ORDER BY seq LIMIT $MERGE_PAGE"

private fun trackPageQuery(table: String, sourceId: String, after: PageKey): RoomRawQuery =
    RoomRawQuery(trackPageSql(table)) { statement ->
        statement.bindText(1, sourceId)
        statement.bindLong(2, after.order.toLong())
        statement.bindLong(3, after.seq)
        statement.bindText(4, after.id)
    }

private fun seqPageQuery(table: String, sourceId: String, after: Long): RoomRawQuery =
    RoomRawQuery(seqPageSql(table)) { statement ->
        statement.bindText(1, sourceId)
        statement.bindLong(2, after)
    }

// The library song already placed that a copy belongs to: not yet given a
// copy by this source (lastSourceRank 0), with the same title, within the
// length allowed, the first placed. In the copy's album first, then
// anywhere by the same artist.
internal const val FIT_IN_ALBUM_SQL =
    "SELECT id, albumId, title, credits FROM merge_candidate WHERE albumId = ?1 AND titleKey = ?2 AND lastSourceRank = 0 " +
        "AND durationMs BETWEEN ?3 AND ?4 ORDER BY seq LIMIT 1"

internal const val FIT_ANYWHERE_SQL =
    "SELECT id, albumId, title, credits FROM merge_candidate WHERE matchKey = ?1 AND titleKey = ?2 AND lastSourceRank = 0 " +
        "AND durationMs BETWEEN ?3 AND ?4 ORDER BY seq LIMIT 1"

// Every value on the right reads the row as it was, so discKey follows the
// disc number the row ends with, and the lookup keys (?15, ?16) are taken
// exactly when the artist is. Both are null when the copy gives the song
// no artist, so a row that took an artist without its keys fails.
internal const val ADD_COPY_SQL = """
    UPDATE merge_candidate SET
        trackNo = COALESCE(trackNo, ?1),
        discNo = COALESCE(discNo, ?2),
        discKey = COALESCE(discNo, ?2, 1),
        year = COALESCE(year, ?3),
        originalYear = COALESCE(originalYear, ?4),
        genre = CASE WHEN genre = '' THEN ?5 ELSE genre END,
        rating = CASE WHEN rating BETWEEN 1 AND 5 THEN rating WHEN ?6 BETWEEN 1 AND 5 THEN ?6 ELSE 0 END,
        explicit = COALESCE(explicit, ?7),
        addedAt = CASE WHEN addedAt > 0 AND ?8 > 0 THEN MIN(addedAt, ?8) WHEN addedAt > 0 THEN addedAt
            WHEN ?8 > 0 THEN ?8 ELSE 0 END,
        artist = CASE WHEN ?9 > credits AND ?10 THEN ?11 ELSE artist END,
        lookupKey = CASE WHEN ?9 > credits AND ?10 THEN ?15 ELSE lookupKey END,
        looseKey = CASE WHEN ?9 > credits AND ?10 THEN ?16 ELSE looseKey END,
        credits = CASE WHEN ?9 > credits AND ?10 THEN ?9 ELSE credits END,
        onPhone = onPhone OR ?12,
        lastSourceRank = ?13,
        mixed = 1
    WHERE id = ?14
"""

internal val CANDIDATE_COLUMNS = listOf(
    "id", "sourceId", "nativeId", "title", "searchKey", "sortKey", "artist", "artistId", "album", "albumId",
    "trackNo", "discNo", "year", "durationMs", "addedAt", "mimeType", "sizeBytes", "artwork", "uri", "albumOrder",
    "relinkKey", "genre", "onPhone", "rating", "explicit", "artistSortKey", "albumSortKey", "lookupKey", "looseKey",
    "seq", "titleKey", "matchKey", "originalYear", "credits", "discKey", "lastSourceRank", "createdRank", "mixed",
)

private val INSERT_CANDIDATE_SQL =
    "INSERT INTO merge_candidate (${CANDIDATE_COLUMNS.joinToString { "`$it`" }}) " +
        "VALUES (${CANDIDATE_COLUMNS.joinToString { "?" }})"

private const val LINK_SQL = "INSERT OR REPLACE INTO merged_id_next (sourceRowId, mergedId) VALUES (?1, ?2)"

internal const val BY_ARRIVAL_SQL =
    "SELECT id, seq FROM merge_candidate WHERE albumId = ?1 AND seq > ?2 ORDER BY seq LIMIT $MERGE_PAGE"

internal const val BY_TRACK_NUMBER_SQL =
    "SELECT id, discKey, trackNo, seq FROM merge_candidate WHERE albumId = ?1 " +
        "AND (discKey, trackNo, seq) > (?2, ?3, ?4) ORDER BY discKey, trackNo, seq LIMIT $MERGE_PAGE"

// The earliest of some times added, leaving out unknown ones (0 or less).
internal fun earliestAdded(times: List<Long>): Long = times.filter { it > 0 }.minOrNull() ?: 0

private val Bracketed = Regex("""\s*[(\[][^)\]]*[)\]]""")

// A name for matching across sources as a whole, such as an album or an
// artist: case, accents, punctuation, spacing, lookalike letters and
// bracketed extras such as "(Deluxe)" or "[Remastered]" are ignored, and
// "&" reads as "and". Songs are matched by SongIdentity instead, which also
// knows versions and guests.
internal fun matchKey(name: String): String = SongIdentity.key(name.replace(Bracketed, " "))
