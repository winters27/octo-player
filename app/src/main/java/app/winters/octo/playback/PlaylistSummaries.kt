package app.winters.octo.playback

import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.MosaicPicker
import app.winters.octo.catalog.PlaylistRow
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.shownWith
import app.winters.octo.catalog.summaryOf
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// How many playlist rows one page of a cover walk reads.
internal const val COVER_PAGE = 100

// Points in the summaries' reads where a test can step in.
internal open class SummaryHooks {
    // A playlist's totals and covers are about to be read.
    open suspend fun reading(playlistId: String) {}

    // A cover walk read a page and is about to read the next.
    open suspend fun betweenPages(playlistId: String) {}
}

// The listener's playlists with their song counts, lengths and covers,
// read a playlist at a time. A playlist's count, length and covers follow
// only from its current rows and the library songs and finds they name: an
// edit moves its updatedAt, a whole new list of songs moves its item set,
// and the library's songs change only with a swap, which moves the swap
// count. So each playlist's are kept with those three as they were read,
// and read again only once one moves; one that holds a song found online is
// read again too once the finds change. Reads that pile up while one runs
// become one more, and every read runs to its end, so a burst of writes
// never keeps the summaries from coming. Memory: a few values a playlist,
// and one page of one playlist's rows at a time.
internal class PlaylistSummaries(
    private val db: CatalogDatabase,
    private val owners: WorkOwners,
    private val serverInUse: Flow<String?>,
    // Lets a cover walk's pin of its item set go (PlaylistSets.releasePin).
    private val releasePin: suspend (owner: String, playlistId: String) -> Unit,
    private val page: Int = COVER_PAGE,
) {
    private val user = db.userDao()
    private val work = db.workDao()

    // What a playlist's totals and covers were read under.
    private data class Key(val updatedAt: Long, val itemSet: Long, val swapCount: Long)

    // A playlist's totals and covers as last read, and the count of finds
    // changes they have been checked against.
    private class Kept(val key: Key, val songCount: Int, val durationMs: Long, val covers: List<String>, val findsSeen: Long)

    private val kept = ConcurrentHashMap<String, Kept>()

    // Moves on every change of the songs found online.
    private val findChanges = AtomicLong()

    internal var hooks = SummaryHooks()

    // The playlists the server in use shows, the one changed last first.
    val all: Flow<List<PlaylistSummary>> = passes().map { server -> readAll(server) }

    // One playlist's summary, as it changes; null while it is not shown.
    fun one(id: String): Flow<PlaylistSummary?> = passes().map { server -> readOne(id, server) }

    // The server in use, once at first and again on each change of what a
    // summary reads or of the server.
    private fun passes(): Flow<String?> = combine(changes(), serverInUse.distinctUntilChanged()) { _, server -> server }.conflate()

    private fun changes(): Flow<Long> = flow {
        var n = 0L
        db.invalidationTracker.createFlow("playlist", "playlist_item", "track", "online_song").collect { tables ->
            if (tables.any { it.equals("online_song", ignoreCase = true) }) findChanges.incrementAndGet()
            emit(++n)
        }
    }

    private suspend fun readAll(server: String?): List<PlaylistSummary> {
        // Read first, so a swap during the reads below is seen next time.
        val swaps = work.swapCount()
        val finds = findChanges.get()
        val rows = user.playlistRows()
        kept.keys.retainAll(rows.mapTo(HashSet()) { it.id })
        return rows.filter { it.shownWith(server) }.mapNotNull { summaryFor(it, swaps, finds) }
    }

    private suspend fun readOne(id: String, server: String?): PlaylistSummary? {
        val swaps = work.swapCount()
        val finds = findChanges.get()
        val row = user.playlistRowOf(id)?.takeIf { it.shownWith(server) } ?: return null
        return summaryFor(row, swaps, finds)
    }

    private suspend fun summaryFor(row: PlaylistRow, swaps: Long, finds: Long): PlaylistSummary? {
        val have = kept[row.id]
        val totals = when {
            have == null || have.key != Key(row.updatedAt, row.itemSet, swaps) -> read(row.id, swaps, finds)
            have.findsSeen == finds -> have
            user.playlistHoldsFind(row.id, row.itemSet) -> read(row.id, swaps, finds)
            else -> Kept(have.key, have.songCount, have.durationMs, have.covers, finds).also { kept[row.id] = it }
        } ?: return null
        return summaryOf(row, totals.songCount, totals.durationMs, totals.covers)
    }

    // Counts a playlist and walks its rows for covers, its item set pinned
    // from the transaction that counts it until the walk ends, so a new
    // list of songs written meanwhile leaves the walk on the set it began
    // with. The walk reads the set as it is, not as it stood at the pin: it
    // only picks covers, and every edit of the rows moves updatedAt, which
    // reads the summary again. Null when the playlist is gone.
    private suspend fun read(id: String, swaps: Long, finds: Long): Kept? {
        hooks.reading(id)
        val owner = owners.walk()
        var pinned = false
        try {
            val (totals, first) = db.withTransaction {
                val totals = user.playlistTotals(id) ?: return@withTransaction null
                user.pinPlaylistSet(owner, id, totals.itemSet)
                pinned = true
                totals to user.playlistCoverPage(id, totals.itemSet, Long.MIN_VALUE, Long.MIN_VALUE, page)
            } ?: return null
            val picker = MosaicPicker()
            var rows = first
            while (true) {
                for (row in rows) {
                    if (row.inLibrary) picker.offer(row.albumId.orEmpty(), row.artwork)
                    if (row.found) picker.offer(row.findAlbumId, row.findCover)
                }
                if (picker.full || rows.size < page) break
                hooks.betweenPages(id)
                val last = rows.last()
                rows = user.playlistCoverPage(id, totals.itemSet, last.ord, last.itemKey, page)
            }
            val key = Key(totals.updatedAt, totals.itemSet, swaps)
            return Kept(key, totals.songCount, totals.durationMs, picker.covers, finds).also { kept[id] = it }
        } finally {
            if (pinned) withContext(NonCancellable) { releasePin(owner, id) }
        }
    }
}
