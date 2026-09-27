package app.winters.octo.discovery

import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.SongMatchOptions
import app.winters.octo.catalog.SongRef
import app.winters.octo.catalog.SongVerdict
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.findId
import app.winters.octo.catalog.onlineArtwork
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.subsonic.Song

// Octo lists a song it found online as exactly three minutes long when it
// does not know the real length yet.
private const val GUESSED_SECONDS = 180

// Lengths further apart than this are different recordings. Looser than
// the lyrics match, since a library copy and a server's copy of one
// recording can be cut a little differently.
private const val SAME_LENGTH_S = 10

private val WithinTenSeconds = SongMatchOptions(lengthToleranceSeconds = SAME_LENGTH_S)

private val Extras = Regex("""\s*[(\[][^)\]]*[)\]]""")

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

// The keys a title is looked up by in the library's search keys: as
// written, without bracketed extras like "(feat. X)" or "[Remastered]", and
// as the title a person would say ("Song" for "01 - Song - Remastered").
// Only a first pass: whatever they find is still compared song by song.
fun titleKeys(title: String): List<String> =
    listOf(searchKey(title), searchKey(title.replace(Extras, "").trim()), searchKey(SongIdentity.parseTitle(title).core))
        .filter { it.isNotEmpty() }.distinct()

// The two credits share an artist and do not disagree about the guests:
// "Drake feat. Rihanna" is "Drake", and "Ye (侃爷)" is "Kanye West", but "A
// feat. B" is not "A feat. C".
fun sameArtist(a: String, b: String): Boolean = SongIdentity.artistsAgree(a, b)

// Which kind of recording a title names: its version markers that make
// another recording ("live", "remix"), not the ones that never do
// ("remaster", "explicit").
internal fun versionOf(title: String): Set<String> = SongIdentity.distinctVersions(SongIdentity.parseTitle(title))

// The same song by the same artist, and the same kind of recording. Lengths
// only count when both are known.
fun sameSong(title: String, artist: String, lengthMs: Long, track: TrackEntity): Boolean =
    SongIdentity.same(
        SongRef(title, artist, secondsOf(lengthMs)),
        SongRef(track.title, track.artist, secondsOf(track.durationMs)),
        WithinTenSeconds,
    ).isSame

// The same recording by the same artist, lengths not compared.
fun sameRecording(titleA: String, artistA: String, titleB: String, artistB: String): Boolean =
    SongIdentity.same(titleA, artistA, titleB, artistB, SongMatchOptions.AnyLength).isSame

// The same song by the same artist in any version: a live take or a remix
// counts too.
fun sameSongAnyVersion(titleA: String, artistA: String, titleB: String, artistB: String): Boolean =
    SongIdentity.same(titleA, artistA, titleB, artistB, SongMatchOptions.AnyLength).verdict != SongVerdict.Different

private fun secondsOf(ms: Long): Double? = if (ms > 0) ms / 1000.0 else null

// Songs by every key their titles can agree on, so a song is only compared
// with the few whose titles might be its own. What it finds keeps the
// list's order.
class TitleIndex<T>(private val items: List<T>, title: (T) -> String, artist: (T) -> String) {
    private val byKey = HashMap<String, MutableList<Int>>()

    init {
        items.forEachIndexed { index, item ->
            SongIdentity.titleLookupKeys(title(item), artist(item)).forEach { byKey.getOrPut(it) { mutableListOf() } += index }
        }
    }

    fun candidates(title: String, artist: String): List<T> =
        SongIdentity.titleLookupKeys(title, artist).flatMap { byKey[it].orEmpty() }.distinct().sorted().map(items::get)
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
    val byTitle = TitleIndex(library, { it.title }, { it.artist })
    return songs.map { song ->
        links[song.id]?.let { return@map Resolved.InLibrary(it) }
        val length = knownLengthMs(song)
        val same = byTitle.candidates(song.title, song.artist.orEmpty()).firstOrNull { sameSong(song.title, song.artist.orEmpty(), length, it) }
        if (same != null) Resolved.InLibrary(same.id) else Resolved.Found(song.toFind(sourceId, now))
    }
}
