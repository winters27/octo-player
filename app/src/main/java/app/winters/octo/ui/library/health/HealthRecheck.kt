package app.winters.octo.ui.library.health

import androidx.sqlite.db.SimpleSQLiteQuery
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.followAll
import app.winters.octo.catalog.marks
import app.winters.octo.catalog.read
import app.winters.octo.data.NumberedStep
import app.winters.octo.data.StepSource
import app.winters.octo.health.DuplicateGroup
import app.winters.octo.health.SplitAlbum
import app.winters.octo.health.findDuplicates
import app.winters.octo.health.findSplitAlbums
import app.winters.octo.health.heading
import app.winters.octo.ui.paging.SqlOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// How many sets Fix all checks again in one hold of the merge lock.
const val FIX_ALL_RECHECK_PAGE = 50

// What a check found again for a fix: the same set as shown, as it is now
// (`now`, the copies or songs to fix), or another (`now`, what the check
// finds now, or null when it finds none), which a fix leaves alone.
sealed interface Recheck<out T> {
    val now: T?

    data class Same<T>(override val now: T) : Recheck<T>

    data class Changed<T>(override val now: T?) : Recheck<T>
}

// A fix made from a report checks its set again before it removes or joins
// anything: a report built across a swap, or one a later swap made stale,
// can pair copies the library no longer pairs. The set's songs are read
// again, each followed to the song it is now, and the same check runs on
// them alone; one set at a time inside a Read hold of the merge lock (a
// fix from a tap), a page of sets inside a Page hold (Fix all), so each
// reads one state of the library. Only reads; the steps run after.
//
// A set the check no longer finds, or finds otherwise in Fix all, moves
// `gone`, so the pages showing the report check the library again at once
// and its line goes.
@Singleton
class HealthRecheck @Inject constructor(private val db: CatalogDatabase, private val merge: CatalogMerge) {
    private val _gone = MutableStateFlow(0)

    // Moves each time a fix finds a set of the report gone.
    val gone: StateFlow<Int> = _gone

    suspend fun duplicates(shown: DuplicateGroup<SourceTrackEntity>): Recheck<DuplicateGroup<SourceTrackEntity>> =
        withContext(Dispatchers.IO) { merge.betweenRebuilds(HoldKind.Read) { duplicatesNow(shown) } }

    suspend fun split(shown: SplitAlbum<TrackEntity>): Recheck<SplitAlbum<TrackEntity>> =
        withContext(Dispatchers.IO) { merge.betweenRebuilds(HoldKind.Read) { splitNow(shown) } }

    // Fix all's sets, a page at a time, each answered as `duplicates` does.
    suspend fun duplicatePage(shown: List<DuplicateGroup<SourceTrackEntity>>): List<Recheck<DuplicateGroup<SourceTrackEntity>>> =
        withContext(Dispatchers.IO) { merge.betweenRebuilds(HoldKind.Page) { shown.map { duplicatesNow(it) } } }

    suspend fun splitPage(shown: List<SplitAlbum<TrackEntity>>): List<Recheck<SplitAlbum<TrackEntity>>> =
        withContext(Dispatchers.IO) { merge.betweenRebuilds(HoldKind.Page) { shown.map { splitNow(it) } } }

    // Whether each of a page of Fix all's sets is still as shown, all read
    // in one Page hold.
    suspend fun samePage(sets: List<FixSet>): List<Boolean> {
        val same = withContext(Dispatchers.IO) {
            merge.betweenRebuilds(HoldKind.Page) {
                sets.map { set ->
                    val group = set.group
                    val album = set.album
                    when {
                        group != null -> duplicatesNow(group) is Recheck.Same
                        album != null -> splitNow(album) is Recheck.Same
                        else -> true
                    }
                }
            }
        }
        if (false in same) _gone.update { it + 1 }
        return same
    }

    // The window a Fix tap on a set opens: the set as the check finds it
    // now, keeping `keep` when it is still there; null when the check no
    // longer finds it.
    suspend fun fixSheet(title: String, shown: DuplicateGroup<SourceTrackEntity>, keep: SourceTrackEntity?): HealthSheet.Duplicate? {
        val found = duplicates(shown)
        val now = found.now ?: return gone()
        val heading = if (found is Recheck.Same) title else now.heading(SourceCopyHealth)
        return HealthSheet.Duplicate(heading, now, keep?.let { k -> now.copies.firstOrNull { it.id == k.id } })
    }

    // The window a Join tap on a split album opens: the album as the check
    // finds it now, with the steps that join it, each song by its id on the
    // signed-in server (`serverIds`); null when the check no longer finds
    // it or the server has nothing of it to move.
    suspend fun joinSheet(title: String, shown: SplitAlbum<TrackEntity>, serverIds: suspend (List<String>) -> Map<String, String>): HealthSheet.Join? {
        val found = split(shown)
        val now = found.now ?: return gone()
        val ids = serverIds(now.parts.flatMap { part -> part.songs.map { it.id } })
        val (join, steps) = joinPlanOf(now, TrackHealth(), ids) ?: return gone()
        return HealthSheet.Join(if (found is Recheck.Same) title else now.heading(), join.words, steps)
    }

    private fun <T> gone(): T? {
        _gone.update { it + 1 }
        return null
    }

    // A set a page showed that its report no longer holds as shown: the
    // report is built again, so its line goes.
    fun missing() {
        _gone.update { it + 1 }
    }

    // The copies of the set's songs in its place, followed, through the
    // duplicate check, read as the phone lists sets: those whose copies
    // stand for two songs or more. The set holding the shown best copy, or
    // else any shown copy.
    private fun duplicatesNow(shown: DuplicateGroup<SourceTrackEntity>): Recheck<DuplicateGroup<SourceTrackEntity>> {
        val place = shown.best.sourceId
        val songs = db.followAll(shown.copies.map { it.mergedId }.distinct()).values.filterNotNull().distinct()
        val copies = copiesOf(songs).filter { it.sourceId == place && it.mergedId.isNotEmpty() }
        val groups = findDuplicates(copies, SourceCopyHealth).filter { g -> g.copies.map { it.mergedId }.distinct().size >= 2 }
        val shownIds = shown.copies.map { it.id }.toSet()
        val now = groups.firstOrNull { g -> g.copies.any { it.id == shown.best.id } } ?: groups.firstOrNull { g -> g.copies.any { it.id in shownIds } }
        return if (now != null && now.copies.map { it.id }.toSet() == shownIds) Recheck.Same(now) else Recheck.Changed(now)
    }

    // The parts' songs, followed, with their albums and copies, through the
    // split album check: the album holding the shown lead part.
    private suspend fun splitNow(shown: SplitAlbum<TrackEntity>): Recheck<SplitAlbum<TrackEntity>> {
        val ids = db.followAll(shown.parts.flatMap { part -> part.songs.map { it.id } }).values.filterNotNull().distinct()
        val songs = tracksOf(ids).sortedWith { a, b -> libraryOrder(a, b) }
        val albums = albumArtists(songs.map { it.albumId }.toSet())
        val copies = copiesOf(songs.map { it.id }).filter { it.mergedId.isNotEmpty() }
            .sortedWith { a, b -> SqlOrder.text(a.sourceId, b.sourceId).takeIf { it != 0 } ?: a.seq.compareTo(b.seq) }
            .associateBy { it.mergedId }
        val found = findSplitAlbums(songs, TrackHealth(albums, copies))
        val lead = shown.parts[0].albumId
        val now = found.firstOrNull { album -> album.parts.any { it.albumId == lead } } ?: found.firstOrNull()
        fun shape(album: SplitAlbum<TrackEntity>) = album.parts.map { part -> part.albumId to part.songs.map { it.id } }
        return if (now != null && shape(now) == shape(shown)) Recheck.Same(now) else Recheck.Changed(now)
    }

    private fun copiesOf(trackIds: List<String>): List<SourceTrackEntity> {
        val copies = ArrayList<SourceTrackEntity>()
        trackIds.chunked(ID_CHUNK).forEach { chunk ->
            copies += db.readerDao().copyRows(SimpleSQLiteQuery("SELECT rowid AS node, * FROM source_track WHERE mergedId IN (${marks(chunk.size)})", chunk.toTypedArray()))
                .map { it.copy }
        }
        return copies
    }

    private suspend fun tracksOf(ids: List<String>): List<TrackEntity> {
        val tracks = ArrayList<TrackEntity>()
        ids.chunked(ID_CHUNK).forEach { chunk -> tracks += db.dao().tracksByIdsUnordered(chunk) }
        return tracks
    }

    private fun albumArtists(ids: Set<String>): Map<String, String> {
        val artists = HashMap<String, String>()
        ids.chunked(ID_CHUNK).forEach { chunk ->
            db.read("SELECT id, artist FROM album WHERE id IN (${marks(chunk.size)})", *chunk.toTypedArray()) { it.getString(0) to it.getString(1) }
                .forEach { (id, artist) -> artists[id] = artist }
        }
        return artists
    }
}

// Fix all's steps, a page of sets, albums or songs at a time (`pages`
// hands the next, null after the last): a page holding sets of copies or
// split albums is checked again (`same`) just before its steps are sent,
// and a set the check finds otherwise now is left out, its steps with it.
// Pages are asked for in order, each after the last step handed out, and
// steps are numbered as they are handed out. Its run over, `done` lets go
// of what the pages hold.
internal class FixAllSteps(
    override val total: Int,
    private val pages: suspend () -> List<FixSet>?,
    private val same: suspend (List<FixSet>) -> List<Boolean>,
    private val done: suspend () -> Unit = {},
) : StepSource {
    // A list of sets, `page` at a time.
    constructor(sets: List<FixSet>, same: suspend (List<FixSet>) -> List<Boolean>, page: Int = FIX_ALL_RECHECK_PAGE) :
        this(sets.sumOf { it.steps.size }, inPages(sets, page), same)

    private var ord = 0L

    override suspend fun next(after: Long?): List<NumberedStep> {
        while (true) {
            val sets = pages() ?: return emptyList()
            val kept = if (sets.any { it.group != null || it.album != null }) same(sets) else sets.map { true }
            val steps = sets.filterIndexed { i, _ -> kept[i] }.flatMap { it.steps }.map { NumberedStep(ord++, it) }
            if (steps.isNotEmpty()) return steps
        }
    }

    override suspend fun close() = done()

    private companion object {
        fun inPages(sets: List<FixSet>, page: Int): suspend () -> List<FixSet>? {
            val chunks = sets.chunked(page).iterator()
            return { if (chunks.hasNext()) chunks.next() else null }
        }
    }
}
