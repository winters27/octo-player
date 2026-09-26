package app.winters.octo.catalog

import androidx.room.Embedded

// A song still in the library, with how often and when it was last played.
data class PlayedTrack(
    @Embedded val track: TrackEntity,
    val plays: Int,
    val lastPlayedAt: Long,
)

// One play of a song still in the library, and when it started.
data class PlayedAt(
    @Embedded val track: TrackEntity,
    val startedAt: Long,
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
