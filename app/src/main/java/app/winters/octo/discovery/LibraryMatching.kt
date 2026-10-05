package app.winters.octo.discovery

import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.findId
import app.winters.octo.catalog.onlineArtwork
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.device.explicitOf
import app.winters.octo.subsonic.Song

// The matching rules themselves live in shared core (Matching.kt); this is
// the part that works on the catalog's database rows.

// What one song from the server is to the app: a song already in the
// library, or one found online.
sealed interface Resolved {
    data class InLibrary(val trackId: String) : Resolved

    data class Found(val song: OnlineSongEntity) : Resolved
}

// The id the app knows a resolved song by: the library song's, or the find's.
val Resolved.id: String
    get() = when (this) {
        is Resolved.InLibrary -> trackId
        is Resolved.Found -> song.id
    }

// A find already downloaded into the library is the library song it became,
// while the library still has it, so a list built from then on shows it
// once, as the library song.
fun asAdopted(resolved: Resolved, library: Set<String>): Resolved =
    if (resolved is Resolved.Found && resolved.song.adoptedId in library) Resolved.InLibrary(resolved.song.adoptedId) else resolved

// The same song by the same artist, and the same kind of recording. Lengths
// only count when both are known.
fun sameSong(title: String, artist: String, lengthMs: Long, track: TrackEntity): Boolean =
    sameSong(title, artist, lengthMs, track.title, track.artist, track.durationMs)

fun Song.toFind(sourceId: String, now: Long) = OnlineSongEntity(
    id = findId(id),
    sourceId = sourceId,
    nativeId = id,
    title = title,
    artist = artist.orEmpty(),
    album = album.orEmpty(),
    albumId = albumId?.takeIf(String::isNotEmpty),
    artistId = artistId?.takeIf(String::isNotEmpty),
    durationMs = knownLengthMs(this),
    coverId = coverArt?.takeIf(String::isNotEmpty),
    mimeType = mimeTypeOf(this),
    bitrate = bitRate?.takeIf { it > 0 },
    seenAt = now,
    explicit = explicitOf(explicitStatus.orEmpty()),
)

// A found song shaped like a library song, so every list, menu and the
// player can show it. It has no album or artist in the library to open.
fun OnlineSongEntity.asTrack() = TrackEntity(
    id = id,
    sourceId = sourceId,
    nativeId = nativeId,
    title = title,
    searchKey = searchKey(title),
    sortKey = sortKey(title),
    artist = artist,
    artistId = "",
    album = album,
    albumId = "",
    trackNo = null,
    discNo = null,
    year = null,
    durationMs = durationMs,
    addedAt = 0,
    mimeType = mimeType,
    sizeBytes = null,
    artwork = onlineArtwork(sourceId, coverId),
    uri = null,
    onPhone = false,
    explicit = explicit,
)

// Sorts out what the server sent. A song the library has from the server is
// that library song, unless the server marks it as outside the library (a
// link left by an older copy is not trusted then). A song with the same
// title and artist as a library song is that song too, so a copy on the
// phone plays instead of a stream. Anything else was found online.
fun resolveSongs(
    songs: List<Song>,
    sourceId: String,
    links: Map<String, String>,
    library: List<TrackEntity>,
    now: Long,
): List<Resolved> {
    val byTitle = TitleIndex(library, { it.title }, { it.artist })
    return songs.map { song ->
        if (!song.isExternal) links[song.id]?.let { return@map Resolved.InLibrary(it) }
        val length = knownLengthMs(song)
        val same = byTitle.candidates(song.title, song.artist.orEmpty()).firstOrNull { sameSong(song.title, song.artist.orEmpty(), length, it) }
        if (same != null) Resolved.InLibrary(same.id) else Resolved.Found(song.toFind(sourceId, now))
    }
}
