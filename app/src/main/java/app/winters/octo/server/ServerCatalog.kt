package app.winters.octo.server

import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.catalog.SourceAlbumEntity
import app.winters.octo.catalog.SourceArtistEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.joinLines
import app.winters.octo.catalog.relinkKey
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.device.explicitOf
import app.winters.octo.device.musicIds
import app.winters.octo.device.parseGenres
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
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
) {
    val isEmpty get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

// Of the rows made for music taken in ahead of a full copy, those that go
// in: songs the library lacks (`haveSongs`, by server id), albums it lacks
// (`haveAlbums`) or that were just read whole from the server (`readAlbums`,
// whose details and song counts replace the kept ones), and artists it
// lacks (`haveArtists`, by row id). An album made up from songs alone never
// replaces a kept one.
fun rowsToTakeIn(
    rows: ServerCatalog,
    haveSongs: Set<String>,
    haveAlbums: Set<String>,
    readAlbums: Set<String>,
    haveArtists: Set<String>,
): ServerCatalog = ServerCatalog(
    rows.tracks.filter { it.nativeId !in haveSongs },
    rows.albums.filter { it.nativeId !in haveAlbums || it.nativeId in readAlbums },
    rows.artists.filter { it.id !in haveArtists },
)

// Turns what a server lists into its source's rows, shaped like the
// phone's so the two merge: a song's artist id is its album artist's, its
// keys come from the same functions, and times added are in seconds. A
// whole copy builds the same rows album by album (StagedCopy).
fun buildServerCatalog(sourceId: String, library: Library): ServerCatalog {
    // A library that changes while it is read can repeat a song across pages.
    // A song the server marks as outside the library (Octo lists an album's
    // missing tracks with it) is not a library song, so it is not kept.
    val songs = library.songs.filterNot { it.isExternal }.distinctBy { it.id }

    // A song whose album was added after the album list was read still
    // gets an album, made from its songs.
    val listed = library.albums.associateBy { it.id }
    val unlisted = songs.filter { serverAlbumKey(it) !in listed }.groupBy(::serverAlbumKey)
        .map { (key, group) -> albumFromSongs(key, group) }

    val albumsByKey = (listed.values + unlisted).associateBy { it.id }
    // Each artist's MusicBrainz id, where the server knows it.
    val artistIds = library.artists.mapNotNull { artist -> musicId(artist.musicBrainzId)?.let { artist.id to it } }.toMap()

    // Each album's first song's own cover, drawn in place of the album's when
    // the server answers that with its stand-in picture. One per album, so
    // its songs still share one picture and one stored cover.
    val firstSongCovers = songs.groupBy(::serverAlbumKey).mapValues { (_, group) -> inAlbumOrder(group).first().coverArt }

    val albumRows = albumsByKey.values.associate { album -> album.id to serverAlbumRow(sourceId, album, firstSongCovers[album.id]) }

    val tracks = songs.groupBy(::serverAlbumKey).flatMap { (key, group) ->
        val album = albumRows.getValue(key)
        val listedAlbum = albumsByKey[key]
        inAlbumOrder(group).mapIndexed { index, song -> serverTrackRow(sourceId, song, album, listedAlbum, index, artistIds) }
    }

    // Artists are the album artists, as the phone's are, named as the
    // server's artist list names them.
    val listedArtists = library.artists.associateBy { "$sourceId:${it.id}" }
    val songsBy = tracks.groupingBy { it.artistId }.eachCount()
    val artists = albumRows.values.groupBy { it.artistId }.map { (artistId, owned) ->
        serverArtistRow(
            sourceId,
            artistId,
            listedArtists[artistId],
            firstAlbumArtist = owned.first().artist,
            albumCount = owned.size,
            songCount = songsBy[artistId] ?: 0,
            newestAlbumArtwork = owned.maxBy { it.addedAt }.artwork,
        )
    }

    return ServerCatalog(tracks, albumRows.values.toList(), artists)
}

// A cover on the server, with the picture to draw instead when the server
// answers with its stand-in one.
internal fun serverArt(sourceId: String, coverId: String?, fallbackId: String? = null): String? =
    coverId?.takeIf(String::isNotEmpty)?.let { ArtworkRef.Server(sourceId, it, fallbackId = fallbackId).encode() }

// One album's row. `firstSongCover` is the cover of its first song in album
// order, if it has songs.
internal fun serverAlbumRow(sourceId: String, album: Album, firstSongCover: String?): SourceAlbumEntity {
    val title = album.name.ifBlank { UNKNOWN_ALBUM }
    val artist = album.artist.ifBlank { UNKNOWN_ARTIST }
    return SourceAlbumEntity(
        id = "$sourceId:${album.id}",
        sourceId = sourceId,
        nativeId = album.id,
        title = title,
        searchKey = searchKey("$title $artist"),
        sortKey = sortKey(title),
        artist = artist,
        artistId = "$sourceId:${album.artistId?.takeIf(String::isNotEmpty) ?: "artist:${searchKey(artist)}"}",
        // The year it shows: the first edition's where known.
        year = albumYears(album).let { it.original ?: it.year },
        songCount = album.songCount,
        durationMs = album.duration * 1000L,
        addedAt = seconds(album.created) ?: 0,
        artwork = serverArt(sourceId, album.coverArt, firstSongCover),
        releaseTypes = joinLines(album.releaseTypes.map(String::trim).filter(String::isNotEmpty)),
    )
}

// One song's row: `album` is its album's row, `listedAlbum` the album as the
// server listed it (or as made up from its songs), `index` the song's place
// in album order, and `artistIds` each server artist's MusicBrainz id.
internal fun serverTrackRow(
    sourceId: String,
    song: Song,
    album: SourceAlbumEntity,
    listedAlbum: Album?,
    index: Int,
    artistIds: Map<String, String>,
): SourceTrackEntity {
    val albumYear = listedAlbum?.let(::albumYears)
    val title = song.title.ifBlank { "Untitled" }
    val durationMs = song.duration * 1000L
    val year = song.year.positive() ?: albumYear?.year
    val genres = song.genres.ifEmpty { parseGenres(listOfNotNull(song.genre)) }
    return SourceTrackEntity(
        id = "$sourceId:${song.id}",
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
        year = year,
        durationMs = durationMs,
        // Navidrome's "created" for a song is when its scan first saw
        // the file (the file's modified time only when the server is
        // set to sort recently added by it). Subsonic and OpenSubsonic
        // send nothing nearer to when the song was really got, so the
        // merge takes the earliest time any copy has, usually the
        // phone file's own.
        addedAt = seconds(song.created) ?: album.addedAt,
        mimeType = mimeFor(song.suffix) ?: song.contentType,
        sizeBytes = song.size,
        // The album's cover first, so an album's songs share one picture.
        artwork = album.artwork ?: serverArt(sourceId, song.coverArt),
        uri = null,
        albumOrder = index,
        relinkKey = relinkKey(album.artist, album.title, song.discNumber.positive(), song.track.positive(), title, durationMs),
        genre = genres.firstOrNull() ?: song.genre?.trim().orEmpty(),
        bitrate = song.bitRate.positive()?.times(1000),
        sampleRate = song.samplingRate.positive(),
        bitDepth = song.bitDepth.positive(),
        playCount = song.playCount?.toInt(),
        lastPlayedAt = epochMs(song.played),
        starredAt = epochMs(song.starred),
        trackGain = song.replayGain?.trackGain.finite(),
        albumGain = song.replayGain?.albumGain.finite(),
        trackPeak = song.replayGain?.trackPeak.finite()?.takeIf { it > 0f },
        albumPeak = song.replayGain?.albumPeak.finite()?.takeIf { it > 0f },
        baseGain = song.replayGain?.baseGain.finite(),
        fallbackGain = song.replayGain?.fallbackGain.finite(),
        rating = song.userRating?.coerceIn(0, 5),
        // The album's first-edition year, which cannot be after this one's.
        originalYear = albumYear?.original?.takeIf { year == null || it <= year },
        genres = joinLines(genres),
        artists = joinLines(song.artists.map { it.name.trim() }.filter(String::isNotEmpty)),
        composer = song.displayComposer.orBlankNull(),
        bpm = song.bpm.positive(),
        comment = song.comment.orBlankNull(),
        // The song's own mark only: an album is marked explicit when
        // any one of its songs is, which says nothing of the others.
        explicit = explicitOf(song.explicitStatus.orEmpty()),
        discTitle = listedAlbum?.discTitles?.firstOrNull { it.disc == (song.discNumber.positive() ?: 1) }?.title.orBlankNull(),
        mbRecordingId = musicId(song.musicBrainzId),
        mbAlbumId = musicId(listedAlbum?.musicBrainzId),
        mbArtistIds = joinLines(song.artists.mapNotNull { artistIds[it.id] }.distinct()),
        personal = song.octoPersonal,
    )
}

// One artist's row: named as the server's artist list names it, or as its
// first album names it, with the cover of its most recently added album.
internal fun serverArtistRow(
    sourceId: String,
    artistId: String,
    listedArtist: Artist?,
    firstAlbumArtist: String,
    albumCount: Int,
    songCount: Int,
    newestAlbumArtwork: String?,
): SourceArtistEntity {
    val name = listedArtist?.name?.takeIf(String::isNotBlank) ?: firstAlbumArtist
    return SourceArtistEntity(
        id = artistId,
        sourceId = sourceId,
        name = name,
        searchKey = searchKey(name),
        sortKey = sortKey(name),
        albumCount = albumCount,
        songCount = songCount,
        artwork = newestAlbumArtwork ?: serverArt(sourceId, listedArtist?.coverArt),
    )
}

// The album a song is filed under: its album id, or its album's name.
internal fun serverAlbumKey(song: Song): String = song.albumId?.takeIf(String::isNotEmpty) ?: "album:${searchKey(song.album.orEmpty())}"

internal fun albumFromSongs(key: String, songs: List<Song>): Album = albumFromSongTally(
    key,
    first = songs.first(),
    count = songs.size,
    duration = songs.sumOf { it.duration },
    year = songs.firstNotNullOfOrNull { it.year.positive() },
    created = songs.mapNotNull { it.created }.maxOrNull(),
)

// An album made up from its songs: the first of them in the order they came,
// how many there are, their length in seconds together, the first year one
// gives, and the latest time one was added.
internal fun albumFromSongTally(key: String, first: Song, count: Int, duration: Int, year: Int?, created: String?): Album = Album(
    id = key,
    name = first.album.orEmpty(),
    artist = listOfNotNull(
        first.displayAlbumArtist,
        first.albumArtists.joinToString(", ") { it.name.trim() },
        first.artist,
    ).firstOrNull(String::isNotBlank).orEmpty(),
    coverArt = first.coverArt,
    songCount = count,
    duration = duration,
    year = year,
    created = created,
)

// Disc, then track number, then title.
internal fun inAlbumOrder(songs: List<Song>): List<Song> =
    songs.sortedWith(
        compareBy<Song> { songDiscKey(it) }
            .thenBy { songTrackKey(it) }
            .thenBy { sortKey(it.title) },
    )

// A song's disc and track for album order: disc 1 and last place when unknown.
internal fun songDiscKey(song: Song): Int = song.discNumber.positive() ?: 1

internal fun songTrackKey(song: Song): Int = song.track.positive() ?: Int.MAX_VALUE

internal fun serverMusicId(text: String?): String? = musicId(text)

// This edition's year and the first edition's, as an album gives them. The
// older year field is this edition's; the release dates are OpenSubsonic's.
private class AlbumYears(val year: Int?, val original: Int?)

private fun albumYears(album: Album): AlbumYears {
    val year = album.year.positive() ?: album.releaseDate?.year.positive()
    val original = album.originalReleaseDate?.year.positive()
    return AlbumYears(year, original?.takeIf { year == null || it <= year })
}

private fun String?.orBlankNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

// A MusicBrainz id when the text is one, in lower case.
private fun musicId(text: String?): String? = musicIds(listOfNotNull(text)).firstOrNull()

// Servers send 0 for a number they do not know.
private fun Int?.positive(): Int? = this?.takeIf { it > 0 }

private fun Float?.finite(): Float? = this?.takeIf { it.isFinite() }

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
