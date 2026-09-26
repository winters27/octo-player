package app.winters.octo.catalog

import androidx.room.Embedded

// A playlist as its list shows it: name, how many songs, how long, and the
// covers for its picture.
data class PlaylistSummary(
    val id: String,
    val name: String,
    val songCount: Int,
    val durationMs: Long,
    val covers: List<String>,
    // Kept in step with a copy on the server.
    val onServer: Boolean = false,
)

// One song of a playlist, as much of it as the list of playlists needs.
data class PlaylistEntry(
    val playlistId: String,
    val albumId: String,
    val durationMs: Long,
    val artwork: String?,
)

// A song on a playlist page, with the row it came from so it can be moved
// or taken out.
data class PlaylistTrack(
    val itemId: Long,
    @Embedded val track: TrackEntity,
)

// Up to four covers for a playlist's picture, one per album in the order
// the songs come. Songs with no artwork are skipped.
fun mosaicCovers(albumArt: List<Pair<String, String?>>): List<String> =
    albumArt
        .mapNotNull { (album, art) -> art?.let { album to it } }
        .distinctBy { it.first }
        .take(4)
        .map { it.second }

// Every playlist with its totals, in the order the playlists were given.
// Entries are expected in play order within each playlist.
fun summarize(playlists: List<PlaylistEntity>, entries: List<PlaylistEntry>): List<PlaylistSummary> {
    val byPlaylist = entries.groupBy { it.playlistId }
    return playlists.map { playlist ->
        val songs = byPlaylist[playlist.id].orEmpty()
        PlaylistSummary(
            id = playlist.id,
            name = playlist.name,
            songCount = songs.size,
            durationMs = songs.sumOf { it.durationMs },
            covers = mosaicCovers(songs.map { it.albumId to it.artwork }),
            onServer = playlist.sourceId != null,
        )
    }
}

// New rows for songs added to the end of a playlist, numbered on from `start`.
fun appendedItems(playlistId: String, start: Int, tracks: List<TrackEntity>): List<PlaylistItemEntity> =
    tracks.mapIndexed { offset, track ->
        PlaylistItemEntity(playlistId = playlistId, trackId = track.id, relinkKey = track.relinkKey, position = start + offset)
    }

// The rows numbered 0, 1, 2... in the order given, closing any gaps.
fun renumbered(items: List<PlaylistItemEntity>): List<PlaylistItemEntity> =
    items.mapIndexed { index, item -> if (item.position == index) item else item.copy(position = index) }

// Puts a row taken out back at the place it had, `item.position`, and
// renumbers. A place past the end puts it last. A row already there
// changes nothing.
fun restoredItems(items: List<PlaylistItemEntity>, item: PlaylistItemEntity): List<PlaylistItemEntity> {
    if (items.any { it.id == item.id }) return items
    val at = item.position.coerceIn(0, items.size)
    return renumbered(items.toMutableList().apply { add(at, item) })
}

// Moves one row into the place of another, the one it was dropped on, and
// renumbers. Rows are expected in play order. Unknown ids change nothing.
fun movedItem(items: List<PlaylistItemEntity>, itemId: Long, targetId: Long): List<PlaylistItemEntity> {
    val from = items.indexOfFirst { it.id == itemId }
    val to = items.indexOfFirst { it.id == targetId }
    if (from < 0 || to < 0) return items
    return renumbered(items.toMutableList().apply { add(to, removeAt(from)) })
}
