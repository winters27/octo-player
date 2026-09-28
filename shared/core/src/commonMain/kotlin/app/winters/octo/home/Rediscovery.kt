package app.winters.octo.home

import app.winters.octo.server.serverTime
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Song

// Home's rediscovery shelves: albums worth going back to, found by plain
// questions about the library's own play counts and dates. Nothing is
// guessed or recommended; the same library always gives the same shelves.

// How one album has been listened to, from its songs.
data class AlbumListening(
    val id: String,
    // Songs the album holds.
    val songs: Int,
    // Of them, the songs played at least once.
    val playedSongs: Int,
    // Plays of all its songs together.
    val plays: Long,
    // The latest play of any of its songs, or null when none has a date.
    val lastPlayed: Long?,
    // When the album came into the library, or null when unknown.
    val added: Long?,
) {
    val played: Boolean get() = playedSongs > 0 || plays > 0 || lastPlayed != null
}

// How long an album must have gone unplayed to count as not played lately.
const val QUIET_DAYS = 180

private const val DAY_MS = 24L * 60 * 60 * 1000

// Albums played before but not in the last six months, the most played
// first: old favourites that dropped out of rotation.
fun <T> notPlayedLately(albums: List<T>, of: (T) -> AlbumListening, now: Long, limit: Int = Int.MAX_VALUE): List<T> {
    val before = now - QUIET_DAYS * DAY_MS
    return albums.map { it to of(it) }
        .filter { (_, a) -> a.lastPlayed != null && a.lastPlayed < before }
        .sortedWith(compareByDescending<Pair<T, AlbumListening>> { it.second.plays }.thenBy { it.second.lastPlayed }.thenBy { it.second.id })
        .take(limit)
        .map { it.first }
}

// Albums started but not played through: some of their songs have plays and
// some have none. The most recently played first.
fun <T> neverFinished(albums: List<T>, of: (T) -> AlbumListening, limit: Int = Int.MAX_VALUE): List<T> =
    albums.map { it to of(it) }
        .filter { (_, a) -> a.playedSongs > 0 && a.playedSongs < a.songs }
        .sortedWith(compareByDescending<Pair<T, AlbumListening>> { it.second.lastPlayed ?: Long.MIN_VALUE }.thenBy { it.second.id })
        .take(limit)
        .map { it.first }

// Albums none of whose songs has ever been played, the newest added first.
fun <T> neverPlayed(albums: List<T>, of: (T) -> AlbumListening, limit: Int = Int.MAX_VALUE): List<T> =
    albums.map { it to of(it) }
        .filter { (_, a) -> !a.played }
        .sortedWith(compareByDescending<Pair<T, AlbumListening>> { it.second.added ?: Long.MIN_VALUE }.thenBy { it.second.id })
        .take(limit)
        .map { it.first }

// Albums by how often their songs were played, the most first; of two played
// as often, the one played more recently. Albums never played are left out.
fun <T> mostPlayedAlbums(albums: List<T>, of: (T) -> AlbumListening, limit: Int = Int.MAX_VALUE): List<T> =
    albums.map { it to of(it) }
        .filter { (_, a) -> a.plays > 0 }
        .sortedWith(
            compareByDescending<Pair<T, AlbumListening>> { it.second.plays }
                .thenByDescending { it.second.lastPlayed ?: Long.MIN_VALUE }
                .thenBy { it.second.id },
        )
        .take(limit)
        .map { it.first }

// Home's three rediscovery shelves, each at most `limit` long. An album shows
// on one shelf only: one that fits two keeps the first, in the order Home
// shows them.
data class Rediscovery<T>(
    val notPlayedLately: List<T> = emptyList(),
    val neverFinished: List<T> = emptyList(),
    val neverPlayed: List<T> = emptyList(),
) {
    val isEmpty: Boolean get() = notPlayedLately.isEmpty() && neverFinished.isEmpty() && neverPlayed.isEmpty()
}

fun <T> rediscovery(albums: List<T>, of: (T) -> AlbumListening, now: Long, limit: Int): Rediscovery<T> {
    val quiet = notPlayedLately(albums, of, now, limit)
    val shown = quiet.mapTo(HashSet()) { of(it).id }
    return Rediscovery(
        notPlayedLately = quiet,
        neverFinished = neverFinished(albums.filter { of(it).id !in shown }, of, limit),
        neverPlayed = neverPlayed(albums, of, limit),
    )
}

// Each album's listening, by album id, from the songs a Subsonic library
// lists. An album with no songs listed falls back to its own counts. The
// album's own play date and count join in, since a server can know of plays
// its songs no longer carry.
fun albumListening(albums: List<Album>, songs: List<Song>): Map<String, AlbumListening> {
    val byAlbum = songs.filter { it.albumId != null }.groupBy { it.albumId!! }
    return albums.associate { album ->
        val own = byAlbum[album.id].orEmpty()
        val songTimes = own.mapNotNull { serverTime(it.played) }
        val songPlays = own.sumOf { it.playCount ?: 0L }
        val playedSongs = own.count { (it.playCount ?: 0L) > 0 || serverTime(it.played) != null }
        val lastPlayed = listOfNotNull(songTimes.maxOrNull(), serverTime(album.played)).maxOrNull()
        album.id to AlbumListening(
            id = album.id,
            songs = if (own.isNotEmpty()) own.size else album.songCount,
            playedSongs = playedSongs,
            plays = maxOf(songPlays, album.playCount),
            lastPlayed = lastPlayed,
            added = serverTime(album.created),
        )
    }
}
