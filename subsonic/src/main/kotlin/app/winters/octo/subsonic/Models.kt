package app.winters.octo.subsonic

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

// Every field has a default, so a server that leaves one out still parses.

@Serializable
data class ServerInfo(
    val status: String = "",
    val version: String = "",
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean = false,
)

@Serializable
data class Extension(val name: String, val versions: List<Int> = emptyList())

@Serializable
data class User(
    val username: String = "",
    val adminRole: Boolean = false,
    val scrobblingEnabled: Boolean = false,
    val streamRole: Boolean = true,
)

@Serializable
data class ArtistRef(val id: String = "", val name: String = "")

@Serializable
data class Album(
    val id: String,
    val name: String = "",
    val artist: String = "",
    val artistId: String? = null,
    val displayArtist: String? = null,
    val artists: List<ArtistRef> = emptyList(),
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val genre: String? = null,
    val created: String? = null,
    val played: String? = null,
    val playCount: Long = 0,
    val starred: String? = null,
    val isCompilation: Boolean = false,
)

@Serializable
data class Song(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val displayArtist: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val duration: Int = 0,
    val coverArt: String? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val bitRate: Int? = null,
    val samplingRate: Int? = null,
    val bitDepth: Int? = null,
    val size: Long? = null,
    val starred: String? = null,
    // What a full library copy also keeps: who the album is by, the genre,
    // when the song was added and last played, and how often.
    val displayAlbumArtist: String? = null,
    val genre: String? = null,
    val created: String? = null,
    val played: String? = null,
    val playCount: Long? = null,
)

@Serializable
data class AlbumWithSongs(
    val id: String,
    val name: String = "",
    val artist: String = "",
    val artistId: String? = null,
    val displayArtist: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val genre: String? = null,
    val song: List<Song> = emptyList(),
)

@Serializable
data class Artist(
    val id: String,
    val name: String = "",
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val starred: String? = null,
)

@Serializable
data class ArtistWithAlbums(
    val id: String,
    val name: String = "",
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val album: List<Album> = emptyList(),
)

@Serializable
data class ArtistIndex(val name: String = "", val artist: List<Artist> = emptyList())

@Serializable
data class Artists(val index: List<ArtistIndex> = emptyList())

@Serializable
data class AlbumList(val album: List<Album> = emptyList())

@Serializable
data class Playlist(
    val id: String,
    val name: String = "",
    val comment: String? = null,
    val owner: String? = null,
    val public: Boolean = false,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val changed: String? = null,
)

@Serializable
data class Playlists(val playlist: List<Playlist> = emptyList())

@Serializable
data class PlaylistWithSongs(
    val id: String,
    val name: String = "",
    val comment: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val entry: List<Song> = emptyList(),
)

@Serializable
data class SearchResult(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList(),
)

@Serializable
data class Starred(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList(),
)

enum class AlbumListType(val wire: String) {
    NEWEST("newest"),
    RECENT("recent"),
    FREQUENT("frequent"),
    RANDOM("random"),
    ALPHABETICAL("alphabeticalByName"),
}

@Serializable
data class SongList(val song: List<Song> = emptyList())

@Serializable
data class RadioStation(
    val id: String,
    val name: String = "",
    val streamUrl: String = "",
    val coverArt: String? = null,
)

@Serializable
data class RadioStations(val internetRadioStation: List<RadioStation> = emptyList())

// A library folder. Servers send its id as a number or as a string, so it
// is read as either and kept as text.
@Serializable
data class MusicFolder(@Serializable(with = LooseString::class) val id: String, val name: String = "")

@Serializable
data class MusicFolders(val musicFolder: List<MusicFolder> = emptyList())

@Serializable
data class TokenInfo(val username: String? = null)

// Reads a number or a string as text.
internal object LooseString : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("LooseString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String =
        (decoder as? JsonDecoder)?.decodeJsonElement()?.jsonPrimitive?.content ?: decoder.decodeString()

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
