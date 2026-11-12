package app.winters.octo.ui.library.health

import android.database.Cursor
import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.read
import app.winters.octo.catalog.readLong
import app.winters.octo.catalog.write
import app.winters.octo.health.HealthCheck
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlOrder
import app.winters.octo.ui.paging.SqlValue
import app.winters.octo.ui.paging.Survey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// How many lines a copy of a check reads and writes at a time.
private const val COPY_PAGE = 500

// The most songs with no length whose lines are read from the length
// index and sorted; with more, the library's own order is walked.
internal const val NO_LENGTH_SORTED_MOST = 5_000

// A health check's songs for a queue's fill, each song once at its first
// line, in the check's own order: sets of copies in report order, each
// set's songs best copy first; split albums part by part, each part in
// album order; a missing tag's songs by artist, album and album order; No
// length's songs in library order. A line's key is its order's columns,
// with the song as its id.
//
// Until `copy`, each read is one statement over the report published when
// it runs (the small check, the first song, the song at a place). `copy`
// copies the check's lines from the report published then, pinned while it
// copies, into the fill's own owner (`into`); from then on every read is
// of that copy, which nothing changes until the fill clears it, and a song
// the library has renamed since stays in it under its old id for the fill
// to follow. No length has no lines: it reads the library as it is, by
// the length index while at most `sortedMost` songs have no length (a
// bounded read and sort), else by walking the library's order. With
// `pinned`, every read is of that report, which its reader keeps pinned.
class HealthLoader internal constructor(
    private val build: HealthBuild,
    private val db: CatalogDatabase,
    val check: HealthCheck,
    val into: String,
    private val sortedMost: Int = NO_LENGTH_SORTED_MOST,
    private val pinned: Long? = null,
) : ListLoader<String> {
    @Volatile private var copied: Long? = null

    override val tables: Array<String> = when (check) {
        HealthCheck.NoLength -> arrayOf("track")
        else -> arrayOf("health_run", "health_song", "health_group", "health_member", "health_split", "health_split_song")
    }

    override val order: Comparator<RowKey> = SqlOrder.rows()

    override val songsOnce = true

    override fun trackIdOf(row: String): String = row

    // Where reads go now: the copy once made, else the pinned or the
    // published report.
    private suspend fun source(): Pair<String, Long>? =
        copied?.let { into to it } ?: pinned?.let { HealthBuild.OWNER to it } ?: build.shown()?.let { HealthBuild.OWNER to it.gen }

    // Copies the check's lines from the report published now into `into`,
    // building one first when none is (a fill resumed after the work tables
    // were emptied). Answers the swap count the report was built at and how
    // many songs the copy holds.
    suspend fun copy(): HealthCopy = withContext(Dispatchers.IO) {
        if (check == HealthCheck.NoLength) return@withContext HealthCopy(db.readLong("SELECT swapCount FROM merge_state"), count())
        val gen = build.pin() ?: run {
            build.report()
            build.pin() ?: error("no report")
        }
        try {
            val swapCount = db.readLong("SELECT swapCount FROM health_run WHERE id = 0")
            var count = 0
            var after: RowKey? = null
            while (true) {
                val lines = lines(HealthBuild.OWNER, gen, after, COPY_PAGE)
                if (lines.isEmpty()) break
                withContext(WriteLabels.of("health copy")) { db.withTransaction { lines.forEach { copyLine(gen, it) } } }
                count += lines.size
                if (lines.size < COPY_PAGE) break
                after = lines.last().key
            }
            copied = gen
            HealthCopy(swapCount, count)
        } finally {
            build.release(gen)
        }
    }

    // Clears the fill's copy.
    suspend fun clear() = withContext(Dispatchers.IO) {
        for (table in listOf("health_song", "health_group", "health_member", "health_split", "health_split_song")) {
            while (true) {
                val gone = db.withTransaction { db.write("DELETE FROM $table WHERE rowid IN (SELECT rowid FROM $table WHERE owner = ? LIMIT 500)", into) }
                if (gone < 500) break
            }
        }
        copied = null
    }

    override suspend fun count(): Int = io {
        val (owner, gen) = source() ?: return@io 0
        when (check) {
            HealthCheck.NoLength -> db.readLong("SELECT COUNT(*) FROM track WHERE durationMs < 1000").toInt()
            HealthCheck.Duplicates -> db.readLong(
                "SELECT COUNT(DISTINCT m.trackId) FROM health_group g CROSS JOIN health_member m ON m.owner = g.owner AND m.gen = g.gen AND m.root = g.root " +
                    "WHERE g.owner = ? AND g.gen = ? AND g.songs >= 2 ${known(owner)}",
                owner, gen,
            ).toInt()
            HealthCheck.SplitAlbums -> db.readLong("SELECT COUNT(*) FROM health_split_song WHERE owner = ? AND gen = ?", owner, gen).toInt()
            else -> db.readLong("SELECT COUNT(*) FROM health_song WHERE owner = ? AND gen = ? AND (mask & ?) != 0", owner, gen, check.tag!!.bit()).toInt()
        }
    }

    override suspend fun survey(anchor: RowKey?): Survey = Survey(count(), null, anchor?.let { position(it) })

    override suspend fun after(key: RowKey?, limit: Int): List<Keyed<String>> = io {
        val (owner, gen) = source() ?: return@io emptyList()
        lines(owner, gen, key, limit)
    }

    // Walked from the top, keeping the last `limit` before the key: a
    // check's list is read backwards only near where it is shown.
    override suspend fun before(key: RowKey, limit: Int): List<Keyed<String>> = io {
        val (owner, gen) = source() ?: return@io emptyList()
        val kept = ArrayDeque<Keyed<String>>()
        var after: RowKey? = null
        while (true) {
            val page = lines(owner, gen, after, COPY_PAGE)
            val stop = page.indexOfFirst { order.compare(it.key, key) >= 0 }
            for (line in if (stop < 0) page else page.subList(0, stop)) {
                kept += line
                if (kept.size > limit) kept.removeFirst()
            }
            if (stop >= 0 || page.size < COPY_PAGE) break
            after = page.last().key
        }
        kept.reversed()
    }

    override suspend fun keyAt(position: Int): RowKey? = io {
        val (owner, gen) = source() ?: return@io null
        val (sql, args) = lineSql(owner, gen, null, null)
        db.read("$sql LIMIT 1 OFFSET ?", *args, position, row = ::lineOf).firstOrNull()?.key
    }

    override suspend fun keyOf(trackId: String): RowKey? = io {
        val (owner, gen) = source() ?: return@io null
        val (sql, args) = lineSql(owner, gen, null, trackId)
        db.read("$sql LIMIT 1", *args, row = ::lineOf).firstOrNull()?.key
    }

    override suspend fun rekey(key: RowKey): RowKey? = keyOf(key.id)

    override suspend fun present(ids: List<String>): Set<String> = ids.filterTo(HashSet()) { keyOf(it) != null }

    // How many lines come before `key`.
    private suspend fun position(key: RowKey): Int {
        var n = 0
        var after: RowKey? = null
        while (true) {
            val page = after(after, COPY_PAGE)
            for (line in page) {
                if (order.compare(line.key, key) >= 0) return n
                n++
            }
            if (page.size < COPY_PAGE) return n
            after = page.last().key
        }
    }

    // The check's lines after `after`.
    private fun lines(owner: String, gen: Long, after: RowKey?, limit: Int): List<Keyed<String>> {
        val (sql, args) = lineSql(owner, gen, after, null)
        return db.read("$sql LIMIT ?", *args, limit, row = ::lineOf)
    }

    // A set's songs are those the library has, in the report; a copy keeps
    // the ones it copied, whatever the library did since.
    private fun known(owner: String) = if (owner == HealthBuild.OWNER) "AND EXISTS (SELECT 1 FROM track t WHERE t.id = m.trackId)" else ""

    // The statement of a check's lines in order, after `after`, or of one
    // song's first line. Its columns are the line's order, then the song.
    private fun lineSql(owner: String, gen: Long, after: RowKey?, trackId: String?): Pair<String, Array<Any?>> {
        val past = after?.let { it.values.map(SqlValue::bound) + it.id }.orEmpty()
        val one = listOfNotNull(trackId)
        fun since(columns: String) = if (after == null) "" else "AND ($columns) > (${past.joinToString(", ") { "?" }})"
        fun only(column: String) = if (trackId == null) "" else "AND $column = ?"

        // No length's lines: a few such songs are read from the length
        // index and sorted, many walk the library in its order; one song's
        // line is found by its id.
        fun walk() = when {
            trackId != null -> ""
            fewWithNoLength() -> "INDEXED BY index_track_length"
            else -> "INDEXED BY index_track_sortKey_id"
        }
        return when (check) {
            HealthCheck.NoLength ->
                "SELECT sortKey, id FROM track ${walk()} WHERE durationMs < 1000 ${since("sortKey, id")} ${only("id")} ORDER BY sortKey, id" to
                    (past + one).toTypedArray()
            HealthCheck.Duplicates ->
                (
                    "SELECT g.k1, g.k2, g.bestId, -m.sound, -m.tags, m.copyId, m.trackId FROM health_group g INDEXED BY index_health_group_order " +
                        "CROSS JOIN health_member m ON m.owner = g.owner AND m.gen = g.gen AND m.root = g.root " +
                        "WHERE g.owner = ? AND g.gen = ? AND g.songs >= 2 ${known(owner)} " +
                        "AND NOT EXISTS (SELECT 1 FROM health_member m2 INDEXED BY index_health_member_track CROSS JOIN health_group g2 " +
                        "ON g2.owner = m2.owner AND g2.gen = m2.gen AND g2.root = m2.root " +
                        "WHERE m2.owner = m.owner AND m2.gen = m.gen AND m2.trackId = m.trackId AND g2.songs >= 2 " +
                        "AND (g2.k1, g2.k2, g2.bestId, -m2.sound, -m2.tags, m2.copyId) < (g.k1, g.k2, g.bestId, -m.sound, -m.tags, m.copyId)) " +
                        "${since("g.k1, g.k2, g.bestId, -m.sound, -m.tags, m.copyId, m.trackId")} ${only("m.trackId")} " +
                        "ORDER BY g.k1, g.k2, g.bestId, m.sound DESC, m.tags DESC, m.copyId"
                    ) to (listOf(owner, gen) + past + one).toTypedArray()
            HealthCheck.SplitAlbums ->
                (
                    "SELECT s.k1, s.k2, s.leadAlbum, ss.part, ss.disc, ss.trackKey, ss.titleKey, ss.trackId FROM health_split s INDEXED BY index_health_split_order " +
                        "CROSS JOIN health_split_song ss ON ss.owner = s.owner AND ss.gen = s.gen AND ss.setId = s.setId " +
                        "WHERE s.owner = ? AND s.gen = ? ${since("s.k1, s.k2, s.leadAlbum, ss.part, ss.disc, ss.trackKey, ss.titleKey, ss.trackId")} " +
                        "${only("ss.trackId")} ORDER BY s.k1, s.k2, s.leadAlbum, ss.part, ss.disc, ss.trackKey, ss.titleKey, ss.trackId"
                    ) to (listOf(owner, gen) + past + one).toTypedArray()
            else ->
                (
                    "SELECT k1, k2, albumId, disc, trackKey, k3, trackId FROM health_song WHERE owner = ? AND gen = ? AND (mask & ?) != 0 " +
                        "${since("k1, k2, albumId, disc, trackKey, k3, trackId")} ${only("trackId")} ORDER BY k1, k2, albumId, disc, trackKey, k3, trackId"
                    ) to (listOf(owner, gen, check.tag!!.bit()) + past + one).toTypedArray()
        }
    }

    // Whether at most `sortedMost` songs have no length: a count that
    // stops one past it, on the length index alone.
    private fun fewWithNoLength(): Boolean =
        db.readLong("SELECT COUNT(*) FROM (SELECT 1 FROM track WHERE durationMs < 1000 LIMIT ?)", sortedMost + 1) <= sortedMost

    // A line as a row key: its order's columns as values, the song as id.
    private fun lineOf(c: Cursor): Keyed<String> {
        val last = c.columnCount - 1
        val id = c.getString(last)
        return Keyed(RowKey(0, (0 until last).map { SqlValue.of(c, it) }, id), id)
    }

    // One line copied into the fill's owner.
    private fun copyLine(gen: Long, line: Keyed<String>) {
        val v = line.key.values.map(SqlValue::bound)
        when (check) {
            HealthCheck.NoLength -> Unit
            HealthCheck.Duplicates -> {
                db.write(
                    "INSERT OR IGNORE INTO health_group (owner, gen, k1, k2, bestId, root, size, basis, reason, songs) " +
                        "SELECT ?, gen, k1, k2, bestId, root, size, basis, reason, songs FROM health_group WHERE owner = ? AND gen = ? AND k1 = ? AND k2 = ? AND bestId = ?",
                    into, HealthBuild.OWNER, gen, v[0], v[1], v[2],
                )
                db.write(
                    "INSERT OR IGNORE INTO health_member (owner, gen, root, sound, tags, copyId, trackId) " +
                        "SELECT ?, gen, root, sound, tags, copyId, trackId FROM health_member WHERE owner = ? AND gen = ? AND copyId = ? AND root = " +
                        "(SELECT root FROM health_group WHERE owner = ? AND gen = ? AND k1 = ? AND k2 = ? AND bestId = ?)",
                    into, HealthBuild.OWNER, gen, v[5], HealthBuild.OWNER, gen, v[0], v[1], v[2],
                )
            }
            HealthCheck.SplitAlbums -> {
                db.write(
                    "INSERT OR IGNORE INTO health_split (owner, gen, k1, k2, leadAlbum, setId, title, artist, reasons, differences) " +
                        "SELECT ?, gen, k1, k2, leadAlbum, setId, title, artist, reasons, differences FROM health_split " +
                        "WHERE owner = ? AND gen = ? AND k1 = ? AND k2 = ? AND leadAlbum = ?",
                    into, HealthBuild.OWNER, gen, v[0], v[1], v[2],
                )
                db.write(
                    "INSERT OR IGNORE INTO health_split_song (owner, gen, setId, part, disc, trackKey, titleKey, trackId) " +
                        "SELECT ?, gen, setId, part, disc, trackKey, titleKey, trackId FROM health_split_song WHERE owner = ? AND gen = ? AND trackId = ? " +
                        "AND setId = (SELECT setId FROM health_split WHERE owner = ? AND gen = ? AND k1 = ? AND k2 = ? AND leadAlbum = ?)",
                    into, HealthBuild.OWNER, gen, line.key.id, HealthBuild.OWNER, gen, v[0], v[1], v[2],
                )
            }
            else -> db.write(
                "INSERT OR IGNORE INTO health_song (owner, gen, trackId, mask, k1, k2, albumId, disc, trackKey, k3) " +
                    "SELECT ?, gen, trackId, mask, k1, k2, albumId, disc, trackKey, k3 FROM health_song WHERE owner = ? AND gen = ? AND trackId = ?",
                into, HealthBuild.OWNER, gen, line.key.id,
            )
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}

// A copy of a health check made for one reader: the swap count the report
// was built at, and how many songs it holds.
data class HealthCopy(val swapCount: Long, val count: Int)
