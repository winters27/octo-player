package app.winters.octo.catalog

import androidx.annotation.VisibleForTesting
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// The most rows one transaction deletes when an owner's rows go.
const val OWNER_PAGE = 500

// This launch's mark, made once when the process starts, before anything
// opens the library: every work owner minted in this launch leads with it
// ("<mark>/vm:3"), and the library's open writes it into merge_state. Two
// launches' marks differ, so a token of this launch never names a row an
// older launch wrote. 63 bits from SecureRandom; no older mark is read.
object LaunchMark {
    private val random = SecureRandom()

    @Volatile var current: Long = fresh()
        private set

    // What every owner of this launch begins with.
    val prefix: String get() = "$current$LAUNCH_SEPARATOR"

    // A new launch in the same process, for tests that stand for a restart.
    @VisibleForTesting
    fun next(): Long {
        current = fresh()
        return current
    }

    private fun fresh(): Long {
        while (true) {
            val mark = random.nextLong() ushr 1
            if (mark != 0L) return mark
        }
    }
}

// What ends a launch's mark in an owner, and the character just past it,
// so a launch's owners are those from "<mark>/" up to, not including,
// "<mark>0".
const val LAUNCH_SEPARATOR = "/"
const val LAUNCH_SEPARATOR_END = "0"

// This launch's owners as a range, in SQL, from the mark the library's open
// wrote (merge_state.launchMark): for a statement that seeks by owner and
// takes no arguments for the range.
const val LAUNCH_OWNERS_FROM = "((SELECT launchMark FROM merge_state WHERE id = 0) || '$LAUNCH_SEPARATOR')"
const val LAUNCH_OWNERS_UNTIL = "((SELECT launchMark FROM merge_state WHERE id = 0) || '$LAUNCH_SEPARATOR_END')"

// The text just past every text that starts with `prefix`, so the owners
// that start with it are those >= prefix and < this.
fun prefixEnd(prefix: String): String = prefix.dropLast(1) + (prefix.last() + 1)

// Who owns rows of a work table, and whether they are still in use. Every
// work table a screen, a car read or a queue fill writes leads its key with
// an owner, a token handed out here, after this launch's mark: vm:<n> for a
// page's view model, car:<n> for a car read, queue:<listId> for a queue
// list's fill, live:<listId> for a saved live list, draft:<n> for a live
// list being made, copy:<n> for a live list's Copy, report for Library
// health, export:<n> for an export, hold:<n> for a server folder gather,
// match:<n> for a match job and fix:<n> for a health fix, and walk:<n> for
// any other walk that pins a playlist. Each n comes from one counter for
// the process. The library's open deletes nothing: an older launch's rows
// carry another mark, so every reader, which keys by the full owner, misses
// them, and the sweep at launch (sweepOlderLaunches) takes them a page at a
// time.
//
// An owner in use is pinned, as many times as it has readers. A clear
// asked for while it is pinned waits for the last unpin. A published
// generation (a live list's, a health report's) is pinned the same way,
// and an older one goes only once nothing pins it.
//
// The lock here guards memory only: nothing suspends or touches the
// database while holding it, so it can be taken under any other lock.
@Singleton
class WorkOwners @Inject constructor() {
    private val counter = AtomicLong()
    private val lock = Any()
    private val pins = HashMap<String, Int>()
    private val waiting = HashMap<String, MutableList<suspend () -> Unit>>()
    private val published = HashMap<String, Long>()
    private val genPins = HashMap<Pair<String, Long>, Int>()
    private val retired = HashMap<String, MutableSet<Long>>()

    fun next(): Long = counter.incrementAndGet()

    fun vm(): String = marked("vm:${next()}")

    fun car(): String = marked("car:${next()}")

    fun queue(listId: Long): String = marked("queue:$listId")

    fun live(listId: String): String = marked("live:$listId")

    fun draft(): String = marked("draft:${next()}")

    fun copy(): String = marked("copy:${next()}")

    fun export(): String = marked("export:${next()}")

    fun hold(): String = marked("hold:${next()}")

    fun match(): String = marked("match:${next()}")

    fun fix(): String = marked("fix:${next()}")

    fun walk(): String = marked("walk:${next()}")

    fun pin(owner: String) {
        synchronized(lock) { pins[owner] = (pins[owner] ?: 0) + 1 }
    }

    // Lets one pin go; the last runs any clear that waited for it.
    suspend fun unpin(owner: String) {
        val clears = synchronized(lock) {
            val left = (pins[owner] ?: return).minus(1)
            if (left > 0) {
                pins[owner] = left
                null
            } else {
                pins.remove(owner)
                waiting.remove(owner)
            }
        }
        clears?.forEach { it() }
    }

    fun pinned(owner: String): Boolean = synchronized(lock) { owner in pins }

    // Runs `clear` now, or when the owner's last pin goes if it is pinned.
    // Answers whether it ran now.
    suspend fun clear(owner: String, clear: suspend () -> Unit): Boolean {
        val now = synchronized(lock) {
            if (owner in pins) {
                waiting.getOrPut(owner) { ArrayList() } += clear
                false
            } else {
                true
            }
        }
        if (now) clear()
        return now
    }

    // `gen` is now the one readers of `key` get. Answers the generations
    // before it that nothing pins, for the caller to delete; one still
    // pinned is answered by the unpin that frees it.
    fun publish(key: String, gen: Long): List<Long> = synchronized(lock) {
        val old = published.put(key, gen)
        if (old == null || old == gen) return@synchronized emptyList()
        if ((genPins[key to old] ?: 0) == 0) {
            listOf(old)
        } else {
            retired.getOrPut(key) { HashSet() } += old
            emptyList()
        }
    }

    // Pins the generation readers of `key` get now and answers it, or null
    // when none is published yet.
    fun pinPublished(key: String): Long? = synchronized(lock) {
        val gen = published[key] ?: return@synchronized null
        genPins[key to gen] = (genPins[key to gen] ?: 0) + 1
        gen
    }

    // Whether something pins `gen` of `key` now.
    fun genPinned(key: String, gen: Long): Boolean = synchronized(lock) { (genPins[key to gen] ?: 0) > 0 }

    // Lets a pin of `gen` go. Answers whether the caller should now delete
    // it: no longer published, and this was its last pin.
    fun unpinGen(key: String, gen: Long): Boolean = synchronized(lock) {
        val pair = key to gen
        val left = (genPins[pair] ?: return@synchronized false) - 1
        if (left > 0) {
            genPins[pair] = left
            return@synchronized false
        }
        genPins.remove(pair)
        val set = retired[key]
        if (set == null || !set.remove(gen)) return@synchronized false
        if (set.isEmpty()) retired.remove(key)
        true
    }

    fun publishedGen(key: String): Long? = synchronized(lock) { published[key] }

    companion object {
        // Library health's report, as this launch owns it.
        val REPORT: String get() = marked("report")

        // A name owned in this launch: the launch's mark, then the name.
        fun marked(name: String): String = LaunchMark.prefix + name

        // The kind of a token, read after its launch's mark: "queue" for
        // "<mark>/queue:5", and for a token written without one.
        fun kindOf(owner: String): String = owner.substringAfter('/', owner).substringBefore(':')

        // The owners of `kind` in this launch, as a range to seek by:
        // from (inclusive) and until (exclusive).
        fun kindRange(kind: String): Pair<String, String> = marked("$kind:").let { it to prefixEnd(it) }
    }
}

// The work tables whose owners are tokens minted by WorkOwners, so their
// rows of an older launch go by the sweep. The copy's ordering rows
// (sync_order, owned by the copy as source and generation) and the scan's
// tallies (scan_tally, owned by what is counted) belong to one job each,
// whose start clears them.
val SWEPT_WORK_TABLES: List<String> get() = OWNED_WORK_TABLES - JOB_OWNED_TABLES

val JOB_OWNED_TABLES = listOf("sync_order", "scan_tally")

// At launch, in the background: every row of `tables` an older launch
// owned goes, OWNER_PAGE a transaction, the owners below this launch's
// range and then those above it, each a seek by the owner the table's key
// leads with. Rows of this launch stay. Answers how many went.
suspend fun RoomDatabase.sweepOlderLaunches(tables: List<String>, page: Int = OWNER_PAGE, mark: String = LaunchMark.prefix): Long {
    val end = prefixEnd(mark)
    var total = 0L
    for (table in tables) {
        for (range in listOf("owner < ?1", "owner >= ?1")) {
            val sql = "DELETE FROM `$table` WHERE rowid IN (SELECT rowid FROM `$table` WHERE $range LIMIT $page)"
            val bound = if (range.startsWith("owner <")) mark else end
            while (true) {
                val deleted = withTransaction {
                    openHelper.writableDatabase.compileStatement(sql).use {
                        it.bindString(1, bound)
                        it.executeUpdateDelete()
                    }
                }
                total += deleted
                if (deleted < page) break
            }
        }
    }
    return total
}

// Whether an owner holds any row of a work table, read on the table's key,
// which leads with the owner.
fun RoomDatabase.holdsOwned(table: String, owner: String): Boolean =
    readLong("SELECT EXISTS (SELECT 1 FROM `$table` WHERE owner = ?1)", owner) == 1L

// Deletes an owner's rows of a work table a page at a time, each page its
// own transaction, picked on the table's key, which leads with the owner.
// Answers how many went.
suspend fun RoomDatabase.deleteOwned(table: String, owner: String, page: Int = OWNER_PAGE): Long {
    val sql = "DELETE FROM `$table` WHERE rowid IN (SELECT rowid FROM `$table` WHERE owner = ?1 LIMIT $page)"
    var total = 0L
    while (true) {
        val deleted = withTransaction {
            openHelper.writableDatabase.compileStatement(sql).use {
                it.bindString(1, owner)
                it.executeUpdateDelete()
            }
        }
        total += deleted
        if (deleted < page) return total
    }
}
