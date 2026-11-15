package app.winters.octo.ui.playlist

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.PinnedSet
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SongToAdd
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.PlaylistRowView
import app.winters.octo.catalog.rawQuery
import app.winters.octo.playback.ListSpec
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.paging.Retiring
import app.winters.octo.ui.paging.SqlValue
import app.winters.octo.ui.paging.marks
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Songs to add to a playlist: a few named by id (a song, a selection, the
// queue), or a whole list named by what it is (an album, an artist, a
// playlist), walked a page at a time.
sealed interface AddSource {
    data class Ids(val ids: List<String>) : AddSource

    data class Spec(val spec: ListSpec) : AddSource
}

// How many songs one chunk of a count reads.
private const val CHUNK = 900

// The song a list's row adds to a playlist: a library song, or with
// `finds` a song found online (a playlist's row of one, or an album's); none
// for anything else.
internal fun addableRow(row: Any?, finds: Boolean): TrackEntity? = when (row) {
    is TrackEntity -> row.takeIf { finds || !isFind(it.id) }
    is PlaylistRowView -> row.track.takeIf { row.inLibrary || (finds && isFind(it.id)) }
    else -> null
}

// A pinned set's distinct library songs in id order from just past
// `after`, as the set stood when it was pinned: its untouched rows and each
// edited row's first log row after the pin, merged by song id as they come
// off their indexes; a song in several rows comes several times and is
// counted once by its reader.
private const val SET_SONGS_SQL = """
    SELECT trackId FROM (
        SELECT i.trackId AS trackId FROM playlist_item i
        WHERE i.playlistId = ? AND i.itemSet = ? AND i.itemKey < ? AND i.trackId > ?
          AND NOT EXISTS (SELECT 1 FROM playlist_edit_log f WHERE f.playlistId = ? AND f.itemSet = ? AND f.itemKey = i.itemKey AND f.seq > ?)
        UNION ALL
        SELECT e.trackId FROM playlist_edit_log e
        WHERE e.playlistId = ? AND e.itemSet = ? AND e.itemKey < ? AND e.trackId > ? AND e.op IN (0, 1)
          AND e.seq = (SELECT MIN(f.seq) FROM playlist_edit_log f WHERE f.playlistId = ? AND f.itemSet = ? AND f.itemKey = e.itemKey AND f.seq > ?)
    ) s WHERE EXISTS (SELECT 1 FROM track t WHERE t.id = s.trackId) ORDER BY trackId LIMIT ?
"""

// The same set's songs among `chunk`, each once: how many, or which.
private fun setHoldsSql(n: Int, which: Boolean = false) = """
    SELECT ${if (which) "DISTINCT trackId" else "COUNT(DISTINCT trackId)"} FROM (
        SELECT i.trackId AS trackId FROM playlist_item i
        WHERE i.playlistId = ? AND i.itemSet = ? AND i.itemKey < ? AND i.trackId IN ${marks(n)}
          AND NOT EXISTS (SELECT 1 FROM playlist_edit_log f WHERE f.playlistId = ? AND f.itemSet = ? AND f.itemKey = i.itemKey AND f.seq > ?)
        UNION ALL
        SELECT e.trackId FROM playlist_edit_log e
        WHERE e.playlistId = ? AND e.itemSet = ? AND e.itemKey < ? AND e.trackId IN ${marks(n)} AND e.op IN (0, 1)
          AND e.seq = (SELECT MIN(f.seq) FROM playlist_edit_log f WHERE f.playlistId = ? AND f.itemSet = ? AND f.itemKey = e.itemKey AND f.seq > ?)
    )
"""

private fun PinnedSet.head() = listOf(SqlValue.Text(playlistId), SqlValue.Int64(itemSet), SqlValue.Int64(nextKey0))

private fun PinnedSet.tail() = listOf(SqlValue.Text(playlistId), SqlValue.Int64(itemSet), SqlValue.Int64(seq0))

// Walks what an add brings, a page at a time, and counts it against a
// playlist chunk by chunk, never holding the songs: every multi-page walk
// of a playlist pins the set it reads under a token of its own, so a sync
// or an edit meanwhile changes nothing it reads, and lets it go when done.
@Singleton
class AddSources @Inject constructor(
    private val db: CatalogDatabase,
    private val lists: dagger.Lazy<ListLoaders>,
    private val owners: WorkOwners,
    private val sets: PlaylistSets,
) {
    private val loaders: ListLoaders get() = lists.get()
    private val dao = db.listsDao()

    // Hears each chunk of a playlist's songs counted, numbered from 1, so a
    // test can step in between them.
    internal var counted: suspend (chunk: Int) -> Unit = {}
    private val catalog = db.dao()
    private val online = db.onlineDao()

    // The library songs an add brings, in their order, a page at a time;
    // with `finds` (the server keeps songs found online in playlists, and the
    // playlist takes them), the songs found online among them too, those
    // whose rows are written.
    suspend fun pages(source: AddSource, emit: suspend (List<SongToAdd>) -> Unit, finds: Boolean = false) {
        when (source) {
            is AddSource.Ids -> for (chunk in source.ids.chunked(CHUNK)) {
                val found = catalog.tracksByIdsUnordered(chunk.distinct()).associateBy { it.id }
                val kept = if (finds) online.byIds(chunk.filter(::isFind).distinct()).mapTo(HashSet()) { it.id } else emptySet()
                emit(chunk.mapNotNull { id -> found[id]?.let { t -> SongToAdd(t.id, t.relinkKey) } ?: id.takeIf { it in kept }?.let { SongToAdd(it, "") } })
            }
            is AddSource.Spec -> walkSpec(source.spec, finds) { tracks -> emit(tracks.map { SongToAdd(it.id, it.relinkKey) }) }
        }
    }

    // How many rows a list holds, read the cheapest exact way.
    suspend fun count(spec: ListSpec): Int? {
        val owner = owners.walk()
        val loader = loaders.forSpec(spec, owner) ?: return null
        try {
            return loader.count()
        } finally {
            withContext(NonCancellable) {
                (loader as? Retiring)?.retire()
                loaders.release(owner)
            }
        }
    }

    // The library songs of a list in its order, a page at a time; with
    // `finds`, its songs found online too.
    private suspend fun walkSpec(spec: ListSpec, finds: Boolean = false, page: suspend (List<TrackEntity>) -> Unit) {
        val owner = owners.walk()
        @Suppress("UNCHECKED_CAST")
        val loader = loaders.forSpec(spec, owner) as? app.winters.octo.ui.paging.ListLoader<Any> ?: return
        try {
            var after: app.winters.octo.ui.paging.RowKey? = null
            while (true) {
                val rows = loader.after(after, CHUNK)
                if (rows.isEmpty()) return
                val tracks = rows.mapNotNull { row -> addableRow(row.row, finds) }
                page(tracks)
                if (rows.size < CHUNK) return
                after = rows.last().key
            }
        } finally {
            withContext(NonCancellable) {
                (loader as? Retiring)?.retire()
                loaders.release(owner)
            }
        }
    }

    // How an add of `source` to `playlistId` would go: the songs it brings
    // (each once), how many the playlist holds already, how many are new.
    // For songs named by id, the plan itself, as today's adds plan it.
    suspend fun check(playlistId: String, source: AddSource): Pair<AddCounts, AddPlan?> {
        val target = owners.walk()
        val pinned = sets.pin(target, playlistId) ?: return AddCounts(0, 0, 0) to null
        try {
            return when (source) {
                is AddSource.Ids -> {
                    val held = source.ids.distinct().chunked(CHUNK).flatMap { held(pinned, it) }.toSet()
                    val plan = planAdd(source.ids, held)
                    plan.counts() to plan
                }
                is AddSource.Spec -> {
                    var songs = 0
                    var repeats = 0
                    distinctChunks(source.spec) { chunk ->
                        songs += chunk.size
                        repeats += dao.count(rawQuery(setHoldsSql(chunk.size), pinned.head() + chunk.map { SqlValue.Text(it) } + pinned.tail() + pinned.head() + chunk.map { SqlValue.Text(it) } + pinned.tail()))
                    }
                    AddCounts(songs, songs - repeats, repeats) to null
                }
            }
        } finally {
            withContext(NonCancellable) { sets.releasePin(target, playlistId) }
        }
    }

    // Which of these songs the pinned set holds.
    private suspend fun held(pinned: PinnedSet, ids: List<String>): List<String> {
        val values = ids.map { SqlValue.Text(it) }
        return dao.ids(rawQuery(setHoldsSql(ids.size, which = true), pinned.head() + values + pinned.tail() + pinned.head() + values + pinned.tail()))
    }

    // A list's library songs, each once, in chunks: a playlist's in id
    // order from its pinned set, any other list's in its own order (each
    // holds a song once).
    private suspend fun distinctChunks(spec: ListSpec, chunk: suspend (List<String>) -> Unit) {
        if (spec !is ListSpec.Playlist) {
            walkSpec(spec) { tracks -> if (tracks.isNotEmpty()) chunk(tracks.map { it.id }.distinct()) }
            return
        }
        val owner = owners.walk()
        val pinned = sets.pin(owner, spec.playlistId) ?: return
        try {
            var after = ""
            var n = 0
            while (true) {
                val rows = dao.ids(
                    rawQuery(
                        SET_SONGS_SQL,
                        pinned.head() + SqlValue.Text(after) + pinned.tail() + pinned.head() + SqlValue.Text(after) + pinned.tail() + SqlValue.Int64(CHUNK.toLong()),
                    ),
                )
                if (rows.isEmpty()) return
                val distinct = rows.distinct()
                chunk(distinct)
                counted(++n)
                if (rows.size < CHUNK) return
                after = rows.last()
            }
        } finally {
            withContext(NonCancellable) { sets.releasePin(owner, spec.playlistId) }
        }
    }
}
