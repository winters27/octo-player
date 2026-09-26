package app.winters.octo.server

import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.catalog.SourceAlbumEntity
import app.winters.octo.catalog.SourceArtistEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.relinkKey
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Song
import okhttp3.HttpUrl
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

private const val SERVER = "server"
private const val UNKNOWN_ARTIST = "Unknown artist"
private const val UNKNOWN_ALBUM = "Unknown album"

// The source a server's music is kept under: its host, and its port when
// that is not the usual one for the address. Always made from the server's
// main address, so moving between home and away keeps the same library.
fun serverSourceId(base: HttpUrl): String {
    val port = if (base.port == HttpUrl.defaultPort(base.scheme)) "" else ":${base.port}"
    return "$SERVER:${base.host}$port"
}

fun isServerSource(sourceId: String) = sourceId.startsWith("$SERVER:")

class ServerCatalog(
    val tracks: List<SourceTrackEntity>,
    val albums: List<SourceAlbumEntity>,
    val artists: List<SourceArtistEntity>,
)

// Turns what a server lists into its source's rows, shaped like the
// phone's so the two merge: a song's artist id is its album artist's, its
// keys come from the same functions, and times added are in seconds.
fun buildServerCatalog(sourceId: String, library: Library): ServerCatalog {
    fun id(native: String) = "$sourceId:$native"
    fun art(coverId: String?) = coverId?.takeIf(String::isNotEmpty)?.let { ArtworkRef.Server(sourceId, it).encode() }

    // A library that changes while it is read can repeat a song across pages.
    val songs = library.songs.distinctBy { it.id }

    // A song whose album was added after the album list was read still
    // gets an album, made from its songs.
    val listed = library.albums.associateBy { it.id }
    val unlisted = songs.filter { albumKey(it) !in listed }.groupBy(::albumKey)
        .map { (key, group) -> albumFromSongs(key, group) }

    val albumRows = (listed.values + unlisted).associate { album ->
        val title = album.name.ifBlank { UNKNOWN_ALBUM }
        val artist = album.artist.ifBlank { UNKNOWN_ARTIST }
        album.id to SourceAlbumEntity(
            id = id(album.id),
            sourceId = sourceId,
            nativeId = album.id,
            title = title,
            searchKey = searchKey("$title $artist"),
            sortKey = sortKey(title),
            artist = artist,
            artistId = id(album.artistId?.takeIf(String::isNotEmpty) ?: "artist:${searchKey(artist)}"),
            year = album.year.positive(),
            songCount = album.songCount,
            durationMs = album.duration * 1000L,
            addedAt = seconds(album.created) ?: 0,
            artwork = art(album.coverArt),
        )
    }

    val tracks = songs.groupBy(::albumKey).flatMap { (key, group) ->
        val album = albumRows.getValue(key)
        inAlbumOrder(group).mapIndexed { index, song ->
            val title = song.title.ifBlank { "Untitled" }
            val durationMs = song.duration * 1000L
            SourceTrackEntity(
                id = id(song.id),
                sourceId = sourceId,
                nativeId = song.id,
                title = title,
                searchKey = searchKey(title),
                sortKey = sortKey(title),
                artist = (song.displayArtist ?: song.artist)?.takeIf(String::isNotBlank) ?: album.artist,
                artistId = album.artistId,
                album = album.title,
                albumId = album.id,
                trackNo = song.track.positive(),
                discNo = song.discNumber.positive(),
                year = song.year.positive() ?: album.year,
                durationMs = durationMs,
                addedAt = seconds(song.created) ?: album.addedAt,
                mimeType = mimeFor(song.suffix) ?: song.contentType,
                sizeBytes = song.size,
                // The album's cover first, so an album's songs share one picture.
                artwork = album.artwork ?: art(song.coverArt),
                uri = null,
                albumOrder = index,
                relinkKey = relinkKey(album.artist, album.title, song.discNumber.positive(), song.track.positive(), title, durationMs),
                genre = song.genre.orEmpty(),
                bitrate = song.bitRate.positive()?.times(1000),
                sampleRate = song.samplingRate.positive(),
                bitDepth = song.bitDepth.positive(),
                playCount = song.playCount?.toInt(),
                lastPlayedAt = epochMs(song.played),
                starredAt = epochMs(song.starred),
            )
        }
    }

    // Artists are the album artists, as the phone's are, named as the
    // server's artist list names them.
    val listedArtists = library.artists.associateBy { id(it.id) }
    val songsBy = tracks.groupingBy { it.artistId }.eachCount()
    val artists = albumRows.values.groupBy { it.artistId }.map { (artistId, owned) ->
        val listedArtist = listedArtists[artistId]
        val name = listedArtist?.name?.takeIf(String::isNotBlank) ?: owned.first().artist
        SourceArtistEntity(
            id = artistId,
            sourceId = sourceId,
            name = name,
            searchKey = searchKey(name),
            sortKey = sortKey(name),
            albumCount = owned.size,
            songCount = songsBy[artistId] ?: 0,
            artwork = owned.maxBy { it.addedAt }.artwork ?: art(listedArtist?.coverArt),
        )
    }

    return ServerCatalog(tracks, albumRows.values.toList(), artists)
}

private fun albumKey(song: Song): String = song.albumId?.takeIf(String::isNotEmpty) ?: "album:${searchKey(song.album.orEmpty())}"

private fun albumFromSongs(key: String, songs: List<Song>): Album {
    val first = songs.first()
    return Album(
        id = key,
        name = first.album.orEmpty(),
        artist = (first.displayAlbumArtist ?: first.artist).orEmpty(),
        coverArt = first.coverArt,
        songCount = songs.size,
        duration = songs.sumOf { it.duration },
        year = songs.firstNotNullOfOrNull { it.year.positive() },
        created = songs.mapNotNull { it.created }.maxOrNull(),
    )
}

// Disc, then track number, then title.
private fun inAlbumOrder(songs: List<Song>): List<Song> =
    songs.sortedWith(
        compareBy<Song> { it.discNumber.positive() ?: 1 }
            .thenBy { it.track.positive() ?: Int.MAX_VALUE }
            .thenBy { sortKey(it.title) },
    )

// Servers send 0 for a number they do not know.
private fun Int?.positive(): Int? = this?.takeIf { it > 0 }

// The kind of file, from its extension.
internal fun mimeFor(suffix: String?): String? = when (suffix?.lowercase()) {
    "flac" -> "audio/flac"
    "mp3" -> "audio/mpeg"
    "m4a", "m4b", "mp4", "alac" -> "audio/mp4"
    "aac" -> "audio/aac"
    "ogg", "oga", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    "aif", "aiff" -> "audio/aiff"
    "wv" -> "audio/x-wavpack"
    "ape" -> "audio/x-ape"
    "dsf" -> "audio/x-dsf"
    else -> null
}

// A server's date and time, as milliseconds since 1970. One with no zone is
// taken as UTC.
internal fun epochMs(text: String?): Long? = text?.let {
    runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(it).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

private fun seconds(text: String?): Long? = epochMs(text)?.div(1000)
