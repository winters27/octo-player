package app.winters.octo.ui.folders.today

import app.winters.octo.catalog.TrackEntity

// A labeled pin: today's order of a phone folder's own songs, as
// ui/folders/FoldersViewModel.kt had it at c2208eaa, frozen as the measure
// the folder's ordered copy must match. Never edited to follow the app.
// Today's last tie fell to the order the library read the songs in; the
// song's id is the declared tie after it.
val phoneSongOrder: Comparator<TrackEntity> =
    compareBy<TrackEntity>({ it.album.lowercase() }, { it.albumId }, { it.albumOrder }, { it.sortKey })
