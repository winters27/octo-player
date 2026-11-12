package app.winters.octo.catalog.today

import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistEntry
import app.winters.octo.catalog.PlaylistSummary

// A labeled pin: today's playlist summaries over every playlist's entries,
// as catalog/Playlists.kt had them at c2208eaa, frozen as the measure the
// per-playlist reads must match. Never edited to follow the app.

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
            createdAt = playlist.createdAt,
            updatedAt = playlist.updatedAt,
            octoNotice = playlist.octoNotice,
            family = playlist.family,
            familyMayAdd = playlist.familyMayAdd,
            familyMayRemove = playlist.familyMayRemove,
            familyMayRename = playlist.familyMayRename,
            familyMayDelete = playlist.familyMayDelete,
        )
    }
}
