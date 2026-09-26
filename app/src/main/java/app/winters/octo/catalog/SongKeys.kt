package app.winters.octo.catalog

// A like, rating or playlist song with what the library knows of the song,
// for a backup. The song fields are null when the library no longer has it;
// the relink key is always there. `playlistId` is set for playlist songs and
// `rating` for ratings.
data class SongKeyRow(
    val playlistId: String?,
    val relinkKey: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val rating: Int?,
)
