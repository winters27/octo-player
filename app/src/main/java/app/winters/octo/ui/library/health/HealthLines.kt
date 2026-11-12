package app.winters.octo.ui.library.health

import androidx.sqlite.db.SimpleSQLiteQuery
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.copiesInChunks
import app.winters.octo.catalog.marks
import app.winters.octo.catalog.read
import app.winters.octo.catalog.readLong
import app.winters.octo.data.serverSongIds
import app.winters.octo.health.AlbumPart
import app.winters.octo.health.BestReason
import app.winters.octo.health.DuplicateBasis
import app.winters.octo.health.DuplicateGroup
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.SplitAlbum
import app.winters.octo.health.heading
import app.winters.octo.health.summary
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlOrder
import app.winters.octo.ui.paging.SqlValue
import app.winters.octo.ui.paging.Survey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString
import okio.ByteString.Companion.toByteString

// How many sets or albums a page of a check's lines reads at a time, and
// how many lines a walk from the top reads at a time.
private const val SET_PAGE = 50
private const val WALK_PAGE = 500

// Whether the server in use has a song of a split album's first part and
// one of another part: arguments owner, gen, set and the server's source.
// Each song's copy is looked for by the song: `+` keeps the planner off the
// indexes that start with the source, which would walk every copy the
// server has for each heading.
internal const val SPLIT_JOINABLE_SQL =
    "SELECT EXISTS (SELECT 1 FROM health_split_song ss CROSS JOIN source_track c ON c.mergedId = ss.trackId " +
        "WHERE ss.owner = ?1 AND ss.gen = ?2 AND ss.setId = ?3 AND ss.part = 0 AND +c.sourceId = ?4) " +
        "AND EXISTS (SELECT 1 FROM health_split_song ss CROSS JOIN source_track c ON c.mergedId = ss.trackId " +
        "WHERE ss.owner = ?1 AND ss.gen = ?2 AND ss.setId = ?3 AND ss.part > 0 AND +c.sourceId = ?4)"

// Where a song's lines in Split albums are: each album of the report
// holding it, by the song, with the song's place in it. Arguments owner,
// gen and the song.
internal const val SPLIT_LINE_OF_SQL =
    "SELECT s.k1, s.k2, s.leadAlbum, ss.part, ss.disc, ss.trackKey, ss.titleKey FROM health_split_song ss INDEXED BY index_health_split_song_track " +
        "CROSS JOIN health_split s ON s.owner = ss.owner AND s.gen = ss.gen AND s.setId = ss.setId WHERE ss.owner = ? AND ss.gen = ? AND ss.trackId = ?"

// One line of a check's page: a heading over a set of copies or a split
// album, or a song with a note under it.
sealed interface HealthRow {
    // A set of copies: its words, and the set as its copies are now, best
    // first, for its Fix.
    data class Copies(val title: String, val detail: String, val group: DuplicateGroup<SourceTrackEntity>) : HealthRow

    // A split album: its words, where it is in which report, and whether
    // the server in use has a song to lead its join and one to move.
    data class Split(val title: String, val detail: String, val gen: Long, val setId: Long, val joinable: Boolean) : HealthRow

    // A song: the note under it, its id on the server in use when the page
    // offers to look it up there, and for a song of a set of copies, that
    // set and the copy the song stands for in it.
    data class Song(
        val track: TrackEntity,
        val note: String?,
        val serverId: String? = null,
        val group: DuplicateGroup<SourceTrackEntity>? = null,
        val copy: SourceTrackEntity? = null,
    ) : HealthRow
}

// A check's page in the published report, a page of lines at a time: sets
// of copies in report order, each under its heading with its songs best
// copy first (a song once a set, as the phone lists it); split albums in
// report order, each under its heading with its songs part by part; a
// missing tag's songs, and No length's, as HealthLoader lists them. Each
// read reads the report published when it runs, so a new one shows at the
// next refresh. `source` is the server in use, whose ids the look-ups and
// the joins need.
class HealthLines internal constructor(
    private val build: HealthBuild,
    private val db: CatalogDatabase,
    val check: HealthCheck,
    private val source: String?,
    sortedMost: Int = NO_LENGTH_SORTED_MOST,
) : ListLoader<HealthRow> {
    // A check of songs alone, read as a fill reads it, never copied.
    private val songs = HealthLoader(build, db, check, PAGE_OWNER, sortedMost)

    private val sets = check == HealthCheck.Duplicates || check == HealthCheck.SplitAlbums

    override val tables: Array<String> = if (check == HealthCheck.NoLength) arrayOf("track") else arrayOf("health_run")

    override val order: Comparator<RowKey> = SqlOrder.rows()

    // A song in two sets of copies shows under each.
    override val songsOnce = check != HealthCheck.Duplicates

    override fun trackIdOf(row: HealthRow): String? = (row as? HealthRow.Song)?.track?.id

    override suspend fun count(): Int = io {
        val gen = gen()
        when (check) {
            HealthCheck.Duplicates -> (
                db.readLong("SELECT COUNT(*) FROM health_group WHERE owner = ? AND gen = ? AND songs >= 2", OWNER, gen) +
                    db.readLong(
                        "SELECT COUNT(*) FROM health_group g CROSS JOIN health_member m ON m.owner = g.owner AND m.gen = g.gen AND m.root = g.root " +
                            "WHERE g.owner = ? AND g.gen = ? AND g.songs >= 2 AND EXISTS (SELECT 1 FROM track t WHERE t.id = m.trackId) " +
                            "AND NOT EXISTS (SELECT 1 FROM health_member m2 WHERE m2.owner = m.owner AND m2.gen = m.gen AND m2.root = m.root " +
                            "AND m2.copyId < m.copyId AND m2.trackId = m.trackId)",
                        OWNER, gen,
                    )
                ).toInt()
            HealthCheck.SplitAlbums -> (
                db.readLong("SELECT COUNT(*) FROM health_split WHERE owner = ? AND gen = ?", OWNER, gen) +
                    db.readLong(
                        "SELECT COUNT(*) FROM health_split_song ss WHERE ss.owner = ? AND ss.gen = ? AND EXISTS (SELECT 1 FROM track t WHERE t.id = ss.trackId)",
                        OWNER, gen,
                    )
                ).toInt()
            else -> songs.count()
        }
    }

    override suspend fun survey(anchor: RowKey?): Survey =
        if (sets) Survey(count(), null, anchor?.let { position(it) }) else songs.survey(anchor)

    override suspend fun after(key: RowKey?, limit: Int): List<Keyed<HealthRow>> = io {
        when (check) {
            HealthCheck.Duplicates -> copiesAfter(gen(), key, limit)
            HealthCheck.SplitAlbums -> splitsAfter(gen(), key, limit)
            else -> songRows(songs.after(key, limit))
        }
    }

    // Split albums' lines before a key are read on from the line `limit`
    // places up, found by its album's place; other sets walk from the top.
    override suspend fun before(key: RowKey, limit: Int): List<Keyed<HealthRow>> {
        if (!sets) return io { songRows(songs.before(key, limit)) }
        val places = io { splitPlaces() } ?: return walkBefore(key, limit)
        val at = io { places.position(key) } ?: count()
        val start = maxOf(0, at - limit)
        if (at == start) return emptyList()
        val anchor = if (start == 0) null else io { places.keyAt(start - 1) } ?: return walkBefore(key, limit)
        return after(anchor, at - start).filter { order.compare(it.key, key) < 0 }.reversed()
    }

    override suspend fun keyAt(position: Int): RowKey? {
        if (!sets) return songs.keyAt(position)
        io { splitPlaces() }?.let { places -> return io { places.keyAt(position) } }
        var at = 0
        var after: RowKey? = null
        while (true) {
            val page = after(after, WALK_PAGE)
            if (position - at < page.size) return page[position - at].key
            if (page.size < WALK_PAGE) return null
            at += page.size
            after = page.last().key
        }
    }

    override suspend fun keyOf(trackId: String): RowKey? {
        if (!sets) return songs.keyOf(trackId)
        if (check == HealthCheck.SplitAlbums) return io { splitKeyOf(trackId) }
        var after: RowKey? = null
        while (true) {
            val page = after(after, WALK_PAGE)
            page.firstOrNull { trackIdOf(it.row) == trackId }?.let { return it.key }
            if (page.size < WALK_PAGE) return null
            after = page.last().key
        }
    }

    // A song's line in Split albums, found by the song: its album's lead and
    // its own place in the album, the first in report order when the report
    // files it twice. Null when the report has no line for it, or the
    // library no longer has the song, as a page shows no line for it then.
    private fun splitKeyOf(trackId: String): RowKey? {
        if (db.readLong("SELECT EXISTS (SELECT 1 FROM track WHERE id = ?)", trackId) == 0L) return null
        return db.read(SPLIT_LINE_OF_SQL, OWNER, gen(), trackId) { c ->
            val lead = listOf(SqlValue.Blob(c.getBlob(0).toByteString()), SqlValue.Blob(c.getBlob(1).toByteString()), SqlValue.Text(c.getString(2)))
            val within = listOf(SqlValue.Int64(c.getLong(3)), SqlValue.Int64(c.getLong(4)), SqlValue.Int64(c.getLong(5)), SqlValue.Blob(c.getBlob(6).toByteString()))
            RowKey(0, lead + within, trackId)
        }.minWithOrNull(order)
    }

    // A line's key is its place in the report, whatever the report holds.
    override suspend fun rekey(key: RowKey): RowKey? = key

    override suspend fun present(ids: List<String>): Set<String> = io {
        if (!sets) return@io songs.present(ids)
        val gen = gen()
        val found = HashSet<String>()
        ids.distinct().chunked(ID_CHUNK).forEach { chunk ->
            found += if (check == HealthCheck.Duplicates) {
                db.read(
                    "SELECT m.trackId FROM health_member m INDEXED BY index_health_member_track WHERE m.owner = ? AND m.gen = ? AND m.trackId IN (${marks(chunk.size)}) " +
                        "AND EXISTS (SELECT 1 FROM health_group g WHERE g.owner = m.owner AND g.gen = m.gen AND g.root = m.root AND g.songs >= 2)",
                    OWNER, gen, *chunk.toTypedArray(),
                ) { it.getString(0) }
            } else {
                db.read(
                    "SELECT ss.trackId FROM health_split_song ss WHERE ss.owner = ? AND ss.gen = ? AND ss.trackId IN (${marks(chunk.size)})",
                    OWNER, gen, *chunk.toTypedArray(),
                ) { it.getString(0) }
            }
        }
        found
    }

    // The report published now; 0, which no report has, before the first.
    private fun gen(): Long = db.readLong("SELECT gen FROM health_run WHERE id = 0")

    // A page of a check of songs: each with its song, and its id on the
    // server in use when it can be looked up there.
    private suspend fun songRows(lines: List<Keyed<String>>): List<Keyed<HealthRow>> {
        val ids = lines.map { it.row }
        val tracks = tracksOf(ids)
        val serverIds = if (check.tag != null && check != HealthCheck.NoCover && source != null) {
            serverSongIds(db.sourceDao().copiesInChunks(ids), source)
        } else {
            emptyMap()
        }
        return lines.mapNotNull { line -> tracks[line.row]?.let { Keyed(line.key, HealthRow.Song(it, null, serverIds[line.row])) } }
    }

    // Sets of copies' lines after `key`: the rest of its set, then the sets
    // after it, each a heading and its songs.
    private suspend fun copiesAfter(gen: Long, key: RowKey?, limit: Int): List<Keyed<HealthRow>> {
        val rows = ArrayList<Keyed<HealthRow>>()
        var from: GroupKey? = null
        if (key != null) {
            val at = GroupKey(blob(key.values[0]), blob(key.values[1]), text(key.values[2]), 0, 0)
            groupAt(gen, at)?.let { group -> rows += setLines(gen, group).filter { order.compare(it.key, key) > 0 }.take(limit) }
            from = at
        }
        while (rows.size < limit) {
            val groups = groupPage(db, OWNER, gen, from, SET_PAGE)
            for (group in groups) {
                if (group.songs < 2) continue
                rows += setLines(gen, group).take(limit - rows.size)
                if (rows.size >= limit) break
            }
            if (groups.size < SET_PAGE) break
            from = groups.last()
        }
        return rows
    }

    private fun groupAt(gen: Long, key: GroupKey): GroupKey? = db.read(
        "SELECT k1, k2, bestId, root, songs FROM health_group WHERE owner = ? AND gen = ? AND k1 = ? AND k2 = ? AND bestId = ? AND songs >= 2",
        OWNER, gen, key.k1, key.k2, key.bestId,
    ) { GroupKey(it.getBlob(0), it.getBlob(1), it.getString(2), it.getLong(3), it.getInt(4)) }.firstOrNull()

    // One set's heading and songs, the set read whole: its copies best
    // first, and each song the library has once, at its first copy.
    private suspend fun setLines(gen: Long, key: GroupKey): List<Keyed<HealthRow>> {
        val group = copySet(db, gen, key.root) ?: return emptyList()
        val members = db.read(
            "SELECT m.sound, m.tags, m.copyId, m.trackId FROM health_member m WHERE m.owner = ? AND m.gen = ? AND m.root = ? ORDER BY m.sound DESC, m.tags DESC, m.copyId",
            OWNER, gen, key.root,
        ) { Member(it.getLong(0), it.getLong(1), it.getString(2), it.getString(3)) }
        val tracks = tracksOf(members.map { it.trackId })
        val quality = HashMap<String, String>()
        tracks.keys.chunked(ID_CHUNK).forEach { chunk ->
            db.read("SELECT trackId, text FROM health_quality WHERE gen = ? AND trackId IN (${marks(chunk.size)})", gen, *chunk.toTypedArray()) {
                it.getString(0) to it.getString(1)
            }.forEach { (id, text) -> quality[id] = text }
        }
        val copies = group.copies.associateBy { it.id }
        val lead = listOf(SqlValue.Blob(key.k1.toByteString()), SqlValue.Blob(key.k2.toByteString()), SqlValue.Text(key.bestId))
        val rows = ArrayList<Keyed<HealthRow>>()
        rows += Keyed(RowKey(0, lead + List(3) { SqlValue.Null }, ""), HealthRow.Copies(group.heading(SourceCopyHealth), group.summary(SourceCopyHealth), group))
        val seen = HashSet<String>()
        for (member in members) {
            val track = tracks[member.trackId] ?: continue
            if (!seen.add(track.id)) continue
            val note = listOfNotNull(quality[track.id], track.album.ifBlank { null }).joinToString(" • ").ifEmpty { null }
            val values = lead + listOf(SqlValue.Int64(-member.sound), SqlValue.Int64(-member.tags), SqlValue.Text(member.copyId))
            rows += Keyed(RowKey(0, values, track.id), HealthRow.Song(track, note, group = group, copy = copies[member.copyId]))
        }
        return rows
    }

    // Split albums' lines after `key`: the rest of its album, then the
    // albums after it, each a heading and its songs.
    private suspend fun splitsAfter(gen: Long, key: RowKey?, limit: Int): List<Keyed<HealthRow>> {
        val rows = ArrayList<Keyed<HealthRow>>()
        var from: Triple<ByteArray, ByteArray, String>? = null
        if (key != null) {
            val at = Triple(blob(key.values[0]), blob(key.values[1]), text(key.values[2]))
            val set = splitAt(gen, at)
            if (set != null) {
                val within = if (key.values[3] == SqlValue.Null) null else key
                rows += splitSongs(gen, set, within, limit)
            }
            from = at
        }
        while (rows.size < limit) {
            val page = splitPage(gen, from)
            for (set in page) {
                rows += Keyed(RowKey(0, set.lead() + List(4) { SqlValue.Null }, ""), splitHeading(gen, set))
                if (rows.size >= limit) break
                rows += splitSongs(gen, set, null, limit - rows.size)
                if (rows.size >= limit) break
            }
            if (page.size < SET_PAGE) break
            from = page.last().let { Triple(it.k1, it.k2, it.leadAlbum) }
        }
        return rows
    }

    private fun splitPage(gen: Long, after: Triple<ByteArray, ByteArray, String>?): List<SplitSet> = if (after == null) {
        db.read(
            "SELECT k1, k2, leadAlbum, setId, title, artist, reasons, differences FROM health_split WHERE owner = ? AND gen = ? ORDER BY k1, k2, leadAlbum LIMIT ?",
            OWNER, gen, SET_PAGE, row = ::splitOf,
        )
    } else {
        db.read(
            "SELECT k1, k2, leadAlbum, setId, title, artist, reasons, differences FROM health_split WHERE owner = ? AND gen = ? " +
                "AND (k1, k2, leadAlbum) > (?, ?, ?) ORDER BY k1, k2, leadAlbum LIMIT ?",
            OWNER, gen, after.first, after.second, after.third, SET_PAGE, row = ::splitOf,
        )
    }

    private fun splitAt(gen: Long, key: Triple<ByteArray, ByteArray, String>): SplitSet? = db.read(
        "SELECT k1, k2, leadAlbum, setId, title, artist, reasons, differences FROM health_split WHERE owner = ? AND gen = ? AND k1 = ? AND k2 = ? AND leadAlbum = ?",
        OWNER, gen, key.first, key.second, key.third, row = ::splitOf,
    ).firstOrNull()

    // A split album's heading: its title, artist and how many parts, why
    // its parts are one and how they differ, and whether the server in use
    // has a song of its first part to lead a join and one of another to
    // move onto it.
    private fun splitHeading(gen: Long, set: SplitSet): HealthRow.Split {
        val parts = db.readLong("SELECT MAX(part) FROM health_split_song WHERE owner = ? AND gen = ? AND setId = ?", OWNER, gen, set.setId).toInt() + 1
        val album = SplitAlbum(set.title, set.artist, List(parts) { AlbumPart("", emptyList<TrackEntity>()) }, decodeDifferences(set.differences), decodeReasons(set.reasons))
        val joinable = source != null && db.readLong(SPLIT_JOINABLE_SQL, OWNER, gen, set.setId, source) == 1L
        return HealthRow.Split(album.heading(), album.summary(), gen, set.setId, joinable)
    }

    // A split album's songs after `key` (from its first for null), part by
    // part, each part in its album's order.
    private suspend fun splitSongs(gen: Long, set: SplitSet, key: RowKey?, limit: Int): List<Keyed<HealthRow>> {
        val rows = ArrayList<Keyed<HealthRow>>()
        var after = key?.values?.drop(3)?.map(SqlValue::bound)?.plus(key.id)
        while (rows.size < limit) {
            val want = limit - rows.size
            val page = if (after == null) {
                db.read(
                    "SELECT part, disc, trackKey, titleKey, trackId FROM health_split_song WHERE owner = ? AND gen = ? AND setId = ? " +
                        "ORDER BY part, disc, trackKey, titleKey, trackId LIMIT ?",
                    OWNER, gen, set.setId, want, row = ::splitSongOf,
                )
            } else {
                db.read(
                    "SELECT part, disc, trackKey, titleKey, trackId FROM health_split_song WHERE owner = ? AND gen = ? AND setId = ? " +
                        "AND (part, disc, trackKey, titleKey, trackId) > (?, ?, ?, ?, ?) ORDER BY part, disc, trackKey, titleKey, trackId LIMIT ?",
                    OWNER, gen, set.setId, *after.toTypedArray(), want, row = ::splitSongOf,
                )
            }
            if (page.isEmpty()) break
            val tracks = tracksOf(page.map { it.second })
            page.forEach { (values, id) -> tracks[id]?.let { rows += Keyed(RowKey(0, set.lead() + values, id), HealthRow.Song(it, null)) } }
            if (page.size < want) break
            after = page.last().let { (values, id) -> values.map(SqlValue::bound) + id }
        }
        return rows
    }

    // The lines before `key`, nearest first: a walk from the top keeping
    // the last `limit`, as a check's page is read backwards only near
    // where it shows.
    private suspend fun walkBefore(key: RowKey, limit: Int): List<Keyed<HealthRow>> {
        val kept = ArrayDeque<Keyed<HealthRow>>()
        var after: RowKey? = null
        while (true) {
            val page = after(after, WALK_PAGE)
            val stop = page.indexOfFirst { order.compare(it.key, key) >= 0 }
            for (line in if (stop < 0) page else page.subList(0, stop)) {
                kept += line
                if (kept.size > limit) kept.removeFirst()
            }
            if (stop >= 0 || page.size < WALK_PAGE) break
            after = page.last().key
        }
        return kept.reversed()
    }

    // How many lines come before `key`: for split albums by the album's
    // place, while the report's places hold; otherwise a walk from the top.
    private suspend fun position(key: RowKey): Int {
        io { splitPlaces() }?.let { places -> return io { places.position(key) } ?: count() }
        var n = 0
        var after: RowKey? = null
        while (true) {
            val page = after(after, WALK_PAGE)
            for (line in page) {
                if (order.compare(line.key, key) >= 0) return n
                n++
            }
            if (page.size < WALK_PAGE) return n
            after = page.last().key
        }
    }

    private suspend fun tracksOf(ids: List<String>): Map<String, TrackEntity> =
        ids.distinct().chunked(ID_CHUNK).flatMap { db.dao().tracksByIdsUnordered(it) }.associateBy { it.id }

    // The published report's split album places, for Split albums' lines,
    // while they hold: no swap since the report's build began, so every song
    // the report holds is still the library's and each album shows the lines
    // its place counts. Null otherwise, or for another check.
    private fun splitPlaces(): SplitPlaces? {
        if (check != HealthCheck.SplitAlbums) return null
        val hold = db.readLong(
            "SELECT COALESCE((SELECT swapCount FROM health_run WHERE id = 0), -1) = COALESCE((SELECT swapCount FROM merge_state WHERE id = 0), 0)",
        )
        return if (hold == 1L) SplitPlaces(gen()) else null
    }

    // Split albums' lines by each album's place: a line is found by seeking
    // the album whose place is the nearest at or before it, then reading
    // within that album alone.
    private inner class SplitPlaces(private val gen: Long) {
        fun keyAt(position: Int): RowKey? {
            val (set, before) = db.read(
                "SELECT k1, k2, leadAlbum, setId, title, artist, reasons, differences, linesBefore FROM health_split INDEXED BY index_health_split_place " +
                    "WHERE owner = ? AND gen = ? AND linesBefore <= ? ORDER BY linesBefore DESC LIMIT 1",
                OWNER, gen, position,
            ) { splitOf(it) to it.getLong(8) }.firstOrNull() ?: return null
            val within = position - before
            if (within == 0L) return RowKey(0, set.lead() + List(4) { SqlValue.Null }, "")
            return db.read(
                "SELECT part, disc, trackKey, titleKey, trackId FROM health_split_song WHERE owner = ? AND gen = ? AND setId = ? " +
                    "ORDER BY part, disc, trackKey, titleKey, trackId LIMIT 1 OFFSET ?",
                OWNER, gen, set.setId, within - 1, row = ::splitSongOf,
            ).firstOrNull()?.let { (values, id) -> RowKey(0, set.lead() + values, id) }
        }

        // How many lines come before `key`; null when it comes after every
        // album, where every line does.
        fun position(key: RowKey): Int? {
            val k1 = blob(key.values[0])
            val k2 = blob(key.values[1])
            val lead = text(key.values[2])
            val (same, setId, before) = db.read(
                "SELECT k1, k2, leadAlbum, setId, linesBefore FROM health_split WHERE owner = ? AND gen = ? AND (k1, k2, leadAlbum) >= (?, ?, ?) " +
                    "ORDER BY k1, k2, leadAlbum LIMIT 1",
                OWNER, gen, k1, k2, lead,
            ) { c -> Triple(c.getBlob(0) contentEquals k1 && c.getBlob(1) contentEquals k2 && c.getString(2) == lead, c.getLong(3), c.getLong(4)) }
                .firstOrNull() ?: return null
            if (!same || key.values[3] == SqlValue.Null) return before.toInt()
            val after = key.values.drop(3).map(SqlValue::bound) + key.id
            return (
                before + 1 + db.readLong(
                    "SELECT COUNT(*) FROM health_split_song WHERE owner = ? AND gen = ? AND setId = ? AND (part, disc, trackKey, titleKey, trackId) < (?, ?, ?, ?, ?)",
                    OWNER, gen, setId, *after.toTypedArray(),
                )
                ).toInt()
        }
    }

    private class Member(val sound: Long, val tags: Long, val copyId: String, val trackId: String)

    private class SplitSet(
        val k1: ByteArray,
        val k2: ByteArray,
        val leadAlbum: String,
        val setId: Long,
        val title: String,
        val artist: String,
        val reasons: String,
        val differences: String,
    ) {
        fun lead(): List<SqlValue> = listOf(SqlValue.Blob(k1.toByteString()), SqlValue.Blob(k2.toByteString()), SqlValue.Text(leadAlbum))
    }

    private fun splitOf(c: android.database.Cursor) =
        SplitSet(c.getBlob(0), c.getBlob(1), c.getString(2), c.getLong(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7))

    private fun splitSongOf(c: android.database.Cursor): Pair<List<SqlValue>, String> =
        listOf(SqlValue.Int64(c.getLong(0)), SqlValue.Int64(c.getLong(1)), SqlValue.Int64(c.getLong(2)), SqlValue.Blob(c.getBlob(3).toByteString())) to c.getString(4)

    private fun blob(value: SqlValue): ByteArray = (value as SqlValue.Blob).v.toByteArray()

    private fun text(value: SqlValue): String = (value as SqlValue.Text).v

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private val OWNER: String get() = HealthBuild.OWNER

        // The owner a page's song reads name: the published report is read,
        // never copied.
        private const val PAGE_OWNER = "page"
    }
}

// A set of copies of a report as its copies are now, best first, with why
// they are one and why the best is best; null when none of its copies is
// kept any more.
internal fun copySet(db: CatalogDatabase, gen: Long, root: Long): DuplicateGroup<SourceTrackEntity>? {
    val (basis, reason) = db.read("SELECT basis, reason FROM health_group WHERE owner = ? AND gen = ? AND root = ?", HealthBuild.OWNER, gen, root) {
        DuplicateBasis.valueOf(it.getString(0)) to BestReason.valueOf(it.getString(1))
    }.firstOrNull() ?: return null
    val ids = db.read(
        "SELECT copyId FROM health_member WHERE owner = ? AND gen = ? AND root = ? ORDER BY sound DESC, tags DESC, copyId",
        HealthBuild.OWNER, gen, root,
    ) { it.getString(0) }
    val copies = HashMap<String, SourceTrackEntity>()
    ids.chunked(ID_CHUNK).forEach { chunk ->
        db.readerDao().copyRows(SimpleSQLiteQuery("SELECT rowid AS node, * FROM source_track WHERE id IN (${marks(chunk.size)})", chunk.toTypedArray()))
            .forEach { copies[it.copy.id] = it.copy }
    }
    val kept = ids.mapNotNull(copies::get)
    return if (kept.isEmpty()) null else DuplicateGroup(kept, basis, reason)
}

// A split album of a report with its songs as the library has them, part
// by part in album order; null when the report no longer holds it. Read
// whole: one album's songs.
internal suspend fun splitAlbumOf(db: CatalogDatabase, gen: Long, setId: Long): SplitAlbum<TrackEntity>? {
    val head = db.read("SELECT setId, title, artist, reasons, differences FROM health_split WHERE owner = ? AND gen = ? AND setId = ?", HealthBuild.OWNER, gen, setId) {
        SplitHead(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4))
    }.firstOrNull() ?: return null
    val songs = db.read(
        "SELECT part, trackId FROM health_split_song WHERE owner = ? AND gen = ? AND setId = ? ORDER BY part, disc, trackKey, titleKey, trackId",
        HealthBuild.OWNER, gen, setId,
    ) { it.getInt(0) to it.getString(1) }
    val tracks = songs.map { it.second }.distinct().chunked(ID_CHUNK).flatMap { db.dao().tracksByIdsUnordered(it) }.associateBy { it.id }
    return splitAlbum(head, songs, tracks)
}

// What a page of Fix all's split albums reads of the report: an album's
// set, its words and why its parts are one.
internal class SplitHead(val setId: Long, val title: String, val artist: String, val reasons: String, val differences: String)

// A page of split albums of a report, each as splitAlbumOf makes it (null
// where it would be), in the order given, with every copy their songs the
// library has: their songs read in one statement, then the songs' library
// rows and their copies, ID_CHUNK songs a statement.
internal suspend fun splitAlbumsOf(db: CatalogDatabase, gen: Long, heads: List<SplitHead>): Pair<List<SplitAlbum<TrackEntity>?>, List<SourceTrackEntity>> {
    if (heads.isEmpty()) return emptyList<SplitAlbum<TrackEntity>?>() to emptyList()
    val rows = db.read(
        "SELECT setId, part, disc, trackKey, titleKey, trackId FROM health_split_song WHERE owner = ? AND gen = ? AND setId IN (${marks(heads.size)})",
        HealthBuild.OWNER, gen, *heads.map { it.setId }.toTypedArray(),
    ) { c -> c.getLong(0) to SplitSongRow(c.getInt(1), c.getLong(2), c.getLong(3), c.getBlob(4).toByteString(), c.getString(5)) }
    val bySet = rows.groupBy({ it.first }, { it.second })
    val tracks = rows.map { it.second.trackId }.distinct().chunked(ID_CHUNK).flatMap { db.dao().tracksByIdsUnordered(it) }.associateBy { it.id }
    val copies = db.sourceDao().copiesInChunks(tracks.keys.toList())
    val albums = heads.map { head ->
        val songs = bySet[head.setId].orEmpty().sortedWith(SplitSongRow.Order).map { it.part to it.trackId }
        splitAlbum(head, songs, tracks)
    }
    return albums to copies
}

// One song of a split album as the report keeps it, compared in the order
// its key keeps them: part, disc, track, title, song.
private class SplitSongRow(val part: Int, val disc: Long, val trackKey: Long, val titleKey: ByteString, val trackId: String) {
    companion object {
        val Order: Comparator<SplitSongRow> = compareBy<SplitSongRow>({ it.part }, { it.disc }, { it.trackKey })
            .thenComparator { a, b -> SqlOrder.bytes(a.titleKey, b.titleKey) }
            .thenComparator { a, b -> SqlOrder.text(a.trackId, b.trackId) }
    }
}

// One split album from its report row and its songs in album order (part,
// song id), with the library's rows of them; null when fewer than two of
// its parts still have a song.
private fun splitAlbum(head: SplitHead, songs: List<Pair<Int, String>>, tracks: Map<String, TrackEntity>): SplitAlbum<TrackEntity>? {
    val parts = songs.groupBy({ it.first }, { tracks[it.second] }).toSortedMap().values
        .map { it.filterNotNull() }
        .filter { it.isNotEmpty() }
        .map { AlbumPart(it.first().albumId, it) }
    if (parts.size < 2) return null
    return SplitAlbum(head.title, head.artist, parts, decodeDifferences(head.differences), decodeReasons(head.reasons))
}
