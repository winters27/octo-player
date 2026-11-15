package app.winters.octo.ui.album

import app.winters.octo.ui.common.factsLine
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.ambient.PageArtwork
import app.winters.octo.animated.AnimatedArts
import app.winters.octo.animated.LocalPlayerCovers
import app.winters.octo.animated.LoopVideo
import app.winters.octo.animated.rememberLoopPlay
import app.winters.octo.subsonic.AnimatedClip
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.rawQuery
import app.winters.octo.discovery.ONLY_MY_SONGS
import app.winters.octo.discovery.highlightWalk
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.playback.QUEUE_WINDOW
import app.winters.octo.ui.common.adoptedFinds
import app.winters.octo.ui.common.picked
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListItem
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.paging.PagedPicks
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.RowPlaceholder
import app.winters.octo.ui.paging.SqlValue
import app.winters.octo.ui.paging.WHOLE_ALBUM_MAX
import app.winters.octo.ui.paging.marks
import app.winters.octo.ui.paging.rememberLinedRows
import app.winters.octo.ui.paging.tableChanges
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import app.winters.octo.player.PlayerSettings
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.artworkRim
import app.winters.octo.design.elevation3
import app.winters.octo.data.ServerDownloads
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.TopSongsSource
import app.winters.octo.discovery.albumHighlight
import app.winters.octo.discovery.DownloadState
import app.winters.octo.discovery.Downloads
import app.winters.octo.discovery.OwnedFinds
import app.winters.octo.discovery.wholeAlbum
import app.winters.octo.listening.FavouriteKind
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.CHECK_SETTLE_MS
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.FavouriteHeart
import app.winters.octo.ui.common.LocalAdoptedFinds
import app.winters.octo.ui.common.LocalOwnedFinds
import app.winters.octo.ui.common.Pickable
import app.winters.octo.ui.common.HeaderControlLine
import app.winters.octo.ui.common.HeaderMore
import app.winters.octo.ui.common.MoreItem
import app.winters.octo.ui.common.PlayShuffleButtons
import app.winters.octo.ui.common.Refreshable
import app.winters.octo.ui.common.SelectableSongs
import app.winters.octo.ui.common.SongLead
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.albumLibraryNote
import app.winters.octo.ui.common.asLength
import app.winters.octo.ui.common.isOutsideLibrary
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.menu.SongMenuContext
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.ArtistRoute
import app.winters.octo.ui.nav.GenreRoute
import app.winters.octo.subsonic.SubsonicException
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = AlbumViewModel.Factory::class)
class AlbumViewModel @AssistedInject constructor(
    @Assisted private val id: String,
    private val dao: CatalogDao,
    private val playback: PlaybackConnection,
    private val discovery: Discovery,
    private val downloads: Downloads,
    private val topSongs: TopSongsSource,
    private val serverDownloads: ServerDownloads,
    private val player: PlayerSettings,
    animatedArts: AnimatedArts,
    private val loaders: ListLoaders,
    private val reads: LibraryReads,
    private val db: CatalogDatabase,
    owners: WorkOwners,
    private val merge: CatalogMerge,
    // The server's own songs the copy lacks: library songs, never missing.
    private val owned: OwnedFinds,
) : ViewModel() {
    val album: StateFlow<AlbumEntity?> =
        dao.album(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // How many songs the library has of it, by a count on its index; null
    // until read.
    val count: StateFlow<Int?> = db.tableChanges(arrayOf("track")).onStart { emit(Unit) }.map {
        db.listsDao().count(rawQuery("SELECT COUNT(*) FROM track WHERE albumId = ?", listOf(SqlValue.Text(id))))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // An album bigger than WHOLE_ALBUM_MAX is never held: its page reads it
    // in pages, and everything about it is counted or walked.
    val huge: StateFlow<Boolean> = count.map { (it ?: 0) > WHOLE_ALBUM_MAX }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // The album's songs in the library, for an album held whole.
    @OptIn(ExperimentalCoroutinesApi::class)
    val library: StateFlow<List<TrackEntity>> = count.filterNotNull().distinctUntilChanged { a, b -> (a <= WHOLE_ALBUM_MAX) == (b <= WHOLE_ALBUM_MAX) }
        .flatMapLatest { smallLibrary(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun smallLibrary(songCount: Int): Flow<List<TrackEntity>> = if (songCount <= WHOLE_ALBUM_MAX) dao.albumTracks(id) else flowOf(emptyList())

    // A big album's listing copy, under the page's two owners: written again
    // under the other when a find it shows is taken into the library. A
    // listing with no find writes nothing: the page reads the library.
    private val copyOwners = listOf(owners.vm(), owners.vm())
    private val copies = AlbumCopies(
        copyOwners,
        { owner -> loaders.buildAlbum(owner, id, keepLibrary = false) },
        { owner -> loaders.albumCopy(owner) },
        { owner, finds -> copyShowsFinds(db, owner, finds) },
        { owner -> copyIsStale(db, owner, id) },
    )

    // A big album's listing holds songs found online.
    private val hugeFinds = MutableStateFlow(false)
    val hasFinds: StateFlow<Boolean> = hugeFinds

    // A big album's songs in pages: the listing's copy with its finds in
    // place, or the library's songs alone when there are none or the
    // switches leave them out.
    private var pagesHandle: ListHandle<Boolean, TrackEntity>? = null
    val pages: StateFlow<ListHandle<Boolean, TrackEntity>?> by lazy {
        combine(huge, hugeFinds, player.prefs.map { it.libraryOnly }, onlyMine) { big, finds, everywhere, here ->
            if (!big) null else finds && !(everywhere || here)
        }.map { withFinds ->
            if (withFinds == null) return@map null
            pagesHandle?.also { it.ask(withFinds) } ?: ListHandle(
                viewModelScope,
                withFinds,
                { finds -> if (finds) copies.loader else loaders.albumLibrary(id) },
                reads,
                { _, loader -> db.tableChanges(loader.tables) },
            ).also { pagesHandle = it }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

    // A big album's pages with the held album's disc headings among them.
    @OptIn(ExperimentalCoroutinesApi::class)
    val bigLines: Flow<PagingData<AlbumLine>> by lazy {
        pages.filterNotNull().flatMapLatest { handle -> handle.pages.withDiscs { first -> otherDisc(handle.asked.value, first) } }
            .cachedIn(viewModelScope)
    }

    // Whether the big album's rows hold a song on another disc than `disc`,
    // the first row's: the library's songs, or the copy's shown rows.
    private suspend fun otherDisc(withFinds: Boolean, disc: Int): Boolean {
        val lists = db.listsDao()
        val other = SqlValue.Int64(disc.toLong())
        return if (withFinds) {
            lists.count(
                rawQuery(
                    "SELECT EXISTS(SELECT 1 $COPY_SHOWN AND IFNULL(CASE WHEN t.id IS NOT NULL THEN t.discNo ELSE e.discNo END, 1) != ?)",
                    listOf(SqlValue.Text(copies.owner), other),
                ),
            ) == 1
        } else {
            libraryOtherDisc(db, id, disc)
        }
    }

    // A big album's header, by counts over its rows: re-read when the rows
    // it shows read again, and when a find is asked for or adopted.
    @OptIn(ExperimentalCoroutinesApi::class)
    val bigHeader: StateFlow<BigAlbumHeader?> by lazy {
        val generation = pages.flatMapLatest { handle -> handle?.shown?.map { handle.asked.value to it?.generation } ?: flowOf(null) }
        // Songs the server owns count as in the library and are never asked for.
        val adopted = combine(downloads.phases, owned.ids) { phases, mine -> adoptedFinds(phases) to mine }.distinctUntilChanged()
        combine(generation, hugeFinds, downloads.states, adopted) { shown, finds, states, (done, mine) ->
            HeaderInputs(shown?.first, finds, states + mine.associateWith { DownloadState.Done }, done + mine)
        }
            .conflate()
            .map { inputs -> inputs.withFinds?.let { bigHeaderOf(it, inputs.finds, inputs.states, inputs.adopted) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    private class HeaderInputs(val withFinds: Boolean?, val finds: Boolean, val states: Map<String, DownloadState>, val adopted: Set<String>)

    private suspend fun bigHeaderOf(withFinds: Boolean, hasFinds: Boolean, states: Map<String, DownloadState>, adopted: Set<String>): BigAlbumHeader {
        val lists = db.listsDao()
        val album = listOf(SqlValue.Text(id))
        val songs = lists.count(rawQuery("SELECT COUNT(*) FROM track WHERE albumId = ?", album))
        val ms = lists.numbers(rawQuery("SELECT IFNULL(SUM(durationMs), 0) FROM track WHERE albumId = ?", album)).firstOrNull() ?: 0
        val rated = lists.count(rawQuery("SELECT COUNT(*) FROM track WHERE albumId = ? AND rating BETWEEN 1 AND 5", album))
        val sum = lists.numbers(rawQuery("SELECT IFNULL(SUM(rating), 0) FROM track WHERE albumId = ? AND rating BETWEEN 1 AND 5", album)).firstOrNull() ?: 0
        // The genre most of its songs have, spelled as its first song with it.
        val genre = lists.ids(
            rawQuery(
                "SELECT genre FROM (SELECT genre, MIN(albumOrder) AS first, COUNT(*) AS n FROM track " +
                    "WHERE albumId = ? AND TRIM(genre) != '' GROUP BY genre ORDER BY n DESC, first LIMIT 1)",
                album,
            ),
        ).firstOrNull()
        val average = if (rated > 0) sum.toDouble() / rated else null
        if (!hasFinds) return BigAlbumHeader(songs, ms, songs, 0, 0, 0, genre, average)
        val owned = listOf(SqlValue.Text(copies.owner))
        val whole = lists.count(rawQuery("SELECT COUNT(*) $COPY_SHOWN", owned))
        val wholeMs = lists.numbers(rawQuery("SELECT IFNULL(SUM(CASE WHEN t.id IS NOT NULL THEN t.durationMs ELSE o.durationMs END), 0) $COPY_SHOWN", owned)).firstOrNull() ?: 0
        val finds = lists.count(rawQuery("SELECT COUNT(*) $COPY_SHOWN AND e.isFind = 1", owned))
        val asked = states.filterValues { it != DownloadState.None }.keys
        val coming = states.filterValues { it == DownloadState.Requested }.keys
        return BigAlbumHeader(
            shown = if (withFinds) whole else songs,
            lengthMs = if (withFinds) wholeMs else ms,
            whole = whole,
            outside = finds - findsAmong(adopted),
            askable = finds - findsAmong(asked),
            coming = findsAmong(coming),
            genre = genre,
            average = average,
        )
    }

    // How many of the page's finds are among `ids`, a chunk at a time.
    private suspend fun findsAmong(ids: Set<String>): Int = ids.filter(::isFind).chunked(900).sumOf { chunk ->
        db.listsDao().count(
            rawQuery(
                "SELECT COUNT(DISTINCT e.shownId) $COPY_SHOWN AND e.isFind = 1 AND e.shownId IN ${marks(chunk.size)}",
                listOf(SqlValue.Text(copies.owner)) + chunk.map { SqlValue.Text(it) },
            ),
        )
    }

    // The album's square loop from its server, in place of its cover.
    @OptIn(ExperimentalCoroutinesApi::class)
    val loop: StateFlow<AnimatedClip?> = animatedArts.pageLoop(
        combine(huge, library) { big, songs ->
            if (big) loaders.albumLibrary(id).after(null, 8).map { it.row.id } else songs.map(TrackEntity::id)
        }
            .filter { it.isNotEmpty() }
            .distinctUntilChanged()
            .mapLatest { animatedArts.serverAlbumOf(it) },
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The album as the server lists it, once asked: an Octo server lists the
    // songs the library lacks too. Empty until then, or when it cannot say.
    private val server = MutableStateFlow<List<TrackEntity>>(emptyList())

    // The album whole: the library's songs, and in their places the songs
    // found online, which play through the server and can be added.
    val whole: StateFlow<List<TrackEntity>> = combine(library, server, downloads.adoptions) { library, server, adoptions ->
        wholeAlbum(library, server, adoptions.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Whether songs found online are left out everywhere, by the setting.
    val libraryOnly: StateFlow<Boolean> =
        player.prefs.map { it.libraryOnly }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // This page's own "Only my songs", for while it is open.
    private val onlyMine = MutableStateFlow(false)
    val ownOnly: StateFlow<Boolean> = onlyMine

    fun setOnlyMine(on: Boolean) {
        onlyMine.value = on
    }

    // The songs the page lists and plays: the album whole, or only the
    // library's songs when either switch says so. Play and shuffle count
    // in this list, so a row plays itself.
    val tracks: StateFlow<List<TrackEntity>> = combine(whole, libraryOnly, onlyMine) { all, everywhere, here ->
        albumSongsShown(all, everywhere || here)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // How each song asked for is getting on.
    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states

    // The album's main song, starred as Apple Music does: the one first in
    // the artist's top songs on the server. None until known, or when no song
    // here is among them.
    // A big album's is found by one walk of the rows it shows, a page at a
    // time, keeping each top song's first id match and first title match.
    @OptIn(ExperimentalCoroutinesApi::class)
    val highlight: StateFlow<String?> = album.map { it?.artist }.distinctUntilChanged()
        .flatMapLatest { artist -> if (artist == null) flowOf(emptyList()) else flowOf(topSongs.forArtist(artist)) }
        .combine(combine(tracks, pages.flatMapLatest { it?.shown ?: flowOf(null) }) { tracks, meta -> tracks to meta?.generation }) { ranked, (tracks, _) ->
            val big = pages.value
            if (big != null) {
                val loader = big.loader
                highlightWalk(ranked) { after -> loader.after(after, WALK_PAGE) }
            } else {
                albumHighlight(tracks, ranked)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Asked once the library's songs are known, then again when a song
        // from here arrives in the library, once its check has landed. A big
        // album's listing is read into the page's copy instead, and read
        // again into the other copy when a find it shows is taken in, so the
        // song shows as the one it became, in place, and when a swap renamed,
        // removed or added one of its songs.
        viewModelScope.launch {
            if (count.filterNotNull().first() > WHOLE_ALBUM_MAX) {
                hugeFinds.value = runCatching { copies.build(downloads.adoptions.value.orEmpty().keys) }.getOrDefault(false)
                launch {
                    merge.swapStamp.filter { it % 2 == 0L }.distinctUntilChanged().drop(1).collectLatest {
                        if (hugeFinds.value) rebuilt { copies.swapped() }
                    }
                }
                downloads.adoptions.filterNotNull().map { it.keys }.distinctUntilChanged().collectLatest { adopted ->
                    if (hugeFinds.value) rebuilt { copies.adopted(adopted) }
                }
                return@launch
            }
            askServer(library.first { it.isNotEmpty() })
            downloads.arrived.drop(1).collectLatest {
                delay(CHECK_SETTLE_MS)
                if (server.value.any { isFind(it.id) }) askServer(library.value)
            }
        }
    }

    // A rebuild of the big album's copy: once the page's loader has turned to
    // the new copy, the page reads again around the row in view. Offline,
    // refused or short of room, the page keeps its copy.
    private suspend fun rebuilt(write: suspend () -> Boolean?) {
        val held = try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        hugeFinds.value = held
        pagesHandle?.refresh()
    }

    override fun onCleared() {
        pagesHandle?.close()
        RELEASE.launch { copyOwners.forEach { loaders.release(it) } }
    }

    // The server's listing of an album held whole, read whole: only for one
    // of at most WHOLE_ALBUM_MAX library songs, and only when the listing
    // itself names at most WHOLE_ALBUM_MAX songs; a longer listing is never
    // held, and the page shows the library's songs. A bigger album is never
    // held, and its listing is read a page at a time into the page's copy.
    private suspend fun askServer(songs: List<TrackEntity>) {
        if (songs.isEmpty() || songs.size > WHOLE_ALBUM_MAX) return
        try {
            discovery.libraryAlbum(id, most = WHOLE_ALBUM_MAX)?.let { server.value = it }
        } catch (e: SubsonicException) {
            // Offline or refused: the library's songs are the album for now.
        }
    }

    // Has the server add every song here it lacks and not yet asked for:
    // for a big album, its listing's rows walked a page at a time, shown or
    // not.
    fun addMissing() {
        if (pages.value == null) {
            val waiting = whole.value.filter { isFind(it.id) && downloads.state(it.id) == DownloadState.None }
            viewModelScope.launch { waiting.forEach { downloads.request(it) } }
            return
        }
        if (!hugeFinds.value) return
        viewModelScope.launch {
            val copy = loaders.albumCopy(copies.owner)
            var after: RowKey? = null
            while (true) {
                val rows = copy.after(after, WALK_PAGE)
                rows.map { it.row }.filter { isFind(it.id) && downloads.state(it.id) == DownloadState.None }.forEach { downloads.request(it) }
                if (rows.size < WALK_PAGE) break
                after = rows.last().key
            }
        }
    }

    // Whether the server keeps a downloads list the sheet can show.
    val canFollow: StateFlow<Boolean> = serverDownloads.supported

    // The server downloads sheet, on its list, to follow the songs on their way.
    fun showDownloads() {
        if (!serverDownloads.supported.value) return
        serverDownloads.showList()
        serverDownloads.open()
    }

    // The artist's other albums, for the foot of the page.
    @OptIn(ExperimentalCoroutinesApi::class)
    val moreByArtist: StateFlow<List<AlbumEntity>> = dao.album(id)
        .map { it?.artistId }
        .distinctUntilChanged()
        .flatMapLatest { artistId -> if (artistId == null) flowOf(emptyList()) else dao.artistAlbums(artistId) }
        .map { albums -> albums.filter { it.id != id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The album as a play names it: its rows when it shows at most
    // QUEUE_WINDOW (found songs in their places), else the album itself,
    // with its finds when the page shows them.
    private fun spec(shown: List<TrackEntity>, withFinds: Boolean): ListSpec =
        if (shown.size <= QUEUE_WINDOW) ListSpec.Ids(shown.map { it.id }, album.value?.title) else ListSpec.Album(id, withFinds)

    private fun shownFinds() = tracks.value.any { isFind(it.id) }

    // Plays the album from a song tapped on an album held whole.
    fun play(track: TrackEntity) {
        val shown = tracks.value
        playback.playList(spec(shown, shownFinds()), ListStart.Row(track.id, null), source = album.value?.title, shownOverWindow = shown.size > QUEUE_WINDOW)
    }

    // Plays from a song tapped on a big album's pages.
    fun play(item: ListItem<TrackEntity>) {
        val big = pages.value ?: return
        playback.playList(ListSpec.Album(id, big.asked.value), ListStart.Row(item.row.id, item.key), source = album.value?.title, shownOverWindow = true)
    }

    // Plays the album from its first song, or shuffled from a song picked
    // at random: on an album held whole the page knows that song, so it
    // plays at once.
    fun playAll(shuffle: Boolean) {
        val big = pages.value
        if (big != null) {
            playback.playList(ListSpec.Album(id, big.asked.value), null, shuffle, album.value?.title, pageOwner = copies.owner, shownOverWindow = true)
            return
        }
        val shown = tracks.value
        val first = (if (shuffle) shown.randomOrNull() else shown.firstOrNull()) ?: return
        playback.playList(spec(shown, shownFinds()), ListStart.First(first.id, null), shuffle, album.value?.title, shownOverWindow = shown.size > QUEUE_WINDOW)
    }

    fun shuffle() = playAll(shuffle = true)

    @AssistedFactory
    interface Factory {
        fun create(id: String): AlbumViewModel
    }
}

// A big album's header, by counts: the rows its page shows and their
// length; the listing's rows with its finds, shown or not, the finds outside
// the library, those not asked for yet and those on their way; and its
// genre and its songs' average rating, from its library songs.
data class BigAlbumHeader(
    val shown: Int,
    val lengthMs: Long,
    val whole: Int,
    val outside: Int,
    val askable: Int,
    val coming: Int,
    val genre: String?,
    val average: Double?,
)

// The rows of a page's album copy it shows: each listed song once, the
// first time it comes, and the library songs the listing left out, while
// the song is still there.
private const val COPY_SHOWN =
    "FROM lists_album_entry e LEFT JOIN track t ON t.id = e.shownId LEFT JOIN online_song o ON o.id = e.shownId " +
        "WHERE e.owner = ? AND (t.id IS NOT NULL OR o.id IS NOT NULL) AND (e.albumOrder IS NOT NULL OR " +
        "e.position = (SELECT MIN(f.position) FROM lists_album_entry f WHERE f.owner = e.owner AND f.shownId = e.shownId))"

// Where a page's copy is let go of once the page is gone.
private val RELEASE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// The rows read at a time by a big album's walks.
private const val WALK_PAGE = 500

private val CoverShape = RoundedCornerShape(10.dp)
private val CoverSide = 240.dp

@Composable
fun AlbumScreen(
    id: String,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
    vm: AlbumViewModel = hiltViewModel<AlbumViewModel, AlbumViewModel.Factory> { it.create(id) },
) {
    val album by vm.album.collectAsStateWithLifecycle()
    val big by vm.pages.collectAsStateWithLifecycle()
    PageArtwork(AlbumRoute(id), album?.artwork)

    Refreshable {
        val handle = big
        if (handle != null) BigAlbumSongs(id, handle, vm, onOpen) else HeldAlbumSongs(id, vm, onOpen)
        BackButton(onBack)
    }
}

// An album held whole: its merged list in memory, as it has always been.
@Composable
private fun HeldAlbumSongs(id: String, vm: AlbumViewModel, onOpen: (NavKey) -> Unit) {
    val album by vm.album.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val whole by vm.whole.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val states by vm.downloadStates.collectAsStateWithLifecycle()
    // Whether the album lists songs found online, so every row says which
    // songs are in the library; `missing` whether it has any, shown or not.
    // A song the server owns that the copy lacks is the library's.
    val owned = LocalOwnedFinds.current
    val outsideFind = { track: TrackEntity -> isFind(track.id) && track.id !in owned }
    val mixed = tracks.any(outsideFind)
    val missing = whole.any(outsideFind)
    // Whether the rows are more than the library's own, so the heading
    // counts the rows.
    val listed = tracks.any { isFind(it.id) }
    val moreByArtist by vm.moreByArtist.collectAsStateWithLifecycle()
    val highlight by vm.highlight.collectAsStateWithLifecycle()

    val pickable = remember(tracks) { tracks.map { Pickable(it.id, it) } }
    // Its songs' menus leave out the way back to this page.
    val menuContext = remember(id) { SongMenuContext(albumId = id) }

    SelectableSongs(pickable) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(extraTop = DetailTopGap)) {
            album?.let { a ->
                item(key = "header") {
                    val adopted = LocalAdoptedFinds.current
                    val outside = if (missing) whole.count { isOutsideLibrary(it.id, adopted) } else 0
                    AlbumTop(
                        id, a, vm, onOpen,
                        AlbumTopFacts(
                            songs = if (listed) tracks.size else a.songCount,
                            lengthSeconds = if (listed) (tracks.sumOf { it.durationMs } / 1000).toInt() else (a.durationMs / 1000).toInt(),
                            genre = albumGenre(library),
                            average = averageRating(library),
                            whole = whole.size,
                            outside = outside,
                            askable = whole.count { outsideFind(it) && (states[it.id] ?: DownloadState.None) == DownloadState.None },
                            coming = whole.count { outsideFind(it) && states[it.id] == DownloadState.Requested },
                            missing = missing,
                        ),
                        download = albumDownloadItem(library, outside),
                        onPlay = { vm.playAll(shuffle = false) },
                    )
                }
            }
            val discs = tracks.groupBy { it.discNo ?: 1 }
            discs.forEach { (disc, onDisc) ->
                if (discs.size > 1) {
                    item(key = "disc:$disc") { DiscHeading(disc) }
                }
                items(onDisc, key = { it.id }) { track ->
                    // Only say who is singing when it is not the album's artist.
                    // In an album mixing in songs found online, each row
                    // says after its number whether the song is in the
                    // library, as the desktop's album does.
                    SongRow(
                        track,
                        SongLead.Number(track.trackNo),
                        subtitle = { it.artist.takeIf { artist -> artist != album?.artist } },
                        menuContext = menuContext,
                        ownership = mixed,
                        highlight = track.id == highlight,
                    ) { vm.play(track) }
                }
            }
            album?.let { a ->
                if (moreByArtist.isNotEmpty()) {
                    item(key = "more-by-artist") { MoreByArtist(a.artist, moreByArtist, onOpen) }
                }
            }
        }
    }
}

// An album too big to hold: its rows in pages, its header by counts. Its
// rows run in album order, with a disc's heading where the disc changes, as
// the held album's.
@Composable
private fun BigAlbumSongs(id: String, handle: ListHandle<Boolean, TrackEntity>, vm: AlbumViewModel, onOpen: (NavKey) -> Unit) {
    val album by vm.album.collectAsStateWithLifecycle()
    val header by vm.bigHeader.collectAsStateWithLifecycle()
    val hasFinds by vm.hasFinds.collectAsStateWithLifecycle()
    val asked by handle.asked.collectAsStateWithLifecycle()
    val moreByArtist by vm.moreByArtist.collectAsStateWithLifecycle()
    val highlight by vm.highlight.collectAsStateWithLifecycle()
    val menuContext = remember(id) { SongMenuContext(albumId = id) }
    val state = rememberLazyListState()
    val items = rememberLinedRows(handle, vm.bigLines, AlbumLine::row, state, above = if (album != null) 1 else 0)
    // The rows say which songs are in the library while finds show.
    val mixed = asked && hasFinds

    PagedPicks(
        handle,
        { items.itemSnapshotList.items.mapNotNull(AlbumLine::row) },
        pickOf = { key, item -> if (item.row.id == key) Pickable(key, item.row, item.key).picked() else null },
        base = ListSpec.Album(id, asked),
    ) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = screenPadding(extraTop = DetailTopGap)) {
            album?.let { a ->
                item(key = "header") {
                    val counts = header
                    val outside = counts?.outside ?: 0
                    AlbumTop(
                        id, a, vm, onOpen,
                        AlbumTopFacts(
                            songs = counts?.shown ?: a.songCount,
                            lengthSeconds = ((counts?.lengthMs ?: a.durationMs) / 1000).toInt(),
                            genre = counts?.genre,
                            average = counts?.average,
                            whole = counts?.whole ?: a.songCount,
                            outside = outside,
                            askable = counts?.askable ?: 0,
                            coming = counts?.coming ?: 0,
                            missing = hasFinds,
                        ),
                        download = bigAlbumDownloadItem(id, outside),
                        onPlay = { vm.playAll(shuffle = false) },
                    )
                }
            }
            items(items.itemCount, key = { at -> items.peek(at)?.key() ?: "row:$at" }) { at ->
                when (val line = items[at]) {
                    null -> RowPlaceholder(numbered = true)
                    is AlbumLine.Disc -> DiscHeading(line.disc)
                    is AlbumLine.Song -> {
                        val item = line.item
                        SongRow(
                            item.row,
                            SongLead.Number(item.row.trackNo),
                            subtitle = { it.artist.takeIf { artist -> artist != album?.artist } },
                            menuContext = menuContext,
                            ownership = mixed,
                            highlight = item.row.id == highlight,
                        ) { vm.play(item) }
                    }
                }
            }
            album?.let { a ->
                if (moreByArtist.isNotEmpty()) {
                    item(key = "more-by-artist") { MoreByArtist(a.artist, moreByArtist, onOpen) }
                }
            }
        }
    }
}

// What an album's header says and offers, however its songs are read.
private class AlbumTopFacts(
    val songs: Int,
    val lengthSeconds: Int,
    val genre: String?,
    val average: Double?,
    val whole: Int,
    val outside: Int,
    val askable: Int,
    val coming: Int,
    val missing: Boolean,
)

@Composable
private fun AlbumTop(
    id: String,
    a: AlbumEntity,
    vm: AlbumViewModel,
    onOpen: (NavKey) -> Unit,
    facts: AlbumTopFacts,
    download: MoreItem?,
    onPlay: () -> Unit,
) {
    val libraryOnly by vm.libraryOnly.collectAsStateWithLifecycle()
    val ownOnly by vm.ownOnly.collectAsStateWithLifecycle()
    val loop by vm.loop.collectAsStateWithLifecycle()
    val canFollow by vm.canFollow.collectAsStateWithLifecycle()
    val genre = facts.genre
    val note = if (facts.missing) albumLibraryNote(facts.whole, facts.outside) else null
    val share = albumShareItem(id, a.title)
    val follows = facts.askable == 0 && facts.coming > 0 && canFollow
    // The setting already leaves them out; the switch is for this album alone.
    val offerOnlyMine = !libraryOnly
    val more = albumMore(share != null, download != null, facts.missing, offerOnlyMine, genre != null).mapNotNull { entry ->
        when (entry) {
            AlbumMore.Share -> share
            AlbumMore.Download -> download
            AlbumMore.AddMissing -> {
                val (label, enabled) = addMissingAction(facts.askable, follows)
                MoreItem(label, OctoIcons.AddToLibrary, enabled = enabled) { if (follows) vm.showDownloads() else vm.addMissing() }
            }
            AlbumMore.OnlyMine -> MoreItem(ONLY_MY_SONGS, OctoIcons.Library, checked = ownOnly) { vm.setOnlyMine(!ownOnly) }
            AlbumMore.Genre -> genre?.let { MoreItem("Go to genre", OctoIcons.Genres, detail = it, opensPage = true) { onOpen(GenreRoute(it)) } }
        }
    }
    AlbumHeader(
        artwork = a.artwork,
        loop = loop,
        title = a.title,
        artist = a.artist,
        details = factsLine(albumFacts(a.year, songs(facts.songs), facts.lengthSeconds.asLength(), genre, facts.average, note)),
        onArtist = { onOpen(ArtistRoute(a.artistId)) },
        onPlay = onPlay,
        onShuffle = vm::shuffle,
        // Favorites: the heart leads the control line.
        heart = { FavouriteHeart(FavouriteKind.Album, id) },
        more = more,
    )
}

@Composable
private fun DiscHeading(disc: Int) {
    Text(
        "Disc $disc",
        style = OctoType.caption.copy(fontWeight = FontWeight.Bold),
        color = OctoColors.TextMuted,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

// The top of an album page: the cover, the title under it, one quiet line
// with the artist (in the accent, opening their page when there is one to
// go to) and the album's facts, then one control line: the heart, and
// round Play, Shuffle and More at the end. `more` holds the album's lesser
// actions. An album with animated art plays its square `loop` in place of
// the cover while the page is in front.
@Composable
fun AlbumHeader(
    artwork: String?,
    title: String,
    artist: String,
    details: String,
    onArtist: (() -> Unit)?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    heart: (@Composable () -> Unit)? = null,
    more: List<MoreItem> = emptyList(),
    loop: AnimatedClip? = null,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val play = rememberLoopPlay(inView = !LocalPlayerCovers.current)
            Box {
                LoopVideo(loop, play, Modifier.elevation3(CoverShape).size(CoverSide).clip(CoverShape)) {
                    Artwork(artwork, CoverSide, shape = CoverShape, rim = loop == null)
                    loop?.poster?.let { poster ->
                        AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(CoverSide))
                    }
                }
                // One rim over the still and the loop alike.
                if (loop != null) Box(Modifier.size(CoverSide).artworkRim(CoverShape))
            }
            Spacer(Modifier.height(20.dp))
            Text(title, style = OctoType.title, color = OctoColors.TextPrimary, textAlign = TextAlign.Center)
            AlbumByline(artist, details, onArtist, Modifier.padding(top = 4.dp))
        }
        HeaderControlLine(
            control = heart,
            actions = {
                PlayShuffleButtons(onPlay, onShuffle)
                HeaderMore(more)
            },
            modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 10.dp),
        )
    }
}

// The artist and the album's facts as one centered line, wrapping only when
// it must. The artist's name is a link while there is a page to open.
@Composable
private fun AlbumByline(artist: String, details: String, onArtist: (() -> Unit)?, modifier: Modifier = Modifier) {
    val open by rememberUpdatedState(onArtist)
    val linkInk = OctoColors.AccentWords
    val quietInk = OctoColors.TextSecondary
    val line = remember(artist, details, onArtist != null, linkInk, quietInk) {
        buildAnnotatedString {
            val name = SpanStyle(color = if (onArtist != null) linkInk else quietInk)
            if (onArtist != null) {
                withLink(LinkAnnotation.Clickable("artist", TextLinkStyles(name)) { open?.invoke() }) { append(artist) }
            } else {
                withStyle(name) { append(artist) }
            }
            if (details.isNotEmpty()) append(" • $details")
        }
    }
    Text(line, style = OctoType.bodySmall, color = OctoColors.TextMuted, textAlign = TextAlign.Center, modifier = modifier)
}
