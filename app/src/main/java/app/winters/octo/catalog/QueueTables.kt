package app.winters.octo.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// The queue as Room keeps it, so the player holds only a window of it. A
// generation (queueId) is one queue; each play or add is a list in it. None
// of these are emptied on open: a queue, and the edits waiting on a list
// still being written, outlive the app.

// One song of a queue, keyed by its entry number (the list that numbered it
// in the high 32 bits), placed by its key in play order (qk) and in shuffle
// order (sk).
@Entity(
    tableName = "queue_entry",
    primaryKeys = ["queueId", "entry"],
    indices = [
        Index("queueId", "qk", "entry", name = "index_queue_entry_qk"),
        Index("queueId", "sk", "entry", name = "index_queue_entry_sk"),
        Index("trackId", name = "index_queue_entry_trackId"),
        Index("queueId", "skBefore", name = "index_queue_entry_skBefore"),
    ],
)
data class QueueEntryEntity(
    val queueId: Long,
    val entry: Long,
    val trackId: String,
    val listId: Long,
    val qk: Long,
    val sk: Long,
    // The song's length when it was written.
    val durationMs: Long,
    // The shuffled key the row had before turning shuffle on made it the
    // first, while it holds the first key; null otherwise.
    val skBefore: Long? = null,
)

// One play or add of a queue: where its songs came from, how it was asked
// for, and while it is still being written, how far it got.
@Entity(tableName = "queue_list", indices = [Index("queueId")])
data class QueueListEntity(
    @PrimaryKey val listId: Long,
    val queueId: Long,
    val source: String?,
    val autoplay: Boolean,
    // What was asked for, for a list built from it.
    val spec: String?,
    val seed: Long,
    // 0 joined, 1 being written, 2 waiting for another list to join.
    val fillState: Int,
    val phase: Int?,
    // The last row written, and the row the play started at.
    val cursor: String?,
    val anchor: String?,
    @ColumnInfo(defaultValue = "0") val filled0: Long = 0,
    @ColumnInfo(defaultValue = "0") val filled1: Long = 0,
    val phase0End: Long?,
    val expected: Long?,
    // The library's swap count the list's rows date from.
    val swapCount: Long?,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    // An add's keys, in play order and in shuffle order.
    val qBase: Long?,
    val qStep: Long?,
    val sBase: Long?,
    val sStep: Long?,
    // The owner of the list's copy or playlist set pin, while it has one.
    val copyOwner: String?,
    // Whether the list plays each of its songs once, as the loader its
    // rows were read with says.
    @ColumnInfo(defaultValue = "0") val songsOnce: Boolean = false,
)

// An edit made while a list was still being written, waiting to be applied
// when the list joins, in the order the edits came.
@Entity(tableName = "queue_pending", primaryKeys = ["listId", "seq"])
data class QueuePendingEntity(val listId: Long, val seq: Long, val op: String, val body: String)

// Each account's saved queue, the one in use or one put aside: which
// generation, where playback was, and how it plays.
@Entity(tableName = "queue_account")
data class QueueAccountEntity(
    @PrimaryKey val account: String,
    val queueId: Long,
    val currentEntry: Long?,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffle: Boolean,
    // Where Remove played left the queue's start, in each order.
    val floorQ: Long?,
    val floorS: Long?,
    // The library's swap count the queue's songs were last followed at.
    val healedAt: Long?,
    val savedAt: Long,
)

// The next generation and list ids. One row, id 0.
@Entity(tableName = "queue_meta")
data class QueueMetaEntity(@PrimaryKey val id: Int, val nextQueueId: Long, val nextListId: Long)
