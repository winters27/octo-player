package app.winters.octo.catalog

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

// Usable bytes on the disk the library lives on. Tests hand in their own.
fun interface FreeSpace {
    fun bytes(): Long
}

class DiskFreeSpace @Inject constructor(@ApplicationContext private val context: Context) : FreeSpace {
    override fun bytes(): Long = StatFs(context.getDatabasePath(CATALOG_FILE).parentFile!!.apply { mkdirs() }.path).availableBytes
}

// What was being written when the phone ran short of room, as the words
// for it say it ("Not enough space to <doing>"). Copy is the library's own
// copy, the phone's scan included.
enum class SpaceFor(val doing: String) {
    Copy("copy the library"),
    List("show this list"),
    Health("update Library health"),
    KeepList("keep this list"),
    PlayCounts("update play counts"),
    Restore("restore this backup"),
    SaveBackup("save this backup"),
    SavePlaylist("save this playlist"),
    Queue("queue every song"),
}

// A library change that needs more free space than the phone has, and
// what it was for. A write the disk refused has no figure for how much it
// needed (`neededBytes` null).
class NotEnoughSpace(val neededBytes: Long?, val doing: SpaceFor = SpaceFor.Copy) :
    Exception(if (neededBytes != null) "needs $neededBytes bytes free" else "the disk is full")

// Whole megabytes, rounded up, for telling someone how much to free.
fun megabytes(bytes: Long): Long = (bytes + 999_999) / 1_000_000

// The room the write-ahead log can take beside a write of `work` bytes
// whose largest transaction writes `largest`: the log keeps every page a
// transaction writes until it holds LOG_CHECKPOINT_BYTES, when a checkpoint
// copies them into the file and the log starts again, unless a reader
// still reads from it (Room's change tracker reads after every commit),
// when the next transaction goes on after it. So it holds at most that span
// and two transactions more, and no more than the work writes.
fun logRoom(work: Long, largest: Long): Long = minOf(work, LOG_CHECKPOINT_BYTES + 2 * largest)

// The bytes the library's log holds before it starts a checkpoint: the 100
// pages of 4 KB Android sets as every connection's wal_autocheckpoint.
// Taken as a constant, as reading the pragma waits for the writer's
// connection, which a save's group holds. SwapSpaceTest holds it to the
// connection's own.
const val LOG_CHECKPOINT_BYTES = 100L * 4_096
