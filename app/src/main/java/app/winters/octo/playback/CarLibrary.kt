@file:OptIn(UnstableApi::class)

package app.winters.octo.playback

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FavouritesDao
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.ServerHeadEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.isFind
import app.winters.octo.discovery.asTrack
import app.winters.octo.catalog.UserDao
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.runsOcto
import app.winters.octo.data.userMessage
import app.winters.octo.design.OctoIcons
import app.winters.octo.discovery.ArtistExtrasSource
import app.winters.octo.discovery.CHARTS_ROW
import app.winters.octo.discovery.ChartAnswer
import app.winters.octo.discovery.ChartLists
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.Downloads
import app.winters.octo.discovery.OwnedFinds
import app.winters.octo.discovery.SearchTopSongs
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.ServerReads
import app.winters.octo.discovery.Station
import app.winters.octo.favourites.PinStore
import app.winters.octo.favourites.PinnedItem
import app.winters.octo.home.HomeSection
import app.winters.octo.home.mixGroup
import app.winters.octo.listening.PlayHistory
import app.winters.octo.listening.PlayShelves
import app.winters.octo.listening.PlayTotals
import app.winters.octo.listening.TotalsAnswer
import app.winters.octo.listening.TotalsFailed
import app.winters.octo.liveradio.LiveRadioStore
import app.winters.octo.liveradio.LiveShelfEntry
import app.winters.octo.liveradio.Places
import app.winters.octo.liveradio.RadioBrowser
import app.winters.octo.liveradio.liveShelf
import app.winters.octo.liveradio.radioId
import app.winters.octo.liveradio.usesMiles
import app.winters.octo.livelists.LiveListSongs
import app.winters.octo.livelists.LiveListStore
import app.winters.octo.livelists.LiveShown
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.player.PlayerSettings
import app.winters.octo.covers.PlaylistCoverStyle
import app.winters.octo.playlists.PlaylistArtSettings
import app.winters.octo.playlists.PlaylistFolderStore
import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.playlists.PlaylistFolderWords
import app.winters.octo.ui.playlist.placeKey
import app.winters.octo.server.QueueSync
import app.winters.octo.server.ResumeOffer
import app.winters.octo.server.ServerSync
import app.winters.octo.server.serverSourceId
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.SortSettings
import app.winters.octo.sort.SortedLibrary
import app.winters.octo.sort.sortFavourites
import app.winters.octo.sort.sortPlaylists
import app.winters.octo.ui.common.adoptedFinds
import app.winters.octo.ui.common.isOutsideLibrary
import app.winters.octo.ui.home.HomePrefs
import app.winters.octo.ui.home.songCountLine
import app.winters.octo.ui.liveradio.LIVE_NEAR_TITLE
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.ListLoaders
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// What lists hold when a car asks how many are in a row of Home's shelves.
private const val HISTORY_SHELF = 20
private const val FAVORITES_SHELF = 20
private const val SURPRISE_ALBUMS = 20
private const val SURPRISE_ARTISTS = 16
private const val ARTIST_TOP_SHOWN = 5

// A list the car could not have, with the phone's words for why.
class CarWords(val words: String) : Exception(words)

// The music as a car sees it: the phone's Home, Radio, Playlists and
// Library as four tabs, every list in the phone's order, lists that play
// from the chosen song, the server's lists and stations, and search. A long
// list is never held: its answer is read a page at a time from the loader
// the phone's own page reads (CarPaged), and it plays as a list by what it
// is, through the queue.
@Singleton
class CarLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: CatalogDao,
    private val online: OnlineDao,
    private val user: UserDao,
    private val favourites: FavouritesDao,
    private val history: PlayHistory,
    private val playlists: PlaylistStore,
    private val sorted: SortedLibrary,
    private val sorting: SortSettings,
    private val pins: PinStore,
    private val homePrefs: HomePrefs,
    private val liveLists: LiveListStore,
    private val liveSongs: LiveListSongs,
    private val liveRadio: LiveRadioStore,
    private val radioCatalog: RadioBrowser,
    private val places: Places,
    private val offline: OfflineDownloads,
    private val downloads: Downloads,
    private val player: PlayerSettings,
    private val sessions: SessionRepository,
    private val discovery: Discovery,
    private val charts: ChartLists,
    private val extras: ArtistExtrasSource,
    private val tops: SearchTopSongs,
    private val serverSync: ServerSync,
    private val serverQueue: QueueSync,
    private val shelves: CarShelves,
    private val lists: CarLists,
    private val search: CarSearch,
    private val artSettings: PlaylistArtSettings,
    private val loaders: ListLoaders,
    private val likes: LikeStore,
    private val owners: WorkOwners,
    private val servers: ServerReads,
    private val totals: PlayTotals,
    private val folders: PlaylistFolderStore,
    merge: CatalogMerge,
    // Finds a start plays before their rows are written.
    private val finds: FindMap,
    // Songs the server owns that the phone's copy lacked: shown as library songs.
    private val owned: OwnedFinds,
) {
    // Finds downloaded into the library, and songs the server owns already:
    // neither is marked as outside the library.
    private fun inLibraryFinds(): Set<String> = adoptedFinds(downloads.phases.value) + owned.ids.value

    // The cover style the listener chose for playlists, as the phone draws them.
    @Volatile private var coverStyle = PlaylistCoverStyle.Designed
    private val look = CarLook(context) { coverStyle }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reads = CarReads(merge, totals)

    // The long lists, each read a page at a time from the phone's loader.
    private val pages = CarPages(
        loaders, lists, owners, servers, liveSongs,
        liveRules = { id -> liveLists.lists.map { all -> all.firstOrNull { it.id == id }?.query } },
        catalog = catalog,
        look = look,
        doneAmong = { ids -> offline.doneAmong(ids) },
        adopted = ::inLibraryFinds,
        order = ::order,
        albumWithFinds = ::albumWithFinds,
        libraryOnly = ::libraryOnly,
        scope = scope,
        keptState = { merge.swapStamp.value to offline.doneVersion.value },
    )

    // Told when a list fetched behind another (a whole album, top songs)
    // came back changing it.
    var listChanged: (String) -> Unit = {}

    // What the car's lists play, for a choice made in the car.
    val plays = CarPlays(::playList, ::signedIn) { discovery.listGone(it) }

    init {
        scope.launch { artSettings.style.collect { coverStyle = it } }
    }

    fun root(): MediaItem = look.folder(CarNode.Root, "Octo")

    // What changes reach the car's open lists, by what they read.
    val inputs: Flow<CarInput> = carInputs(
        listOf(
            CarInput.Library to carLibraryInput(serverSync.ready, serverSync.last.map { it?.at }, catalog.libraryTrackCount()),
            // A folder pull or a placement changes what the tab and each folder hold.
            CarInput.Playlists to combine(
                playlists.playlists.map { all -> all.map { listOf(it.id, it.name, it.songCount, it.upkeep, it.serverId) } },
                folders.offered,
                folders.tree,
                ::Triple,
            ),
            CarInput.Liked to likes.changes,
            CarInput.Favorites to combine(favourites.likedAlbumIds(), favourites.likedArtistIds(), ::Pair),
            CarInput.LiveLists to liveLists.lists,
            CarInput.Kept to liveRadio.kept,
            CarInput.Shelves to shelves.version,
            CarInput.HomeOrder to homePrefs.homeSections,
            CarInput.Sorts to combine(
                listOf(
                    SortList.Playlists, SortList.Albums, SortList.Artists, SortList.Songs, SortList.Liked,
                    SortList.Favourites, SortList.Downloads, SortList.ArtistAlbums, SortList.GenreSongs,
                ).map(sorting::order),
            ) { it.toList() },
            CarInput.Pins to pins.shown,
            CarInput.Adopted to combine(downloads.phases, owned.ids) { phases, mine -> adoptedFinds(phases) + mine },
            CarInput.Downloads to offline.doneVersion,
            CarInput.LibraryOnly to player.prefs.map { it.libraryOnly },
            CarInput.Offer to serverQueue.offer.map(::resumeOfferKey),
            CarInput.CoverStyle to artSettings.style,
            CarInput.Copy to combine(serverSync.copyWait, serverSync.copyRun, ::carCopyKey),
            CarInput.Totals to totals.failed,
        ),
    )

    // The first copy's status row while the server's music is still to
    // come, or null.
    private fun copyStatus(): MediaItem? {
        if (serverSync.copyWait.value?.awaiting != true) return null
        val account = (sessions.state.value as? SessionState.SignedIn)?.session?.id
        return look.copyStatus(serverSync.copyRun.value?.takeIf { it.account == account && it.whole })
    }

    // The whole answer for a list a car opened, before it is cut into the
    // page asked for. `limit` is the most tabs this car shows; `ownAccord`
    // says the car opened it itself, rather than because it was told of a
    // change.
    suspend fun children(parentId: String, limit: Int?, ownAccord: Boolean = true): List<MediaItem> {
        val node = parseCarId(parentId) ?: return emptyList()
        if (needsServer(node) && !signedIn()) throw CarNotSignedIn()
        val whole = wholeListOf(node)
        if (isPaged(whole)) return pagedAnswer(node, whole, ownAccord)
        return when (node) {
            CarNode.Root -> carTabs(limit).let { tabs -> tabs.tabs.map { tab(it, tabs) } }
            CarNode.More -> carTabs(limit).more.map { tab(it, null) }
            CarNode.Home -> home()
            is CarNode.Shelf -> carFitOrParts(node, shelfItems(node), splitRows, style = shelfStyles(node.section).first)
            CarNode.Radio -> carHomeAnswer(null, radioShelves(), homeParts)
            CarNode.Playlists -> carFitOrParts(node, playlistsTab(), splitRows, style = CarStyle.List)
            is CarNode.PlaylistFolder -> carFitOrParts(node, folderItems(node.id), splitRows, style = CarStyle.List)
            CarNode.Library -> libraryRows()
            CarNode.Favorites -> favoriteRows()
            CarNode.Charts -> shelves.current().charts.map(look::chart)
            is CarNode.Part -> partOf(node)
            is CarNode.Heading -> listed(node.parent, ownAccord)?.let { carLetterAnswer(node, it, splitRows) }.orEmpty()
            else -> listed(node, ownAccord)?.let { list ->
                // An empty library list says the server's music is on its way while it is.
                val coming = carCopyAnswer(node, list.count, ::copyStatus)
                if (coming != null) listOf(coming) else carListAnswer(node, list, splitRows)
            }.orEmpty()
        }
    }

    private val splitRows = CarSplitRows(
        letter = { node, style, covers, count -> look.heading(node, style, covers, count) },
        part = { node, range, firstTitle, style, covers -> look.part(node, range, firstTitle, style, covers) },
    )

    // A part of a list held whole opens what its row was cut from, built the
    // same way, so it holds exactly the items its row names.
    private suspend fun partOf(node: CarNode.Part): List<MediaItem> {
        val items = when (val parent = node.parent) {
            CarNode.Playlists -> playlistsTab()
            is CarNode.PlaylistFolder -> folderItems(parent.id)
            is CarNode.Shelf -> shelfItems(parent)
            is CarNode.Heading -> listed(parent.parent, ownAccord = false)?.under(parent.heading).orEmpty()
            else -> listed(parent, ownAccord = false)?.all().orEmpty()
        }
        return carPartItems(node.index, items)
    }

    // The list a letter or a part of a list belongs to.
    private fun wholeListOf(node: CarNode): CarNode = when (node) {
        is CarNode.Heading -> wholeListOf(node.parent)
        is CarNode.Part -> wholeListOf(node.parent)
        else -> node
    }

    // The lists that can hold any number of songs: read a page at a time.
    private fun isPaged(node: CarNode): Boolean = when (node) {
        CarNode.Songs, CarNode.Liked, CarNode.Downloads, is CarNode.Genre, is CarNode.Album, is CarNode.Playlist,
        is CarNode.LiveList, is CarNode.OctoList, is CarNode.Station, is CarNode.OnlineAlbum,
        -> true
        else -> false
    }

    // The answer for a long list (`whole`) or one of its letters or parts,
    // as one state of the library. A list opened of the car's own accord is
    // read from the server again where the server lists it; an empty library
    // list says the server's music is on its way while it is.
    private suspend fun pagedAnswer(node: CarNode, whole: CarNode, ownAccord: Boolean): List<MediaItem> {
        val paged = pages.paged(whole, ownAccord && node == whole) ?: return emptyList()
        try {
            if (node == whole && CarInput.Copy in carInputsOf(node)) {
                val empty = paged.listing.lead.isEmpty() && paged.listing.loader.after(null, 1).isEmpty()
                carCopyAnswer(node, if (empty) 0 else 1, ::copyStatus)?.let { return listOf(it) }
            }
            return reads.answer(node, whole, paged.listing, splitRows)
        } finally {
            withContext(NonCancellable) { paged.release() }
        }
    }

    // Whether a library album shows the songs it lacks, from the server's
    // listing: an Octo server signed in, and Library songs only off.
    private suspend fun albumWithFinds(): Boolean =
        (sessions.state.value as? SessionState.SignedIn)?.session?.runsOcto == true && !libraryOnly()

    private suspend fun order(list: SortList): SortOrder = sorting.order(list).first()

    // A list's items, each built when an answer needs it, with the letters
    // the phone files them under when it is sorted by name. Every list here
    // is album-, artist- or answer-sized.
    private suspend fun listed(node: CarNode, ownAccord: Boolean): CarListing? {
        val adopted = inLibraryFinds()
        suspend fun songs(tracks: List<TrackEntity>, list: CarNode): List<MediaItem> {
            val done = offline.doneAmong(tracks.map { it.id })
            return tracks.map { look.song(it, list, isOutsideLibrary(it.id, adopted), it.id in done) }
        }
        fun of(items: List<MediaItem>, style: CarStyle? = null) = CarListing.of(items, style)
        return when (node) {
            CarNode.Albums -> sorted.albums(SortList.Albums).first().let { s ->
                CarListing(s.items.size, carLetters(s.headings), CarStyle.Grid, ::albumCountLine, { s.items[it].artwork }) { look.album(s.items[it]) }
            }
            CarNode.Artists -> sorted.artists().first().let { s ->
                CarListing(s.items.size, carLetters(s.headings), countLine = ::artistCountLine, coverOf = { s.items[it].artwork }) { look.artist(s.items[it]) }
            }
            CarNode.Genres -> of(catalog.genres().first().map(look::genre))
            CarNode.FavoriteAlbums -> of(favoriteAlbums().map(look::album), CarStyle.Grid)
            CarNode.FavoriteArtists -> of(favoriteArtists().map(look::artist))
            CarNode.MostPlayed -> of(songs(playShelves().mostPlayed, node))
            CarNode.Chart -> of(songs(shelves.current().chart, node))
            is CarNode.Artist -> of(artistPage(node, adopted))
            is CarNode.ChartList -> of(songs(chartSongs(node), node))
            is CarNode.OnlineArtist -> of(onlineArtistPage(node, adopted))
            is CarNode.Found -> of(search.kept(node.token)?.let { foundAll(it, node.kind) }.orEmpty())
            is CarNode.Top -> of(songs(search.kept(node.token)?.top?.songs.orEmpty().map { it.track }, node))
            // The server is asked again only when the car opens the row
            // itself; a read for a change shows the last answer.
            is CarNode.Retry -> of((if (ownAccord) search.retry(node.token) else search.kept(node.token))?.let { outsideRows(it) }.orEmpty())
            else -> null
        }
    }

    // What was played, from the stored totals: Home's and the car's shelves.
    // While they cannot be brought up to date, TotalsFailed says why.
    private suspend fun playShelves(): PlayShelves = when (val answer = history.shelves.first()) {
        is TotalsAnswer.Value -> answer.value
        is TotalsAnswer.Problem -> throw TotalsFailed(answer.words)
    }

    // One item by its id, for a car that asks about a single place or song.
    // Nothing is asked of the server: a list it holds is named from its id.
    suspend fun lookup(id: String, limit: Int?): MediaItem? {
        val node = parseCarId(id) ?: return null
        return when (node) {
            CarNode.Root -> root()
            CarNode.Home, CarNode.Radio, CarNode.Playlists, CarNode.Library, CarNode.More -> tab(node, carTabs(limit))
            CarNode.Albums, CarNode.Artists, CarNode.Songs, CarNode.Genres, CarNode.Favorites, CarNode.Downloads -> libraryRow(node)
            CarNode.FavoriteAlbums, CarNode.FavoriteArtists, CarNode.Liked -> favoriteRow(node)
            is CarNode.Album -> catalog.album(node.id).first()?.let(look::album)
            is CarNode.Artist -> catalog.artist(node.id).first()?.let(look::artist)
            is CarNode.Playlist -> playlists.playlists.first().firstOrNull { it.id == node.id }?.let(look::playlist)
            is CarNode.PlaylistFolder -> folderRow(node.id)
            is CarNode.LiveList -> liveLists.lists.first().firstOrNull { it.id == node.id }?.let { look.liveList(it, liveSongs.covers(it.id, it.query)) }
            is CarNode.Song -> trackOf(node.trackId)?.let { look.song(it, node.list, isOutsideLibrary(it.id, inLibraryFinds()), offline.isDone(it.id)) }
            is CarNode.OctoList -> look.folder(node, node.name)
            is CarNode.Station -> look.radio(node)
            is CarNode.ChartList -> look.folder(node, node.name)
            is CarNode.Genre -> look.folder(node, node.name)
            is CarNode.Shelf -> shelfRow(node.section)
            is CarNode.Heading -> look.folder(node, node.heading)
            CarNode.Charts -> look.folder(node, HomeSection.Charts.title)
            else -> if (node.isBrowsable) look.folder(node, "") else null
        }
    }

    // What a car is told when a list it opened could not be had: the words
    // for signing in, for a list the server no longer has (which leaves the
    // phone's lists too), or the phone's words for the failure.
    fun browseWords(parentId: String, failure: Throwable): String {
        val node = parseCarId(parentId)
        carTotalsWords(failure, totals::foreground)?.let { return it }
        return when {
            failure is CarNotSignedIn -> CAR_SIGN_IN
            failure is app.winters.octo.subsonic.SubsonicException.NotFound && (node is CarNode.OctoList || node is CarNode.Station) -> {
                val (id, name) = when (node) {
                    is CarNode.OctoList -> node.id to node.name
                    is CarNode.Station -> node.id to node.name
                    else -> return failure.userMessage()
                }
                discovery.listGone(id)
                goneLine(name)
            }
            else -> failure.userMessage()
        }
    }

    // Lets a car app read the covers of the items it was just sent. Icons
    // from the app's own drawables need no grant.
    fun grantArtwork(packageName: String, items: List<MediaItem>) {
        items.mapNotNull { it.mediaMetadata.artworkUri }.filter { it.scheme == ContentResolver.SCHEME_CONTENT }.forEach {
            context.grantUriPermission(packageName, it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // A search's answer as the car shows it, as on the phone's All: artists,
    // songs (the library's first), the named artist's top songs, albums
    // (the library's, then those it holds part of, then the rest),
    // playlists, and the server's artists last when the library has none.
    // Each kind with more than shown ends with "See all".
    suspend fun searchItems(answer: CarSearchAnswer): List<MediaItem> {
        val adopted = inLibraryFinds()
        val rows = carSearchRows(answer)
        val done = offline.doneAmong(rows.flatMap { row -> row.entries.mapNotNull { (it as? SearchEntry.Song)?.track?.id } })
        return rows.flatMap { row ->
            val items = row.entries.map { entry ->
                when (entry) {
                    is SearchEntry.LibraryArtist -> look.artist(entry.artist)
                    is SearchEntry.OutsideArtist -> look.onlineArtist(entry.artist)
                    is SearchEntry.Song -> look.song(entry.track, entry.list, isOutsideLibrary(entry.track.id, adopted), entry.track.id in done)
                    is SearchEntry.LibraryAlbum -> look.album(entry.album)
                    is SearchEntry.OutsideAlbum -> look.onlineAlbum(entry.album)
                    is SearchEntry.Playlist -> look.playlist(entry.playlist)
                    // A kind's whole list shows as the Library shows it: albums as tiles, the rest as rows.
                    is SearchEntry.SeeAll -> look.seeAll(entry.node, row.title, browsable = CarStyle.Grid.takeIf { (entry.node as? CarNode.Found)?.kind == FoundKind.Albums })
                    is SearchEntry.Retry -> look.retry(entry.token)
                }
            }
            items.map { look.grouped(it, row.title) }
        }
    }

    // A kind of a search whole, for its "See all".
    private suspend fun foundAll(answer: CarSearchAnswer, kind: FoundKind): List<MediaItem> {
        val adopted = inLibraryFinds()
        val done = if (kind == FoundKind.Songs) offline.doneAmong(foundSongs(answer).map { it.id }) else emptySet()
        val list = CarNode.Found(answer.token, FoundKind.Songs)
        return when (kind) {
            FoundKind.Artists -> answer.library.artists.map(look::artist) + answer.outside?.artists.orEmpty().map(look::onlineArtist)
            FoundKind.Songs -> foundSongs(answer).map { look.song(it, list, isOutsideLibrary(it.id, adopted), it.id in done) }
            FoundKind.Albums -> answer.library.albums.map(look::album) +
                answer.outside?.let { it.partAlbums + it.albums }.orEmpty().map(look::onlineAlbum)
            FoundKind.Playlists -> answer.library.playlists.map(look::playlist)
        }
    }

    // What the server found outside the library, after a search tried again.
    private fun outsideRows(answer: CarSearchAnswer): List<MediaItem> {
        if (answer.outsideFailed) return listOf(look.retry(answer.token))
        val adopted = inLibraryFinds()
        val found = answer.outside ?: return emptyList()
        val list = CarNode.Found(answer.token, FoundKind.Songs)
        return found.artists.map { look.grouped(look.onlineArtist(it), "Artists") } +
            found.songs.map { look.grouped(look.song(it, list, isOutsideLibrary(it.id, adopted), false), "Songs") } +
            (found.partAlbums + found.albums).map { look.grouped(look.onlineAlbum(it), "Albums") }
    }

    // The lists the car's choices play from (CarPlays), each by what it is,
    // so the queue reads it a page at a time. `fresh` asks the server again;
    // otherwise what the car was shown serves.
    private suspend fun playList(node: CarNode, fresh: Boolean): CarList? = when (node) {
        is CarNode.Album -> catalog.album(node.id).first()?.let { album -> pages.albumPlay(node, album.title) }
        is CarNode.Artist -> artistTop(node, fetchBehind = false)?.let { top ->
            carIds(top.map { it.id }, QueueSource.Played(catalog.artist(node.id).first()?.name))
        }
        is CarNode.Playlist -> playlists.playlists.first().firstOrNull { it.id == node.id }?.let { list ->
            CarList(ListSpec.Playlist(node.id), QueueSource.Played(list.name))
        }
        CarNode.Liked -> CarList(ListSpec.Songs(SongScope.Liked, order(SortList.Liked)), QueueSource.Played(CAR_LIKED))
        // A live list plays as the car shows it, from the car's own copy
        // when it keeps one and no page of the phone shows the list.
        is CarNode.LiveList -> liveLists.lists.first().firstOrNull { it.id == node.id }?.let { list ->
            val owner = lists.keptFor<CarOwned<CarLive>>(node)?.owner
            CarList(ListSpec.Live(list.id, list.query, list.name), QueueSource.Played(list.name), pageOwner = owner)
        }
        CarNode.MostPlayed -> carIds(playShelves().mostPlayed.map { it.id }, NoSource)
        CarNode.Songs -> CarList(ListSpec.Songs(SongScope.All, order(SortList.Songs)), QueueSource.Played("Songs"))
        CarNode.Downloads -> CarList(ListSpec.Downloads(order(SortList.Downloads)), NoSource)
        is CarNode.Genre -> CarList(ListSpec.Songs(SongScope.Genre(node.name), order(SortList.GenreSongs)), QueueSource.Played(node.name))
        CarNode.Chart -> carIds(shelves.current().chart.map { it.id }, NoSource)
        is CarNode.ChartList -> {
            val kept = if (fresh) null else lists.keptFor<List<TrackEntity>>(node)
            val songs = kept ?: chartSongs(node)
            carIds(songs.map(TrackEntity::id), QueueSource.Played(node.name), network = kept == null)
        }
        // A list or an album the server answers for plays as the car was
        // shown it, from the car's own read; a station is asked again.
        is CarNode.OctoList -> CarList(ListSpec.Online(OnlineKind.List, node.id), QueueSource.Played(node.name), network = true)
        is CarNode.Station -> CarList(ListSpec.Online(OnlineKind.Station, node.id), QueueSource.Station(node.id, node.name), network = true)
        is CarNode.OnlineAlbum -> pages.onlineAlbumPlay(node, fresh)
        is CarNode.OnlineArtist -> lists.keptFor<OnlineArtistView>(node)?.let { carIds(it.top.map(TrackEntity::id), QueueSource.Played(it.name)) }
        is CarNode.Found -> search.kept(node.token)?.let { carIds(foundSongs(it).map(TrackEntity::id), NoSource) }
        is CarNode.Top -> search.kept(node.token)?.top?.let { top -> carIds(top.songs.map { it.track.id }, NoSource) }
        is CarNode.Live -> liveLineup(node)
        is CarNode.Near -> nearLineup(node)
        CarNode.Resume -> resumeList()
        is CarNode.Shelf -> when (HomeSection.ofId(node.section)) {
            HomeSection.MostPlayed -> playList(CarNode.MostPlayed, fresh)
            HomeSection.Charts -> playList(CarNode.Chart, fresh)
            else -> null
        }
        is CarNode.Heading -> playList(node.parent, fresh)
        is CarNode.Part -> when (node.parent) {
            CarNode.Radio, CarNode.Playlists -> null
            else -> playList(node.parent, fresh)
        }
        else -> null
    }


    private suspend fun chartSongs(node: CarNode.ChartList): List<TrackEntity> = lists.fetch(node) {
        when (val answer = charts.answer(node.id)) {
            is ChartAnswer.Got -> answer.list.songs.map { it.track }
            ChartAnswer.None -> emptyList()
            is ChartAnswer.Failed -> throw IllegalStateException(answer.refusal ?: "No chart")
        }
    }

    // An artist beyond the library as the server gives them: their name,
    // top songs and albums; empty when no server answered.
    data class OnlineArtistView(val name: String, val top: List<TrackEntity>, val albums: List<app.winters.octo.discovery.OnlineAlbum>)

    private suspend fun onlineArtistView(node: CarNode.OnlineArtist): OnlineArtistView = lists.fetch(node) {
        val page = discovery.artist(node.id) ?: return@fetch OnlineArtistView("", emptyList(), emptyList())
        val top = tops.forArtist(page.artist.name, null, node.id)?.songs.orEmpty().map { it.track }
        OnlineArtistView(page.artist.name, top, page.albums)
    }

    private suspend fun onlineArtistPage(node: CarNode.OnlineArtist, adopted: Set<String>): List<MediaItem> {
        val view = onlineArtistView(node)
        val only = libraryOnly()
        val top = view.top.filter { !only || !isOutsideLibrary(it.id, owned.ids.value) }.take(ARTIST_TOP_SHOWN)
        val done = offline.doneAmong(top.map { it.id })
        return top.map { look.grouped(look.song(it, node, isOutsideLibrary(it.id, adopted), it.id in done), "Top songs") } +
            view.albums.map { look.grouped(look.onlineAlbum(it), "Albums") }
    }

    // An artist's page: their top songs from the server when it ranks them,
    // then their albums in the order the phone's artist page uses.
    private suspend fun artistPage(node: CarNode.Artist, adopted: Set<String>): List<MediaItem> {
        val shown = artistTop(node, fetchBehind = true).orEmpty().take(ARTIST_TOP_SHOWN)
        val done = offline.doneAmong(shown.map { it.id })
        val top = shown.map { look.grouped(look.song(it, node, isOutsideLibrary(it.id, adopted), it.id in done), "Top songs") }
        val albums = sorted.albums(SortList.ArtistAlbums, artistId = node.id).first().items.map { look.grouped(look.album(it), "Albums") }
        return top + albums
    }

    private suspend fun artistTop(node: CarNode.Artist, fetchBehind: Boolean): List<TrackEntity>? {
        val artist = catalog.artist(node.id).first() ?: return null
        val top = if (fetchBehind) {
            lists.behind(node, { extras.forArtist(artist)?.topSongs }) { listChanged(carId(node)) }
        } else {
            lists.keptFor<Behind<List<TrackEntity>>>(node)?.value
        }
        val only = libraryOnly()
        return top?.let { songs -> if (only) songs.filterNot { isOutsideLibrary(it.id, owned.ids.value) } else songs }
    }

    // Home in the car: the resume row, then the phone's shelves in the
    // listener's order (see carHomeAnswer).
    private suspend fun home(): List<MediaItem> {
        val order = homePrefs.homeSections.first()
        val empty = catalog.libraryTrackCount().first() == 0
        val shelvesNow = homeShelves(carHomeWanted(order, empty))
        // A first copy on its way leads Home, as on the phone.
        val lead = copyStatus() ?: serverQueue.offer.value?.let(look::resume)
        return carHomeAnswer(lead, carHomeShelves(order, shelvesNow, empty), homeParts)
    }

    private val homeParts = CarHomeParts(
        grouped = { item, title -> look.grouped(item, title) },
        seeAll = { shelf ->
            val (browsable, playable) = shelfStyles(shelf.id)
            look.seeAll(if (shelf.id == HomeSection.Charts.id) CarNode.Charts else CarNode.Shelf(shelf.id), shelf.title, browsable, playable)
        },
        row = { shelf -> shelfRow(shelf.id) },
    )

    // A shelf as one row that opens it whole, in the styles its items show in.
    private fun shelfRow(id: String): MediaItem {
        val (browsable, playable) = shelfStyles(id)
        val title = if (id == NEAR_SHELF) LIVE_NEAR_TITLE else HomeSection.ofId(id)?.title.orEmpty()
        return look.folder(CarNode.Shelf(id), title, browsable = browsable, playable = playable)
    }

    // How a shelf's items show when it opens whole, as on Home: covers as
    // tiles, songs as rows; the charts, which have no covers, as rows.
    private fun shelfStyles(id: String): Pair<CarStyle, CarStyle> = when (id) {
        HomeSection.Charts.id -> CarStyle.List to CarStyle.List
        HomeSection.MostPlayed.id -> CarStyle.Grid to CarStyle.List
        else -> CarStyle.Grid to CarStyle.Grid
    }

    // A shelf's items as Home or the Radio tab shows them, for the shelf
    // opened whole and for its parts alike.
    private suspend fun shelfItems(node: CarNode.Shelf): List<MediaItem> {
        if (node.section == NEAR_SHELF) return nearItems(shelves.current())
        val section = HomeSection.ofId(node.section) ?: return emptyList()
        return homeShelves(setOf(section))[section].orEmpty()
    }

    // Each of `wanted` of Home's shelves as the phone's Home fills it. The
    // shelves of what was played come from the stored totals, read once;
    // while those cannot be brought up to date, they are left out, and the
    // car hears when they are back.
    private suspend fun homeShelves(wanted: Set<HomeSection>): Map<HomeSection, List<MediaItem>> {
        val server = shelves.current()
        val adopted = inLibraryFinds()
        val played = if (wanted.any { it in PLAYED }) playedShelves() else null
        val done = offline.doneAmong(played?.mostPlayed.orEmpty().map { it.id } + server.chart.take(CHARTS_ROW).map { it.id })
        fun song(track: TrackEntity, list: CarNode) = look.song(track, list, isOutsideLibrary(track.id, adopted), track.id in done)
        val rediscovered = played?.rediscovery
        val out = LinkedHashMap<HomeSection, List<MediaItem>>()
        for (section in wanted) {
            out[section] = when (section) {
                HomeSection.Pinned -> pins.items.first().mapNotNull { pin ->
                    when (pin) {
                        is PinnedItem.Album -> look.album(pin.album)
                        is PinnedItem.Artist -> look.artist(pin.artist)
                        is PinnedItem.Playlist -> if (isToolPlaylist(pin.playlist.name)) null else look.playlist(pin.playlist)
                    }
                }
                HomeSection.RecentlyPlayed -> played?.recentlyPlayed.orEmpty().take(HISTORY_SHELF).map(look::album)
                HomeSection.MadeForYou -> server.madeForYou.map(::homeStation)
                HomeSection.Stations -> radioStations(server).map(::homeStation)
                HomeSection.InternetRadio -> liveEntries(server).map { look.live(it, sourceIdNow()) }
                HomeSection.YearMixes, HomeSection.GenreMixes, HomeSection.ArtistMixes, HomeSection.CountryMixes ->
                    server.groups.firstOrNull { it.first == section.mixGroup }?.second.orEmpty().map(::homeStation)
                HomeSection.RecentlyAdded -> catalog.recentAlbums(HISTORY_SHELF).first().map(look::album)
                HomeSection.MostPlayed -> played?.mostPlayed.orEmpty().take(HISTORY_SHELF).map { song(it, CarNode.MostPlayed) }
                HomeSection.FavoriteAlbums -> favourites.likedAlbums().first().sortedByDescending { it.likedAt }.take(FAVORITES_SHELF).map { look.album(it.album) }
                HomeSection.Charts -> server.chart.take(CHARTS_ROW).map { song(it, CarNode.Chart) }
                HomeSection.Artists -> picks().second.map(look::artist)
                HomeSection.SomethingDifferent -> picks().first.map(look::album)
                HomeSection.NotPlayedLately -> rediscovered?.notPlayedLately.orEmpty().map(look::album)
                HomeSection.NeverFinished -> rediscovered?.neverFinished.orEmpty().map(look::album)
                HomeSection.NeverPlayed -> rediscovered?.neverPlayed.orEmpty().map(look::album)
            }
        }
        return out
    }

    private suspend fun playedShelves(): PlayShelves? = try {
        playShelves()
    } catch (e: TotalsFailed) {
        totals.foreground()
        null
    }

    // Home's random shelves (Something different, Artists), picked once and
    // again only when the library changes size or is copied anew, as the
    // phone's Home picks them, so the car's Home does not reshuffle. The
    // size is read only while no save is applying, so a save picks them
    // again once, at its end.
    private val picker = CarHomePicks(merge.applyStamp, { catalog.libraryTrackCount().first() }, { serverSync.last.first()?.at }) {
        catalog.randomAlbums(SURPRISE_ALBUMS) to catalog.randomArtists(SURPRISE_ARTISTS)
    }

    private suspend fun picks() = picker.now()

    private fun homeStation(station: Station): MediaItem = look.homeStation(station)

    private fun radioStations(server: CarServerShelves): List<Station> = carRadioStations(server.stations, server.groups.flatMap { it.second })

    // Radio: Octo's stations, the Live radio shelf, and the stations near the
    // listener's saved place, grouped as on the phone (see carHomeAnswer).
    private suspend fun radioShelves(): List<CarShelf> {
        val home = homeShelves(setOf(HomeSection.Stations, HomeSection.InternetRadio))
        return carRadioShelves(home[HomeSection.Stations].orEmpty(), home[HomeSection.InternetRadio].orEmpty(), nearItems(shelves.current()))
    }

    private suspend fun nearItems(server: CarServerShelves): List<MediaItem> {
        val miles = usesMiles(places.localeCountry())
        return server.near.map { look.near(it, miles) }
    }

    private suspend fun liveEntries(server: CarServerShelves): List<LiveShelfEntry> =
        liveShelf(server.stations.filter { it.streamUrl != null }, liveRadio.kept.first().stations)

    private suspend fun liveLineup(node: CarNode.Live): CarList? {
        val (lineup, started) = liveLineupOf(liveEntries(shelves.current()), node.key) ?: return null
        if (started != null) scope.launch { runCatching { radioCatalog.started(started) } }
        return lineup
    }

    private suspend fun nearLineup(node: CarNode.Near): CarList? {
        val near = shelves.current().near
        val start = near.indexOfFirst { it.uuid == node.uuid }.takeIf { it >= 0 } ?: return null
        scope.launch { runCatching { radioCatalog.started(node.uuid) } }
        return carIds(near.map { it.radioId() }, QueueSource.Played(LIVE_NEAR_TITLE), start = start)
    }

    // The other device's queue, read from the server again into the play's
    // own copy, from its song where it was left. The offer is put away.
    private suspend fun resumeList(): CarList {
        val offer = serverQueue.offer.value ?: return carIds(emptyList(), NoSource, network = true)
        serverQueue.taken(offer)
        return CarList(ListSpec.ServerQueue, NoSource, network = true)
    }

    // Playlists: Liked songs, the live lists, the folders A to Z (while
    // folders are offered), the playlists in no folder in the phone's order
    // (never Octo's library upkeep lists, which act on songs added), then
    // the lists Octo made, under each kind's heading. Each list's covers are
    // found by a walk that stops at four.
    private suspend fun playlistsTab(): List<MediaItem> {
        val liked = user.likedCount().first()
        val live = liveLists.lists.first().map { list -> look.liveList(list, liveSongs.covers(list.id, list.query)) }
        val order = sorting.order(SortList.Playlists).first()
        val own = carFolderChildren(carTree(), null, sortPlaylists(carPlaylists(playlists.playlists.first()), order)).map(::carChild)
        val octo = shelves.current().groups.flatMap { (group, items) -> items.map { look.grouped(look.station(it), group.title) } }
        val likedCovers = if (liked > 0) likes.likedCovers() else emptyList()
        return listOfNotNull(liked.takeIf { it > 0 }?.let { look.liked(it, likedCovers) }) + live + own + octo
    }

    // The folders a car shows, or none while folders are not offered.
    private suspend fun carTree(): PlaylistFolderTree? = if (folders.offered.first()) folders.tree.first() else null

    // A folder's rows: its folders, then its playlists in the phone's order.
    private suspend fun folderItems(id: String): List<MediaItem> {
        val tree = carTree() ?: return emptyList()
        val order = sorting.order(SortList.Playlists).first()
        return carFolderChildren(tree, id, sortPlaylists(carPlaylists(playlists.playlists.first()), order)).map(::carChild)
    }

    // A folder's own row, for a car that asks about it by its id.
    private suspend fun folderRow(id: String): MediaItem? {
        val tree = carTree() ?: return null
        val folder = tree.folder(id) ?: return null
        val keys = carPlaylists(playlists.playlists.first()).map { it.placeKey }
        val subtitle = PlaylistFolderWords.folderSubtitle(tree.playlistsIn(id, keys).size, tree.children(id).size)
        return look.folder(CarNode.PlaylistFolder(id), folder.name, OctoIcons.PlaylistFolder, subtitle = subtitle, browsable = CarStyle.List)
    }

    // A folder or playlist as a car's row: a folder browses, its glyph as its picture.
    private fun carChild(child: CarFolderChild): MediaItem = when (child) {
        is CarFolderChild.Folder ->
            look.folder(CarNode.PlaylistFolder(child.folder.id), child.folder.name, OctoIcons.PlaylistFolder, subtitle = child.subtitle, browsable = CarStyle.List)
        is CarFolderChild.Playlist -> look.playlist(child.playlist)
    }

    private fun libraryRows(): List<MediaItem> =
        listOf(CarNode.Favorites, CarNode.Artists, CarNode.Albums, CarNode.Songs, CarNode.Genres, CarNode.Downloads).map(::libraryRow)

    private fun libraryRow(node: CarNode): MediaItem = when (node) {
        CarNode.Favorites -> look.folder(node, "Favorites", OctoIcons.Like, browsable = CarStyle.Category)
        CarNode.Artists -> look.folder(node, "Artists", OctoIcons.Artist)
        CarNode.Albums -> look.folder(node, "Albums", OctoIcons.Album, browsable = CarStyle.Grid, playable = CarStyle.Grid)
        CarNode.Songs -> look.folder(node, "Songs", OctoIcons.Songs)
        CarNode.Genres -> look.folder(node, "Genres", OctoIcons.Genres)
        else -> look.folder(CarNode.Downloads, "Downloads", OctoIcons.Downloaded)
    }

    private fun favoriteRows(): List<MediaItem> = listOf(CarNode.Liked, CarNode.FavoriteAlbums, CarNode.FavoriteArtists).map(::favoriteRow)

    private fun favoriteRow(node: CarNode): MediaItem = when (node) {
        CarNode.Liked -> look.folder(node, CAR_LIKED, OctoIcons.Liked)
        CarNode.FavoriteAlbums -> look.folder(node, CAR_FAVORITE_ALBUMS, OctoIcons.Album, browsable = CarStyle.Grid)
        else -> look.folder(CarNode.FavoriteArtists, CAR_FAVORITE_ARTISTS, OctoIcons.Artist)
    }

    // A tab, or the last tab of a car with room for fewer than four.
    private fun tab(node: CarNode, tabs: CarTabs?): MediaItem = when (node) {
        CarNode.Home -> look.folder(node, "Home", OctoIcons.Home, browsable = CarStyle.Grid, playable = CarStyle.List)
        CarNode.Radio -> look.folder(node, "Radio", OctoIcons.Radio, browsable = CarStyle.Grid, playable = CarStyle.Grid)
        CarNode.Playlists -> look.folder(node, "Playlists", OctoIcons.Playlists, browsable = CarStyle.List)
        CarNode.Library -> look.folder(node, "Library", OctoIcons.Library, browsable = CarStyle.Category)
        else -> look.folder(CarNode.More, tabs?.let(::moreTitle) ?: "More", OctoIcons.More, browsable = CarStyle.Category)
    }

    private suspend fun favoriteAlbums() = sortFavourites(
        favourites.likedAlbums().first(), sorting.order(SortList.Favourites).first(), { it.likedAt }, { it.album.sortKey }, { it.album.id },
    ).map { it.album }

    private suspend fun favoriteArtists() = sortFavourites(
        favourites.likedArtists().first(), sorting.order(SortList.Favourites).first(), { it.likedAt }, { it.artist.sortKey }, { it.artist.id },
    ).map { it.artist }

    // A song by id: one in the library, or one found beyond it.
    private suspend fun trackOf(id: String): TrackEntity? =
        if (isFind(id)) (online.byIds(listOf(id)).firstOrNull() ?: finds[id])?.asTrack() else catalog.track(id)

    // A song among a search's: the library's songs, then those found outside.
    private fun foundSongs(answer: CarSearchAnswer): List<TrackEntity> = answer.library.songs + answer.outside?.songs.orEmpty()

    private suspend fun libraryOnly(): Boolean = player.prefs.first().libraryOnly

    private fun signedIn(): Boolean = sessions.state.value is SessionState.SignedIn

    private fun sourceIdNow(): String? =
        (sessions.state.value as? SessionState.SignedIn)?.session?.client?.primaryUrl?.let(::serverSourceId)
}

// What a car is told for play counts that could not be brought up to date,
// or for a list's own worded problem: their words, at once, with the counts
// asked to build again (`again`), so the car hears when they are back; null
// for any other failure.
internal fun carTotalsWords(failure: Throwable, again: () -> Unit): String? = when (failure) {
    is TotalsFailed -> failure.words.also { again() }
    is CarWords -> failure.words.also { again() }
    else -> null
}

// The Live radio shelf played from the station with `key`, and the radio
// catalog's station to tell it started (one kept on the phone); null when
// the station has left the shelf.
internal fun liveLineupOf(entries: List<LiveShelfEntry>, key: String): Pair<CarList, String?>? {
    val start = entries.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return null
    val started = when (val entry = entries[start]) {
        is LiveShelfEntry.Kept -> entry.station.uuid
        is LiveShelfEntry.Server -> entry.kept?.uuid
    }
    return carIds(entries.map { it.radioId() }, QueueSource.Played(HomeSection.InternetRadio.title), start = start) to started
}

// Each kind of input as it changes: a kind is heard when its value differs
// from the one before, never for its first value.
internal fun carInputs(sources: List<Pair<CarInput, Flow<*>>>): Flow<CarInput> =
    sources.map { (kind, values) -> values.distinctUntilChanged().drop(1).map { kind } }.merge()

// The library as the car's lists read it: which account's first copy is
// done, when a copy last ended, and how many songs it holds.
internal fun carLibraryInput(ready: Flow<Any?>, lastCopy: Flow<Any?>, songs: Flow<Int>): Flow<Any> =
    combine(ready, lastCopy, songs, ::Triple)

// A queue to pick up, by what the car shows of it, so an offer checked again
// and found the same changes nothing.
internal fun resumeOfferKey(offer: ResumeOffer?): List<Any?>? =
    offer?.let { listOf(it.device, it.title, it.artist, it.remote.changedAt) }

// Octo's stations (those with no stream), each with the song count the
// server's lists give it, so a station reads alike on Home, Radio and
// Playlists.
internal fun carRadioStations(stations: List<Station>, lists: List<Station>): List<Station> {
    val counts = lists.associate { it.id to it.songCount }
    return stations.filter { it.streamUrl == null }.map { it.copy(songCount = it.songCount ?: counts[it.id]) }
}

// Home's shelves of what was played, read from the stored totals together.
private val PLAYED = setOf(
    HomeSection.RecentlyPlayed, HomeSection.MostPlayed, HomeSection.NotPlayedLately, HomeSection.NeverFinished, HomeSection.NeverPlayed,
)
