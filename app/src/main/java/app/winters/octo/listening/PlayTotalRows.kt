package app.winters.octo.listening

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Each library song's listening, counted once and kept, so the shelves and
// listening sorts read a few rows instead of every play: plays on the phone
// and on the servers together, the latest of them, the latest that counts
// for Recently played, and the phone's own. A row exists while a song has a
// play or a recent time. The album, artist and sort keys are the song's, so
// the totals walk in those orders with no join.
@Entity(
    tableName = "play_total",
    indices = [
        Index(
            "plays", "lastPlayedAt", "trackId",
            orders = [Index.Order.DESC, Index.Order.DESC, Index.Order.ASC],
            name = "index_play_total_most",
        ),
        Index(
            "albumId", "plays", "lastPlayedAt", "trackId",
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC, Index.Order.ASC],
            name = "index_play_total_album",
        ),
        Index(
            "artistId", "plays", "lastPlayedAt", "trackId",
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC, Index.Order.ASC],
            name = "index_play_total_artist",
        ),
        Index("recentAt", "trackId", orders = [Index.Order.DESC, Index.Order.ASC], name = "index_play_total_recent"),
        Index("lastPlayedAt", "trackId", orders = [Index.Order.DESC, Index.Order.ASC], name = "index_play_total_last"),
        Index("phoneLastAt", "trackId", orders = [Index.Order.DESC, Index.Order.ASC], name = "index_play_total_phone"),
        Index("plays", "lastPlayedAt", "sortKey", "artistSortKey", "trackId", name = "index_play_total_most_title"),
        Index("lastPlayedAt", "plays", "sortKey", "artistSortKey", "trackId", name = "index_play_total_recent_title"),
    ],
)
data class PlayTotalEntity(
    @PrimaryKey val trackId: String,
    val albumId: String,
    val artistId: String,
    val plays: Int,
    val lastPlayedAt: Long,
    // Null when nothing counts for Recently played.
    val recentAt: Long?,
    val phonePlays: Int,
    // Null when the phone never played it.
    val phoneLastAt: Long?,
    val sortKey: String,
    val artistSortKey: String,
)

// What a rebuild of the totals found different, before it is applied: a
// put (op 1) with the row's new values, or a delete (op 0) with none. Kept
// only while one rebuild runs.
@Entity(tableName = "play_total_diff")
data class PlayTotalDiffEntity(
    @PrimaryKey val trackId: String,
    val op: Int,
    val albumId: String?,
    val artistId: String?,
    val plays: Int?,
    val lastPlayedAt: Long?,
    val recentAt: Long?,
    val phonePlays: Int?,
    val phoneLastAt: Long?,
    val sortKey: String?,
    val artistSortKey: String?,
)
