package app.winters.octo.discovery

import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.matchKey
import app.winters.octo.catalog.onlineArtwork
import app.winters.octo.catalog.searchKey
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.playback.tracksByIds
import app.winters.octo.server.serverSourceId
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Octo holds back songs found online when a search asks for 12 songs or
// fewer, so always ask for more than that.
private const val SEARCH_SONGS = 40

// How many songs a radio plays after the one it started from.
private const val RADIO_SONGS = 50

// An album on the server, not in the library.
data class OnlineAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val year: Int?,
    val songCount: Int,
    val artwork: String?,
)

data class OnlineArtist(val id: String, val name: String, val albumCount: Int, val artwork: String?)

// A station the server runs, played as the list of songs it has lined up.
data class Station(val id: String, val name: String, val artwork: String?)

// What a search found beyond the library.
class Discovered(val songs: List<TrackEntity>, val albums: List<OnlineAlbum>, val artists: List<OnlineArtist>) {
    val isEmpty get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

class OnlineAlbumPage(val album: OnlineAlbum, val songs: List<TrackEntity>)

class OnlineArtistPage(val artist: OnlineArtist, val albums: List<OnlineAlbum>)

// What the signed-in server offers beyond the library: songs, albums and
// artists found online, radio from any song, and stations. Songs come back
// shaped like library songs; one found online has an id starting "find:"
// and plays as a stream. Every call throws SubsonicException when the server
// cannot answer, and answers nothing when no server is signed in.
@Singleton
class Discovery @Inject constructor(
    private val sessions: SessionRepository,
    private val sources: SourceDao,
    private val catalog: CatalogDao,
    private val online: OnlineDao,
) {
    // Whether a server is signed in, so there is anything to discover.
    val available: Flow<Boolean> = sessions.state.map { it is SessionState.SignedIn }

    suspend fun search(query: String): Discovered? {
        val (client, sourceId) = server() ?: return null
        val found = client.search(query.trim(), artists = 10, albums = 20, songs = SEARCH_SONGS)
        val songs = resolve(client, sourceId, found.song).filter { isFind(it.id) }
        return Discovered(songs, notInLibrary(sourceId, found.album).map { it.toOnline(sourceId) }, artistsNotInLibrary(sourceId, found))
    }

    // The song first, then songs like it.
    suspend fun radio(seed: TrackEntity): List<TrackEntity> {
        val (client, sourceId) = server() ?: return emptyList()
        val seedId = serverIdOf(client, seed) ?: return emptyList()
        val similar = resolve(client, sourceId, client.similarSongs(seedId, RADIO_SONGS))
        return (listOf(seed) + similar).distinctBy { it.id }
    }

    suspend fun stations(): List<Station> {
        val (client, sourceId) = server() ?: return emptyList()
        return client.radioStations().map { station ->
            Station(station.id, station.name, ArtworkRef.Server(sourceId, station.coverArt ?: station.id).encode())
        }
    }

    // The songs a station has lined up now.
    suspend fun stationSongs(id: String): List<TrackEntity> {
        val (client, sourceId) = server() ?: return emptyList()
        return resolve(client, sourceId, client.playlist(id).entry)
    }

    suspend fun album(id: String): OnlineAlbumPage? {
        val (client, sourceId) = server() ?: return null
        val album = client.album(id)
        val info = OnlineAlbum(
            album.id, album.name, album.artist, album.artistId, album.year, album.songCount,
            onlineArtwork(sourceId, album.coverArt),
        )
        return OnlineAlbumPage(info, resolve(client, sourceId, album.song))
    }

    suspend fun artist(id: String): OnlineArtistPage? {
        val (client, sourceId) = server() ?: return null
        val artist = client.artist(id)
        return OnlineArtistPage(
            OnlineArtist(artist.id, artist.name, artist.albumCount, onlineArtwork(sourceId, artist.coverArt)),
            artist.album.map { it.toOnline(sourceId) },
        )
    }

    // Songs from the signed-in server as the app shows them, in the order
    // sent. Nothing when no server is signed in.
    internal suspend fun resolveFromServer(songs: List<Song>): List<TrackEntity> {
        val (client, sourceId) = server() ?: return emptyList()
        return resolve(client, sourceId, songs)
    }

    // One id for each song sent, in the same order with repeats kept: the
    // library song it is, or a find. For lists that must keep every entry,
    // like a playlist. Nothing when no server is signed in.
    internal suspend fun idsFromServer(songs: List<Song>): List<String> {
        val (_, sourceId) = server() ?: return emptyList()
        return resolveEach(sourceId, songs).map { it.id }
    }

    // Songs from the server as the app shows them, in the order sent: a
    // library song as it is in the library, anything else as a find. A find
    // already downloaded is the library song it became.
    private suspend fun resolve(client: SubsonicClient, sourceId: String, songs: List<Song>): List<TrackEntity> {
        if (songs.isEmpty()) return emptyList()
        val each = resolveEach(sourceId, songs)
        val adopted = each.filterIsInstance<Resolved.Found>().mapNotNull { it.song.adoptedId.ifEmpty { null } }
        val library = catalog.tracksByIds(each.filterIsInstance<Resolved.InLibrary>().map { it.trackId } + adopted).associateBy { it.id }
        val resolved = each.map { asAdopted(it, library.keys) }
        return resolved.mapNotNull { r ->
            when (r) {
                is Resolved.InLibrary -> library[r.trackId]
                is Resolved.Found -> r.song.asTrack()
            }
        }.distinctBy { it.id }
    }

    // What each song sent is, in order, with the finds among them kept.
    private suspend fun resolveEach(sourceId: String, songs: List<Song>): List<Resolved> {
        if (songs.isEmpty()) return emptyList()
        val links = songs.map { it.id }.distinct().chunked(900)
            .flatMap { sources.libraryLinks(sourceId, it) }
            .associate { it.serverId to it.trackId }
        val unlinked = songs.filter { it.id !in links }
        val candidates = unlinked.flatMap { titleKeys(it.title) }.distinct().chunked(900).flatMap { catalog.tracksWithKeys(it) }
        val resolved = resolveSongs(songs, sourceId, links, candidates, System.currentTimeMillis())
        // Finds as stored, so a length learned from playing one shows.
        val kept = online.keep(resolved.filterIsInstance<Resolved.Found>().map { it.song }.distinctBy { it.id }).associateBy { it.id }
        return resolved.map { r -> if (r is Resolved.Found) kept[r.song.id]?.let(Resolved::Found) ?: r else r }
    }

    // The server's id for a song: a find's own, a library song's server copy,
    // or, for a song only on the phone, the same song as the server knows it.
    private suspend fun serverIdOf(client: SubsonicClient, track: TrackEntity): String? {
        if (isFind(track.id)) return track.id.removePrefix(FIND_PREFIX)
        sources.copies(track.id).firstOrNull { it.sourceId.startsWith("server:") }?.let { return it.nativeId }
        val found = try {
            client.search("${track.artist} ${track.title}", artists = 0, albums = 0, songs = SEARCH_SONGS).song
        } catch (e: SubsonicException) {
            return null
        }
        return found.firstOrNull { matchKey(it.title) == matchKey(track.title) && sameArtist(it.artist.orEmpty(), track.artist) }?.id
    }

    // Albums the library has neither from this server nor by the same name
    // and artist from anywhere else.
    private suspend fun notInLibrary(sourceId: String, albums: List<Album>): List<Album> {
        if (albums.isEmpty()) return emptyList()
        val known = sources.knownAlbums(sourceId, albums.map { it.id }).toSet()
        val named = catalog.albumsWithKeys(albums.map { searchKey("${it.name} ${it.artist}") })
            .mapTo(HashSet()) { matchKey(it.title) + "|" + matchKey(it.artist) }
        return albums.filter { it.id !in known && matchKey(it.name) + "|" + matchKey(it.artist) !in named }
    }

    private suspend fun artistsNotInLibrary(sourceId: String, found: app.winters.octo.subsonic.SearchResult): List<OnlineArtist> {
        if (found.artist.isEmpty()) return emptyList()
        val known = sources.knownArtists(found.artist.map { "$sourceId:${it.id}" }).toSet()
        val named = catalog.artistsWithKeys(found.artist.map { searchKey(it.name) }).mapTo(HashSet()) { matchKey(it.name) }
        return found.artist
            .filter { "$sourceId:${it.id}" !in known && matchKey(it.name) !in named }
            .map { OnlineArtist(it.id, it.name, it.albumCount, onlineArtwork(sourceId, it.coverArt)) }
    }

    private fun Album.toOnline(sourceId: String) = OnlineAlbum(
        id, name, artist, artistId, year, songCount, onlineArtwork(sourceId, coverArt),
    )

    private fun server(): Pair<SubsonicClient, String>? {
        val client = (sessions.state.value as? SessionState.SignedIn)?.session?.client ?: return null
        return client to serverSourceId(client.primaryUrl)
    }
}
