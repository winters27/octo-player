package app.winters.octo.catalog

import androidx.room.Embedded

// A song still in the library, with how often and when it was last played.
data class PlayedTrack(
    @Embedded val track: TrackEntity,
    val plays: Int,
    val lastPlayedAt: Long,
)

// An album still in the library, with when any of its songs was last played.
data class PlayedAlbum(
    @Embedded val album: AlbumEntity,
    val lastPlayedAt: Long,
)

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
