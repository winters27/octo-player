package app.winters.octo.livelists

import android.database.sqlite.SQLiteFullException
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.MosaicPicker
import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.naturalSortKey
import app.winters.octo.catalog.trackOrderPage
import app.winters.octo.listening.TotalsFailed
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.foldText
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.Survey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton

// How many songs a walk for a list's choices or its copy reads at a time.
private const val CHOICES_PAGE = 500

// What Add a rule can ask which of, from the library: its genres and
// artists A to Z, each once however it is spelt, and its decades.
data class LiveChoices(val genres: List<String> = emptyList(), val artists: List<String> = emptyList(), val decades: List<Int> = emptyList())

// Live lists as the screens read them, from what LivePicks keeps in the
// database: a saved list's songs for its page and the car's (under
// live:<id>) and an editor's preview (under a draft owner), each kept up to
// date while shown and read a page at a time; the covers of a list's first
// albums, by a walk that stops at four; and the choices the editor offers,
// from one walk of the library.
@Singleton
class LiveListSongs @Inject constructor(
    private val picks: LivePicks,
    private val db: CatalogDatabase,
    private val owners: WorkOwners,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A saved list's owner, for its page, its covers and plays of it.
    fun owner(listId: String): String = owners.live(listId)

    // A new owner for an editor's preview, cleared with `clear`.
    fun draft(): String = owners.draft()

    // `owner`'s list of what `query` picks, kept up to date while
    // collected: what it shows now, then after each walk. With `keepHeld` a
    // generation a reader holds never changes: a walk while one is held
    // goes into a generation of its own.
    fun watch(owner: String, listId: String?, query: Flow<LibraryQuery>, keepHeld: Boolean = false): Flow<LiveShown> =
        picks.watch(owner, listId, query, keepHeld)

    // `owner`'s songs as shown, in the list's order, a page at a time: the
    // generation shown when each read runs, or with `at` that one.
    fun pages(owner: String, at: LivePinned? = null): ListLoader<TrackEntity> = LiveTracks(picks.loader(owner, at), db)

    // Holds the generation `owner` shows now for a reader, until
    // unpinShown; null before one is shown.
    suspend fun pinShown(owner: String): LivePinned? = picks.pinShown(owner)

    suspend fun unpinShown(owner: String, gen: Long) = picks.unpinShown(owner, gen)

    // Empties an editor's preview.
    suspend fun clear(owner: String) = picks.clear(owner)

    // Up to four covers for a list's picture, one per album, in the order
    // its songs come: read from the list as shown when it is, else picked
    // by a walk of the library that keeps no list. With no order and no
    // limit the walk stops at the fourth album; with a limit it keeps the
    // list's top songs (at most the limit); with an order and no limit it
    // keeps each album's first place among the songs with art. No covers
    // while play counts the list reads cannot be brought up to date.
    suspend fun covers(listId: String?, query: LibraryQuery): List<String> = withContext(Dispatchers.IO) {
        val shown = listId?.let(owners::live)?.takeIf { picks.shown(it) != null }
        if (shown != null) return@withContext shownCovers(shown)
        try {
            pickedCovers(query)
        } catch (e: TotalsFailed) {
            emptyList()
        }
    }

    // A saved list's covers, as long as anything shows or reads a list.
    fun coversOf(list: LiveList): Flow<List<String>> = changes().map { covers(list.id, list.query) }.flowOn(Dispatchers.IO)

    // The library's genres, artists and decades for the editor, from one
    // walk in library order, again after each change of the songs: each
    // name once, spelt as its first song in library order spells it.
    fun choices(): Flow<LiveChoices> = db.invalidationTracker.createFlow("track").conflate().map { choicesNow() }.flowOn(Dispatchers.IO)

    // Lets an editor's preview go once its page is gone.
    fun discard(owner: String) {
        scope.launch { picks.clear(owner) }
    }

    // A playlist of a saved list's songs as it shows them now (or, with
    // none shown, as its rules pick them now), made by `create` from pages
    // of rows in the list's order, each song as the library has it now.
    // Answers how many songs went in; with none, nothing is made. Too
    // little space to copy the list throws NotEnoughSpace for keeping it.
    suspend fun copy(list: LiveList, create: suspend (pages: suspend (suspend (List<NewItem>) -> Unit) -> Unit) -> Unit): Int = withContext(Dispatchers.IO) {
        val owner = try {
            picks.copyForPlaylist(list.id, list.query)
        } catch (e: NotEnoughSpace) {
            throw NotEnoughSpace(e.neededBytes, SpaceFor.KeepList)
        } catch (e: SQLiteFullException) {
            throw NotEnoughSpace(null, SpaceFor.KeepList)
        }
        try {
            if ((picks.shown(owner)?.count ?: 0) == 0) return@withContext 0
            var written = 0
            create { emit ->
                eachPage(owner) { songs ->
                    written += songs.size
                    emit(songs.map { NewItem(it.id, it.relinkKey) })
                }
            }
            written
        } finally {
            withContext(NonCancellable) { picks.clear(owner) }
        }
    }

    // What `owner`'s list shows now, or null before its first walk.
    suspend fun shown(owner: String): LiveShown.Shown? = picks.shown(owner)

    // A copy's songs in its order, a page at a time, each followed to the
    // song it is now; one gone from the library is left out.
    private suspend fun eachPage(owner: String, each: suspend (List<TrackEntity>) -> Unit) {
        val rows = picks.rows(owner)
        var after: ByteArray? = null
        while (true) {
            val (ids, next) = rows.page(after)
            val tracks = ids.chunked(ID_CHUNK).flatMap { db.dao().tracksByIdsUnordered(it) }.associateBy { it.id }
            val songs = ids.mapNotNull { tracks[it] }
            if (songs.isNotEmpty()) each(songs)
            after = next ?: return
        }
    }

    private fun changes(): Flow<Unit> = db.invalidationTracker.createFlow("track", "album", "liked_track", "play_total", "live_state").conflate().map { }

    private suspend fun shownCovers(owner: String): List<String> {
        val picker = MosaicPicker()
        val loader = picks.loader(owner)
        var after: RowKey? = null
        while (!picker.full) {
            val page = loader.after(after, CHOICES_PAGE)
            val art = artOf(page.map { it.row })
            page.forEach { line -> art[line.row]?.let { (album, cover) -> picker.offer(album, cover) } }
            if (page.size < CHOICES_PAGE) break
            after = page.last().key
        }
        return picker.covers
    }

    private suspend fun pickedCovers(query: LibraryQuery): List<String> {
        val limit = query.limit?.takeIf { it >= 0 }
        val sorted = query.sort != null
        return when {
            limit == 0 -> emptyList()
            // In library order: the first albums met, among the first
            // `limit` songs picked when there is a limit.
            !sorted -> {
                val picker = MosaicPicker()
                var taken = 0
                picks.eachPick(query) { song, _ ->
                    picker.offer(song.albumId, song.artwork)
                    taken++
                    !picker.full && (limit == null || taken < limit)
                }
                picker.covers
            }
            // The list's top songs, then their first albums in its order.
            limit != null -> {
                val worstFirst = PriorityQueue<Pair<ByteArray, TrackEntity>>(limit + 1) { a, b -> unsigned(b.first, a.first) }
                picks.eachPick(query) { song, key ->
                    worstFirst += key to song
                    if (worstFirst.size > limit) worstFirst.poll()
                    true
                }
                val picker = MosaicPicker()
                worstFirst.sortedWith { a, b -> unsigned(a.first, b.first) }.forEach { (_, song) -> picker.offer(song.albumId, song.artwork) }
                picker.covers
            }
            // Each album's first place among its songs with art; the four
            // first.
            else -> {
                val first = HashMap<String, Pair<ByteArray, String>>()
                picks.eachPick(query) { song, key ->
                    val art = song.artwork
                    if (art != null) {
                        val had = first[song.albumId]
                        if (had == null || unsigned(key, had.first) < 0) first[song.albumId] = key to art
                    }
                    true
                }
                first.values.sortedWith { a, b -> unsigned(a.first, b.first) }.take(4).map { it.second }
            }
        }
    }

    // Each song's album and cover, by id.
    private suspend fun artOf(ids: List<String>): Map<String, Pair<String, String?>> =
        ids.distinct().chunked(ID_CHUNK).flatMap { db.dao().tracksByIdsUnordered(it) }.associate { it.id to (it.albumId to it.artwork) }

    private suspend fun choicesNow(): LiveChoices {
        val genres = LinkedHashMap<String, String>()
        val artists = LinkedHashMap<String, String>()
        val decades = HashSet<Int>()
        val reader = db.readerDao()
        var after = "" to ""
        while (true) {
            val songs = reader.trackOrderPage(after.first, after.second, CHOICES_PAGE)
            for (song in songs) {
                song.artist.takeIf(String::isNotBlank)?.let { name -> artists.getOrPut(foldText(name)) { name.trim() } }
                song.genre.takeIf(String::isNotBlank)?.let { name -> genres.getOrPut(foldText(name)) { name.trim() } }
                song.year?.takeIf { it > 0 }?.let { decades += it / 10 * 10 }
            }
            if (songs.size < CHOICES_PAGE) break
            after = songs.last().let { it.sortKey to it.id }
        }
        return LiveChoices(genres.values.sortedBy(::naturalSortKey), artists.values.sortedBy(::naturalSortKey), decades.sorted())
    }
}

// A live list's songs as an owner shows them, each read by its id with the
// page it is on: the list's keys and order, its songs whole for the rows.
// A song gone from the library since the list's last walk is left out
// until the next.
class LiveTracks internal constructor(private val ids: LiveLoader, private val db: CatalogDatabase) : ListLoader<TrackEntity> {
    override val tables = ids.tables
    override val order = ids.order
    override val songsOnce = true

    override fun trackIdOf(row: TrackEntity): String = row.id

    override suspend fun count(): Int = ids.count()

    override suspend fun survey(anchor: RowKey?): Survey = ids.survey(anchor)

    override suspend fun after(key: RowKey?, limit: Int): List<Keyed<TrackEntity>> = songs(ids.after(key, limit))

    override suspend fun before(key: RowKey, limit: Int): List<Keyed<TrackEntity>> = songs(ids.before(key, limit))

    override suspend fun keyAt(position: Int): RowKey? = ids.keyAt(position)

    override suspend fun keyOf(trackId: String): RowKey? = ids.keyOf(trackId)

    override suspend fun rekey(key: RowKey): RowKey? = ids.rekey(key)

    override suspend fun present(ids: List<String>): Set<String> = this.ids.present(ids)

    private suspend fun songs(lines: List<Keyed<String>>): List<Keyed<TrackEntity>> = withContext(Dispatchers.IO) {
        val tracks = lines.map { it.row }.distinct().chunked(ID_CHUNK).flatMap { db.dao().tracksByIdsUnordered(it) }.associateBy { it.id }
        lines.mapNotNull { line -> tracks[line.row]?.let { Keyed(line.key, it) } }
    }
}
