package app.winters.octo.radio

import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.searchKey
import app.winters.octo.discovery.knownLengthMs
import app.winters.octo.server.serverTime
import app.winters.octo.subsonic.Song

// A song as the radio sees it, from either app: what it is, who made it,
// and how the listener has treated it. Each app turns its own songs into
// these. `album` is any id or name that is the same for every song of one
// album; `durationMs` is 0 when the length is not known.
data class RadioSong(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val durationMs: Long = 0,
    val composers: List<String> = emptyList(),
    val liked: Boolean = false,
    val rating: Int = 0,
    val plays: Long = 0,
    val lastPlayedAt: Long? = null,
) {
    // What the radio compares songs by, worked out when first needed.
    internal val artistKeys: Set<String> by lazy { artists.mapNotNullTo(LinkedHashSet(), ::artistKey) }
    internal val composerKeys: Set<String> by lazy { composers.mapNotNullTo(LinkedHashSet(), ::artistKey) }
    internal val genreKeys: List<String> by lazy { genres.map(::searchKey).filter(String::isNotEmpty).distinct() }
    internal val titleKey: String by lazy { searchKey(SongIdentity.parseTitle(title).core) }
}

// One spelling per artist: the main name of a credit, lowercase, accents gone.
internal fun artistKey(name: String): String? =
    searchKey(SongIdentity.primaryArtist(name)).takeIf(String::isNotEmpty)

// A server's song as the radio sees it. `rating` is the one on screen, which
// can be newer than the server's.
fun Song.radioSong(rating: Int = userRating ?: 0): RadioSong = RadioSong(
    id = id,
    title = title,
    artists = artists.map { it.name }.filter(String::isNotBlank)
        .ifEmpty { listOfNotNull(artist ?: displayArtist) }
        .ifEmpty { listOfNotNull(artistId) },
    album = albumId ?: album,
    genres = genres.ifEmpty { listOfNotNull(genre?.trim()?.takeIf(String::isNotEmpty)) },
    year = year,
    durationMs = knownLengthMs(this),
    composers = displayComposer?.split(',', ';', '/')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
    liked = starred != null,
    rating = rating,
    plays = playCount ?: 0,
    lastPlayedAt = serverTime(played),
)
