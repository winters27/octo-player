package app.winters.octo.ui.library.health

import android.database.sqlite.SQLiteFullException
import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.marks
import app.winters.octo.catalog.read
import app.winters.octo.catalog.readLong
import app.winters.octo.catalog.splitLines
import app.winters.octo.catalog.stringOrNull
import app.winters.octo.catalog.trackOrderPage
import app.winters.octo.catalog.write
import app.winters.octo.data.spaceWords
import app.winters.octo.health.AlbumDifference
import app.winters.octo.health.AlbumDifferenceValues
import app.winters.octo.health.BestReason
import app.winters.octo.health.DuplicateBasis
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.HealthTag
import app.winters.octo.health.PartFacts
import app.winters.octo.health.SAME_LENGTH_SECONDS
import app.winters.octo.health.SplitBasis
import app.winters.octo.health.SplitReason
import app.winters.octo.health.albumArtistKey
import app.winters.octo.health.albumArtistsNamed
import app.winters.octo.health.albumVersions
import app.winters.octo.health.creditKeys
import app.winters.octo.health.healthSortKey
import app.winters.octo.health.qualityText
import app.winters.octo.health.recordingCodes
import app.winters.octo.health.releaseCode
import app.winters.octo.health.sameByTitle
import app.winters.octo.health.soundOf
import app.winters.octo.health.splitSets
import app.winters.octo.health.tagsClose
import app.winters.octo.health.tagsOf
import app.winters.octo.health.titleReadable
import app.winters.octo.ui.paging.SqlOrder
import app.winters.octo.ui.paging.Utf16Key
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// How many rows each step of a health build reads or writes at a time.
internal const val HEALTH_PAGE = 500

// How many sets of copies a build keeps in memory while it joins them.
internal const val HEALTH_FIND_CACHE = 4_096

// The disk a build takes per copy of a song in the library, its share of
// the write-ahead log included: measured at 469 with a phone's short ids
// (HealthDiskFullTest), with room for a server's ids, about twice as long.
// A build asks for twice that free.
internal const val HEALTH_BYTES_PER_COPY = 1_000L

// The report's tables with no owner, cleared by a launch's first build;
// how many rows each transaction of that clear deletes, and its writes'
// label.
internal val HEALTH_UNOWNED_TABLES = listOf("health_run", "health_tag", "health_node", "health_quality", "health_part", "health_code", "health_tally")
internal const val HEALTH_CLEAR_ROWS = 2_000
internal const val HEALTH_CLEAR = "health clear"

// Which tag a missing-tag row lacks, as a bit of health_song.mask.
internal fun HealthTag.bit(): Int = 1 shl ordinal

// What Library health shows: nothing yet, the report published last (what
// each check found, how many songs were looked at), or the words for why
// it could not be brought up to date, over the report shown before.
sealed interface HealthShown {
    data object Loading : HealthShown

    data class Shown(val gen: Long, val counts: Map<HealthCheck, Int>, val checked: Int, val swapCount: Long) : HealthShown {
        fun count(check: HealthCheck): Int = counts[check] ?: 0
    }

    data class Problem(val words: String, val before: Shown?) : HealthShown
}

// Points a test can stop a build at. Each does nothing in the app.
internal open class HealthHooks {
    // A build holds the build lock, before its first read.
    open suspend fun opened(gen: Long) {}

    // After each page of a build's walks is written.
    open suspend fun page(n: Int) {}

    open suspend fun published(gen: Long) {}
}

// Library health's report, kept in the database: each build walks the
// library and its copies once, a page at a time, and writes what every
// check found into the health tables under the owner "report" and a new
// generation, then publishes that generation in one step. Readers read
// the last one published, never half of one. Copies of one recording are
// joined in sets kept in the database, with the most used in memory.
//
// A build runs to its end under the build lock, which is held by nothing
// else and taken with no other lock held, never inside the merge lock: a
// swap during it is seen in its writes; the build still publishes, and the
// swap's own change of the library builds it again. A fix made from a
// report checks its copies again first (HealthRecheck), so a report a
// swap made stale never removes or joins the wrong copy.
@Singleton
class HealthBuild internal constructor(
    private val db: CatalogDatabase,
    private val merge: CatalogMerge,
    private val owners: WorkOwners,
    private val freeSpace: FreeSpace,
    private val page: Int,
    private val cache: Int,
) {
    @Inject constructor(db: CatalogDatabase, merge: CatalogMerge, owners: WorkOwners, freeSpace: FreeSpace) :
        this(db, merge, owners, freeSpace, HEALTH_PAGE, HEALTH_FIND_CACHE)

    private val reader = db.readerDao()
    private val work = db.workDao()
    private val lock = Mutex()

    internal var hooks = HealthHooks()

    // Whether a build of this launch has published a report. Before one,
    // the run an older launch left (the library's open deletes nothing) is
    // not this launch's report, so none is shown, and the first build clears
    // the tables without an owner that it left.
    @Volatile private var publishedHere = false

    // The report while collected: the one published now, then after each
    // build. A build follows every change of the songs, the albums or the
    // copies, once the song keys are current, and each move of `again` (a
    // fix that found the set it was asked for gone).
    fun watch(again: Flow<*> = emptyFlow<Unit>()): Flow<HealthShown> = flow {
        emit(shown() ?: HealthShown.Loading)
        merge(db.invalidationTracker.createFlow("track", "album", "source_track").map { }, again.map { })
            .conflate()
            .collect { emit(buildShowing()) }
    }.flowOn(Dispatchers.IO)

    // The report published now, or null before the first of this launch.
    suspend fun shown(): HealthShown.Shown? = withContext(Dispatchers.IO) {
        if (!publishedHere) return@withContext null
        db.read(
            "SELECT gen, duplicates, splitAlbums, noLength, noTrackNumber, noAlbumArtist, noCover, noYear, noGenre, checked, swapCount FROM health_run WHERE id = 0",
        ) { c ->
            val counts = mapOf(
                HealthCheck.Duplicates to c.getInt(1), HealthCheck.SplitAlbums to c.getInt(2), HealthCheck.NoLength to c.getInt(3),
                HealthCheck.NoTrackNumber to c.getInt(4), HealthCheck.NoAlbumArtist to c.getInt(5), HealthCheck.NoCover to c.getInt(6),
                HealthCheck.NoYear to c.getInt(7), HealthCheck.NoGenre to c.getInt(8),
            )
            HealthShown.Shown(c.getLong(0), counts, c.getInt(9), c.getLong(10))
        }.firstOrNull()
    }

    // Builds a report now, unless one is published, and answers it; for a
    // fill whose report went with the work tables.
    suspend fun report(): HealthShown.Shown {
        shown()?.let { return it }
        return when (val built = buildShowing()) {
            is HealthShown.Shown -> built
            is HealthShown.Problem -> throw NotEnoughSpace(null, SpaceFor.Health)
            HealthShown.Loading -> error("no report")
        }
    }

    // A check's songs in a published report, for a queue's fill under its
    // own owner (`into`).
    fun loader(check: HealthCheck, into: String): HealthLoader = HealthLoader(this, db, check, into)

    // A check's page in the published report, its headings and songs, with
    // what the server in use (`source`) can fix.
    fun lines(check: HealthCheck, source: String?): HealthLines = HealthLines(this, db, check, source)

    // The report published now, pinned until `release`; null with none.
    internal fun pin(): Long? = owners.pinPublished(WorkOwners.REPORT)

    internal suspend fun release(gen: Long) {
        if (owners.unpinGen(WorkOwners.REPORT, gen)) withContext(NonCancellable) { deleteGen(gen) }
    }

    // Builds a report now and answers what it shows.
    internal suspend fun buildNow(): HealthShown = withContext(Dispatchers.IO) { buildShowing() }

    private suspend fun buildShowing(): HealthShown {
        val before = shown()
        return try {
            merge.keysReady.first { it }
            merge.swapStamp.first { it % 2 == 0L }
            lock.withLock { Build().run() }
            shown() ?: HealthShown.Loading
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotEnoughSpace) {
            Log.w("Octo", "health: not enough space")
            HealthShown.Problem(spaceWords(SpaceFor.Health, e.neededBytes), before)
        } catch (e: SQLiteFullException) {
            Log.w("Octo", "health: the disk is full")
            HealthShown.Problem(spaceWords(SpaceFor.Health, null), before)
        }
    }

    // One build: its generation, the swap count it began at, and what it
    // found so far.
    private inner class Build {
        var gen = 0L
        var baseline = 0L
        var crossed = false
        var pages = 0
        var checked = 0
        var noLength = 0
        val missing = IntArray(HealthTag.entries.size)
        var duplicates = 0
        var splits = 0

        // Each album's size, and the albums whose first song was seen, kept
        // for the build: album-sized.
        val albumSizes = HashMap<String, Int>()
        val seenAlbums = HashSet<String>()

        suspend fun run() {
            if (!publishedHere) clearLeft()
            baseline = db.withTransaction { work.swapCount() }
            gen = maxOf(db.readLong("SELECT gen FROM health_run WHERE id = 0"), db.readLong("SELECT MAX(gen) FROM health_song")) + 1
            hooks.opened(gen)
            needs(db.readLong("SELECT COUNT(*) FROM source_track") * HEALTH_BYTES_PER_COPY * 2)
            var published = false
            try {
                songs()
                copies()
                splitAlbums()
                publish()
                published = true
            } finally {
                withContext(NonCancellable) {
                    deleteScratch(gen)
                    if (!published) deleteGen(gen)
                }
            }
            if (crossed) Log.i("Octo", "health: a swap came during the build")
            hooks.published(gen)
        }

        suspend fun write(block: () -> Unit) = withContext(WriteLabels.of("health page")) {
            db.withTransaction {
                if (work.swapCount() != baseline) crossed = true
                block()
            }
        }

        suspend fun paged() = hooks.page(++pages)

        // Missing tags and no length, in library order, with each album's
        // facts for the split album check.
        suspend fun songs() {
            var after = "" to ""
            while (true) {
                val songs = reader.trackOrderPage(after.first, after.second, page)
                if (songs.isEmpty()) break
                val albums = albumArtists(songs.map { it.albumId }.toSet())
                val chosen = chosenCopies(songs.map { it.id })
                val rows = ArrayList<Array<Any?>>()
                val firsts = ArrayList<TrackEntity>()
                for (song in songs) {
                    checked++
                    if (song.durationMs < 1_000) noLength++
                    var mask = 0
                    if (song.genre.isBlank()) mask = mask or HealthTag.Genre.bit()
                    if ((song.year ?: 0) <= 0) mask = mask or HealthTag.Year.bit()
                    if ((song.trackNo ?: 0) <= 0 && albumSize(song.albumId) > 1) mask = mask or HealthTag.TrackNumber.bit()
                    if (song.artwork.isNullOrBlank()) mask = mask or HealthTag.Cover.bit()
                    HealthTag.entries.forEach { if (mask and it.bit() != 0) missing[it.ordinal]++ }
                    if (mask != 0) {
                        val albumArtist = albums[song.albumId]?.ifBlank { null }
                        rows += arrayOf(
                            OWNER, gen, song.id, mask, Utf16Key(healthSortKey(albumArtist ?: song.artist.ifBlank { null })),
                            Utf16Key(healthSortKey(song.album.ifBlank { null })), song.albumId, song.discNo ?: 0,
                            song.trackNo?.takeIf { it > 0 } ?: Int.MAX_VALUE, Utf16Key(healthSortKey(song.title)),
                        )
                    }
                    if (song.albumId.isNotEmpty() && seenAlbums.add(song.albumId)) firsts += song
                }
                write {
                    rows.forEach { db.write("INSERT INTO health_song (owner, gen, trackId, mask, k1, k2, albumId, disc, trackKey, k3) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", *it) }
                    for (song in firsts) {
                        db.write(
                            "INSERT INTO health_part (gen, albumId, bucket, firstKey, firstId, size, artist, artistKey, albumArtists, years) VALUES (?, ?, ?, ?, ?, 0, '', '', '', '')",
                            gen, song.albumId, SongIdentity.key(song.album.ifBlank { null }), song.sortKey, song.id,
                        )
                    }
                    for (song in songs) {
                        if (song.albumId.isEmpty()) continue
                        song.year?.takeIf { it > 0 }?.let { code(song.albumId, YEAR, it.toString()) }
                        chosen[song.id]?.let { copy ->
                            copy.mbAlbumId?.let(::releaseCode)?.takeIf(String::isNotEmpty)?.let { code(song.albumId, RELEASE, it) }
                            copy.mbReleaseGroupId?.let(::releaseCode)?.takeIf(String::isNotEmpty)?.let { code(song.albumId, GROUP, it) }
                        }
                        song.artist.takeIf(String::isNotBlank)?.let { artist ->
                            db.write("INSERT OR IGNORE INTO health_tally (gen, albumId, value, n) VALUES (?, ?, ?, 0)", gen, song.albumId, artist)
                            db.write("UPDATE health_tally SET n = n + 1 WHERE gen = ? AND albumId = ? AND value = ?", gen, song.albumId, artist)
                        }
                    }
                }
                paged()
                if (songs.size < page) break
                after = songs.last().let { it.sortKey to it.id }
            }
        }

        private fun code(albumId: String, kind: Int, code: String) {
            db.write("INSERT OR IGNORE INTO health_code (gen, albumId, kind, code) VALUES (?, ?, ?, ?)", gen, albumId, kind, code)
        }

        // How many songs an album has, counted once a build.
        fun albumSize(albumId: String): Int =
            albumSizes.getOrPut(albumId) { db.readLong("SELECT COUNT(*) FROM track WHERE albumId = ?", albumId).toInt() }

        // Copies of one recording, among each place's own copies: first those
        // whose tags name it, in copy order, then those whose title and
        // artist read the same, bucket by bucket. Then each set of two or
        // more, best copy first, and the library songs it stands for.
        suspend fun copies() {
            val sets = NodeSets(gen)
            // The tags: the first copy with each code, and every later one
            // close enough in length joins it.
            var after = Triple("", Long.MIN_VALUE, Long.MIN_VALUE)
            while (true) {
                val rows = copyPage(after)
                if (rows.isEmpty()) break
                val codes = rows.flatMap { (_, copy) -> recordingCodes(copy, SourceCopyHealth).map { "${copy.sourceId}\u0000$it" } }.toSet()
                val firsts = HashMap<String, Pair<Long, Int>>()
                codes.chunked(ID_CHUNK).forEach { chunk ->
                    db.read("SELECT key, firstNode, firstSeconds FROM health_tag WHERE gen = ? AND key IN (${marks(chunk.size)})", gen, *chunk.toTypedArray()) {
                        it.getString(0) to (it.getLong(1) to it.getInt(2))
                    }.forEach { (key, first) -> firsts[key] = first }
                }
                write {
                    for ((node, copy) in rows) {
                        val seconds = SourceCopyHealth.seconds(copy)
                        for (code in recordingCodes(copy, SourceCopyHealth)) {
                            val key = "${copy.sourceId}\u0000$code"
                            val first = firsts[key]
                            if (first == null) {
                                firsts[key] = node to seconds
                                db.write("INSERT INTO health_tag (gen, key, firstNode, firstSeconds) VALUES (?, ?, ?, ?)", gen, key, node, seconds)
                            } else if (tagsClose(first.second, seconds) && sets.join(first.first, node)) {
                                sets.tag(first.first)
                                sets.tag(node)
                            }
                        }
                    }
                }
                paged()
                if (rows.size < page) break
                rows.last().second.let { after = Triple(it.sourceId, it.seq, rows.last().first) }
            }
            // The titles: each bucket of one place and one match key, in
            // order of length, two pages at a time.
            var bucket = "" to ""
            while (true) {
                val buckets = db.read(
                    "SELECT sourceId, matchKey FROM source_track WHERE (sourceId, matchKey) > (?, ?) AND mergedId != '' GROUP BY sourceId, matchKey " +
                        "HAVING COUNT(*) > 1 ORDER BY sourceId, matchKey LIMIT ?",
                    bucket.first, bucket.second, page,
                ) { it.getString(0) to it.getString(1) }
                for ((sourceId, matchKey) in buckets) if (titleReadable(matchKey)) joinBucket(sets, sourceId, matchKey)
                if (buckets.size < page) break
                bucket = buckets.last()
            }
            members(sets)
            groups()
        }

        // One bucket's pairs: each page with itself, then with the pages
        // after it while their lengths can still be close.
        private suspend fun joinBucket(sets: NodeSets, sourceId: String, matchKey: String) {
            // Lengths can be below zero, read as unknown like 0.
            var outerAfter = Long.MIN_VALUE to Long.MIN_VALUE
            while (true) {
                val outer = bucketPage(sourceId, matchKey, outerAfter)
                if (outer.isEmpty()) return
                val joins = ArrayList<Pair<Long, Long>>()
                val local = LocalSets()
                pairs(outer, outer, local, joins, within = true)
                val anyUnknown = outer.any { SourceCopyHealth.seconds(it.second) <= 0 }
                val longest = outer.maxOf { SourceCopyHealth.seconds(it.second) }
                var innerAfter = outer.last().let { it.second.durationMs to it.first }
                while (true) {
                    val inner = bucketPage(sourceId, matchKey, innerAfter)
                    if (inner.isEmpty()) break
                    if (!anyUnknown && SourceCopyHealth.seconds(inner.first().second) - longest > SAME_LENGTH_SECONDS) break
                    pairs(outer, inner, local, joins, within = false)
                    if (inner.size < page) break
                    innerAfter = inner.last().let { it.second.durationMs to it.first }
                }
                if (joins.isNotEmpty()) write { joins.forEach { (a, b) -> sets.join(a, b) } }
                if (outer.size < page) return
                outerAfter = outer.last().let { it.second.durationMs to it.first }
            }
        }

        private fun pairs(a: List<Pair<Long, SourceTrackEntity>>, b: List<Pair<Long, SourceTrackEntity>>, local: LocalSets, joins: MutableList<Pair<Long, Long>>, within: Boolean) {
            for (i in a.indices) {
                val (nodeA, copyA) = a[i]
                val versionsA = albumVersions(copyA, SourceCopyHealth)
                for (j in (if (within) i + 1 else 0) until b.size) {
                    val (nodeB, copyB) = b[j]
                    if (local.same(nodeA, nodeB)) continue
                    if (sameByTitle(copyA, copyB, SourceCopyHealth, versionsA = versionsA)) {
                        local.join(nodeA, nodeB)
                        joins += nodeA to nodeB
                    }
                }
            }
        }

        private fun bucketPage(sourceId: String, matchKey: String, after: Pair<Long, Long>): List<Pair<Long, SourceTrackEntity>> = copyRows(
            "SELECT rowid AS node, * FROM source_track WHERE sourceId = ? AND matchKey = ? AND (durationMs, rowid) > (?, ?) AND mergedId != '' " +
                "ORDER BY durationMs, rowid LIMIT ?",
            sourceId, matchKey, after.first, after.second, page,
        )

        private fun copyPage(after: Triple<String, Long, Long>): List<Pair<Long, SourceTrackEntity>> = copyRows(
            "SELECT rowid AS node, * FROM source_track WHERE (sourceId, seq, rowid) > (?, ?, ?) AND mergedId != '' ORDER BY sourceId, seq, rowid LIMIT ?",
            after.first, after.second, after.third, page,
        )

        // Each copy in a set of two or more: its set, how it sounds, its
        // tags, its library song; and a set's root marked when any of its
        // copies was joined by its tags.
        private suspend fun members(sets: NodeSets) {
            var after = -1L
            while (true) {
                val nodes = db.read("SELECT node, tagged FROM health_node WHERE gen = ? AND node > ? ORDER BY node LIMIT ?", gen, after, page) {
                    it.getLong(0) to (it.getInt(1) == 1)
                }
                if (nodes.isEmpty()) break
                val copies = copyRows("SELECT rowid AS node, * FROM source_track WHERE rowid IN (${marks(nodes.size)})", *nodes.map { it.first }.toTypedArray()).toMap()
                write {
                    for ((node, tagged) in nodes) {
                        val root = sets.root(node)
                        if (sets.size(root) < 2) continue
                        if (tagged) sets.tag(root)
                        val copy = copies[node] ?: continue
                        db.write(
                            "INSERT INTO health_member (owner, gen, root, sound, tags, copyId, trackId) VALUES (?, ?, ?, ?, ?, ?, ?)",
                            OWNER, gen, root, soundOf(copy, SourceCopyHealth), tagsOf(copy, SourceCopyHealth), copy.id, copy.mergedId,
                        )
                    }
                }
                paged()
                if (nodes.size < page) break
                after = nodes.last().first
            }
        }

        // Each set's line: its best copy's artist and title, its size, why
        // its copies are one and why its best is best, and how many library
        // songs it holds; then each song's sound from the last set that
        // holds it.
        private suspend fun groups() {
            var after = -1L
            while (true) {
                val roots = db.read("SELECT DISTINCT root FROM health_member WHERE owner = ? AND gen = ? AND root > ? ORDER BY root LIMIT ?", OWNER, gen, after, page) { it.getLong(0) }
                if (roots.isEmpty()) break
                val lines = roots.map { root -> groupOf(root) }
                write {
                    for (line in lines) {
                        db.write(
                            "INSERT INTO health_group (owner, gen, k1, k2, bestId, root, size, basis, reason, songs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            OWNER, gen, line.k1, line.k2, line.bestId, line.root, line.size, line.basis.name, line.reason.name, line.songs,
                        )
                    }
                }
                duplicates += lines.count { it.songs >= 2 }
                paged()
                if (roots.size < page) break
                after = roots.last()
            }
            // Each song's sound line, from the last set in report order that
            // holds it.
            var at: GroupKey? = null
            while (true) {
                val keys = groupPage(db, OWNER, gen, at, page)
                if (keys.isEmpty()) break
                write {
                    for (key in keys) {
                        val members = db.read(
                            "SELECT m.copyId FROM health_member m WHERE m.owner = ? AND m.gen = ? AND m.root = ? ORDER BY m.sound DESC, m.tags DESC, m.copyId",
                            OWNER, gen, key.root,
                        ) { it.getString(0) }
                        val copies = copyRows("SELECT rowid AS node, * FROM source_track WHERE id IN (${marks(members.size)})", *members.toTypedArray()).associate { it.second.id to it.second }
                        val known = db.read("SELECT id FROM track WHERE id IN (${marks(copies.size)})", *copies.values.map { it.mergedId }.toTypedArray()) { it.getString(0) }.toSet()
                        for (id in members) {
                            val copy = copies[id] ?: continue
                            if (copy.mergedId !in known) continue
                            db.write("INSERT OR REPLACE INTO health_quality (gen, trackId, text) VALUES (?, ?, ?)", gen, copy.mergedId, qualityText(copy, SourceCopyHealth))
                        }
                    }
                }
                if (keys.size < page) break
                at = keys.last()
            }
        }

        private fun groupOf(root: Long): GroupLine {
            val top = db.read(
                "SELECT copyId FROM health_member WHERE owner = ? AND gen = ? AND root = ? ORDER BY sound DESC, tags DESC, copyId LIMIT 2",
                OWNER, gen, root,
            ) { it.getString(0) }
            val copies = copyRows("SELECT rowid AS node, * FROM source_track WHERE id IN (${marks(top.size)})", *top.toTypedArray()).associate { it.second.id to it.second }
            val best = copies.getValue(top[0])
            val next = copies.getValue(top[1])
            val reason = when {
                soundOf(best, SourceCopyHealth) > soundOf(next, SourceCopyHealth) -> BestReason.Sound
                tagsOf(best, SourceCopyHealth) > tagsOf(next, SourceCopyHealth) -> BestReason.Tags
                else -> BestReason.None
            }
            val size = db.readLong("SELECT COUNT(*) FROM health_member WHERE owner = ? AND gen = ? AND root = ?", OWNER, gen, root).toInt()
            val songs = db.readLong(
                "SELECT COUNT(DISTINCT m.trackId) FROM health_member m WHERE m.owner = ? AND m.gen = ? AND m.root = ? AND EXISTS (SELECT 1 FROM track t WHERE t.id = m.trackId)",
                OWNER, gen, root,
            ).toInt()
            val tagged = db.readLong("SELECT tagged FROM health_node WHERE gen = ? AND node = ?", gen, root) == 1L
            return GroupLine(
                Utf16Key(healthSortKey(SourceCopyHealth.artist(best))), Utf16Key(healthSortKey(best.title)), best.id, root, size,
                if (tagged) DuplicateBasis.Tags else DuplicateBasis.TitleAndLength, reason, songs,
            )
        }

        // The split album check: each bucket of albums of one title, parts in
        // order of their first song, joined as the shared check joins them.
        suspend fun splitAlbums() {
            var after = Triple("", "", "")
            var setId = 0L
            var bucket: String? = null
            val parts = ArrayList<PartFacts>()
            suspend fun finish() {
                val title = bucket ?: return
                for (set in splitSets(title, parts)) splitSet(++setId, set.parts.map(parts::get), set.reasons)
                parts.clear()
            }
            while (true) {
                val rows = db.read(
                    "SELECT bucket, firstKey, firstId, albumId FROM health_part WHERE gen = ? AND (bucket, firstKey, firstId) > (?, ?, ?) " +
                        "ORDER BY bucket, firstKey, firstId LIMIT ?",
                    gen, after.first, after.second, after.third, page,
                ) { listOf(it.getString(0), it.getString(1), it.getString(2), it.getString(3)) }
                for ((key, _, _, albumId) in rows) {
                    if (key.isEmpty()) continue
                    if (key != bucket) {
                        finish()
                        bucket = key
                    }
                    partFacts(albumId)?.let(parts::add)
                }
                paged()
                if (rows.size < page) break
                after = rows.last().let { Triple(it[0], it[1], it[2]) }
            }
            finish()
            splitPlaces()
        }

        // Each split album's place in the check's lines, in report order: the
        // headings and songs of every album before it, so a line's place is
        // found by one seek. A page of albums a transaction.
        private suspend fun splitPlaces() {
            var lines = 0L
            var after: Triple<ByteArray, ByteArray, String>? = null
            while (true) {
                val from = after
                val sets = if (from == null) {
                    db.read(
                        "SELECT k1, k2, leadAlbum, setId FROM health_split WHERE owner = ? AND gen = ? ORDER BY k1, k2, leadAlbum LIMIT ?",
                        OWNER, gen, page,
                    ) { Triple(it.getBlob(0), it.getBlob(1), it.getString(2)) to it.getLong(3) }
                } else {
                    db.read(
                        "SELECT k1, k2, leadAlbum, setId FROM health_split WHERE owner = ? AND gen = ? AND (k1, k2, leadAlbum) > (?, ?, ?) " +
                            "ORDER BY k1, k2, leadAlbum LIMIT ?",
                        OWNER, gen, from.first, from.second, from.third, page,
                    ) { Triple(it.getBlob(0), it.getBlob(1), it.getString(2)) to it.getLong(3) }
                }
                if (sets.isEmpty()) break
                write {
                    for ((_, setId) in sets) {
                        db.write("UPDATE health_split SET linesBefore = ? WHERE owner = ? AND gen = ? AND setId = ?", lines, OWNER, gen, setId)
                        lines += 1 + db.readLong("SELECT COUNT(*) FROM health_split_song WHERE owner = ? AND gen = ? AND setId = ?", OWNER, gen, setId)
                    }
                }
                paged()
                if (sets.size < page) break
                after = sets.last().first
            }
        }

        // What the split check reads of one album: its album artist (the
        // album's own, else the artist most of its songs name), the artists
        // it names, its years and its release codes. Null for an album with
        // no artist to tell it by.
        private fun partFacts(albumId: String): PartFacts? {
            val shown = db.read("SELECT artist FROM album WHERE id = ?", albumId) { it.getString(0) }.firstOrNull()?.takeIf(String::isNotBlank)
            val artist = shown ?: db.read(
                "SELECT value FROM health_tally WHERE gen = ? AND albumId = ? ORDER BY n DESC, value LIMIT 1", gen, albumId,
            ) { it.getString(0) }.firstOrNull().orEmpty()
            val key = albumArtistKey(artist)
            if (key.isEmpty()) return null
            val codes = db.read("SELECT kind, code FROM health_code WHERE gen = ? AND albumId = ?", gen, albumId) { it.getInt(0) to it.getString(1) }
            val credits = HashMap<String, String?>()
            return PartFacts(
                id = albumId,
                size = albumSize(albumId),
                artist = artist,
                artistKey = key,
                albumArtists = albumArtistsNamed(listOfNotNull(shown), artist),
                credits = { credit -> credits.getOrPut(credit) { creditedBy(albumId, credit) } },
                years = codes.filter { it.first == YEAR }.mapTo(HashSet()) { it.second.toInt() },
                releases = codes.filter { it.first == RELEASE }.mapTo(HashSet()) { it.second },
                groups = codes.filter { it.first == GROUP }.mapTo(HashSet()) { it.second },
                barcodes = emptySet(),
                labels = emptyMap(),
            )
        }

        // The title of the album's first song, in library order, that
        // credits the artist with this key; null when none does.
        private fun creditedBy(albumId: String, key: String): String? {
            var found: TrackEntity? = null
            forAlbumNow(albumId) { songs ->
                val chosen = chosenCopies(songs.map { it.id })
                for (song in songs) {
                    val credits = (listOfNotNull(song.artist.ifBlank { null }) + splitLines(chosen[song.id]?.artists.orEmpty())).distinct()
                    if (credits.none { credit -> creditKeys(credit).any { it.first == key } }) continue
                    val best = found
                    if (best == null || libraryOrder(song, best) < 0) found = song
                }
            }
            return found?.title
        }

        // One set of split album parts: its songs, part by part in album
        // order, and its line.
        private suspend fun splitSet(setId: Long, parts: List<PartFacts>, reasons: List<SplitReason>) {
            parts.forEachIndexed { part, facts ->
                walkAlbum(facts.id) { songs ->
                    write {
                        songs.forEach { song ->
                            db.write(
                                "INSERT INTO health_split_song (owner, gen, setId, part, disc, trackKey, titleKey, trackId) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                                OWNER, gen, setId, part, song.discNo ?: 0, song.trackNo?.takeIf { it > 0 } ?: Int.MAX_VALUE, Utf16Key(healthSortKey(song.title)), song.id,
                            )
                        }
                    }
                }
            }
            val firsts = parts.indices.map { part ->
                db.read(
                    "SELECT t.album FROM health_split_song s CROSS JOIN track t ON t.id = s.trackId WHERE s.owner = ? AND s.gen = ? AND s.setId = ? AND s.part = ? " +
                        "ORDER BY s.disc, s.trackKey, s.titleKey, s.trackId LIMIT 1",
                    OWNER, gen, setId, part,
                ) { it.getString(0) }.firstOrNull()?.ifBlank { null }.orEmpty()
            }
            val title = firsts[0]
            val artist = parts[0].artist
            val differences = ArrayList<AlbumDifferenceValues>()
            fun check(kind: AlbumDifference, values: List<String>) {
                if (values.distinct().size > 1) differences += AlbumDifferenceValues(kind, values)
            }
            check(AlbumDifference.Title, firsts)
            check(AlbumDifference.AlbumArtist, parts.map { p -> shownArtist(p.id).orEmpty() })
            check(AlbumDifference.Year, parts.map { p -> p.years.sorted().joinToString(", ") })
            if (differences.isEmpty()) differences += AlbumDifferenceValues(AlbumDifference.Other, emptyList())
            write {
                db.write(
                    "INSERT INTO health_split (owner, gen, k1, k2, leadAlbum, setId, title, artist, reasons, differences) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    OWNER, gen, Utf16Key(healthSortKey(artist)), Utf16Key(healthSortKey(title)), parts[0].id, setId, title, artist,
                    encodeReasons(reasons), encodeDifferences(differences),
                )
            }
            splits++
        }

        // The album's own artist, when it names one.
        private fun shownArtist(albumId: String): String? =
            db.read("SELECT artist FROM album WHERE id = ?", albumId) { it.getString(0) }.firstOrNull()?.takeIf(String::isNotBlank)

        // The last transaction: the new report becomes what readers read.
        suspend fun publish() {
            write {
                db.write(
                    "INSERT OR REPLACE INTO health_run (id, gen, building, duplicates, splitAlbums, noLength, noTrackNumber, noAlbumArtist, noCover, noYear, noGenre, checked, swapCount) " +
                        "VALUES (0, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    gen, duplicates, splits, noLength, missing[HealthTag.TrackNumber.ordinal], missing[HealthTag.AlbumArtist.ordinal],
                    missing[HealthTag.Cover.ordinal], missing[HealthTag.Year.ordinal], missing[HealthTag.Genre.ordinal], checked, baseline,
                )
            }
            owners.publish(WorkOwners.REPORT, gen).forEach { deleteGen(it) }
            publishedHere = true
        }
    }

    // Before this launch's first report: the rows an older launch's builds
    // left in the tables without an owner go, health_run first, a page a
    // transaction. Their owned rows go by the launch's sweep.
    private suspend fun clearLeft() = withContext(WriteLabels.of(HEALTH_CLEAR)) {
        db.withTransaction { db.write("DELETE FROM health_run") }
        for (table in HEALTH_UNOWNED_TABLES - "health_run") {
            while (true) {
                val gone = db.withTransaction { db.write("DELETE FROM $table WHERE rowid IN (SELECT rowid FROM $table LIMIT $HEALTH_CLEAR_ROWS)") }
                if (gone < HEALTH_CLEAR_ROWS) break
            }
        }
    }

    // A set of copies' line, as health_group keeps it.
    private class GroupLine(
        val k1: ByteArray,
        val k2: ByteArray,
        val bestId: String,
        val root: Long,
        val size: Int,
        val basis: DuplicateBasis,
        val reason: BestReason,
        val songs: Int,
    )

    // Sets of copies kept in health_node, by copy (its row in source_track),
    // joined by size with the paths halved as they are walked; the most
    // used `cache` nodes are kept in memory too. A copy with no row is a set
    // of its own. Every change is written at once, inside the caller's
    // transaction.
    private inner class NodeSets(private val gen: Long) {
        private inner class Node(var parent: Long, var size: Int, var tagged: Boolean)

        private val known = object : LinkedHashMap<Long, Node>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Node>?) = size > cache
        }

        private fun node(id: Long): Node = known[id] ?: (
            db.read("SELECT parent, size, tagged FROM health_node WHERE gen = ? AND node = ?", gen, id) { Node(it.getLong(0), it.getInt(1), it.getInt(2) == 1) }
                .firstOrNull() ?: Node(id, 1, false)
            ).also { known[id] = it }

        private fun save(id: Long, node: Node) {
            known[id] = node
            db.write("INSERT OR REPLACE INTO health_node (gen, node, parent, size, tagged) VALUES (?, ?, ?, ?, ?)", gen, id, node.parent, node.size, node.tagged)
        }

        fun root(id: Long): Long {
            var at = id
            while (true) {
                val here = node(at)
                if (here.parent == at) return at
                val up = node(here.parent)
                if (up.parent != here.parent) {
                    here.parent = up.parent
                    save(at, here)
                }
                at = here.parent
            }
        }

        fun size(root: Long): Int = node(root).size

        // Joins the two sets; false when they were one already.
        fun join(a: Long, b: Long): Boolean {
            val x = root(a)
            val y = root(b)
            if (x == y) return false
            val nx = node(x)
            val ny = node(y)
            if (nx.size < ny.size) {
                nx.parent = y
                ny.size += nx.size
            } else {
                ny.parent = x
                nx.size += ny.size
            }
            save(x, nx)
            save(y, ny)
            return true
        }

        fun tag(id: Long) {
            val n = node(id)
            if (!n.tagged) {
                n.tagged = true
                save(id, n)
            }
        }
    }

    // Copies' rows, each with its row number.
    private fun copyRows(sql: String, vararg args: Any?): List<Pair<Long, SourceTrackEntity>> =
        reader.copyRows(SimpleSQLiteQuery(sql, args)).map { it.node to it.copy }

    // Each song's copy the checks read its credits and release from: of its
    // copies in (source, order), the last. A third of a chunk of songs at a
    // time, so a song's few copies keep each read within a chunk of rows.
    private fun chosenCopies(ids: List<String>): Map<String, SourceTrackEntity> {
        val chosen = HashMap<String, SourceTrackEntity>()
        ids.chunked(ID_CHUNK / 3).forEach { chunk ->
            copyRows("SELECT rowid AS node, * FROM source_track WHERE mergedId IN (${marks(chunk.size)})", *chunk.toTypedArray())
                .map { it.second }
                .sortedWith { a, b -> SqlOrder.text(a.sourceId, b.sourceId).takeIf { it != 0 } ?: a.seq.compareTo(b.seq) }
                .forEach { chosen[it.mergedId] = it }
        }
        return chosen
    }

    private fun albumArtists(ids: Set<String>): Map<String, String> {
        val artists = HashMap<String, String>()
        ids.chunked(ID_CHUNK).forEach { chunk ->
            db.read("SELECT id, artist FROM album WHERE id IN (${marks(chunk.size)})", *chunk.toTypedArray()) { it.getString(0) to it.getString(1) }
                .forEach { (id, artist) -> artists[id] = artist }
        }
        return artists
    }

    // An album's songs a page at a time, in the album's own index order.
    private suspend fun walkAlbum(albumId: String, each: suspend (List<TrackEntity>) -> Unit) {
        var after = Int.MIN_VALUE to ""
        while (true) {
            val songs = albumPage(albumId, after)
            if (songs.isEmpty()) return
            each(songs)
            if (songs.size < page) return
            after = songs.last().let { it.albumOrder to it.id }
        }
    }

    // The same on the calling thread, for the split check's questions.
    private fun forAlbumNow(albumId: String, each: (List<TrackEntity>) -> Unit) {
        var after = Int.MIN_VALUE to ""
        while (true) {
            val songs = albumPage(albumId, after)
            if (songs.isEmpty()) return
            each(songs)
            if (songs.size < page) return
            after = songs.last().let { it.albumOrder to it.id }
        }
    }

    private fun albumPage(albumId: String, after: Pair<Int, String>): List<TrackEntity> = reader.tracksNow(
        SimpleSQLiteQuery(
            "SELECT * FROM track WHERE albumId = ? AND (albumOrder, id) > (?, ?) ORDER BY albumOrder, id LIMIT ?",
            arrayOf<Any>(albumId, after.first, after.second, page),
        ),
    )

    // Deletes one generation of the report, a page a transaction: the
    // report's own rows, never a fill's copy of them, which keeps the
    // generation's number under its own owner.
    private suspend fun deleteGen(gen: Long) {
        for (table in listOf("health_song", "health_member", "health_group", "health_split", "health_split_song")) deletePages(table, gen, OWNER)
        deletePages("health_quality", gen)
        deleteScratch(gen)
    }

    // The rows only a build needs, once it ends.
    private suspend fun deleteScratch(gen: Long) {
        for (table in listOf("health_tag", "health_node", "health_part", "health_code", "health_tally")) deletePages(table, gen)
    }

    // A generation's rows of a table keyed by generation, or by `owner` and
    // generation.
    private suspend fun deletePages(table: String, gen: Long, owner: String? = null) {
        while (true) {
            val gone = db.withTransaction {
                if (owner == null) {
                    db.write("DELETE FROM $table WHERE rowid IN (SELECT rowid FROM $table WHERE gen = ? LIMIT 2000)", gen)
                } else {
                    db.write("DELETE FROM $table WHERE rowid IN (SELECT rowid FROM $table WHERE owner = ? AND gen = ? LIMIT 2000)", owner, gen)
                }
            }
            if (gone < 2000) return
        }
    }

    private fun needs(bytes: Long) {
        if (freeSpace.bytes() < bytes) throw NotEnoughSpace(bytes, SpaceFor.Health)
    }

    // Sets of nodes for one block of a bucket, in memory, to skip pairs
    // already joined there.
    private class LocalSets {
        private val parent = HashMap<Long, Long>()

        fun root(x: Long): Long {
            var at = x
            while (true) {
                val up = parent[at] ?: return at
                at = up
            }
        }

        fun same(a: Long, b: Long) = root(a) == root(b)

        fun join(a: Long, b: Long) {
            val x = root(a)
            val y = root(b)
            if (x != y) parent[x] = y
        }
    }

    companion object {
        // The report's owner in this launch.
        internal val OWNER: String get() = WorkOwners.REPORT
        private const val RELEASE = 0
        private const val GROUP = 1
        private const val YEAR = 2
    }
}

// Two songs in library order: sort key, then id, as the database orders
// them.
internal fun libraryOrder(a: TrackEntity, b: TrackEntity): Int = SqlOrder.text(a.sortKey, b.sortKey).takeIf { it != 0 } ?: SqlOrder.text(a.id, b.id)

// A set's place in report order.
internal class GroupKey(val k1: ByteArray, val k2: ByteArray, val bestId: String, val root: Long, val songs: Int)

// A page of sets in report order after `after`, from the top for null.
internal fun groupPage(db: CatalogDatabase, owner: String, gen: Long, after: GroupKey?, limit: Int): List<GroupKey> =
    if (after == null) {
        db.read("SELECT k1, k2, bestId, root, songs FROM health_group WHERE owner = ? AND gen = ? ORDER BY k1, k2, bestId LIMIT ?", owner, gen, limit) {
            GroupKey(it.getBlob(0), it.getBlob(1), it.getString(2), it.getLong(3), it.getInt(4))
        }
    } else {
        db.read(
            "SELECT k1, k2, bestId, root, songs FROM health_group WHERE owner = ? AND gen = ? AND (k1, k2, bestId) > (?, ?, ?) ORDER BY k1, k2, bestId LIMIT ?",
            owner, gen, after.k1, after.k2, after.bestId, limit,
        ) { GroupKey(it.getBlob(0), it.getBlob(1), it.getString(2), it.getLong(3), it.getInt(4)) }
    }

// Why split parts are one, and how they differ, as health_split keeps them.
internal fun encodeReasons(reasons: List<SplitReason>): String =
    reasons.joinToString("\u0002") { listOf(it.basis.name, it.artist, it.other, it.song, it.value).joinToString("\u0001") }

internal fun decodeReasons(text: String): List<SplitReason> = text.split('\u0002').filter(String::isNotEmpty).map { line ->
    val p = line.split('\u0001')
    SplitReason(SplitBasis.valueOf(p[0]), p[1], p[2], p[3], p[4])
}

internal fun encodeDifferences(differences: List<AlbumDifferenceValues>): String =
    differences.joinToString("\u0002") { (listOf(it.kind.name) + it.values).joinToString("\u0001") }

internal fun decodeDifferences(text: String): List<AlbumDifferenceValues> = text.split('\u0002').filter(String::isNotEmpty).map { line ->
    val p = line.split('\u0001')
    AlbumDifferenceValues(AlbumDifference.valueOf(p[0]), p.drop(1))
}
