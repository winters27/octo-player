package app.winters.octo.desktop.home

import app.winters.octo.desktop.server.Connection
import app.winters.octo.server.serverTime
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.AlbumListType
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.RadioStation
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// How many albums each shelf on Home holds at most. A shelf shows as many
// as fit across the window.
const val SHELF_SIZE = 20

// Home's shelves from the server's own lists, plus the stations and mixes
// an Octo server runs. The rediscovery shelves come from the library the
// app already holds (HomeStore).
data class HomeData(
    val recentlyAdded: List<Album> = emptyList(),
    val recentlyPlayed: List<Album> = emptyList(),
    val mostPlayed: List<Album> = emptyList(),
    val favourites: List<Album> = emptyList(),
    val stations: List<RadioStation> = emptyList(),
) {
    val isEmpty get() = recentlyAdded.isEmpty() && recentlyPlayed.isEmpty() && mostPlayed.isEmpty() && favourites.isEmpty() && stations.isEmpty()
}

// Reads Home's shelves at once. A shelf the server cannot give is empty
// rather than failing the page; only when every one fails does it throw.
// Stations are asked for only from Octo, where each is a list of songs to
// play; on other servers internet radio is a stream this app cannot play yet.
suspend fun loadHome(connection: Connection): HomeData = coroutineScope {
    val client = connection.client
    suspend fun <T> shelf(read: suspend () -> T): Result<T> =
        runCatching { read() }.onFailure { if (it !is SubsonicException) throw it }
    val added = async { shelf { client.albumList(AlbumListType.NEWEST, SHELF_SIZE) } }
    val played = async { shelf { client.albumList(AlbumListType.RECENT, SHELF_SIZE) } }
    val most = async { shelf { client.albumList(AlbumListType.FREQUENT, SHELF_SIZE) } }
    val starred = async { shelf { newestFavourites(client.starred().album) } }
    val stations = async {
        if (connection.isOcto) runCatching { client.radioStations() }.getOrDefault(emptyList()) else emptyList()
    }
    val shelves = listOf(added.await(), played.await(), most.await(), starred.await())
    shelves.firstOrNull { it.isFailure }?.takeIf { shelves.all { s -> s.isFailure } }?.exceptionOrNull()?.let { throw it }
    HomeData(
        recentlyAdded = shelves[0].getOrDefault(emptyList()),
        recentlyPlayed = shelves[1].getOrDefault(emptyList()),
        mostPlayed = shelves[2].getOrDefault(emptyList()),
        favourites = shelves[3].getOrDefault(emptyList()),
        stations = stations.await(),
    )
}

// The latest favourite albums, the newest favourite first, as the phone
// shows them.
fun newestFavourites(albums: List<Album>): List<Album> =
    albums.sortedWith(compareByDescending<Album> { serverTime(it.starred) ?: Long.MIN_VALUE }.thenBy { it.id }).take(SHELF_SIZE)

// The playlists with the pinned ones first, in the order they were pinned,
// as the sidebar lists them.
fun pinnedFirst(playlists: List<Playlist>, pinned: List<String>): List<Playlist> =
    playlists.sortedBy { pinned.indexOf(it.id).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
