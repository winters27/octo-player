package app.winters.octo.catalog

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Things the listener made: likes, plays, playlists and the saved queue.
//
// None of these point at the catalog with a foreign key. The catalog is
// rebuilt from its sources on every scan, and a cascading delete would wipe
// them. Each row keeps a relinkKey instead, so a song whose id changed (a
// file moved) can be found again.

@Entity(tableName = "liked_track")
data class LikedTrackEntity(
    @PrimaryKey val trackId: String,
    val relinkKey: String,
    val likedAt: Long,
)

// A song's rating made on the phone, 1 to 5 stars. A song with no rating
// has no row.
@Entity(tableName = "track_rating")
data class TrackRatingEntity(
    @PrimaryKey val trackId: String,
    val relinkKey: String,
    val rating: Int,
    val ratedAt: Long,
)

@Entity(tableName = "play_event", indices = [Index("trackId"), Index("startedAt")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val relinkKey: String,
    val startedAt: Long,
    val playedMs: Long,
    val durationMs: Long,
)

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "playlist_item",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId", "position")],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: String,
    val trackId: String,
    val relinkKey: String,
    val position: Int,
)

// The queue as it was, one row per song in play order.
@Entity(tableName = "queue_item")
data class QueueItemEntity(
    @PrimaryKey val position: Int,
    val trackId: String,
    // Where this song falls when shuffle is on.
    val shuffledPosition: Int,
)

// Where playback was in the saved queue. Always a single row.
@Entity(tableName = "queue_state")
data class QueueStateEntity(
    @PrimaryKey val id: Int = 0,
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffle: Boolean,
    val savedAt: Long,
)
