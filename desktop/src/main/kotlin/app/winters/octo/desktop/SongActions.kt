package app.winters.octo.desktop

import app.winters.octo.desktop.library.sortAlbums
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.sort.SortList
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// What the menus do beyond playing and queueing: ratings, radios, and
// taking songs out of a playlist or the queue. The same rules as the phone.

// How many songs a radio asks the server for, as the phone does.
const val RADIO_SONGS = 50

// Ratings, as the phone keeps them: 1 to 5 stars, 0 for none.

fun AppState.ratingOf(song: Song): Int = ratingOverrides[song.id] ?: cleanRating(song.userRating)

// Rates every song at once on screen, then on the server one by one. A
// song the server would not rate goes back to what it showed before.
fun AppState.setRating(songs: List<Song>, rating: Int) {
    val client = connection?.client ?: return
    val stars = cleanRating(rating)
    val ids = songs.map { it.id }.distinct()
    val before = ids.associateWith { ratingOverrides[it] }
    ids.forEach { ratingOverrides[it] = stars }
    scope.launch {
        var failure: SubsonicException? = null
        ids.forEach { id ->
            try {
                client.setRating(id, stars)
            } catch (e: SubsonicException) {
                failure = e
                // Unless a later rating has already replaced this one.
                if (ratingOverrides[id] == stars) {
                    val old = before[id]
                    if (old == null) ratingOverrides.remove(id) else ratingOverrides[id] = old
                }
            }
        }
        failure?.let { notice = "Couldn't change the rating: ${it.userMessage()}" }
    }
}

// A rating in range: 1 to 5 stars, anything else none.
fun cleanRating(rating: Int?): Int = rating?.takeIf { it in 1..5 } ?: 0

// Radios.

// Songs like the seed, as the radio adds them: never the seed again, and
// each song once.
fun radioPicks(seed: Song?, similar: List<Song>): List<Song> =
    similar.filter { it.id != seed?.id }.distinctBy { it.id }

// The song a radio for a list of songs starts from: the most played, or the
// first when none has been played, as the phone picks for an artist.
fun radioSeed(songs: List<Song>): Song? =
    songs.filter { (it.playCount ?: 0) > 0 }.maxByOrNull { it.playCount ?: 0 } ?: songs.firstOrNull()

// Plays the song now, then songs like it after it once the server answers.
// When the server has none, or cannot answer, the song keeps playing alone
// and one quiet line says so. Nothing is added if the listener has put on
// something else meanwhile.
fun AppState.startRadio(seed: Song) {
    val client = connection?.client ?: return
    player.play(listOf(seed))
    val key = player.state.value.current?.key
    scope.launch {
        val similar = try {
            client.similarSongs(seed.id, RADIO_SONGS)
        } catch (e: SubsonicException) {
            emptyList()
        }
        if (player.state.value.queue.none { it.key == key }) return@launch
        val picks = radioPicks(seed, similar)
        if (picks.isEmpty()) notice = "Couldn't find songs like ${seed.title}" else player.addToQueue(picks)
    }
}

// A radio for an artist: songs like theirs, which Navidrome and Octo find
// from the artist's id, or their most played songs when it finds none.
fun AppState.startArtistRadio(artistId: String, name: String) {
    val connection = connection ?: return
    val client = connection.client
    scope.launch {
        val similar = try {
            client.similarSongs(artistId, RADIO_SONGS)
        } catch (e: SubsonicException) {
            emptyList()
        }
        val songs = radioPicks(null, similar).ifEmpty {
            try {
                val byId = connection.supports("topSongsByArtistId")
                radioPicks(null, client.topSongs(name, RADIO_SONGS, if (byId) artistId else null))
            } catch (e: SubsonicException) {
                emptyList()
            }
        }
        if (songs.isEmpty()) notice = "Couldn't find songs like $name" else player.play(songs)
    }
}

// A radio for an album, from its most played song.
fun AppState.startAlbumRadio(albumId: String) {
    scope.launch {
        val songs = try {
            albumSongs(albumId)
        } catch (e: SubsonicException) {
            emptyList()
        }
        radioSeed(songs)?.let(::startRadio)
    }
}

// Songs of whole collections, for the menus on cards and rows.

// An artist's songs album by album, newest album first, as their page
// lists them.
suspend fun AppState.artistSongs(id: String): List<Song> {
    val client = connection?.client ?: return emptyList()
    val albums = sortAlbums(client.artist(id).album, SortList.ArtistAlbums.default)
    return coroutineScope { albums.map { async { client.album(it.id).song } }.awaitAll().flatten() }
}

suspend fun AppState.playlistSongs(id: String): List<Song> = connection?.client?.playlist(id)?.entry.orEmpty()

// Taking songs away.

// Takes rows out of a playlist by their places in it, all in one call so
// no place moves before the others go.
fun AppState.removeFromPlaylist(id: String, positions: List<Int>) {
    val client = connection?.client ?: return
    if (positions.isEmpty()) return
    val formPost = formPost
    scope.launch {
        try {
            client.updatePlaylist(id, songIndexesToRemove = positions.distinct().sorted(), formPost = formPost)
            refreshPlaylists()
        } catch (e: SubsonicException) {
            notice = "Couldn't remove from the playlist: ${e.userMessage()}"
        }
    }
}

fun AppState.removeFromQueue(keys: List<Long>) = keys.forEach(player::remove)
