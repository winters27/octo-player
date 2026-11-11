package app.winters.octo.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.rawQuery
import app.winters.octo.playback.GroupKind
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.QUEUE_WINDOW
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.foldText
import app.winters.octo.catalog.naturalSortKey
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.Sorted
import app.winters.octo.sort.SortedLibrary
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListItem
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.paging.ListMeta
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlValue
import app.winters.octo.ui.paging.tableChanges
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// What the Songs list shows: its order and the filter typed over it.
data class SongsQuestion(val order: SortOrder, val filter: LibraryQuery)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val dao: CatalogDao,
    private val sorted: SortedLibrary,
    private val playback: PlaybackConnection,
    private val loaders: ListLoaders,
    private val reads: LibraryReads,
    private val db: CatalogDatabase,
    private val saved: SavedStateHandle,
) : ViewModel() {
    // The newest albums, for the front page.
    val recent: StateFlow<List<AlbumEntity>> =
        dao.recentAlbums(20).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Each list in its chosen order. Null until the first read, so a loading
    // moment is not shown as empty. Albums and artists are album- and
    // artist-sized and held whole.
    val albums: StateFlow<Sorted<AlbumEntity>?> =
        sorted.albums(SortList.Albums).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val artists: StateFlow<Sorted<ArtistEntity>?> =
        sorted.artists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The Songs list's order, null until read.
    val songOrder: StateFlow<SortOrder?> =
        sorted.settings.order(SortList.Songs).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The Songs list's filters, for this visit only: the words typed and
    // the chips on.
    val songFilter = MutableStateFlow(LibraryQuery())

    private var handle: ListHandle<SongsQuestion, TrackEntity>? = null

    // The Songs list in pages, once its order is read.
    val songs: StateFlow<ListHandle<SongsQuestion, TrackEntity>?> = songOrder.filterNotNull().map { order ->
        val question = SongsQuestion(order, songFilter.value)
        handle?.also { it.ask(question) } ?: ListHandle(
            viewModelScope,
            question,
            { q -> loaders.songs(SongScope.All, q.order, q.filter) },
            reads,
            { _, loader -> db.tableChanges(loader.tables) },
            initialKey = saved.get<String>(ANCHOR)?.let { runCatching { RowKey.of(it) }.getOrNull() },
            filtered = { it.filter.filters },
        ).also { handle = it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // How many songs the library has, for "120 of 2,835".
    val songTotal: StateFlow<Int> = dao.libraryTrackCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // The count and letters of the Songs list on screen.
    val songMeta: StateFlow<ListMeta?> = songs.flatMapLatest { it?.shown ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The genres and decades the Songs list's chips offer, read from the
    // genre list and a skip over the year index, never the songs.
    val songChoices: StateFlow<SongChoices> = db.tableChanges(arrayOf("track")).onStart { emit(Unit) }.map { songChoices(db) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SongChoices())

    fun filterSongs(query: LibraryQuery) {
        songFilter.value = query
        val order = songOrder.value ?: return
        handle?.ask(SongsQuestion(order, query))
    }

    fun setOrder(list: SortList, order: SortOrder) {
        viewModelScope.launch { sorted.setOrder(list, order) }
    }

    // The screen's first visible row, kept across the app's end too.
    fun visible(key: RowKey?) {
        handle?.visible(key)
        saved[ANCHOR] = key?.token
    }

    private fun songSpec(): ListSpec.Songs? {
        val q = handle?.asked?.value ?: return null
        return ListSpec.Songs(SongScope.All, q.order, q.filter.takeIf { it.filters })
    }

    // Plays every song shown, in the order shown, or shuffled.
    fun playSongs(shuffle: Boolean) {
        val spec = songSpec() ?: return
        if (spec.filter == null) {
            playback.playList(spec, null, shuffle, "Songs")
        } else {
            viewModelScope.launch { playFiltered(spec, null, shuffle) }
        }
    }

    // Plays the song list as shown, from the one tapped.
    fun playSong(item: ListItem<TrackEntity>) {
        val spec = songSpec() ?: return
        val start = ListStart.Row(item.row.id, item.key)
        if (spec.filter == null) playback.playList(spec, start, source = "Songs") else viewModelScope.launch { playFiltered(spec, start, false) }
    }

    private var picksUsed = 0
    private var picksOf = -1L

    // True while a Shuffle of the filtered list waits for its count's pass,
    // which brings the songs it starts from; its button shows it loading.
    private val _shuffleWaiting = MutableStateFlow(false)
    val shuffleWaiting: StateFlow<Boolean> = _shuffleWaiting

    // A filtered list decides from what the generation on screen knows:
    // its rows when its pages reached both ends with at most QUEUE_WINDOW,
    // else its count's pass (the first rows and picks it kept), waited for
    // when it has not landed yet.
    private suspend fun playFiltered(spec: ListSpec.Songs, start: ListStart.Row?, shuffle: Boolean) {
        val handle = handle ?: return
        handle.whole()?.let { rows ->
            playback.playList(ListSpec.Ids(rows.map { it.row.id }, "Songs"), start, shuffle, "Songs")
            return
        }
        val counted: (ListMeta?) -> Boolean = { it != null && it.generation == handle.presented && it.start != null }
        val waits = shuffle && start == null && !counted(handle.shown.value)
        if (waits) _shuffleWaiting.value = true
        val meta = try {
            handle.shown.first { counted(it) }
        } finally {
            if (waits) _shuffleWaiting.value = false
        } ?: return
        val kept = meta.start ?: return
        if (kept.count <= QUEUE_WINDOW) {
            playback.playList(ListSpec.Ids(kept.ids, "Songs"), start, shuffle, "Songs")
            return
        }
        val first = when {
            start != null -> start
            shuffle -> {
                if (picksOf != meta.generation) {
                    picksOf = meta.generation
                    picksUsed = 0
                }
                val pick = kept.picks.getOrNull(picksUsed++) ?: kept.picks.firstOrNull()
                if (picksUsed >= kept.picks.size) handle.refresh()
                pick?.let { ListStart.First(it.trackId, it.key) }
            }
            else -> kept.first?.let { ListStart.First(it.trackId, it.key) }
        }
        playback.playList(spec, first, shuffle, "Songs", shownOverWindow = true)
    }

    // Plays every album in the order shown, each from its first song, or
    // every song shuffled.
    fun playAlbums(shuffle: Boolean) {
        val order = albums.value?.order ?: return
        playGroups(GroupKind.Albums, order, shuffle)
    }

    // Plays every artist in the order shown, album by album, or every song
    // shuffled.
    fun playArtists(shuffle: Boolean) {
        val order = artists.value?.order ?: return
        playGroups(GroupKind.Artists, order, shuffle)
    }

    private fun playGroups(kind: GroupKind, order: SortOrder, shuffle: Boolean) {
        if (shuffle) {
            playback.playList(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), null, shuffle = true)
        } else {
            playback.playList(ListSpec.Groups(kind, order), null)
        }
    }

    // Any one album, for "Random album".
    fun randomAlbum(): AlbumEntity? = albums.value?.items?.randomOrNull()

    companion object {
        private const val ANCHOR = "anchor"
    }
}

// What the Songs list's chips can pick from: its genres, A to Z, and the
// decades its songs come from, as the year each starts.
data class SongChoices(val genres: List<String> = emptyList(), val decades: List<Int> = emptyList())

// The chips' choices, read from the genre list and a skip over the year
// index, never the songs.
suspend fun songChoices(db: CatalogDatabase): SongChoices {
    // Spellings of one genre that differ only by case are one row, shown
    // by the smallest; others that fold alike keep the first by name.
    val genres = db.dao().genres().first().map { it.name }.filter { it.isNotBlank() }.sortedBy(::naturalSortKey)
        .distinctBy(::foldText).map { it.trim() }.sortedBy(::naturalSortKey)
    val decades = ArrayList<Int>()
    var from = 1L
    val lists = db.listsDao()
    while (true) {
        // No row past the last year, so the skip ends there.
        val year = lists.numbers(rawQuery("SELECT year FROM track WHERE year >= ? ORDER BY year LIMIT 1", listOf(SqlValue.Int64(from)))).firstOrNull() ?: break
        decades += (year / 10 * 10).toInt()
        from = (year / 10 + 1) * 10
    }
    return SongChoices(genres, decades)
}
