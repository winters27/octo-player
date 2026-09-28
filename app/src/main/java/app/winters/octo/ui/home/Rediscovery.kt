package app.winters.octo.ui.home

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.home.AlbumListening
import app.winters.octo.home.Rediscovery
import app.winters.octo.home.rediscovery

// How many albums each rediscovery shelf holds.
private const val REDISCOVERY_SHELF = 20

// Each album's listening, for the shared rediscovery shelves, from the
// songs played here and on the server together.
fun albumListening(albums: List<AlbumEntity>, played: List<PlayedTrack>): (AlbumEntity) -> AlbumListening {
    val byAlbum = played.groupBy { it.track.albumId }
    val listening = albums.associate { album ->
        val songs = byAlbum[album.id].orEmpty().distinctBy { it.track.id }
        album.id to AlbumListening(
            id = album.id,
            songs = album.songCount,
            playedSongs = songs.count { it.plays > 0 },
            plays = songs.sumOf { it.plays.toLong() },
            lastPlayed = songs.maxOfOrNull { it.lastPlayedAt }?.takeIf { it > 0 },
            added = album.addedAt.takeIf { it > 0 },
        )
    }
    return { album -> listening.getValue(album.id) }
}

// Home's rediscovery shelves: not played in six months, never finished,
// never played. Worked out from the whole library, so run off the main thread.
fun homeRediscovery(albums: List<AlbumEntity>, played: List<PlayedTrack>, now: Long): Rediscovery<AlbumEntity> =
    rediscovery(albums, albumListening(albums, played), now, REDISCOVERY_SHELF)
