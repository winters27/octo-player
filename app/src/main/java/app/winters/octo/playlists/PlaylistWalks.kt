package app.winters.octo.playlists

import androidx.room.withTransaction
import app.winters.octo.catalog.ADD_KIND
import app.winters.octo.catalog.AnchoredRow
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.LocalRow
import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.SEND_SLOT
import app.winters.octo.listening.Paged
import app.winters.octo.catalog.PHONE_SLOT
import app.winters.octo.catalog.PinnedSet
import app.winters.octo.catalog.PlaylistChangeEntity
import app.winters.octo.catalog.PlaylistLocalEntity
import app.winters.octo.catalog.PlaylistNextEntity
import app.winters.octo.catalog.PlaylistSendEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.REMOVE_KIND
import app.winters.octo.catalog.SET_PAGE
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.isFind
import app.winters.octo.subsonic.PagedValues

// The owner every pin a sync step takes is under, in this launch: a step
// runs alone under PlaylistSync's lock, and each of its walks lets its pin
// go before the next pins anew.
internal val SYNC_OWNER: String get() = WorkOwners.marked("sync")

// What the base becomes once a family send landed: the phone's songs, or
// the base as it was with the adds on top (while removals wait).
internal enum class BaseAfter { Phone, BaseAndAdds }

// Each of PlaylistPlan's functions as a walk over the work tables, in the
// order of an index, holding a page of rows and a few values at a time.
// What each writes is what the function answers today, for any input.
internal class PlaylistWalks(
    private val db: CatalogDatabase,
    private val sets: PlaylistSets,
    private val page: Int = SET_PAGE,
) {
    private val work = db.playlistWorkDao()
    private val sources = db.sourceDao()

    // The phone's rows of a pinned set, as the set stood when it was
    // pinned, into playlist_local, each with its ord as its place.
    suspend fun snapshot(set: PinnedSet) {
        sets.walk(set, page) { rows ->
            db.withTransaction {
                work.insertLocal(
                    rows.map {
                        PlaylistLocalEntity(
                            itemPosition = it.ord,
                            itemKey = it.itemKey,
                            trackId = it.trackId,
                            serverSongId = it.serverSongId,
                            anchor = null,
                            relinkKey = it.relinkKey,
                        )
                    },
                )
            }
        }
    }

    // songsToSend (SEND_SLOT: each row's server song by serverSongFor) or
    // familySongsToSend (PHONE_SLOT: the server's own id for a row that
    // came from it, otherwise the same): the local rows in order, less the
    // songs only on the phone. With `outside` (a server listing
    // octoPlaylistSongs, the listener's own playlist) every row goes by
    // outsideSongFor, into either slot. Answers how many ids hold a line break.
    suspend fun sends(sourceId: String, slot: Int, outside: Boolean = false): Int {
        var odd = 0
        var position = 0L
        eachLocalPage { rows ->
            val copies = copiesOf(sourceId, rows.map { it.trackId })
            val library = if (outside) libraryIdsOf(sourceId, rows.mapNotNull { it.serverSongId }) else emptySet()
            val out = rows.mapNotNull { row ->
                val local = Row(row.trackId, row.serverSongId)
                val copy = copies[row.trackId].orEmpty()
                when {
                    outside -> outsideSongFor(local, copy, library)
                    slot == PHONE_SLOT -> row.serverSongId ?: serverSongFor(local, copy)
                    else -> serverSongFor(local, copy)
                }
            }
            if (out.isNotEmpty()) {
                db.withTransaction { work.insertSend(out.map { PlaylistSendEntity(slot, position++, it) }) }
                odd += out.count { '\n' in it }
            }
        }
        return odd
    }

    // withPhoneOnly's first pass: each song only on the phone is anchored
    // to the server's first row of the last song before it that the server
    // also has, or -1 when none came before it.
    suspend fun anchor(read: Long, sourceId: String) {
        var current = -1L
        eachLocalPage { rows ->
            val copies = copiesOf(sourceId, rows.map { it.trackId })
            db.withTransaction {
                for (row in rows) {
                    if (serverSongFor(Row(row.trackId, row.serverSongId), copies[row.trackId].orEmpty()) == null) {
                        work.setAnchor(row.itemPosition, row.itemKey, current)
                    } else {
                        work.firstPositionOf(read, row.trackId)?.let { current = it }
                    }
                }
            }
        }
    }

    // withoutHeld, before the merge: for each song whose removal waits, as
    // many of its last rows from the server as removals wait are left out.
    suspend fun markHeld(read: Long) {
        eachGroup(REMOVE_KIND) { songId, n ->
            val from = work.positionFromEnd(read, songId, n - 1) ?: -1L
            do {
                val marked = db.withTransaction { work.markHeld(read, songId, from, page) }
            } while (marked == page)
        }
    }

    // withPhoneOnly's second pass, merging the read's rows in order (less
    // the held ones) with the phone-only rows by their anchors: those
    // anchored to -1 first, then each server row followed by the rows
    // anchored to it. The rows go into playlist_next in that order, each
    // with its song's relink key as the library has it now.
    suspend fun merge(read: Long) {
        val out = ArrayList<PlaylistNextEntity>(page)
        var position = 0L
        suspend fun emit(trackId: String, serverSongId: String?) {
            out += PlaylistNextEntity(position++, trackId, serverSongId, "", keptKey = null)
            if (out.size >= page) flushNext(out)
        }
        val phone = Paged<AnchoredRow>(page) { last ->
            work.anchoredPage(last?.anchor ?: -1L, last?.itemPosition ?: Long.MIN_VALUE, last?.itemKey ?: Long.MIN_VALUE, page)
        }
        while (phone.peek()?.anchor == -1L) phone.take().let { emit(it.trackId, it.serverSongId) }
        var after = -1L
        while (true) {
            val rows = work.entryRows(read, after, page)
            for (row in rows) {
                if (!row.held) emit(row.trackId!!, row.songId)
                while (phone.peek()?.anchor == row.position) phone.take().let { emit(it.trackId, it.serverSongId) }
            }
            if (rows.size < page) break
            after = rows.last().position
        }
        flushNext(out)
    }

    private suspend fun flushNext(out: MutableList<PlaylistNextEntity>) {
        if (out.isEmpty()) return
        val keys = work.trackKeys(out.map { it.trackId }.filterNot(::isFind).distinct()).associate { it.id to it.relinkKey }
        db.withTransaction { work.insertNext(out.map { it.copy(relinkKey = keys[it.trackId].orEmpty()) }) }
        out.clear()
    }

    // keptRowIds: each new row keeps the key of the first local row of the
    // same song not yet kept, in order, so a row that is picked, or that
    // Undo is about to take back out, is still there. A local row is kept
    // at most once, even if a swap moves ids between pages.
    suspend fun keepKeys() {
        var afterTrack = ""
        var afterPosition = Long.MIN_VALUE
        var song: String? = null
        var cursorPosition = Long.MIN_VALUE
        var cursorKey = Long.MIN_VALUE
        while (true) {
            val rows = work.nextByTrack(afterTrack, afterPosition, page)
            if (rows.isEmpty()) return
            // Two rows written for each, so half a page to a transaction.
            for (part in rows.chunked((page / 2).coerceAtLeast(1))) db.withTransaction {
                for (row in part) {
                    if (row.trackId != song) {
                        song = row.trackId
                        cursorPosition = Long.MIN_VALUE
                        cursorKey = Long.MIN_VALUE
                    }
                    val local = work.nextUntaken(row.trackId, cursorPosition, cursorKey) ?: continue
                    work.keep(row.position, local.itemKey)
                    work.take(local.itemPosition, local.itemKey)
                    cursorPosition = local.itemPosition
                    cursorKey = local.itemKey
                }
            }
            if (rows.size < page) return
            afterTrack = rows.last().trackId
            afterPosition = rows.last().position
        }
    }

    // The rows a write-back gives the playlist, a page at a time, each new
    // one with a fresh key, or with `keep` its kept key when it has one.
    suspend fun nextPages(keep: Boolean, each: suspend (List<NewItem>) -> Unit) {
        var from = 0L
        while (true) {
            val rows = work.nextPage(from, page)
            if (rows.isEmpty()) return
            each(rows.map { NewItem(it.trackId, it.relinkKey, it.serverSongId, if (keep) it.keptKey else null) })
            if (rows.size < page) return
            from = rows.last().position + 1
        }
    }

    // A read's rows as a new playlist's, a page at a time.
    suspend fun entryPages(read: Long, each: suspend (List<NewItem>) -> Unit) {
        var from = 0L
        while (true) {
            val rows = work.entryItems(read, from, page)
            if (rows.isEmpty()) return
            each(rows.map { NewItem(it.trackId, it.relinkKey, it.songId) })
            if (rows.size < page) return
            from = rows.last().position + 1
        }
    }

    // familyChanges: removals and adds into playlist_change. A song the
    // phone holds fewer times than the base comes out of the server's list
    // `read` that many times, last places first (fewer when the list holds
    // fewer); a song the phone holds more times goes on the end, the
    // phone's later copies in its order.
    suspend fun familyChanges(playlistId: String, baseSet: Long, read: Long) {
        eachBase(playlistId, baseSet) { songId, copies ->
            val out = copies - work.phoneCountOf(songId)
            if (out <= 0) return@eachBase
            var left = out
            var before = Long.MAX_VALUE
            while (left > 0) {
                val positions = work.lastPositionsBefore(read, songId, before, minOf(left, page))
                if (positions.isEmpty()) break
                db.withTransaction { work.insertChanges(positions.map { PlaylistChangeEntity(REMOVE_KIND, it, songId) }) }
                left -= positions.size
                before = positions.last()
            }
        }
        var afterSong = ""
        var afterPosition = -1L
        var song: String? = null
        var rank = 0
        var had = 0
        while (true) {
            val rows = work.phoneBySong(afterSong, afterPosition, page)
            if (rows.isEmpty()) break
            val added = ArrayList<Long>()
            for (row in rows) {
                if (row.songId != song) {
                    song = row.songId
                    rank = 0
                    had = work.baseCopies(playlistId, baseSet, row.songId) ?: 0
                }
                if (++rank > had) added += row.position
            }
            if (added.isNotEmpty()) db.withTransaction { work.markAdded(added) }
            if (rows.size < page) break
            afterSong = rows.last().songId
            afterPosition = rows.last().position
        }
        var after = -1L
        while (true) {
            val rows = work.addedPage(after, page)
            if (rows.isEmpty()) return
            db.withTransaction { work.insertChanges(rows.map { PlaylistChangeEntity(ADD_KIND, it.position, it.songId) }) }
            if (rows.size < page) return
            after = rows.last().position
        }
    }

    // songsToRemove's copy numbers: each removal's place among the server
    // list's rows of the same song, counted from 1.
    suspend fun copyNumbers(read: Long) {
        var afterSong = ""
        var afterPosition = -1L
        var song: String? = null
        var seen = 0
        var cursor = -1L
        while (true) {
            val rows = work.changesBySong(REMOVE_KIND, afterSong, afterPosition, page)
            if (rows.isEmpty()) return
            db.withTransaction {
                for (row in rows) {
                    if (row.songId != song) {
                        song = row.songId
                        seen = 0
                        cursor = -1L
                    }
                    // The list's rows of the song since the last removal's place, up to this one.
                    seen += work.entriesBetween(read, row.songId, cursor, row.position)
                    cursor = row.position
                    work.setCopy(REMOVE_KIND, row.position, seen)
                }
            }
            if (rows.size < page) return
            afterSong = rows.last().songId
            afterPosition = rows.last().position
        }
    }

    // familySendLanded, over the changes `done` says (all of them, or the
    // ones not yet sent): every song added is there as many more times as
    // it was added, and every song taken out as many fewer, counting from
    // `before` and the changes sent already. Changes by others meanwhile
    // do not hide it.
    suspend fun landed(before: Long, after: Long, kinds: Set<Int>, positions: Map<Int, Set<Long>>? = null): Boolean {
        for (kind in kinds) {
            val ok = allGroups(kind, positions?.get(kind)) { songId, n ->
                val then = work.entryCountOf(before, songId) - work.doneCountOf(REMOVE_KIND, songId) + work.doneCountOf(ADD_KIND, songId)
                val now = work.entryCountOf(after, songId)
                if (kind == ADD_KIND) now >= then + n else now <= then - n
            }
            if (!ok) return false
        }
        return true
    }

    // Whether a pinned set's songs, as it stood, are a read's, in order: its
    // rows beside the read's entries, page by page, to the first that
    // differs.
    suspend fun sameAsRead(set: PinnedSet, read: Long): Boolean {
        var afterOrd = Long.MIN_VALUE
        var afterKey = Long.MIN_VALUE
        var after = -1L
        while (true) {
            val rows = sets.page(set, afterOrd, afterKey, page)
            val there = work.entryRows(read, after, page)
            if (rows.map { it.trackId } != there.map { it.trackId }) return false
            if (rows.size < page) return true
            afterOrd = rows.last().ord
            afterKey = rows.last().itemKey
            after = there.last().position
        }
    }

    // Whether a read's songs are exactly the SEND list, in order.
    suspend fun sameAsSend(read: Long): Boolean {
        var after = -1L
        var from = 0L
        while (true) {
            val there = work.entrySongs(read, after, page)
            val here = work.sendPage(SEND_SLOT, from, page)
            if (there != here) return false
            if (there.size < page) return true
            after += there.size
            from += here.size
        }
    }

    // A list's values as a call sends them, a page at a time from the
    // table as the call is written.
    fun sendValues(slot: Int, size: Int): PagedValues = TableValues(size) { from, limit -> work.sendPageNow(slot, from, limit) }

    // The base a push keeps when its write-back was refused: the SEND list
    // less the songs the server skipped, written as the PHONE list (empty
    // on a push's replace path), which a base is written from. Answers how
    // many of its ids hold a line break.
    suspend fun pushedLessSkipped(skipped: Collection<String>): Int {
        val out = skipped.toHashSet()
        var odd = 0
        var from = 0L
        var position = 0L
        while (true) {
            val rows = work.sendPage(SEND_SLOT, from, page)
            if (rows.isEmpty()) break
            val kept = rows.filter { it !in out }
            if (kept.isNotEmpty()) {
                db.withTransaction { work.insertSend(kept.map { PlaylistSendEntity(PHONE_SLOT, position++, it) }) }
                odd += kept.count { '\n' in it }
            }
            if (rows.size < page) break
            from += rows.size
        }
        return odd
    }

    // Which of these server ids the library links to a song on this server.
    private suspend fun libraryIdsOf(sourceId: String, serverIds: List<String>): Set<String> {
        val ids = serverIds.distinct()
        if (ids.isEmpty()) return emptySet()
        return ids.chunked(page).flatMap { sources.libraryLinks(sourceId, it) }.mapTo(HashSet()) { it.serverId }
    }

    // A kind of change's values in place order: the song ids, or with
    // `asPlaces` the places, or with `asCopies` "id@copy".
    fun changeValues(kind: Int, size: Int, asPlaces: Boolean = false, asCopies: Boolean = false): PagedValues = object : PagedValues {
        override val size = size

        override fun pages(pageSize: Int): Sequence<List<String>> = sequence {
            var after = -1L
            while (true) {
                val rows = work.changesUpNow(kind, after, pageSize)
                if (rows.isEmpty()) break
                yield(rows.map { if (asPlaces) "${it.position}" else if (asCopies) "${it.songId}@${it.copy}" else it.songId })
                if (rows.size < pageSize) break
                after = rows.last().position
            }
        }
    }

    // Writes a base into base set `baseSet` of a playlist, a page of songs
    // to a transaction: each song of `read`'s entries (or with `read` null,
    // of the PHONE list) once, with how many times it comes, as
    // syncedSongsOf counts them: an empty id is left out, and an id holding
    // a line break counts as its parts (`odd` says whether there are any).
    suspend fun writeBase(playlistId: String, baseSet: Long, read: Long?, odd: Boolean) {
        var from = ""
        var last: String? = null
        while (true) {
            db.withTransaction {
                if (read != null) work.baseFromEntries(playlistId, baseSet, read, from, page) else work.baseFromPhone(playlistId, baseSet, from, page)
            }
            val now = work.lastBaseSong(playlistId, baseSet) ?: break
            if (now == last) break
            last = now
            from = now
        }
        if (!odd) return
        var after = -1L
        while (true) {
            val rows = if (read != null) work.oddEntries(read, after, page) else work.oddSends(PHONE_SLOT, after, page)
            if (rows.isEmpty()) return
            // Each part is two writes, so half a page of parts to a transaction.
            val parts = rows.flatMap { row -> row.songId.split('\n').filter { it.isNotEmpty() } }.groupingBy { it }.eachCount()
            for (chunk in parts.entries.chunked((page / 2).coerceAtLeast(1))) db.withTransaction {
                for ((part, n) in chunk) {
                    work.ensureBase(playlistId, baseSet, part)
                    work.addBase(playlistId, baseSet, part, n)
                }
            }
            if (rows.size < page) return
            after = rows.last().position
        }
    }

    // A copy of base set `from` into base set `to`, a page at a time.
    suspend fun copyBase(playlistId: String, from: Long, to: Long) {
        var after = ""
        var last: String? = null
        while (true) {
            db.withTransaction { work.copyBase(playlistId, from, to, after, page) }
            val now = work.lastBaseSong(playlistId, to) ?: return
            if (now == last) return
            last = now
            after = now
        }
    }

    // Adds `n` copies of each song of a kind of change to base set
    // `baseSet` (or takes them away, with a negative sign), a page of songs
    // to a transaction; only the changes at `positions` when given. Answers
    // how many songs it touched.
    suspend fun changeBase(playlistId: String, baseSet: Long, kind: Int, sign: Int, positions: Set<Long>? = null) {
        val groups = ArrayList<Pair<String, Int>>(page)
        suspend fun flush() {
            if (groups.isEmpty()) return
            db.withTransaction {
                for ((songId, n) in groups) {
                    work.ensureBase(playlistId, baseSet, songId)
                    work.addBase(playlistId, baseSet, songId, sign * n)
                    work.dropEmptyBase(playlistId, baseSet, songId)
                }
            }
            groups.clear()
        }
        // Up to two rows written for each song, so half a page to a transaction.
        allGroups(kind, positions) { songId, n ->
            groups += songId to n
            if (groups.size >= (page / 2).coerceAtLeast(1)) flush()
            true
        }
        flush()
    }

    // How many different songs a kind of change holds, up to `cap`.
    suspend fun distinctChanges(kind: Int, cap: Int): Int {
        var count = 0
        allGroups(kind, null) { _, _ -> ++count <= cap }
        return count
    }

    // Takes a read's entries out, a page at a time.
    suspend fun dropRead(read: Long) {
        do {
            val deleted = db.withTransaction { work.deleteEntries(read, page) }
        } while (deleted == page)
    }

    // Each song of a kind of change with how many there are (only those
    // at `positions`, when given), in song order, until `each` says stop.
    // Answers whether every one passed.
    private suspend fun allGroups(kind: Int, positions: Set<Long>?, each: suspend (String, Int) -> Boolean): Boolean {
        if (positions != null) {
            val counts = LinkedHashMap<String, Int>()
            positions.chunked(page).forEach { chunk -> work.changesAt(kind, chunk).forEach { counts[it] = (counts[it] ?: 0) + 1 } }
            return counts.all { (songId, n) -> each(songId, n) }
        }
        // Each page starts at the last page's last song, read again and skipped.
        var from = ""
        var previous: String? = null
        while (true) {
            val groups = work.changeGroups(kind, from, page)
            val fresh = if (previous != null && groups.firstOrNull()?.songId == previous) groups.drop(1) else groups
            for (group in fresh) if (!each(group.songId, group.n)) return false
            if (groups.size < page || fresh.isEmpty()) return true
            previous = groups.last().songId
            from = groups.last().songId
        }
    }

    private suspend fun eachGroup(kind: Int, each: suspend (String, Int) -> Unit) {
        allGroups(kind, null) { songId, n ->
            each(songId, n)
            true
        }
    }

    // The base's songs of a playlist's base set, in song order.
    private suspend fun eachBase(playlistId: String, baseSet: Long, each: suspend (String, Int) -> Unit) {
        var from = ""
        var previous: String? = null
        while (true) {
            val rows = work.basePage(playlistId, baseSet, from, page)
            val fresh = if (previous != null && rows.firstOrNull()?.songId == previous) rows.drop(1) else rows
            for (row in fresh) each(row.songId, row.copies)
            if (rows.size < page || fresh.isEmpty()) return
            previous = rows.last().songId
            from = rows.last().songId
        }
    }

    private suspend fun eachLocalPage(each: suspend (List<LocalRow>) -> Unit) {
        var afterPosition = Long.MIN_VALUE
        var afterKey = Long.MIN_VALUE
        while (true) {
            val rows = work.localPage(afterPosition, afterKey, page)
            if (rows.isEmpty()) return
            each(rows)
            if (rows.size < page) return
            afterPosition = rows.last().itemPosition
            afterKey = rows.last().itemKey
        }
    }

    // This server's copies of a page's library songs, by song; a find has
    // none. Half a page of songs at a time, since a song may have more than
    // one copy on a server.
    private suspend fun copiesOf(sourceId: String, trackIds: List<String>): Map<String, List<String>> {
        val songs = trackIds.filterNot(::isFind).distinct()
        if (songs.isEmpty()) return emptyMap()
        return songs.chunked((page / 2).coerceAtLeast(1)).flatMap { work.copiesOn(sourceId, it) }.groupBy({ it.mergedId }, { it.nativeId })
    }
}

// Values read from a table a page at a time, from the place after the last
// page's last.
private class TableValues(override val size: Int, private val read: (from: Long, limit: Int) -> List<String>) : PagedValues {
    override fun pages(pageSize: Int): Sequence<List<String>> = sequence {
        var from = 0L
        while (true) {
            val rows = read(from, pageSize)
            if (rows.isEmpty()) break
            yield(rows)
            if (rows.size < pageSize) break
            from += rows.size
        }
    }
}
