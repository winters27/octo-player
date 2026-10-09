package app.winters.octo.discovery

import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.matchKey
import app.winters.octo.catalog.onlineArtwork
import app.winters.octo.catalog.searchKey
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.server.serverSourceId
import app.winters.octo.subsonic.ArtistInfo
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// How many of an artist's top songs their page shows.
const val TOP_SONGS = 10

// The server extension that lets top songs be asked for by artist id.
private const val TOP_SONGS_BY_ID = "topSongsByArtistId"

// An artist like the one on the page: in the library when `libraryId` is
// set, otherwise only on the server, under `serverId`.
data class SimilarArtist(val name: String, val artwork: String?, val libraryId: String?, val serverId: String)

// What the signed-in server adds to a library artist's page. Any part can be
// empty, and the page shows only the parts that are not.
data class ArtistExtras(
    val topSongs: List<TrackEntity> = emptyList(),
    val about: String? = null,
    val similar: List<SimilarArtist> = emptyList(),
)

// The extras as "Library songs only" shows them: top songs in the library,
// and similar artists the library has.
fun ArtistExtras.inLibraryOnly(): ArtistExtras =
    copy(topSongs = librarySongsOnly(topSongs, true) { isFind(it.id) }, similar = similar.filter { it.libraryId != null })

// Reads what the server knows about library artists: their top songs, a
// biography and artists like them. Kept for as long as the app runs, per
// server and user, so going back to a page asks nothing again. Nothing comes
// back without a server, or when it cannot be reached.
@Singleton
class ArtistExtrasSource @Inject constructor(
    private val sessions: SessionRepository,
    private val sources: SourceDao,
    private val catalog: CatalogDao,
    private val discovery: Discovery,
) {
    private val cache = ConcurrentHashMap<String, ArtistExtras>()
    private val extensions = ConcurrentHashMap<String, Set<String>>()

    // Drops what was kept for one artist, so the next read asks the server again.
    fun forget(artistId: String) {
        cache.keys.removeAll { it.endsWith("|$artistId") }
    }

    suspend fun forArtist(artist: ArtistEntity): ArtistExtras? {
        val client = (sessions.state.value as? SessionState.SignedIn)?.session?.client ?: return null
        val sourceId = serverSourceId(client.baseUrl)
        val server = "${client.username}@${client.baseUrl}"
        cache["$server|${artist.id}"]?.let { return it }

        val serverId = serverArtistId(client, sourceId, artist)
        val (info, songs) = coroutineScope {
            val info = async { serverId?.let { id -> answerOf { client.artistInfo(id) } } }
            val songs = async {
                val byId = serverId?.takeIf { TOP_SONGS_BY_ID in extensionsOf(server, client) }
                answerOf { client.topSongs(artist.name, TOP_SONGS, byId) }?.let { found ->
                    if (byId != null) found else ownSongs(found, artist.name)
                }
            }
            info.await() to songs.await()
        }
        // Neither answered: the server could not be reached, so ask again next time.
        if (info == null && songs == null) return null

        val extras = ArtistExtras(
            topSongs = songs?.let { discovery.resolveFromServer(it) }.orEmpty().take(TOP_SONGS),
            about = info?.biography?.let(::cleanBiography),
            similar = info?.let { similarArtists(sourceId, artist, it) }.orEmpty(),
        )
        cache["$server|${artist.id}"] = extras
        return extras
    }

    // The artist's id on the server: the library's own when the artist came
    // from it, the server's artist of the same name when the library merged
    // them, or failing both, the one a search finds.
    private suspend fun serverArtistId(client: SubsonicClient, sourceId: String, artist: ArtistEntity): String? {
        serverArtistIdOf(artist.id, sourceId)?.let { return it }
        sources.artistsNamed(sourceId, searchKey(artist.name))
            .firstNotNullOfOrNull { row -> serverArtistIdOf(row.id, sourceId)?.takeIf { SongIdentity.sameArtistName(row.name, artist.name) } }
            ?.let { return it }
        val found = answerOf { client.search(artist.name, artists = 5, albums = 0, songs = 0).artist } ?: return null
        return found.firstOrNull { SongIdentity.sameArtistName(it.name, artist.name) }?.id
    }

    // Artists like this one, each opening in the library when it is there.
    private suspend fun similarArtists(sourceId: String, artist: ArtistEntity, info: ArtistInfo): List<SimilarArtist> {
        val others = info.similarArtist.filter { it.id.isNotEmpty() && !SongIdentity.sameArtistName(it.name, artist.name) }
        if (others.isEmpty()) return emptyList()
        val library = catalog.artistsWithKeys(others.map { searchKey(it.name) }.distinct()).associateBy { matchKey(it.name) }
        return others.map { other ->
            val known = library[matchKey(other.name)]
            SimilarArtist(
                name = known?.name ?: other.name,
                artwork = known?.artwork ?: onlineArtwork(sourceId, other.coverArt),
                libraryId = known?.id,
                serverId = other.id,
            )
        }.distinctBy { it.libraryId ?: it.serverId }
    }

    private suspend fun extensionsOf(server: String, client: SubsonicClient): Set<String> =
        extensions[server] ?: (answerOf { client.extensions() }?.mapTo(HashSet()) { it.name } ?: emptySet())
            .also { extensions[server] = it }

    // A server call's answer, or nothing when the server could not give one.
    private suspend fun <T> answerOf(call: suspend () -> T): T? =
        try {
            call()
        } catch (e: SubsonicException) {
            null
        }
}

// The server's own id for a library artist id of the form
// "<server source>:<id>". Nothing for other sources, or for an artist the
// library named itself because the server gave no id.
fun serverArtistIdOf(artistId: String, sourceId: String): String? =
    artistId.takeIf { it.startsWith("$sourceId:") }?.removePrefix("$sourceId:")?.takeIf { it.isNotEmpty() && !it.startsWith("artist:") }

// Top songs that are the artist's own, for a server asked by name, which
// can also send songs by someone else of a similar name.
internal fun ownSongs(songs: List<Song>, artistName: String): List<Song> =
    songs.filter { sameArtist(it.artist.orEmpty(), artistName) }
