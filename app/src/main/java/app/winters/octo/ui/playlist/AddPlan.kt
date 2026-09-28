package app.winters.octo.ui.playlist

// The plan, the question and the words for adding songs to a playlist are
// shared with the desktop (shared/core PlaylistAdds.kt).

// Which playlists hold which of the songs, from the rows the database gives.
fun holdingsByPlaylist(rows: List<Pair<String, String>>): Map<String, Set<String>> =
    rows.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
