package app.winters.octo.sort

import app.winters.octo.catalog.PlayedAlbum
import app.winters.octo.catalog.PlayedTrack

// Listening worked out from the catalog's play rows. Listening itself and
// byListening live in shared core (Listening.kt).

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
