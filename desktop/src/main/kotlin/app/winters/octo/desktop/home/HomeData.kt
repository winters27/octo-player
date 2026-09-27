package app.winters.octo.desktop.home

import app.winters.octo.desktop.server.Connection
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.AlbumListType
import app.winters.octo.subsonic.RadioStation
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// How many albums each shelf on Home holds.
const val SHELF_SIZE = 20

// Home's shelves, each straight from the server's album lists, plus the
// stations and mixes an Octo server runs.
data class HomeData(
    val recentlyAdded: List<Album> = emptyList(),
    val recentlyPlayed: List<Album> = emptyList(),
    val mostPlayed: List<Album> = emptyList(),
    val random: List<Album> = emptyList(),
    val stations: List<RadioStation> = emptyList(),
) {
    val isEmpty get() = recentlyAdded.isEmpty() && recentlyPlayed.isEmpty() && mostPlayed.isEmpty() && random.isEmpty() && stations.isEmpty()
}

// Reads Home's shelves at once. A shelf the server cannot give is empty
// rather than failing the page; only when every one fails does it throw.
// Stations are asked for only from Octo, where each is a list of songs to
// play; on other servers internet radio is a stream this app cannot play yet.
suspend fun loadHome(connection: Connection): HomeData = coroutineScope {
    val client = connection.client
    suspend fun shelf(type: AlbumListType): Result<List<Album>> =
        runCatching { client.albumList(type, SHELF_SIZE) }.onFailure { if (it !is SubsonicException) throw it }
    val added = async { shelf(AlbumListType.NEWEST) }
    val played = async { shelf(AlbumListType.RECENT) }
    val most = async { shelf(AlbumListType.FREQUENT) }
    val random = async { shelf(AlbumListType.RANDOM) }
    val stations = async {
        if (connection.isOcto) runCatching { client.radioStations() }.getOrDefault(emptyList()) else emptyList()
    }
    val shelves = listOf(added.await(), played.await(), most.await(), random.await())
    shelves.firstOrNull { it.isFailure }?.takeIf { shelves.all { s -> s.isFailure } }?.exceptionOrNull()?.let { throw it }
    HomeData(
        recentlyAdded = shelves[0].getOrDefault(emptyList()),
        recentlyPlayed = shelves[1].getOrDefault(emptyList()),
        mostPlayed = shelves[2].getOrDefault(emptyList()),
        random = shelves[3].getOrDefault(emptyList()),
        stations = stations.await(),
    )
}
