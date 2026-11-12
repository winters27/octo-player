package app.winters.octo.ui.library.health

import android.database.Cursor
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.copiesInChunks
import app.winters.octo.catalog.read
import app.winters.octo.data.AlbumUpgrade
import app.winters.octo.data.StepSource
import app.winters.octo.data.Upgrades
import app.winters.octo.data.onServer
import app.winters.octo.data.serverSongIds
import app.winters.octo.health.FixStep
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.HealthTag
import app.winters.octo.health.TagChange
import app.winters.octo.health.copyName
import app.winters.octo.health.heading
import app.winters.octo.health.words
import app.winters.octo.subsonic.SongTag
import app.winters.octo.ui.paging.RowKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

// How many lines a Fix all preview lists before the rest is only counted.
const val PREVIEW_LINES = 100

// How many songs one step of a walk of a check of songs reads.
private const val SONG_PAGE = 200

// How many songs of one album a fill's walk of the album reads at a time,
// and how many albums' values it keeps.
private const val ALBUM_PAGE = 500
private const val ALBUMS_KEPT = 64

// One thing a check's Fix all fixes: its line in the preview, and the set
// of copies or split album it checks again before its steps go (none for a
// song), with those steps.
internal class FixItem(val line: Pair<String, String?>, val set: FixSet)

// What a check's Fix all would do over one report, for one server (its
// source) and whether it writes tags: how many sets, albums or songs it
// fixes (its preview's lines) and how many steps it sends.
data class FixAllCount(val gen: Long, val source: String?, val edit: Boolean, val count: Int, val total: Int)

// A check's fixes in one report, in report order, a page at a time: the
// sets of copies on the server in use (`source`), each keeping its best
// copy, filling its blanks when the server can write tags (`edit`), and
// removing the rest; the split albums the server can join; a cover looked
// for each song with none the server has; a missing year or genre filled
// where the rest of the song's album agrees. Each set is read as its
// copies are now, each album as its songs are.
internal class FixWalk(
    private val db: CatalogDatabase,
    build: HealthBuild,
    private val check: HealthCheck,
    private val gen: Long,
    private val source: String?,
    private val edit: Boolean,
    private val page: Int = FIX_ALL_RECHECK_PAGE,
) {
    private var ended = false
    private var groupAfter: GroupKey? = null
    private var splitAfter: Triple<ByteArray, ByteArray, String>? = null
    private var songAfter: RowKey? = null
    private val lines = HealthLoader(build, db, check, PLAN_OWNER, pinned = gen)
    private val albums = AlbumValues(db, check.tag)

    // The next page's fixes, which may be none; null after the last page.
    suspend fun next(): List<FixItem>? = withContext(Dispatchers.IO) {
        if (ended) return@withContext null
        when (check) {
            HealthCheck.Duplicates -> copies()
            HealthCheck.SplitAlbums -> splits()
            HealthCheck.NoCover, HealthCheck.NoYear, HealthCheck.NoGenre, HealthCheck.NoAlbumArtist -> songs()
            HealthCheck.NoLength, HealthCheck.NoTrackNumber -> {
                ended = true
                null
            }
        }
    }

    private fun copies(): List<FixItem> {
        val groups = groupPage(db, HealthBuild.OWNER, gen, groupAfter, page)
        if (groups.size < page) ended = true
        groups.lastOrNull()?.let { groupAfter = it }
        return groups.filter { it.songs >= 2 }.mapNotNull { key ->
            val group = copySet(db, gen, key.root) ?: return@mapNotNull null
            val plan = duplicatePlan(group, source) ?: return@mapNotNull null
            val steps = plan.serverSteps(plan.fills, edit)
            val fills = steps.filterIsInstance<FixStep.Retag>().flatMap { it.tags.map { (tag, value) -> TagChange(tag, null, value).words() } }
            val removes = "Removes " + plan.remove.joinToString(", ") { copyName(it, ServerCopyHealth) } + "."
            val detail = if (fills.isEmpty()) removes else "$removes Fills in ${fills.joinToString(", ")}."
            FixItem("Keeps ${copyName(plan.keep, ServerCopyHealth)}" to detail, FixSet(group = group, steps = steps))
        }
    }

    // A page of split albums, read together: their songs, the songs' rows
    // and their copies, each in one statement (a chunk of ID_CHUNK songs).
    private suspend fun splits(): List<FixItem> {
        val after = splitAfter
        val head: (Cursor) -> Pair<Triple<ByteArray, ByteArray, String>, SplitHead> = {
            Triple(it.getBlob(0), it.getBlob(1), it.getString(2)) to SplitHead(it.getLong(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7))
        }
        val sets = if (after == null) {
            db.read(
                "SELECT k1, k2, leadAlbum, setId, title, artist, reasons, differences FROM health_split WHERE owner = ? AND gen = ? ORDER BY k1, k2, leadAlbum LIMIT ?",
                HealthBuild.OWNER, gen, page, row = head,
            )
        } else {
            db.read(
                "SELECT k1, k2, leadAlbum, setId, title, artist, reasons, differences FROM health_split WHERE owner = ? AND gen = ? " +
                    "AND (k1, k2, leadAlbum) > (?, ?, ?) ORDER BY k1, k2, leadAlbum LIMIT ?",
                HealthBuild.OWNER, gen, after.first, after.second, after.third, page, row = head,
            )
        }
        if (sets.size < page) ended = true
        sets.lastOrNull()?.let { splitAfter = it.first }
        if (source == null) return emptyList()
        val (albums, copies) = splitAlbumsOf(db, gen, sets.map { it.second })
        val ids = serverSongIds(copies, source)
        return albums.mapNotNull { album ->
            if (album == null) return@mapNotNull null
            val (join, steps) = joinPlanOf(album, TrackHealth(), ids) ?: return@mapNotNull null
            FixItem(album.heading() to join.words, FixSet(album = album, steps = steps))
        }
    }

    private suspend fun songs(): List<FixItem> {
        val page = lines.after(songAfter, SONG_PAGE)
        if (page.size < SONG_PAGE) ended = true
        page.lastOrNull()?.let { songAfter = it.key }
        if (source == null) return emptyList()
        val ids = page.map { it.row }
        val tracks = ids.distinct().chunked(ID_CHUNK).flatMap { db.dao().tracksByIdsUnordered(it) }.associateBy { it.id }
        val serverIds = serverSongIds(db.sourceDao().copiesInChunks(ids), source)
        return ids.mapNotNull { id ->
            val track = tracks[id] ?: return@mapNotNull null
            val step = if (check == HealthCheck.NoCover) {
                FixStep.AddCover(track.id, track.title)
            } else {
                val (tag, value) = albums.fillFor(track) ?: return@mapNotNull null
                FixStep.Retag(track.id, track.title, mapOf(tag to value))
            }
            val sent = listOf(step).onServer(serverIds).singleOrNull() ?: return@mapNotNull null
            val detail = (sent as? FixStep.Retag)?.tags?.map { (tag, value) -> TagChange(tag, null, value).words() }?.joinToString(", ")
            FixItem(sent.title to detail, FixSet(steps = listOf(sent)))
        }
    }

    private companion object {
        // The owner a plan's song reads name: the report is read, never copied.
        const val PLAN_OWNER = "plan"
    }
}

// The values of one tag an album's songs have, each with how many songs
// have it, for filling a song's missing tag where the rest of its album
// agrees; read a page of the album at a time, the most recent albums kept.
private class AlbumValues(private val db: CatalogDatabase, private val tag: HealthTag?) {
    private val kept = object : LinkedHashMap<String, Map<String, Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Int>>?) = size > ALBUMS_KEPT
    }

    // The tag's name and the value the song's album agrees on, leaving the
    // song itself out; null when the rest of the album has none or several.
    // The phone never finds an album artist missing, so it fills none.
    fun fillFor(song: TrackEntity): Pair<String, String>? {
        val name = when (tag) {
            HealthTag.Year -> SongTag.YEAR
            HealthTag.Genre -> SongTag.GENRE
            else -> return null
        }
        if (song.albumId.isEmpty()) return null
        val counts = kept.getOrPut(song.albumId) { read(song.albumId) }.toMutableMap()
        valueOf(song.year, song.genre)?.let { own -> counts[own]?.let { counts[own] = it - 1 } }
        return counts.filterValues { it > 0 }.keys.singleOrNull()?.let { name to it }
    }

    // A song's value of the tag as the server names it, or null for none.
    private fun valueOf(year: Int?, genre: String): String? = when (tag) {
        HealthTag.Year -> year?.takeIf { it > 0 }?.toString()
        HealthTag.Genre -> genre.takeIf(String::isNotBlank)
        else -> null
    }

    private fun read(albumId: String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        var after = Long.MIN_VALUE to ""
        while (true) {
            val page = db.read(
                "SELECT albumOrder, id, year, genre FROM track WHERE albumId = ? AND (albumOrder, id) > (?, ?) ORDER BY albumOrder, id LIMIT ?",
                albumId, after.first, after.second, ALBUM_PAGE,
            ) { c -> Triple(c.getLong(0), c.getString(1), valueOf(if (c.isNull(2)) null else c.getInt(2), c.getString(3))) }
            page.forEach { (_, _, value) -> if (value != null) counts[value] = (counts[value] ?: 0) + 1 }
            if (page.size < ALBUM_PAGE) return counts
            after = page.last().let { it.first to it.second }
        }
    }
}

// A check's Fix all over one published report, which stays pinned until
// the plan is sent and its run is over, or put aside: how many lines it
// has and steps it sends, and the first page of its lines. Its steps are
// made again from the report a page at a time as they are sent.
class FixAllPlan internal constructor(
    val check: HealthCheck,
    val count: Int,
    val total: Int,
    val lines: List<Pair<String, String?>>,
    private val walk: () -> FixWalk,
    private val release: () -> Unit,
) {
    // Open, sent, or put aside.
    private val state = AtomicInteger(OPEN)

    // The plan's steps for one run, each page of sets checked again by
    // `same` first; the run's end lets the report go.
    fun steps(same: suspend (List<FixSet>) -> List<Boolean>): StepSource {
        if (!state.compareAndSet(OPEN, SENT)) error("a plan is sent once")
        val pages = walk()
        return FixAllSteps(total, { pages.next()?.map { it.set } }, same, done = { release() })
    }

    // Not sent after all: the report goes, unless the plan was sent.
    fun putAside() {
        if (state.compareAndSet(OPEN, PUT_ASIDE)) release()
    }

    private companion object {
        const val OPEN = 0
        const val SENT = 1
        const val PUT_ASIDE = 2
    }
}

// Library health's Fix all, planned page by page from the report's tables
// so no list of every finding or step is held: its counts for a check's
// button, its preview, and the steps it sends. Also the songs with no
// length a FLAC could replace, and the songs one bulk look-up takes.
@Singleton
class FixAllPlans @Inject constructor(private val db: CatalogDatabase, private val build: HealthBuild) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // What a check's Fix all would do over the report published now, for
    // its button; null before the first report.
    suspend fun count(check: HealthCheck, source: String?, edit: Boolean): FixAllCount? = withContext(Dispatchers.IO) {
        val gen = build.pin() ?: return@withContext null
        try {
            counted(check, gen, source, edit)
        } finally {
            withContext(NonCancellable) { build.release(gen) }
        }
    }

    // A check's Fix all over the report published now, pinned until it is
    // sent or put aside: its counts (`counted` when they are that
    // report's) and its first page of lines. Null before the first report.
    suspend fun open(check: HealthCheck, source: String?, edit: Boolean, counted: FixAllCount? = null): FixAllPlan? = withContext(Dispatchers.IO) {
        val gen = build.pin() ?: return@withContext null
        try {
            val counts = counted?.takeIf { it.gen == gen && it.source == source && it.edit == edit } ?: counted(check, gen, source, edit)
            val lines = ArrayList<Pair<String, String?>>()
            val walk = FixWalk(db, build, check, gen, source, edit)
            while (lines.size < PREVIEW_LINES) lines += (walk.next() ?: break).map { it.line }
            FixAllPlan(check, counts.count, counts.total, lines.take(PREVIEW_LINES), { FixWalk(db, build, check, gen, source, edit) }) {
                scope.launch { build.release(gen) }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { build.release(gen) }
            throw e
        }
    }

    private suspend fun counted(check: HealthCheck, gen: Long, source: String?, edit: Boolean): FixAllCount {
        val walk = FixWalk(db, build, check, gen, source, edit)
        var count = 0
        var total = 0
        while (true) {
            val items = walk.next() ?: break
            count += items.size
            total += items.sumOf { it.set.steps.size }
        }
        return FixAllCount(gen, source, edit, count, total)
    }

    // The songs with no length a FLAC could replace, read a page at a time
    // in the check's order: how many, and the first PREVIEW_LINES to ask.
    suspend fun upgradable(upgrades: Upgrades): AlbumUpgrade = upgrades.upgradableIn(PREVIEW_LINES) { each ->
        val songs = build.loader(HealthCheck.NoLength, PLAN_OWNER)
        var after: RowKey? = null
        while (true) {
            val page = songs.after(after, SONG_PAGE)
            if (page.isNotEmpty()) each(page.map { it.row })
            if (page.size < SONG_PAGE) break
            after = page.last().key
        }
    }

    // The songs one bulk look-up of a check takes: the first the server in
    // use has, at most LOOKUP_BATCH of them, each once with its id there.
    suspend fun lookUpBatch(check: HealthCheck, source: String?): List<Pair<TrackEntity, String>> = withContext(Dispatchers.IO) {
        if (source == null) return@withContext emptyList()
        val songs = build.loader(check, PLAN_OWNER)
        val batch = ArrayList<Pair<TrackEntity, String>>()
        val taken = HashSet<String>()
        var after: RowKey? = null
        while (batch.size < LOOKUP_BATCH) {
            val page = songs.after(after, SONG_PAGE)
            val ids = page.map { it.row }
            val tracks = ids.distinct().chunked(ID_CHUNK).flatMap { db.dao().tracksByIdsUnordered(it) }.associateBy { it.id }
            val serverIds = serverSongIds(db.sourceDao().copiesInChunks(ids), source)
            for (id in ids) {
                val track = tracks[id] ?: continue
                val serverId = serverIds[id] ?: continue
                if (taken.add(serverId)) batch += track to serverId
                if (batch.size == LOOKUP_BATCH) break
            }
            if (page.size < SONG_PAGE) break
            after = page.last().key
        }
        batch
    }

    private companion object {
        const val PLAN_OWNER = "plan"
    }
}
