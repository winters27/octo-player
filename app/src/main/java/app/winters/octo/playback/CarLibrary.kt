@file:OptIn(UnstableApi::class)

package app.winters.octo.playback

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.FavouritesDao
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.discovery.asTrack
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.byLatestPlay
import app.winters.octo.catalog.byPlayCount
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
import app.winters.octo.discovery.SearchTopSongs
import app.winters.octo.discovery.wholeAlbum
import app.winters.octo.favourites.PinStore
import app.winters.octo.favourites.PinnedItem
import app.winters.octo.home.HomeSection
import app.winters.octo.home.mixGroup
import app.winters.octo.listening.PlayHistory
import app.winters.octo.liveradio.LiveRadioStore
import app.winters.octo.liveradio.LiveShelfEntry
import app.winters.octo.liveradio.Places
import app.winters.octo.liveradio.RadioBrowser
import app.winters.octo.liveradio.liveShelf
import app.winters.octo.liveradio.radioId
import app.winters.octo.liveradio.usesMiles
import app.winters.octo.livelists.LiveListSongs
import app.winters.octo.livelists.LiveListStore
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.player.PlayerSettings
import app.winters.octo.server.QueueSync
import app.winters.octo.server.ServerSync
import app.winters.octo.server.serverSourceId
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortSettings
import app.winters.octo.sort.SortedLibrary
import app.winters.octo.sort.sortDownloads
import app.winters.octo.sort.sortFavourites
import app.winters.octo.sort.sortPlaylists
import app.winters.octo.ui.album.albumSongsShown
import app.winters.octo.ui.common.adoptedFinds
import app.winters.octo.ui.common.isOutsideLibrary
import app.winters.octo.ui.home.HomePrefs
import app.winters.octo.ui.home.homeRediscovery
import app.winters.octo.ui.liveradio.LIVE_NEAR_TITLE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// What lists hold when a car asks how many are in a row of Home's shelves.
private const val HISTORY_SHELF = 20
private const val FAVORITES_SHELF = 20
private const val SURPRISE_ALBUMS = 20
private const val SURPRISE_ARTISTS = 16
private const val ARTIST_TOP_SHOWN = 5

// The music as a car sees it: the phone's Home, Radio, Playlists and
// Library as four tabs, every list in the phone's order, lists that play
// from the chosen song, the server's lists and stations, and search.
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
) {
    private val look = CarLook(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Told when a list fetched behind another (a whole album, top songs)
    // came back changing it.
    var listChanged: (String) -> Unit = {}

    // What the car's lists play, for a choice made in the car.
    val plays = CarPlays(::playList, ::signedIn) { discovery.listGone(it) }

    fun root(): MediaItem = look.folder(CarNode.Root, "Octo")

    // What changes reach the car's open lists, by what they read.
    val inputs: Flow<CarInput> = merge(
        input(CarInput.Library, combine(serverSync.ready, serverSync.last.map { it?.at }, catalog.libraryTrackCount(), ::Triple)),
        input(CarInput.Playlists, playlists.playlists.map { all -> all.map { Triple(it.id, it.name, it.songCount) } }),
        input(CarInput.Liked, user.likedIds()),
        input(CarInput.Favorites, combine(favourites.likedAlbumIds(), favourites.likedArtistIds(), ::Pair)),
        input(CarInput.LiveLists, liveLists.lists),
        input(CarInput.Kept, liveRadio.kept),
        input(CarInput.Shelves, shelves.version),
        input(CarInput.HomeOrder, homePrefs.homeSections),
        input(
            CarInput.Sorts,
            combine(
                listOf(
                    SortList.Playlists, SortList.Albums, SortList.Artists, SortList.Songs, SortList.Liked,
                    SortList.Favourites, SortList.Downloads, SortList.ArtistAlbums, SortList.GenreSongs,
                ).map(sorting::order),
            ) { it.toList() },
        ),
        input(CarInput.Pins, pins.shown),
        input(CarInput.Adopted, downloads.phases.map(::adoptedFinds)),
        input(CarInput.Downloads, offline.done),
        input(CarInput.LibraryOnly, player.prefs.map { it.libraryOnly }),
        input(CarInput.Offer, serverQueue.offer),
    )

    private fun <T> input(kind: CarInput, values: Flow<T>): Flow<CarInput> = values.distinctUntilChanged().drop(1).map { kind }

    // The whole answer for a list a car opened, before it is cut into the
    // page asked for. `limit` is the most tabs this car shows.
    suspend fun children(parentId: String, limit: Int?): List<MediaItem> {
        val node = parseCarId(parentId) ?: return emptyList()
        if (needsServer(node) && !signedIn()) throw CarNotSignedIn()
        return when (node) {
            CarNode.Root -> carTabs(limit).let { tabs -> tabs.tabs.map { tab(it, tabs) } }
            CarNode.More -> carTabs(limit).more.map { tab(it, null) }
            CarNode.Home -> home()
            is CarNode.Shelf -> shelfWhole(node)
            CarNode.Radio -> fitParts(node, radio())
            CarNode.Playlists -> fitParts(node, playlistsTab())
            CarNode.Library -> libraryRows()
            CarNode.Favorites -> favoriteRows()
            CarNode.Charts -> shelves.current().charts.map(look::chart)
            is CarNode.Part -> partOf(node)
            is CarNode.Heading -> headingOf(node)
            else -> listed(node)?.let { fit(node, it) } ?: emptyList()
        }
    }

    // A long list as the car is sent it: whole when it fits one answer;
    // otherwise under the phone's headings when it has them, or in parts.
    private suspend fun fit(node: CarNode, listed: Listed): List<MediaItem> {
        val sizes = listed.items.map(::carBytes)
        if (fitsOneAnswer(sizes)) return listed.items
        val headings = listed.headings
        if (headings != null) {
            return headingRuns(headings).map { (heading, _) -> look.heading(CarNode.Heading(heading, node), listed.style) }
        }
        return carParts(sizes).mapIndexed { index, range -> look.part(CarNode.Part(index, node), range, listed.items[range.first].mediaMetadata.title?.toString()) }
    }

    private fun fitParts(node: CarNode, items: List<MediaItem>): List<MediaItem> {
        val sizes = items.map(::carBytes)
        if (fitsOneAnswer(sizes)) return items
        return carParts(sizes).mapIndexed { index, range -> look.part(CarNode.Part(index, node), range, items[range.first].mediaMetadata.title?.toString()) }
    }

    private suspend fun partOf(node: CarNode.Part): List<MediaItem> {
        val items = when (val parent = node.parent) {
            CarNode.Playlists -> playlistsTab()
            CarNode.Radio -> radio()
            is CarNode.Shelf -> HomeSection.ofId(parent.section)?.let { homeShelves(only = it)[it] }.orEmpty()
            is CarNode.Heading -> headingItems(parent)
            else -> listed(parent)?.items.orEmpty()
        }
        val range = carParts(items.map(::carBytes)).getOrNull(node.index) ?: return emptyList()
        return items.subList(range.first, range.last + 1)
    }

    private suspend fun headingOf(node: CarNode.Heading): List<MediaItem> {
        val items = headingItems(node)
        val sizes = items.map(::carBytes)
        if (fitsOneAnswer(sizes)) return items
        return carParts(sizes).mapIndexed { index, range -> look.part(CarNode.Part(index, node), range, items[range.first].mediaMetadata.title?.toString()) }
    }

    private suspend fun headingItems(node: CarNode.Heading): List<MediaItem> {
        val listed = listed(node.parent) ?: return emptyList()
        val headings = listed.headings ?: return emptyList()
        return listed.items.filterIndexed { index, _ -> headings.getOrNull(index) == node.heading }
    }

    // A list's items, with the phone's headings when it is sorted by name,
    // and how what it holds shows.
    private class Listed(val items: List<MediaItem>, val headings: List<String>? = null, val style: CarStyle? = null)

    private suspend fun listed(node: CarNode): Listed? {
        val adopted = adoptedFinds(downloads.phases.value)
        val done = offline.done.value
        fun song(track: TrackEntity, list: CarNode) = look.song(track, list, isOutsideLibrary(track.id, adopted), track.id in done)
        return when (node) {
            CarNode.Albums -> sorted.albums(SortList.Albums).first().let { s -> Listed(s.items.map(look::album), s.headings, CarStyle.Grid) }
            CarNode.Artists -> sorted.artists().first().let { s -> Listed(s.items.map(look::artist), s.headings) }
            CarNode.Songs -> sorted.songs(SortList.Songs, SongScope.All).first().let { s -> Listed(s.items.map { song(it, node) }, s.headings) }
            CarNode.Genres -> Listed(catalog.genres().first().map(look::genre))
            is CarNode.Genre -> {
                val albums = catalog.genreAlbums(node.name).first().map { look.grouped(look.album(it), "Albums") }
                val songs = genreSongs(node.name).map { look.grouped(song(it, node), "Songs") }
                Listed(albums + songs)
            }
            CarNode.FavoriteAlbums -> Listed(favoriteAlbums().map(look::album), style = CarStyle.Grid)
            CarNode.FavoriteArtists -> Listed(favoriteArtists().map(look::artist))
            CarNode.Liked -> Listed(likedSongs().map { song(it, node) })
            CarNode.Downloads -> Listed(downloadedSongs().map { song(it, node) })
            CarNode.MostPlayed -> Listed(byPlayCount(history.tracks.first(), HISTORY_SHELF).map { song(it, node) })
            CarNode.Chart -> Listed(shelves.current().chart.map { song(it, node) })
            is CarNode.Album -> Listed(albumSongs(node.id, listening = true).map { song(it, node) })
            is CarNode.Artist -> Listed(artistPage(node, adopted))
            is CarNode.Playlist -> Listed(user.playlistTracks(node.id).first().map { song(it.track, node) })
            is CarNode.LiveList -> Listed(liveListSongs(node.id).map { song(it, node) })
            is CarNode.OctoList, is CarNode.Station -> serverSongs(node)?.let { songs -> Listed(songs.map { song(it, node) }) }
            is CarNode.ChartList -> Listed(chartSongs(node).map { song(it, node) })
            is CarNode.OnlineAlbum -> onlineAlbum(node)?.let { songs -> Listed(songs.map { song(it, node) }) }
            is CarNode.OnlineArtist -> Listed(onlineArtistPage(node, adopted))
            is CarNode.Found -> search.kept(node.token)?.let { Listed(foundAll(it, node.kind)) } ?: Listed(emptyList())
            is CarNode.Top -> search.kept(node.token)?.top?.let { top -> Listed(top.songs.map { song(it.track, node) }) } ?: Listed(emptyList())
            is CarNode.Retry -> search.retry(node.token)?.let { Listed(outsideRows(it)) } ?: Listed(emptyList())
            else -> null
        }
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
            is CarNode.LiveList -> liveLists.lists.first().firstOrNull { it.id == node.id }?.let { look.liveList(it, null) }
            is CarNode.Song -> trackOf(node.trackId)?.let { look.song(it, node.list, isOutsideLibrary(it.id, adoptedFinds(downloads.phases.value)), it.id in offline.done.value) }
            is CarNode.OctoList -> look.folder(node, node.name)
            is CarNode.Station -> look.folder(node, node.name)
            is CarNode.ChartList -> look.folder(node, node.name)
            is CarNode.Genre -> look.folder(node, node.name)
            is CarNode.Shelf -> look.folder(node, HomeSection.ofId(node.section)?.title.orEmpty())
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
    fun searchItems(answer: CarSearchAnswer): List<MediaItem> {
        val adopted = adoptedFinds(downloads.phases.value)
        val done = offline.done.value
        val rows = carSearchRows(answer)
        return rows.flatMap { row ->
            val items = row.entries.map { entry ->
                when (entry) {
                    is SearchEntry.LibraryArtist -> look.artist(entry.artist)
                    is SearchEntry.OutsideArtist -> look.onlineArtist(entry.artist)
                    is SearchEntry.Song -> look.song(entry.track, entry.list, isOutsideLibrary(entry.track.id, adopted), entry.track.id in done)
                    is SearchEntry.LibraryAlbum -> look.album(entry.album)
                    is SearchEntry.OutsideAlbum -> look.onlineAlbum(entry.album)
                    is SearchEntry.Playlist -> look.playlist(entry.playlist)
                    is SearchEntry.SeeAll -> look.seeAll(entry.node, amongRows = true)
                    is SearchEntry.Retry -> look.retry(entry.token)
                }
            }
            items.map { look.grouped(it, row.title) }
        }
    }

    // A kind of a search whole, for its "See all".
    private fun foundAll(answer: CarSearchAnswer, kind: FoundKind): List<MediaItem> {
        val adopted = adoptedFinds(downloads.phases.value)
        val done = offline.done.value
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
        val adopted = adoptedFinds(downloads.phases.value)
        val found = answer.outside ?: return emptyList()
        val list = CarNode.Found(answer.token, FoundKind.Songs)
        return found.artists.map { look.grouped(look.onlineArtist(it), "Artists") } +
            found.songs.map { look.grouped(look.song(it, list, isOutsideLibrary(it.id, adopted), false), "Songs") } +
            (found.partAlbums + found.albums).map { look.grouped(look.onlineAlbum(it), "Albums") }
    }

    // The lists the car's choices play from (CarPlays). `fresh` asks the
    // server again; otherwise what the car was shown serves.
    private suspend fun playList(node: CarNode, fresh: Boolean): CarList? = when (node) {
        is CarNode.Album -> catalog.album(node.id).first()?.let { album -> CarList(albumSongs(node.id, listening = false).map { it.id }, QueueSource.Played(album.title)) }
        is CarNode.Artist -> artistTop(node, fetchBehind = false)?.let { top ->
            CarList(top.map { it.id }, QueueSource.Played(catalog.artist(node.id).first()?.name))
        }
        is CarNode.Playlist -> playlists.playlists.first().firstOrNull { it.id == node.id }?.let { list ->
            CarList(user.playlistTracks(node.id).first().map { it.track.id }, QueueSource.Played(list.name))
        }
        CarNode.Liked -> CarList(likedSongs().map { it.id }, QueueSource.Played(CAR_LIKED))
        is CarNode.LiveList -> liveLists.lists.first().firstOrNull { it.id == node.id }?.let { list ->
            CarList(liveSongs.now(list).map { it.id }, QueueSource.Played(list.name))
        }
        CarNode.MostPlayed -> CarList(byPlayCount(history.tracks.first(), HISTORY_SHELF).map { it.id }, NoSource)
        CarNode.Songs -> CarList(sorted.songs(SortList.Songs, SongScope.All).first().items.map { it.id }, QueueSource.Played("Songs"))
        CarNode.Downloads -> CarList(downloadedSongs().map { it.id }, NoSource)
        is CarNode.Genre -> CarList(genreSongs(node.name).map { it.id }, QueueSource.Played(node.name))
        CarNode.Chart -> CarList(shelves.current().chart.map { it.id }, NoSource)
        is CarNode.ChartList -> {
            val kept = if (fresh) null else lists.keptFor<List<TrackEntity>>(node)
            val songs = kept ?: chartSongs(node)
            CarList(songs.map(TrackEntity::id), QueueSource.Played(node.name), network = kept == null)
        }
        is CarNode.OctoList -> networkSongs(node, fresh)?.let { (songs, network) -> CarList(songs.map { it.id }, QueueSource.Played(node.name), network) }
        is CarNode.Station -> networkSongs(node, fresh)?.let { (songs, network) -> CarList(songs.map { it.id }, QueueSource.Station(node.id, node.name), network) }
        is CarNode.OnlineAlbum -> {
            val kept = if (fresh) null else lists.keptFor<OnlineSongs>(node)
            val got = kept ?: onlineAlbumPage(node)
            got?.let { CarList(albumSongsShown(it.songs, libraryOnly()).map(TrackEntity::id), QueueSource.Played(it.title), network = kept == null) }
        }
        is CarNode.OnlineArtist -> lists.keptFor<OnlineArtistView>(node)?.let { CarList(it.top.map(TrackEntity::id), QueueSource.Played(it.name)) }
        is CarNode.Found -> search.kept(node.token)?.let { CarList(foundSongs(it).map(TrackEntity::id), NoSource) }
        is CarNode.Top -> search.kept(node.token)?.top?.let { top -> CarList(top.songs.map { it.track.id }, NoSource) }
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

    // An Octo list's or a station's songs: what the car was shown, for a
    // song chosen inside it, or asked of the server again.
    private suspend fun networkSongs(node: CarNode, fresh: Boolean): Pair<List<TrackEntity>, Boolean>? {
        if (!fresh) lists.keptFor<List<TrackEntity>>(node)?.let { return it to false }
        return serverSongs(node)?.let { it to true }
    }

    private suspend fun serverSongs(node: CarNode): List<TrackEntity>? {
        val id = when (node) {
            is CarNode.OctoList -> node.id
            is CarNode.Station -> node.id
            else -> return null
        }
        return lists.fetch(node) { discovery.stationSongs(id) }
    }

    private suspend fun chartSongs(node: CarNode.ChartList): List<TrackEntity> = lists.fetch(node) {
        when (val answer = charts.answer(node.id)) {
            is ChartAnswer.Got -> answer.list.songs.map { it.track }
            ChartAnswer.None -> emptyList()
            is ChartAnswer.Failed -> throw IllegalStateException(answer.refusal ?: "No chart")
        }
    }

    // An album the server has beyond the library: its name and songs.
    data class OnlineSongs(val title: String, val songs: List<TrackEntity>)

    private suspend fun onlineAlbumPage(node: CarNode.OnlineAlbum): OnlineSongs? =
        lists.fetch(node) { discovery.album(node.id)?.let { OnlineSongs(it.album.title, it.songs) } ?: OnlineSongs("", emptyList()) }
            .takeIf { it.title.isNotEmpty() || it.songs.isNotEmpty() }

    private suspend fun onlineAlbum(node: CarNode.OnlineAlbum): List<TrackEntity>? =
        onlineAlbumPage(node)?.let { albumSongsShown(it.songs, libraryOnly()) }

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
        val done = offline.done.value
        val top = view.top.filter { !only || !isOutsideLibrary(it.id, emptySet()) }.take(ARTIST_TOP_SHOWN)
        return top.map { look.grouped(look.song(it, node, isOutsideLibrary(it.id, adopted), it.id in done), "Top songs") } +
            view.albums.map { look.grouped(look.onlineAlbum(it), "Albums") }
    }

    // A library album whole, as the phone's album page lists it: its songs
    // and, from an Octo server, the ones the library lacks in their places;
    // with Library songs only, the library's alone. `listening` asks the
    // server again behind the answer.
    private suspend fun albumSongs(id: String, listening: Boolean): List<TrackEntity> {
        val library = catalog.albumTracks(id).first()
        val octo = (sessions.state.value as? SessionState.SignedIn)?.session?.runsOcto == true
        val server = if (!octo) {
            null
        } else if (listening) {
            lists.behind(CarNode.Album(id), { discovery.libraryAlbum(library.map { it.id }) }) { listChanged(carId(CarNode.Album(id))) }
        } else {
            lists.keptFor<Behind<List<TrackEntity>>>(CarNode.Album(id))?.value
        }
        val whole = wholeAlbum(library, server.orEmpty(), downloads.adoptions.value.orEmpty())
        return albumSongsShown(whole, libraryOnly())
    }

    // An artist's page: their top songs from the server when it ranks them,
    // then their albums in the order the phone's artist page uses.
    private suspend fun artistPage(node: CarNode.Artist, adopted: Set<String>): List<MediaItem> {
        val done = offline.done.value
        val top = artistTop(node, fetchBehind = true).orEmpty()
            .take(ARTIST_TOP_SHOWN)
            .map { look.grouped(look.song(it, node, isOutsideLibrary(it.id, adopted), it.id in done), "Top songs") }
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
        return top?.let { songs -> if (only) songs.filterNot { isOutsideLibrary(it.id, emptySet()) } else songs }
    }

    // Home in the car: the resume row, then the phone's shelves in the
    // listener's order (see carHomeAnswer).
    private suspend fun home(): List<MediaItem> {
        val order = homePrefs.homeSections.first()
        val empty = catalog.libraryTrackCount().first() == 0
        val shelvesNow = homeShelves()
        val resume = serverQueue.offer.value?.let(look::resume)
        return carHomeAnswer(resume, carHomeShelves(order, shelvesNow, empty), homeParts)
    }

    private val homeParts = CarHomeParts(
        grouped = { item, title -> look.grouped(item, title) },
        seeAll = { shelf ->
            val target = if (shelf.section == HomeSection.Charts) CarNode.Charts else CarNode.Shelf(shelf.section.id)
            look.seeAll(target, amongRows = shelf.section.songs)
        },
        row = { shelf -> look.folder(CarNode.Shelf(shelf.section.id), shelf.section.title, browsable = CarStyle.Grid) },
    )

    private suspend fun shelfWhole(node: CarNode.Shelf): List<MediaItem> {
        val section = HomeSection.ofId(node.section) ?: return emptyList()
        return fitParts(node, homeShelves(only = section)[section].orEmpty())
    }

    // Each of Home's shelves as the phone's Home fills it.
    private suspend fun homeShelves(only: HomeSection? = null): Map<HomeSection, List<MediaItem>> {
        val server = shelves.current()
        val adopted = adoptedFinds(downloads.phases.value)
        val done = offline.done.value
        fun song(track: TrackEntity, list: CarNode) = look.song(track, list, isOutsideLibrary(track.id, adopted), track.id in done)
        val wanted = only?.let(::setOf) ?: HomeSection.entries.toSet()
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
                HomeSection.RecentlyPlayed -> byLatestPlay(history.albums.first(), HISTORY_SHELF).map(look::album)
                HomeSection.MadeForYou -> server.madeForYou.map(look::station)
                HomeSection.Stations -> server.stations.filter { it.streamUrl == null }.map(look::station)
                HomeSection.InternetRadio -> liveEntries(server).map { look.live(it, sourceIdNow()) }
                HomeSection.YearMixes, HomeSection.GenreMixes, HomeSection.ArtistMixes, HomeSection.CountryMixes ->
                    server.groups.firstOrNull { it.first == section.mixGroup }?.second.orEmpty().map(look::station)
                HomeSection.RecentlyAdded -> catalog.recentAlbums(HISTORY_SHELF).first().map(look::album)
                HomeSection.MostPlayed -> byPlayCount(history.tracks.first(), HISTORY_SHELF).map { song(it, CarNode.MostPlayed) }
                HomeSection.FavoriteAlbums -> favourites.likedAlbums().first().sortedByDescending { it.likedAt }.take(FAVORITES_SHELF).map { look.album(it.album) }
                HomeSection.Charts -> server.chart.take(CHARTS_ROW).map { song(it, CarNode.Chart) }
                HomeSection.Artists -> picks().second.map(look::artist)
                HomeSection.SomethingDifferent -> picks().first.map(look::album)
                HomeSection.NotPlayedLately -> rediscovery().notPlayedLately.map(look::album)
                HomeSection.NeverFinished -> rediscovery().neverFinished.map(look::album)
                HomeSection.NeverPlayed -> rediscovery().neverPlayed.map(look::album)
            }
        }
        return out
    }

    private suspend fun rediscovery() = homeRediscovery(catalog.albums().first(), history.tracks.first(), System.currentTimeMillis())

    // Home's random shelves (Something different, Artists), picked once and
    // again only when the library changes size or is copied anew, as the
    // phone's Home picks them, so the car's Home does not reshuffle.
    private var picked: Triple<Pair<Int, Long?>, List<app.winters.octo.catalog.AlbumEntity>, List<app.winters.octo.catalog.ArtistEntity>>? = null

    private suspend fun picks(): Pair<List<app.winters.octo.catalog.AlbumEntity>, List<app.winters.octo.catalog.ArtistEntity>> {
        val key = catalog.libraryTrackCount().first() to serverSync.last.first()?.at
        picked?.takeIf { it.first == key }?.let { return it.second to it.third }
        val albums = catalog.randomAlbums(SURPRISE_ALBUMS)
        val artists = catalog.randomArtists(SURPRISE_ARTISTS)
        picked = Triple(key, albums, artists)
        return albums to artists
    }

    // Radio: Octo's stations, the Live radio shelf, and stations near the
    // listener's saved place.
    private suspend fun radio(): List<MediaItem> {
        val server = shelves.current()
        val miles = usesMiles(places.localeCountry())
        val source = sourceIdNow()
        return server.stations.filter { it.streamUrl == null }.map { look.grouped(look.station(it), HomeSection.Stations.title) } +
            liveEntries(server).map { look.grouped(look.live(it, source), HomeSection.InternetRadio.title) } +
            server.near.map { look.grouped(look.near(it, miles), LIVE_NEAR_TITLE) }
    }

    private suspend fun liveEntries(server: CarServerShelves): List<LiveShelfEntry> =
        liveShelf(server.stations.filter { it.streamUrl != null }, liveRadio.kept.first().stations)

    private suspend fun liveLineup(node: CarNode.Live): CarList? {
        val entries = liveEntries(shelves.current())
        val start = entries.indexOfFirst { it.key == node.key }.takeIf { it >= 0 } ?: return null
        val entry = entries[start]
        val uuid = (entry as? LiveShelfEntry.Kept)?.station?.uuid ?: (entry as? LiveShelfEntry.Server)?.kept?.uuid
        if (uuid != null) scope.launch { runCatching { radioCatalog.started(uuid) } }
        return CarList(entries.map { it.radioId() }, QueueSource.Played(HomeSection.InternetRadio.title), start = start)
    }

    private suspend fun nearLineup(node: CarNode.Near): CarList? {
        val near = shelves.current().near
        val start = near.indexOfFirst { it.uuid == node.uuid }.takeIf { it >= 0 } ?: return null
        scope.launch { runCatching { radioCatalog.started(node.uuid) } }
        return CarList(near.map { it.radioId() }, QueueSource.Played(LIVE_NEAR_TITLE), start = start)
    }

    private suspend fun resumeList(): CarList? {
        val offer = serverQueue.offer.value ?: return CarList(emptyList(), NoSource, network = true)
        val queue = serverQueue.take(offer) ?: return CarList(emptyList(), NoSource, network = true)
        return CarList(queue.trackIds, NoSource, network = true, positionMs = queue.positionMs, start = queue.index)
    }

    // Playlists: Liked songs, the live lists, the playlists in the phone's
    // order (less the server's library action lists, which act on songs
    // added), then the lists Octo made, under each kind's heading.
    private suspend fun playlistsTab(): List<MediaItem> {
        val liked = user.likedCount().first()
        val live = liveLists.lists.first().map { list -> look.liveList(list, liveSongs.now(list).firstOrNull()?.artwork) }
        val order = sorting.order(SortList.Playlists).first()
        val own = sortPlaylists(playlists.playlists.first().filterNot { isToolPlaylist(it.name) }, order).map(look::playlist)
        val octo = shelves.current().groups.flatMap { (group, items) -> items.map { look.grouped(look.station(it), group.title) } }
        return listOfNotNull(liked.takeIf { it > 0 }?.let(look::liked)) + live + own + octo
    }

    private fun libraryRows(): List<MediaItem> =
        listOf(CarNode.Favorites, CarNode.Artists, CarNode.Albums, CarNode.Songs, CarNode.Genres, CarNode.Downloads).map(::libraryRow)

    private fun libraryRow(node: CarNode): MediaItem = when (node) {
        CarNode.Favorites -> look.folder(node, "Favorites", OctoIcons.Liked, browsable = CarStyle.Category)
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
        CarNode.Playlists -> look.folder(node, "Playlists", OctoIcons.Playlists)
        CarNode.Library -> look.folder(node, "Library", OctoIcons.Library, browsable = CarStyle.Category)
        else -> look.folder(CarNode.More, tabs?.let(::moreTitle) ?: "More", OctoIcons.More, browsable = CarStyle.Category)
    }

    private suspend fun favoriteAlbums() = sortFavourites(
        favourites.likedAlbums().first(), sorting.order(SortList.Favourites).first(), { it.likedAt }, { it.album.sortKey }, { it.album.id },
    ).map { it.album }

    private suspend fun favoriteArtists() = sortFavourites(
        favourites.likedArtists().first(), sorting.order(SortList.Favourites).first(), { it.likedAt }, { it.artist.sortKey }, { it.artist.id },
    ).map { it.artist }

    private suspend fun likedSongs(): List<TrackEntity> = sorted.songs(SortList.Liked, SongScope.Liked).first().items

    private suspend fun genreSongs(name: String): List<TrackEntity> = sorted.songs(SortList.GenreSongs, SongScope.Genre(name)).first().items

    private suspend fun liveListSongs(id: String): List<TrackEntity> =
        liveLists.lists.first().firstOrNull { it.id == id }?.let { liveSongs.now(it) }.orEmpty()

    // The phone's downloads that are ready, in the Downloads page's order.
    private suspend fun downloadedSongs(): List<TrackEntity> {
        val rows = sortDownloads(offline.rows.first(), sorting.order(SortList.Downloads).first()).filter { it.state == DownloadStatus.Done }
        val tracks = catalog.tracksByIds(rows.map { it.trackId }).associateBy { it.id }
        return rows.mapNotNull { tracks[it.trackId] }
    }

    // A song by id: one in the library, or one found beyond it.
    private suspend fun trackOf(id: String): TrackEntity? =
        if (isFind(id)) online.byIds(listOf(id)).firstOrNull()?.asTrack() else catalog.track(id)

    // A song among a search's: the library's songs, then those found outside.
    private fun foundSongs(answer: CarSearchAnswer): List<TrackEntity> = answer.library.songs + answer.outside?.songs.orEmpty()

    private suspend fun libraryOnly(): Boolean = player.prefs.first().libraryOnly

    private fun signedIn(): Boolean = sessions.state.value is SessionState.SignedIn

    private fun sourceIdNow(): String? =
        (sessions.state.value as? SessionState.SignedIn)?.session?.client?.primaryUrl?.let(::serverSourceId)
}

// Whether a Home shelf holds songs, which show as rows.
private val HomeSection.songs: Boolean
    get() = this == HomeSection.MostPlayed || this == HomeSection.Charts
