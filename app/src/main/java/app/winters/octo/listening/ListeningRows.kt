package app.winters.octo.listening

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// What keeping in step with each kept server remembers, by its account:
// the stars and ratings both sides last agreed on, plays waiting to be
// sent, and plays already sent.

@Entity(tableName = "synced_star", primaryKeys = ["account", "serverId"])
data class SyncedStarEntity(val account: String, val serverId: String)

@Entity(tableName = "synced_rating", primaryKeys = ["account", "serverId"])
data class SyncedRatingEntity(val account: String, val serverId: String, val rating: Int)

@Entity(
    tableName = "pending_play",
    primaryKeys = ["account", "serverId", "startedAt"],
    indices = [Index("account", "startedAt", "serverId")],
)
data class PendingPlayEntity(val account: String, val serverId: String, val startedAt: Long)

@Entity(tableName = "sent_play", primaryKeys = ["account", "serverId"])
data class SentPlayEntity(val account: String, val serverId: String, val folded: Int)

@Entity(tableName = "sent_recent", primaryKeys = ["account", "serverId", "startedAt"])
data class SentRecentEntity(val account: String, val serverId: String, val startedAt: Long)

// Set once the values an older version kept elsewhere were moved here.
@Entity(tableName = "listening_import")
data class ListeningImportEntity(@PrimaryKey val id: Int, val doneAt: Long)
