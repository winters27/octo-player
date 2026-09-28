package app.winters.octo.playlists

import app.winters.octo.catalog.TrackEntity

// Reading and writing playlist files is shared with the desktop
// (shared/core M3uText.kt); the phone matches against its own catalog.

// Finds each playlist entry among the phone's library songs; see the shared
// matchM3u for the order it tries.
fun matchM3u(entries: List<M3uEntry>, library: List<TrackEntity>, paths: Map<String, String>): M3uMatch =
    matchM3u(entries, library.map { M3uSong(it.id, it.title, it.artist, it.durationMs) }, paths)
