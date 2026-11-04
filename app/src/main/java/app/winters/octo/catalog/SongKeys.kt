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

// Raised whenever SongIdentity reads songs differently, so keys stored with
// source rows are worked out again before the next merge.
const val SONG_KEYS_VERSION = 1

// A source row with the keys the merge matches it by: its title as
// SongIdentity reads it, and that title with its main artist.
fun SourceTrackEntity.withKeys(): SourceTrackEntity {
    val keys = SongIdentity.songKeys(artist, title)
    return copy(titleKey = keys.title, matchKey = keys.matchKey, keysVersion = SONG_KEYS_VERSION)
}
