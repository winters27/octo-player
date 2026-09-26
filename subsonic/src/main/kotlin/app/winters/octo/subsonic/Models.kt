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
    // How loud the song is, from an OpenSubsonic server, and the signed-in
    // user's rating of it from 1 to 5.
    val replayGain: SongReplayGain? = null,
    val userRating: Int? = null,
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
    // A playlist the server makes itself and nobody can edit, like a
    // station's list of songs, and when a generated one runs out.
    val readonly: Boolean = false,
    val created: String? = null,
    val validUntil: String? = null,
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
    // Who it belongs to, and when it was made and last changed, as the
    // list of playlists has them.
    val owner: String? = null,
    val public: Boolean = false,
    val created: String? = null,
    val changed: String? = null,
    val readonly: Boolean = false,
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

// A song's loudness as an OpenSubsonic server sends it: gains in decibels,
// peaks where 1 is full scale. The base gain is one the file already applies
// (an Opus header's, say); the fallback is what the server suggests for a
// song with no gain of its own. Any of them can be missing.
@Serializable
data class SongReplayGain(
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
    val baseGain: Float? = null,
    val fallbackGain: Float? = null,
)

// What a server knows about an artist beyond their music: a biography (HTML,
// often ending in a link to where it came from), pictures, and artists like
// them.
@Serializable
data class ArtistInfo(
    val biography: String? = null,
    val musicBrainzId: String? = null,
    val lastFmUrl: String? = null,
    val smallImageUrl: String? = null,
    val mediumImageUrl: String? = null,
    val largeImageUrl: String? = null,
    val similarArtist: List<Artist> = emptyList(),
)

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

// Lyrics a server keeps for a song (OpenSubsonic songLyrics). There can be
// several: synced and plain, other languages, and with version 2 a
// translation or pronunciation beside the main one.
@Serializable
data class LyricsList(val structuredLyrics: List<StructuredLyrics> = emptyList())

@Serializable
data class StructuredLyrics(
    val lang: String = "und",
    val synced: Boolean = false,
    val line: List<LyricsLine> = emptyList(),
    val displayArtist: String? = null,
    val displayTitle: String? = null,
    // Milliseconds to move every time by. It follows the LRC offset tag:
    // above zero, the words come sooner.
    val offset: Double = 0.0,
    // "main", "translation" or "pronunciation". Missing means main.
    val kind: String? = null,
    // Who sings, when the lines are split between voices.
    val agents: List<LyricsAgent> = emptyList(),
    // Word timings, one or more per line, matched to a line by its index.
    val cueLine: List<CueLine> = emptyList(),
)

@Serializable
data class LyricsLine(
    // Milliseconds from the start of the song; missing when not synced.
    val start: Long? = null,
    val value: String = "",
)

@Serializable
data class LyricsAgent(
    val id: String = "",
    // "main", "voice" (another singer), "bg" (backing vocals) or "group".
    val role: String = "main",
    val name: String? = null,
)

@Serializable
data class CueLine(
    val index: Int = 0,
    val agentId: String? = null,
    val start: Long? = null,
    val end: Long? = null,
    val value: String = "",
    val cue: List<Cue> = emptyList(),
)

// One timed word or syllable. Its place in the cue line's value is given in
// UTF-8 bytes, both ends included, not in characters.
@Serializable
data class Cue(
    val start: Long = 0,
    val end: Long? = null,
    val value: String = "",
    val byteStart: Int = 0,
    val byteEnd: Int = 0,
)

// The older lyrics call's answer: one block of plain text.
@Serializable
data class PlainLyrics(val artist: String? = null, val title: String? = null, val value: String? = null)

// A folder on the server, as its folder listings name it. Older servers
// send ids as numbers, so they are read as either and kept as text.
@Serializable
data class DirectoryRef(@Serializable(with = LooseString::class) val id: String, val name: String = "")

// The top of the server's folders: the folders at its root and any songs
// sitting loose there.
data class FolderIndex(val folders: List<DirectoryRef>, val songs: List<Song>)

// One folder on the server: its folders, then its songs, in the order the
// server sent them.
data class MusicDirectory(val id: String, val name: String, val folders: List<DirectoryRef>, val songs: List<Song>)

// The folder calls as they come over the wire. The root's folders arrive
// grouped by first letter; a folder's children mix folders and songs.
@Serializable
internal data class IndexesWire(
    val index: List<IndexGroupWire> = emptyList(),
    val child: List<kotlinx.serialization.json.JsonObject> = emptyList(),
)

@Serializable
internal data class IndexGroupWire(val name: String = "", val artist: List<DirectoryRef> = emptyList())

@Serializable
internal data class DirectoryWire(
    @Serializable(with = LooseString::class) val id: String,
    val name: String = "",
    val child: List<kotlinx.serialization.json.JsonObject> = emptyList(),
)
