package app.winters.octo.catalog.today

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.PlayedAlbum
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.TrackEntity

// A labeled pin: today's orders of played songs and albums, as
// catalog/Plays.kt had them at c2208eaa, frozen as the measure the walks
// over stored play totals must match. Never edited to follow the app.

// Albums by their latest play, newest first.
fun byLatestPlay(albums: List<PlayedAlbum>, limit: Int): List<AlbumEntity> =
    albums
        .sortedWith(compareByDescending<PlayedAlbum> { it.lastPlayedAt }.thenBy { it.album.id })
        .take(limit)
        .map { it.album }

// Songs by how often they were played. Of two played as often, the one
// played more recently comes first; the id keeps the order steady after that.
fun byPlayCount(tracks: List<PlayedTrack>, limit: Int): List<TrackEntity> =
    tracks
        .sortedWith(
            compareByDescending<PlayedTrack> { it.plays }
                .thenByDescending { it.lastPlayedAt }
                .thenBy { it.track.id },
        )
        .take(limit)
        .map { it.track }

// An artist's songs, given album by album. Once any of them has been
// played, the most played come first (of two played as often, the one
// played last), and the rest follow in the order given.
fun artistSongOrder(tracks: List<TrackEntity>, played: List<PlayedTrack>): List<TrackEntity> {
    val plays = played.associateBy { it.track.id }
    val (heard, rest) = tracks.partition { it.id in plays }
    if (heard.isEmpty()) return tracks
    return heard.sortedWith(
        compareByDescending<TrackEntity> { plays.getValue(it.id).plays }.thenByDescending { plays.getValue(it.id).lastPlayedAt },
    ) + rest
}
