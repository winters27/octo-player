package app.winters.octo.discovery

import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.findId
import app.winters.octo.catalog.matchKey
import app.winters.octo.catalog.onlineArtwork
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.subsonic.Song

// Octo lists a song it found online as exactly three minutes long when it
// does not know the real length yet.
private const val GUESSED_SECONDS = 180

// Lengths further apart than this are different recordings.
private const val SAME_LENGTH_MS = 10_000L

private val Extras = Regex("""\s*[(\[][^)\]]*[)\]]""")

// Words that make a title a different recording of a song, not the same
// one with extras: "Nightcall (Breakbot Remix)" is not "Nightcall".
private val VersionWords = setOf(
    "remix", "mix", "rmx", "live", "edit", "acoustic", "instrumental", "demo", "version", "rework",
    "bootleg", "vip", "cover", "karaoke", "extended", "dub", "slowed", "sped", "reverb", "unplugged",
)
private val Words = Regex("""[\p{L}\p{N}]+""")

private val Featuring = Regex("""\s*(,|&|\bfeat\.?|\bft\.?|\bx\b|\bwith\b)\s+.*$""", RegexOption.IGNORE_CASE)

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

// The length the server gave, or zero when it only guessed.
fun knownLengthMs(song: Song): Long =
    if (song.duration <= 0 || song.duration == GUESSED_SECONDS) 0 else song.duration * 1000L

// The keys a title is looked up by: as written, and without bracketed extras
// like "(feat. X)" or "[Remastered]".
fun titleKeys(title: String): List<String> =
    listOf(searchKey(title), searchKey(title.replace(Extras, "").trim())).filter { it.isNotEmpty() }.distinct()

// The first-named artist, so "Drake feat. Rihanna" and "Drake" agree.
internal fun leadArtist(name: String): String = matchKey(name.replace(Featuring, ""))

fun sameArtist(a: String, b: String): Boolean {
    val lead = leadArtist(a)
    return lead.isNotEmpty() && (lead == leadArtist(b) || matchKey(a) == matchKey(b))
}

// Which kind of recording a title names, from its version words.
internal fun versionOf(title: String): Set<String> =
    Words.findAll(title.lowercase()).map { it.value }.filterTo(HashSet()) { it in VersionWords }

// The same song by the same artist, and the same kind of recording. Lengths
// only count when both are known.
fun sameSong(title: String, artist: String, lengthMs: Long, track: TrackEntity): Boolean {
    if (matchKey(title) != matchKey(track.title)) return false
    if (versionOf(title) != versionOf(track.title)) return false
    if (!sameArtist(artist, track.artist)) return false
    return lengthMs <= 0 || track.durationMs <= 0 || kotlin.math.abs(lengthMs - track.durationMs) <= SAME_LENGTH_MS
}

// What the player receives for a server song, from its type or its file ending.
fun mimeTypeOf(song: Song): String? =
    song.contentType?.takeIf { it.isNotBlank() } ?: when (song.suffix?.lowercase()) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> null
    }

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
)

// Sorts out what the server sent. A song the library has from the server is
// that library song. A song with the same title and artist as a library song
// is that song too, so a copy on the phone plays instead of a stream.
// Anything else was found online.
fun resolveSongs(
    songs: List<Song>,
    sourceId: String,
    links: Map<String, String>,
    library: List<TrackEntity>,
    now: Long,
): List<Resolved> {
    val byTitle = library.groupBy { matchKey(it.title) }
    return songs.map { song ->
        links[song.id]?.let { return@map Resolved.InLibrary(it) }
        val length = knownLengthMs(song)
        val same = byTitle[matchKey(song.title)]?.firstOrNull { sameSong(song.title, song.artist.orEmpty(), length, it) }
        if (same != null) Resolved.InLibrary(same.id) else Resolved.Found(song.toFind(sourceId, now))
    }
}
