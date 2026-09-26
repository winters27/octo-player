package app.winters.octo.sort

import app.winters.octo.catalog.PlayedAlbum
import app.winters.octo.catalog.PlayedTrack

// Orders by plays. The counts are plays on the phone and on the server
// together, worked out by PlayHistory the way the Home shelves use them, and
// that sum has to leave out the plays Octo itself sent the server. That part
// lives in the app's settings rather than the database, so these orders are
// put together here: the library comes from SQL in name order, and only the
// songs, albums or artists that were played are sorted by their plays.

// How much something was listened to: how many plays, and when the last was.
data class Listening(val plays: Int, val lastPlayedAt: Long)

// Each song's listening, by song id.
fun songListening(played: List<PlayedTrack>): Map<String, Listening> =
    played.associate { it.track.id to Listening(it.plays, it.lastPlayedAt) }

// Each album's listening: its songs' plays added up, and the latest play of
// any of them, the server's album history included.
fun albumListening(played: List<PlayedTrack>, albums: List<PlayedAlbum>): Map<String, Listening> {
    val byAlbum = HashMap<String, Listening>()
    played.forEach { song ->
        val before = byAlbum[song.track.albumId]
        byAlbum[song.track.albumId] = if (before == null) {
            Listening(song.plays, song.lastPlayedAt)
        } else {
            Listening(before.plays + song.plays, maxOf(before.lastPlayedAt, song.lastPlayedAt))
        }
    }
    albums.forEach { album ->
        val before = byAlbum[album.album.id]
        byAlbum[album.album.id] = before?.copy(lastPlayedAt = maxOf(before.lastPlayedAt, album.lastPlayedAt))
            ?: Listening(0, album.lastPlayedAt)
    }
    return byAlbum
}

// Each artist's listening, from the songs on their albums.
fun artistListening(played: List<PlayedTrack>): Map<String, Listening> =
    played.groupBy { it.track.artistId }.mapValues { (_, songs) ->
        Listening(songs.sumOf { it.plays }, songs.maxOf { it.lastPlayedAt })
    }

// `base` reordered by listening. Played items come first when descending
// (most or latest first) and last when ascending; items never played keep
// their place from `base` among themselves, and so do ties.
fun <T> byListening(
    base: List<T>,
    id: (T) -> String,
    listening: Map<String, Listening>,
    mostPlayed: Boolean,
    descending: Boolean,
): List<T> {
    val played = ArrayList<Pair<T, Listening>>()
    val never = ArrayList<T>()
    base.forEach { item ->
        val heard = listening[id(item)]
        if (heard != null && (if (mostPlayed) heard.plays > 0 else heard.lastPlayedAt > 0)) {
            played += item to heard
        } else {
            never += item
        }
    }
    val order = if (mostPlayed) {
        compareBy<Pair<T, Listening>>({ it.second.plays }, { it.second.lastPlayedAt })
    } else {
        compareBy({ it.second.lastPlayedAt }, { it.second.plays })
    }
    // A stable sort, so ties keep the order they had in `base`.
    played.sortWith(if (descending) order.reversed() else order)
    val listened = played.map { it.first }
    return if (descending) listened + never else never + listened
}
