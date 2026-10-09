package app.winters.octo.desktop

import app.winters.octo.desktop.library.LibraryIndex
import app.winters.octo.desktop.library.libraryOnlySongs
import app.winters.octo.desktop.library.sortAlbums
import app.winters.octo.desktop.queue.radioName
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.playback.QueueSource
import app.winters.octo.radio.RadioInput
import app.winters.octo.radio.radioMix
import app.winters.octo.radio.radioSong
import app.winters.octo.sort.SortList
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

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

// Octo's radio for the desktop: songs like `seeds`, after `first`, from the
// library and the server's `similar` (see radioMix). When the radio finds
// nothing, the server's songs in its own order.
fun radioSongs(
    first: Song,
    seeds: List<Song>,
    similar: List<Song>,
    index: LibraryIndex?,
    exclude: Set<String>,
    rating: (Song) -> Int,
    now: Long = System.currentTimeMillis(),
    random: Random = Random.Default,
): List<Song> {
    val known = HashMap<String, Song>()
    index?.songs?.forEach { known[it.id] = it }
    similar.forEach { known.putIfAbsent(it.id, it) }
    // The library's copy of a song the server suggested has no word of who suggested it.
    val suggestedBy = similar.mapNotNull { s -> s.octoSuggestedBy?.let { s.id to it } }.toMap()
    val mixed = radioMix(
        RadioInput(
            seeds = seeds.map { it.radioSong(rating(it)) },
            library = index?.songs.orEmpty().map { it.radioSong(rating(it)) },
            suggested = similar.map { it.radioSong(rating(it)) },
            before = listOf(first.radioSong(rating(first))),
            exclude = exclude + first.id,
            now = now,
        ),
        RADIO_SONGS,
        random,
    ).mapNotNull { known[it.id] }
        .map { song -> suggestedBy[song.id]?.let { song.copy(octoSuggestedBy = it) } ?: song }
    return mixed.ifEmpty { radioPicks(first, similar).filter { it.id !in exclude } }
}

// Plays the song now, then Octo's radio after it.
fun AppState.startRadio(seed: Song) = startRadioFrom(seed, listOf(seed))

// Plays `first` now, then songs like `seeds` after it once the server has
// answered; without an answer the library alone makes the radio. When
// nothing comes of either, the song keeps playing alone and one quiet line
// says so. Nothing is added if the listener has put on something else
// meanwhile. `exclude` keeps songs out, like the rest of an album.
fun AppState.startRadioFrom(first: Song, seeds: List<Song>, exclude: Set<String> = emptySet()) {
    val client = connection?.client ?: return
    val radio = QueueSource.Played(radioName(first.title))
    player.play(listOf(first), source = radio)
    val key = player.state.value.current?.key
    val index = library?.index
    scope.launch {
        val similar = try {
            libraryOnlySongs(client.similarSongs(first.id, RADIO_SONGS), index, settings.state.value.libraryOnly)
        } catch (e: SubsonicException) {
            emptyList()
        }
        val picks = withContext(Dispatchers.Default) { radioSongs(first, seeds, similar, index, exclude, { ratingOf(it) }) }
        if (player.state.value.queue.none { it.key == key }) return@launch
        if (picks.isEmpty()) notice = "Couldn't find songs like ${first.title}" else player.addToQueue(picks, radio)
    }
}

// A radio for an artist: one of their songs, then songs like theirs. Their
// songs come from the library, else from the server's top songs.
fun AppState.startArtistRadio(artistId: String, name: String) {
    val connection = connection ?: return
    val client = connection.client
    val index = library?.index
    scope.launch {
        val libraryOnly = settings.state.value.libraryOnly
        val similar = try {
            libraryOnlySongs(client.similarSongs(artistId, RADIO_SONGS), index, libraryOnly)
        } catch (e: SubsonicException) {
            emptyList()
        }
        val own = index?.songs?.filter { song -> song.artistId == artistId || song.artists.any { it.id == artistId } }.orEmpty()
            .ifEmpty {
                try {
                    val byId = connection.supports("topSongsByArtistId")
                    libraryOnlySongs(client.topSongs(name, RADIO_SONGS, if (byId) artistId else null), index, libraryOnly)
                } catch (e: SubsonicException) {
                    emptyList()
                }
            }
        val first = radioSeed(own) ?: radioPicks(null, similar).firstOrNull()
        if (first == null) {
            notice = "Couldn't find songs like $name"
            return@launch
        }
        val seeds = (listOf(first) + own.take(200)).distinctBy { it.id }
        // Their own songs ride along as suggestions after the server's, so
        // a radio for an artist outside the library still plays more of them.
        val picks = withContext(Dispatchers.Default) { radioSongs(first, seeds, similar + own, index, emptySet(), { ratingOf(it) }) }
        player.play(listOf(first) + picks, source = QueueSource.Played(radioName(name)))
    }
}

// A radio for an album: its most played song, then songs like the album,
// leaving the rest of the album out.
fun AppState.startAlbumRadio(albumId: String) {
    scope.launch {
        val songs = try {
            albumSongs(albumId)
        } catch (e: SubsonicException) {
            emptyList()
        }
        val first = radioSeed(songs) ?: return@launch
        startRadioFrom(first, songs, exclude = songs.map { it.id }.toSet())
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

fun AppState.removeFromQueue(keys: List<Long>) = removeQueued(keys)
