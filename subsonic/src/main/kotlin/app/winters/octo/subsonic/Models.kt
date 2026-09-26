package app.winters.octo.subsonic

import kotlinx.serialization.Serializable

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
