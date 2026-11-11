package app.winters.octo.playback

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.matchKey
import app.winters.octo.catalog.searchKey
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.discovery.Discovered
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.SearchTopSongs
import app.winters.octo.player.PlayerSettings
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.ui.paging.ListLoaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

// What a spoken request names, when the assistant says: an artist, an album
// or a song. Anything else is words alone.
enum class VoiceFocus { Any, Artist, Album, Song }

// A spoken request: the words said, what they name, and the names the
// assistant picked out of them.
data class VoiceAsk(val words: String, val focus: VoiceFocus, val artist: String?, val album: String?, val title: String?)

// The kinds of focus the assistant sends (MediaStore's entry content types).
const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"
const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
const val FOCUS_SONG = "vnd.android.cursor.item/audio"

// A request from what the assistant sent: the words, its focus and the
// names in its extras. A blank name counts as none.
fun voiceAsk(query: String?, focus: String?, artist: String?, album: String?, title: String?): VoiceAsk = VoiceAsk(
    query.orEmpty().trim(),
    when (focus) {
        FOCUS_ARTIST -> VoiceFocus.Artist
        FOCUS_ALBUM -> VoiceFocus.Album
        FOCUS_SONG -> VoiceFocus.Song
        else -> VoiceFocus.Any
    },
    artist?.trim()?.ifEmpty { null },
    album?.trim()?.ifEmpty { null },
    title?.trim()?.ifEmpty { null },
)

// What a request plays: the list, by what it is (so the queue reads it a
// page at a time), whether shuffled, and its queue heading.
data class VoicePlay(val spec: ListSpec, val shuffle: Boolean, val source: QueueSource)

// Every song, in title order: what saying nothing in particular shuffles.
val EVERY_SONG = ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false), null)

// Where a spoken request is looked up.
internal interface VoiceSource {
    // Whether a list of the library holds any song, by a read of its first.
    suspend fun hasSongs(spec: ListSpec): Boolean
    suspend fun albums(key: String): List<AlbumEntity>
    suspend fun artists(key: String): List<ArtistEntity>
    suspend fun songs(key: String): List<TrackEntity>

    // Whether music outside the library may play: a server, and Library
    // songs only off.
    suspend fun outsideAllowed(): Boolean
    suspend fun outside(words: String): Discovered?
    suspend fun outsideArtistTop(name: String, artistId: String): List<String>
}

// A spoken request, like "play Nothing Was the Same on Octo". The library
// answers first, by the same rules as ever: an album or artist named
// exactly (case, accents and punctuation aside), then songs that match. With
// the assistant's focus, only that kind is looked for. When the library has
// nothing and music outside it may play, the server is asked: an album
// named exactly plays as the server lists it, an artist named exactly their
// top songs, and otherwise the songs it found. Saying nothing in particular
// shuffles the whole library. Null when nothing is found. A list is named
// by what it is, never read whole.
@Singleton
class CarVoice internal constructor(private val source: VoiceSource) {
    @Inject constructor(
        catalog: CatalogDao,
        sessions: SessionRepository,
        discovery: Discovery,
        tops: SearchTopSongs,
        player: PlayerSettings,
        loaders: ListLoaders,
        owners: WorkOwners,
    ) : this(AppVoiceSource(catalog, sessions, discovery, tops, player, loaders, owners))

    suspend fun play(ask: VoiceAsk): VoicePlay? {
        if (ask.words.isEmpty() && ask.artist == null && ask.album == null && ask.title == null) {
            return VoicePlay(EVERY_SONG, true, NoSource).takeIf { source.hasSongs(EVERY_SONG) }
        }
        library(ask)?.let { return it }
        if (!source.outsideAllowed()) return null
        return try {
            outside(ask)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun library(ask: VoiceAsk): VoicePlay? {
        val album = if (ask.focus == VoiceFocus.Any || ask.focus == VoiceFocus.Album) libraryAlbum(ask) else null
        if (album != null) return VoicePlay(ListSpec.Album(album.id, withFinds = false), false, QueueSource.Played(album.title)).orNull()
        val artist = if (ask.focus == VoiceFocus.Any || ask.focus == VoiceFocus.Artist) libraryArtist(ask) else null
        if (artist != null) return VoicePlay(ListSpec.Artist(artist.id, ArtistOrder.Albums), true, QueueSource.Played(artist.name)).orNull()
        if (ask.focus == VoiceFocus.Album || ask.focus == VoiceFocus.Artist) return null
        val title = ask.title ?: ask.words
        val songs = source.songs(searchKey(title)).filter { ask.artist == null || SongIdentity.sameArtistName(it.artist, ask.artist) }
        return VoicePlay(ListSpec.Ids(songs.map { it.id }), false, NoSource).orNull()
    }

    private suspend fun libraryAlbum(ask: VoiceAsk): AlbumEntity? {
        val name = ask.album ?: ask.words
        val key = matchKey(name)
        if (key.isEmpty()) return null
        return source.albums(searchKey(name)).firstOrNull {
            matchKey(it.title) == key && (ask.artist == null || SongIdentity.sameArtistName(it.artist, ask.artist))
        }
    }

    private suspend fun libraryArtist(ask: VoiceAsk): ArtistEntity? {
        val name = ask.artist ?: ask.words
        return source.artists(searchKey(name)).firstOrNull { SongIdentity.sameArtistName(it.name, name) }
    }

    private suspend fun outside(ask: VoiceAsk): VoicePlay? {
        val words = when (ask.focus) {
            VoiceFocus.Album -> listOfNotNull(ask.album, ask.artist).joinToString(" ")
            VoiceFocus.Artist -> ask.artist
            VoiceFocus.Song -> listOfNotNull(ask.title, ask.artist).joinToString(" ")
            VoiceFocus.Any -> null
        }?.ifBlank { null } ?: ask.words
        val found = source.outside(words) ?: return null
        if (ask.focus == VoiceFocus.Any || ask.focus == VoiceFocus.Album) {
            val key = matchKey(ask.album ?: ask.words)
            val album = (found.albums + found.partAlbums).firstOrNull { key.isNotEmpty() && matchKey(it.title) == key }
            // Read from the server when it plays, as the album page reads it.
            if (album != null) return VoicePlay(ListSpec.Online(OnlineKind.Album, album.id), false, QueueSource.Played(album.title))
        }
        if (ask.focus == VoiceFocus.Any || ask.focus == VoiceFocus.Artist) {
            val name = ask.artist ?: ask.words
            val artist = found.artists.firstOrNull { SongIdentity.sameArtistName(it.name, name) }
            if (artist != null) return VoicePlay(ListSpec.Ids(source.outsideArtistTop(artist.name, artist.id)), false, QueueSource.Played(artist.name)).orNull()
        }
        if (ask.focus == VoiceFocus.Album || ask.focus == VoiceFocus.Artist) return null
        return VoicePlay(ListSpec.Ids(found.songs.map { it.id }), false, NoSource).orNull()
    }

    // The request when its list holds a song: songs named one by one hold
    // at least one (at most QUEUE_WINDOW are kept), a library list is asked.
    private suspend fun VoicePlay.orNull(): VoicePlay? = when (val list = spec) {
        is ListSpec.Ids -> takeIf { list.ids.isNotEmpty() }?.copy(spec = ListSpec.Ids(list.ids.take(QUEUE_WINDOW), list.source))
        else -> takeIf { this@CarVoice.source.hasSongs(list) }
    }
}

private class AppVoiceSource(
    private val catalog: CatalogDao,
    private val sessions: SessionRepository,
    private val discovery: Discovery,
    private val tops: SearchTopSongs,
    private val player: PlayerSettings,
    private val loaders: ListLoaders,
    private val owners: WorkOwners,
) : VoiceSource {
    override suspend fun hasSongs(spec: ListSpec): Boolean = loaders.forSpec(spec, owners.walk())?.after(null, 1)?.isNotEmpty() == true

    override suspend fun albums(key: String): List<AlbumEntity> = catalog.searchAlbums(key, 5)

    override suspend fun artists(key: String): List<ArtistEntity> = catalog.searchArtists(key, 5)

    override suspend fun songs(key: String): List<TrackEntity> = catalog.searchTracks(key, 50)

    override suspend fun outsideAllowed(): Boolean =
        sessions.state.value is SessionState.SignedIn && !player.prefs.first().libraryOnly

    override suspend fun outside(words: String): Discovered? = discovery.search(words)

    override suspend fun outsideArtistTop(name: String, artistId: String): List<String> =
        tops.forArtist(name, null, artistId)?.songs.orEmpty().map { it.track.id }
}
