package app.winters.octo.listening

import app.winters.octo.ui.paging.SqlOrder
import kotlin.math.abs

// How many rows one page of a walk over plays reads, and one write of the
// totals holds.
internal const val PLAY_PAGE = 500

// Octo sends its own plays to the server with their start time, which the
// server may keep to the second only.
internal const val OWN_PLAY_SLACK_MS = 1_000L

// Which stream of a walk over plays just read a page.
enum class PlayStream { Phone, Copies, Stored, Diff, Albums }

// Points a test can stop a walk over plays at. Each does nothing in the app.
internal open class PlayWalkHooks {
    // After each page one of the streams read.
    open suspend fun page(stream: PlayStream) {}
}

// A stream read a page at a time in its key's order, looked at one row
// ahead. `load` reads the page after the last row of the one before (null
// for the first); a short page is the last.
internal class Paged<T : Any>(private val size: Int, private val load: suspend (last: T?) -> List<T>) {
    private var rows: List<T> = emptyList()
    private var at = 0
    private var done = false

    suspend fun peek(): T? {
        if (at < rows.size) return rows[at]
        if (done) return null
        rows = load(rows.lastOrNull())
        at = 0
        if (rows.size < size) done = true
        return rows.firstOrNull()
    }

    suspend fun take(): T = peek()!!.also { at++ }
}

// A song's plays, the phone's and every server copy's, read together: the
// phone's row (if it played it) and its copies in their order.
internal class SongPlays(val trackId: String, val phone: PhonePlays?, val copies: List<CopyPlays>)

// Walks the phone's plays and the server copies together by song id, in
// SQLite's order of ids (both come in it), handing each song to `each`
// with its stored row from `stored` when that stream is given. Holds two
// pages and one song's copies.
internal suspend fun walkPlays(
    phone: Paged<PhonePlays>,
    copies: Paged<CopyPlays>,
    stored: Paged<PlayTotalEntity>?,
    each: suspend (SongPlays, PlayTotalEntity?) -> Unit,
) {
    while (true) {
        val id = listOfNotNull(phone.peek()?.trackId, copies.peek()?.trackId, stored?.peek()?.trackId)
            .minWithOrNull(SqlOrder::text) ?: return
        val own = if (phone.peek()?.trackId == id) phone.take() else null
        val run = ArrayList<CopyPlays>(1)
        while (copies.peek()?.trackId == id) run += copies.take()
        val row = if (stored != null && stored.peek()?.trackId == id) stored.take() else null
        each(SongPlays(id, own, run), row)
    }
}

// The phone's plays of library songs as a stream, all of them or from
// `since` on.
internal fun phoneStream(dao: PlayTotalsDao, hooks: PlayWalkHooks, since: Long? = null, size: Int = PLAY_PAGE) =
    Paged<PhonePlays>(size) { last ->
        val after = last?.trackId ?: ""
        (if (since == null) dao.phonePlayPage(after, size) else dao.phonePlayPageSince(after, since, size))
            .also { hooks.page(PlayStream.Phone) }
    }

// Server copies of library songs with a play record as a stream, with the
// plays Octo sent `account` that each record holds.
internal fun copyStream(dao: PlayTotalsDao, hooks: PlayWalkHooks, account: String?, size: Int = PLAY_PAGE) =
    Paged<CopyPlays>(size) { last ->
        dao.serverCopyPage(last?.trackId ?: "", last?.copyRow ?: -1, account, size).also { hooks.page(PlayStream.Copies) }
    }

// A library song's totals as today counts them, or null when nothing is
// worth keeping: the phone's plays plus each copy's plays that are not
// Octo's own (never below none), the latest of the phone's last play and
// each copy's (one with no time counts as 0), the latest time that counts
// for Recently played (the phone's last play and each copy's own time; a
// copy with only a count adds none), and the phone's own plays. A row is
// kept while the song has a play or such a time.
internal fun totalOf(song: SongPlays): PlayTotalEntity? {
    val phone = song.phone
    val keys = phone?.let { SongKeys(it.albumId, it.artistId, it.sortKey, it.artistSortKey) }
        ?: song.copies.firstOrNull()?.let { SongKeys(it.albumId, it.artistId, it.sortKey, it.artistSortKey) }
        ?: return null
    var plays = phone?.plays ?: 0
    var last: Long? = phone?.lastAt
    var recent: Long? = phone?.lastAt
    for (copy in song.copies) {
        plays += (copy.plays - copy.held).coerceAtLeast(0)
        val at = copy.lastPlayedAt ?: 0
        last = if (last == null) at else maxOf(last, at)
        if (copy.lastPlayedAt != null) recent = maxOf(recent ?: copy.lastPlayedAt, copy.lastPlayedAt)
    }
    if (plays <= 0 && recent == null) return null
    return PlayTotalEntity(
        trackId = song.trackId,
        albumId = keys.albumId,
        artistId = keys.artistId,
        plays = plays,
        lastPlayedAt = last ?: 0,
        recentAt = recent,
        phonePlays = phone?.plays ?: 0,
        phoneLastAt = phone?.lastAt,
        sortKey = keys.sortKey,
        artistSortKey = keys.artistSortKey,
    )
}

private class SongKeys(val albumId: String, val artistId: String, val sortKey: String, val artistSortKey: String)

// A song's plays counted from `since` on, and the latest of them, or null
// when none falls there. The phone's plays come counted from `since`. The
// server keeps no date but its last play, so each copy, in its order,
// adds one play when that last play falls in the range, the copy holds
// plays that are not Octo's own, and it is not the song's latest play so
// far (the phone's own play, sent there); each added play can be the latest.
internal fun playsSince(song: SongPlays, since: Long): Pair<Int, Long>? {
    var plays = song.phone?.plays
    var last = song.phone?.lastAt ?: 0
    for (copy in song.copies) {
        val at = copy.lastPlayedAt ?: continue
        if (at < since || copy.plays - copy.held <= 0) continue
        if (plays != null && abs(last - at) <= OWN_PLAY_SLACK_MS) continue
        if (plays == null) {
            plays = 1
            last = at
        } else {
            plays += 1
            last = maxOf(last, at)
        }
    }
    return plays?.let { it to last }
}

// Keeps the first `limit` items in `order` of all it is offered, in
// memory for `limit` items.
internal class TopOf<T>(private val limit: Int, private val order: Comparator<in T>) {
    private val kept = java.util.PriorityQueue<T>(limit + 1, order.reversed())

    fun offer(item: T) {
        if (limit <= 0) return
        kept.add(item)
        if (kept.size > limit) kept.poll()
    }

    fun sorted(): List<T> = kept.sortedWith(order)
}
