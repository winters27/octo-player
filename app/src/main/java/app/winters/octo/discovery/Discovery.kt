package app.winters.octo.discovery

import app.winters.octo.animated.AnimatedArts
import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.keptFind
import app.winters.octo.catalog.matchKey
import app.winters.octo.catalog.onlineArtwork
import app.winters.octo.catalog.searchKey
import app.winters.octo.data.Drains
import app.winters.octo.data.Session
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.accountId
import app.winters.octo.data.runsOcto
import app.winters.octo.playback.FindMap
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.tracksByIds
import app.winters.octo.server.Arriving
import app.winters.octo.server.ServerSync
import app.winters.octo.server.arriving
import app.winters.octo.server.serverSourceId
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.AlbumWithSongs
import app.winters.octo.subsonic.OCTO_LIST_KINDS
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.RadioStation
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.paging.serverAlbumVote
import android.util.Log
import app.winters.octo.catalog.findId
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import app.winters.octo.subsonic.OCTO_STATIONS
import app.winters.octo.playback.STATION_BATCH
import app.winters.octo.playback.isStationId
import app.winters.octo.lists.OctoListGroup
import app.winters.octo.lists.octoListGroups
import app.winters.octo.server.serverIdsOf

// Octo holds back songs found online when a search asks for 12 songs or
// fewer, so always ask for more than that.
private const val SEARCH_SONGS = 40

// How many songs a radio plays after the one it started from.
private const val RADIO_SONGS = 50

// An album on the server, not in the library. `ownedCount` is how many of
// its songs the library holds, when Octo counted them.
data class OnlineAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val year: Int?,
    val songCount: Int,
    val artwork: String?,
    val ownedCount: Int? = null,
)

data class OnlineArtist(val id: String, val name: String, val albumCount: Int, val artwork: String?)

// A station the server runs, played as the list of songs it has lined up.
// A Made for you list is shown and played the same way, with its song count.
// An internet radio station added to the server has its stream instead.
@Serializable
data class Station(val id: String, val name: String, val artwork: String?, val songCount: Int? = null, val streamUrl: String? = null, val homepage: String? = null)

// Whether it is one of Octo's stations, which keep going, rather than a list
// with an end (a Made for you list) or an internet radio stream.
val Station.isRadio: Boolean get() = streamUrl == null && isStationId(id)

// The stations Home shows, from the server's internet radio stations. Octo's
// own carry their id as their cover and play the songs they have lined up;
// any other plays its stream, like on the Radio stations screen. An added
// station with no stream has nothing to play and is left off.
internal fun homeStations(stations: List<RadioStation>, sourceId: String): List<Station> =
    stations.filter { it.coverArt == it.id || it.streamUrl.isNotBlank() }.map {
        val stream = it.streamUrl.takeIf { _ -> it.coverArt != it.id }
        Station(it.id, it.name, ArtworkRef.Server(sourceId, it.coverArt ?: it.id).encode(), streamUrl = stream, homepage = it.homePageUrl?.takeIf(String::isNotBlank))
    }

// The lists Octo made for the listener (New Releases, Rediscover, Deep Cuts),
// in that order, from the server's playlists. Only an Octo server that makes
// them marks a playlist so; any other has none.
internal fun madeForYouLists(playlists: List<Playlist>, sourceId: String): List<Station> =
    playlists.filter { it.octoList != null }
        .sortedBy { OCTO_LIST_KINDS.indexOf(it.octoList).takeIf { i -> i >= 0 } ?: OCTO_LIST_KINDS.size }
        .map { Station(it.id, it.name, ArtworkRef.Server(sourceId, it.coverArt ?: it.id).encode(), it.songCount) }

// What a search found beyond the library. `partAlbums` are albums the
// library holds some of the songs of, kept apart from those it holds none
// of, so the "Not in your library" title is never over one that partly is.
// `arriving` is the server's own music the search found that the library
// does not have yet: it is none of the above, and takeIn brings it in.
class Discovered(
    val songs: List<TrackEntity>,
    val albums: List<OnlineAlbum>,
    val artists: List<OnlineArtist>,
    val partAlbums: List<OnlineAlbum> = emptyList(),
    val arriving: Arriving = Arriving.Nothing,
) {
    val isEmpty get() = notInLibraryEmpty && partAlbums.isEmpty()

    // Nothing to list under "Not in your library".
    val notInLibraryEmpty get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

// Made for you, and every list Octo made for the listener by group, as one
// read of the server's playlists gives them.
data class ServerLists(
    val madeForYou: List<Station> = emptyList(),
    val groups: List<Pair<OctoListGroup, List<Station>>> = emptyList(),
)

class OnlineArtistPage(val artist: OnlineArtist, val albums: List<OnlineAlbum>)

// Radio answers remembered for "Suggested by" before the oldest are dropped,
// and how long one is believed: a later radio may suggest the song again
// from another source, and an old answer would name the wrong one.
private const val SUGGESTIONS_KEPT = 5_000
private const val SUGGESTION_LIFETIME_MS = 12 * 60 * 60 * 1000L

// What the signed-in server offers beyond the library: songs, albums and
// artists found online, radio from any song, and stations. Songs come back
// shaped like library songs; one found online has an id starting "find:"
// and plays as a stream. Every call throws SubsonicException when the server
// cannot answer, and answers nothing when no server is signed in.
@Singleton
class Discovery @Inject constructor(
    private val db: CatalogDatabase,
    private val sessions: SessionRepository,
    private val sources: SourceDao,
    private val catalog: CatalogDao,
    private val online: OnlineDao,
    // Taken lazily: ServerSync needs PlaylistSync, which needs this.
    private val sync: Lazy<ServerSync>,
    private val animatedArts: AnimatedArts,
    private val drains: Drains,
    // Finds whose rows are kept behind the answer that found them.
    private val finds: FindMap,
    // Songs of the server's library its lists named that the copy lacks.
    private val owned: OwnedFinds,
    // Taken lazily: an owned song's heart moves onto its library song.
    private val likes: Lazy<LikeStore>,
) {
    // Names each drained answer read here, so two never share a file.
    private val drained = AtomicLong()

    // Brings owned songs in behind the read that named them.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The owned songs being brought in now, by server id, so two reads of
    // one list ask for them once.
    private val bringing = ConcurrentHashMap.newKeySet<String>()

    // Lists the server no longer has, found out on their own page, by id.
    private val _gone = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val gone: SharedFlow<String> = _gone.asSharedFlow()

    fun listGone(id: String) {
        _gone.tryEmit(id)
    }

    // Whether a server is signed in, so there is anything to discover.
    val available: Flow<Boolean> = sessions.state.map { it is SessionState.SignedIn }

    // Which kept server what is found comes from, by its id; null with
    // none signed in. A switch to another kept server changes it, so what
    // the last one found is asked again.
    val server: Flow<String?> = sessions.state.map { it.accountId }.distinctUntilChanged()

    // Whether the signed-in server runs stations, which only Octo does. It
    // can turn true after sign-in, once the server's extensions are read again.
    val offersStations: Flow<Boolean> =
        sessions.state.map { (it as? SessionState.SignedIn)?.session?.runsOcto == true }

    // The kept server whose stations Home shows, by its id: the one signed
    // in to, while it runs them; null otherwise.
    val stationsFrom: Flow<String?> =
        sessions.state.map { state -> (state as? SessionState.SignedIn)?.session?.takeIf { it.runsOcto }?.id }.distinctUntilChanged()

    suspend fun search(query: String): Discovered? {
        val (client, sourceId) = server() ?: return null
        val found = client.search(query.trim(), artists = 10, albums = 20, songs = SEARCH_SONGS)
        // The server's own songs and albums the library has not copied yet
        // are not finds: they are left out here and taken in (takeIn).
        val linked = found.song.map { it.id }.distinct().chunked(900)
            .flatMap { sources.libraryLinks(sourceId, it) }
            .mapTo(HashSet()) { it.serverId }
        val knownAlbums = if (found.album.isEmpty()) emptySet() else sources.knownAlbums(sourceId, found.album.map { it.id }).toSet()
        val octo = (sessions.state.value as? SessionState.SignedIn)?.session?.runsOcto == true
        val arriving = arriving(found, { it.id in linked }, { it.id in knownAlbums }, findsOnline = octo)
        val newSongs = arriving.songs.mapTo(HashSet()) { it.id }
        val newAlbums = arriving.albums.mapTo(HashSet()) { it.id }
        val songs = resolve(client, sourceId, found.song.filterNot { it.id in newSongs }).filter { isFind(it.id) }
        // Octo counts how many songs of each album the library holds. One it
        // holds every song of is not a find (its songs are in the library);
        // one it holds some of goes apart, saying how many.
        val albums = notInLibrary(sourceId, found.album.filterNot { it.id in newAlbums }).filter { albumShare(it) != AlbumShare.Whole }
        val (part, none) = albums.partition { albumShare(it) == AlbumShare.Part }
        return Discovered(
            songs,
            none.map { it.toOnline(sourceId) },
            artistsNotInLibrary(sourceId, found),
            partAlbums = part.map { it.toOnline(sourceId) },
            arriving = arriving,
        )
    }

    // Brings the server's own music a search found into the library (see
    // Discovered.arriving). Answers whether any of it is in the library now,
    // so the library's results are worth reading again.
    suspend fun takeIn(found: Discovered): Boolean = !found.arriving.isEmpty && sync.get().takeInArriving(found.arriving)

    // The song first, then songs like it.
    suspend fun radio(seed: TrackEntity, count: Int = RADIO_SONGS): List<TrackEntity> {
        val (client, sourceId) = server() ?: return emptyList()
        val seedId = serverIdOf(client, seed) ?: return emptyList()
        val similar = resolve(client, sourceId, client.similarSongs(seedId, count))
        return (listOf(seed) + similar).distinctBy { it.id }
    }

    // Only Octo runs stations. Other servers list internet radio here, which
    // is a stream, not songs, so nothing is shown for them.
    suspend fun stations(): List<Station> {
        val session = (sessions.state.value as? SessionState.SignedIn)?.session ?: return emptyList()
        if (!session.runsOcto) return emptyList()
        val (client, sourceId) = server() ?: return emptyList()
        return homeStations(client.radioStations(), sourceId)
    }

    // The same for the session given rather than the one in use now, so a
    // car's shelves never mix two servers' answers. Only Octo runs stations.
    suspend fun stationsOn(session: Session): List<Station> {
        if (!session.runsOcto) return emptyList()
        return homeStations(session.client.radioStations(), serverSourceId(session.client.primaryUrl))
    }

    // Made for you and every list Octo made for the listener, by group, from
    // one read of the session's playlists; none on another server.
    suspend fun serverListsOn(session: Session): ServerLists {
        if (!session.runsOcto) return ServerLists()
        val client = session.client
        val sourceId = serverSourceId(client.primaryUrl)
        val lists = client.playlists()
        return ServerLists(
            madeForYouLists(lists, sourceId),
            octoListGroups(lists, client.username).map { (group, items) ->
                group to items.map { Station(it.id, it.name, ArtworkRef.Server(sourceId, it.coverArt ?: it.id).encode(), it.songCount) }
            },
        )
    }

    // Every list Octo made for the listener, by group, each shown as a card
    // with its name, cover and song count: the Playlists page's "From Octo"
    // and Home's shelves of mixes.
    suspend fun octoLists(): List<Pair<OctoListGroup, List<Station>>> {
        val session = (sessions.state.value as? SessionState.SignedIn)?.session ?: return emptyList()
        if (!session.runsOcto) return emptyList()
        val (client, sourceId) = server() ?: return emptyList()
        return octoListGroups(client.playlists(), client.username).map { (group, lists) ->
            group to lists.map { Station(it.id, it.name, ArtworkRef.Server(sourceId, it.coverArt ?: it.id).encode(), it.songCount) }
        }
    }

    // The lists Octo made for the listener, from its playlists.
    suspend fun madeForYou(): List<Station> {
        val session = (sessions.state.value as? SessionState.SignedIn)?.session ?: return emptyList()
        if (!session.runsOcto) return emptyList()
        val (client, sourceId) = server() ?: return emptyList()
        return madeForYouLists(client.playlists(), sourceId)
    }

    // Whether the server sends a station's next songs (octoStations), so a
    // station keeps going instead of ending with the songs it had lined up.
    val keepsStationsGoing: Boolean
        get() = (sessions.state.value as? SessionState.SignedIn)?.session?.extensions
            ?.any { it == OCTO_STATIONS || it.startsWith("$OCTO_STATIONS:") } == true

    // A station's next songs, none of `after` (its songs already queued, by
    // the phone's ids; the server is told its own ids for them).
    suspend fun stationMore(id: String, after: List<String>): List<TrackEntity> {
        val (client, sourceId) = server() ?: return emptyList()
        val serverIds = sources.serverIdsOf(sourceId, after)
        return resolve(client, sourceId, client.stationSongs(id, STATION_BATCH, after.mapNotNull(serverIds::get)))
    }

    // A library album as the server lists it now, by its id: the library's
    // songs and, as finds, the ones it lacks (see outsideAlbumSongs), in the
    // server's order. The album asked for is the server's that most of its
    // songs came from (serverAlbumOf). Null when no Octo server is signed in
    // (only Octo lists songs outside the library) or none of its songs came
    // from it. The answer is drained to a file and held only when the
    // listing itself names at most `most` songs; a longer one answers null,
    // and the page shows the library's songs. So only an album page holding
    // at most WHOLE_ALBUM_MAX of the library's songs asks for it, with that
    // limit; a bigger one reads the listing a page at a time (AlbumListing).
    suspend fun libraryAlbum(albumId: String, most: Int): List<TrackEntity>? {
        val session = (sessions.state.value as? SessionState.SignedIn)?.session ?: return null
        if (!session.runsOcto) return null
        val (client, sourceId) = server() ?: return null
        val native = serverAlbumOf(db, albumId, sourceId) ?: return null
        val (album, sent) = drains.use("library-album-${drained.incrementAndGet()}.json") { file -> client.listingUpTo(native, file, most) }
        animatedArts.note(client, native, album.animatedArt)
        sent ?: return null
        val resolved = resolve(client, sourceId, sent)
        ownedSongs(sourceId, ownedAlbumSongs(sent, resolved), starredKnown = true)
        return outsideAlbumSongs(sent, resolved)
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

    // The same, one for each song sent and in its place, null where a
    // library song is gone: for lists whose places mean something, like a
    // ranking. Nothing when no server is signed in.
    internal suspend fun resolveInPlace(songs: List<Song>): List<TrackEntity?> {
        val (_, sourceId) = server() ?: return emptyList()
        return resolvedInPlace(sourceId, songs)
    }

    // Which of Octo's radio sources suggested a track, as its last radio
    // answer said, for the song's info. Kept while the app runs, not saved.
    private val suggested = Suggestions(SUGGESTIONS_KEPT, SUGGESTION_LIFETIME_MS)

    fun suggestedBy(trackId: String): String? = suggested[trackId]

    // Radio sources a server list's read found, by the songs they resolved to.
    internal fun noteSuggestions(by: Map<String, String>) = suggested.putAll(by)

    // Songs from the server as the app shows them, in the order sent: a
    // library song as it is in the library, anything else as a find. A find
    // already downloaded is the library song it became.
    private suspend fun resolve(client: SubsonicClient, sourceId: String, songs: List<Song>): List<TrackEntity> =
        resolvedInPlace(sourceId, songs).filterNotNull().distinctBy { it.id }

    private suspend fun resolvedInPlace(sourceId: String, songs: List<Song>): List<TrackEntity?> {
        if (songs.isEmpty()) return emptyList()
        val each = resolveEach(sourceId, songs)
        val adopted = each.filterIsInstance<Resolved.Found>().mapNotNull { it.song.adoptedId.ifEmpty { null } }
        val library = catalog.tracksByIds(each.filterIsInstance<Resolved.InLibrary>().map { it.trackId } + adopted).associateBy { it.id }
        val resolved = each.map { asAdopted(it, library.keys) }
        val tracks = resolved.map { r ->
            when (r) {
                is Resolved.InLibrary -> library[r.trackId]
                is Resolved.Found -> r.song.asTrack()
            }
        }
        suggested.putAll(suggestionsOf(tracks.map { it?.id }, songs))
        return tracks
    }

    // What each song sent is, in order, with the finds among them kept. The
    // server's own songs the copy lacks are noted as owned and brought in.
    private suspend fun resolveEach(sourceId: String, songs: List<Song>): List<Resolved> =
        resolveEachRead(sources, catalog, online, finds, sourceId, songs).also { bringIn(sourceId, songs, it) }

    // Notes which of the songs sent (`each`, as resolved) the server owns and
    // the copy lacks, and brings those in behind this read, ahead of the next
    // full copy, linking each one's find to the library song it became so a
    // queue holding the find plays that. While a copy runs, the copy brings
    // them instead.
    private fun bringIn(sourceId: String, songs: List<Song>, each: List<Resolved>) {
        val found = each.filterIsInstance<Resolved.Found>()
        if (found.isEmpty()) return
        val (mine, outside) = found.partition { it.owned }
        owned.note(emptyList(), outside.map { it.song.id })
        val wanted = mine.mapTo(HashSet()) { it.song.nativeId }
        ownedSongs(sourceId, songs.filter { it.id in wanted }, starredKnown = true)
    }

    // Songs of the server's own library a read named that the copy lacks, on
    // server `sourceId`: noted as owned, and brought in as bringIn says.
    // With `starredKnown` (songs a full answer gave), each one's heart is
    // whether the server stars it; songs made from a paged read's rows do
    // not say, and keep the heart they had.
    fun ownedSongs(sourceId: String, songs: List<Song>, starredKnown: Boolean = false) {
        if (songs.isEmpty()) return
        val stars = if (starredKnown) songs.associate { findId(it.id) to (it.starred != null) } else emptyMap()
        owned.note(songs.map { findId(it.id) }, starred = stars)
        val asking = songs.distinctBy { it.id }.filter { bringing.add(it.id) }
        if (asking.isEmpty()) return
        scope.launch {
            try {
                if (sync.get().takeInOwned(asking)) {
                    linkOwnedFinds(sources, online, finds, sourceId, asking.map { it.id }) { find, track -> likes.get().ownedArrived(find, track) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Octo", "owned songs take-in failed: ${e.javaClass.simpleName}")
            } finally {
                bringing.removeAll(asking.map { it.id }.toSet())
            }
        }
    }

    // The server's id for a song: a find's own, a library song's server copy,
    // or, for a song only on the phone, the same song as the server knows it:
    // the same recording when the server has it, or else another version.
    private suspend fun serverIdOf(client: SubsonicClient, track: TrackEntity): String? {
        if (isFind(track.id)) return track.id.removePrefix(FIND_PREFIX)
        sources.copies(track.id).firstOrNull { it.sourceId.startsWith("server:") }?.let { return it.nativeId }
        val found = try {
            client.search("${track.artist} ${track.title}", artists = 0, albums = 0, songs = SEARCH_SONGS).song
        } catch (e: SubsonicException) {
            return null
        }
        return (
            found.firstOrNull { sameRecording(it.title, it.artist.orEmpty(), track.title, track.artist) }
                ?: found.firstOrNull { sameSongAnyVersion(it.title, it.artist.orEmpty(), track.title, track.artist) }
            )?.id
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
        ownedCount = ownedCount.takeIf { isExternal },
    )

    private fun server(): Pair<SubsonicClient, String>? {
        val client = (sessions.state.value as? SessionState.SignedIn)?.session?.client ?: return null
        return client to serverSourceId(client.primaryUrl)
    }
}

// What each song sent is, in order (Discovery.resolveEach), read with no
// write: the finds among them as the app keeps them, so a length learned
// from playing one shows, held in the find map with their rows kept behind
// the answer, so a play of them waits for no write.
internal suspend fun resolveEachRead(
    sources: SourceDao,
    catalog: CatalogDao,
    online: OnlineDao,
    finds: FindMap,
    sourceId: String,
    songs: List<Song>,
): List<Resolved> {
    if (songs.isEmpty()) return emptyList()
    val links = songs.map { it.id }.distinct().chunked(900)
        .flatMap { sources.libraryLinks(sourceId, it) }
        .associate { it.serverId to it.trackId }
    val unlinked = songs.filter { it.id !in links }
    val candidates = unlinked.flatMap { titleKeys(it.title) }.distinct().sorted().chunked(900).flatMap { catalog.tracksWithKeys(it) }
    val resolved = resolveSongs(songs, sourceId, links, candidates, System.currentTimeMillis())
    val found = resolved.filterIsInstance<Resolved.Found>().map { it.song }.distinctBy { it.id }
    val before = found.map { it.id }.chunked(900).flatMap { online.byIds(it) }.associateBy { it.id }
    val kept = found.map { f -> before[f.id]?.let { keptFind(f, it) } ?: f }.associateBy { it.id }
    finds.post(kept.values)
    return resolved.map { r -> if (r is Resolved.Found) kept[r.song.id]?.let { r.copy(song = it) } ?: r else r }
}

// The server's own id of the album on server `sourceId` that most of a
// library album's songs came from, their copies counted a page of songs at
// a time (serverAlbumVote); null when none came from it.
internal suspend fun serverAlbumOf(db: CatalogDatabase, albumId: String, sourceId: String): String? =
    serverAlbumVote(db, albumId, sourceId)?.let { db.sourceDao().albumNativeId(it) }

// An album's listing, drained to `file` and read a page at a time: the
// album, and its songs when the listing names at most `most` of them, or
// null for its songs when it names more, holding no more than a page of
// those past the limit.
internal suspend fun SubsonicClient.listingUpTo(id: String, file: File, most: Int): Pair<AlbumWithSongs, List<Song>?> {
    val sent = ArrayList<Song>()
    var over = false
    val album = albumInto(id, file) { page ->
        if (over) return@albumInto
        if (sent.size + page.size > most) {
            over = true
            sent.clear()
        } else {
            sent += page
        }
    }
    return album to sent.takeUnless { over }
}

// Which source suggested each track, newest last: past the cap the oldest
// answers go, and one older than its lifetime is forgotten, so the song info
// never names a source from a radio long gone. Safe across threads.
internal class Suggestions(
    private val kept: Int,
    private val lifetimeMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val entries = LinkedHashMap<String, Pair<String, Long>>()

    @Synchronized
    fun putAll(sources: Map<String, String>) {
        val at = now()
        for ((id, by) in sources) {
            // Taken out first, so a song suggested again moves to the newest end.
            entries.remove(id)
            entries[id] = by to at
        }
        val over = entries.size - kept
        if (over > 0) entries.keys.take(over).forEach(entries::remove)
    }

    @Synchronized
    operator fun get(id: String): String? {
        val (by, at) = entries[id] ?: return null
        if (now() - at <= lifetimeMs) return by
        entries.remove(id)
        return null
    }
}
