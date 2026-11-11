package app.winters.octo.offline

import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.PinnedSet
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SetItem
import app.winters.octo.catalog.SongIds
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.listening.Paged
import app.winters.octo.playback.compareQuality
import app.winters.octo.ui.paging.SqlOrder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// How many songs one page of the rules walk, or of an album's or a
// playlist's download, holds. Each stream of the walk reads this many rows
// at a time too.
internal const val RULES_PAGE = 500

// Walks of the rules made before the last, which no swap can cross.
private const val FREE_WALKS = 2

// How the writes here are told apart when writes are timed.
private const val RULES_WRITE = "rules page"
private const val DOWNLOAD_WRITE = "download page"
private const val AFTER_SYNC_WRITE = "after-sync page"

// Which stream of the rules walk just read a page.
enum class RulesStream { Held, Liked, Playlist }

// Points a test can stop the walks here at. Each does nothing in the app.
internal open class RulesHooks {
    // A walk's opening committed: `attempt` counts from 1.
    open suspend fun opened(attempt: Int) {}

    // After each page one of the walk's streams read, outside every lock.
    open suspend fun read(stream: RulesStream) {}

    // A page of songs is gathered and about to be written, outside every
    // lock; pages count from 1 in each walk.
    open suspend fun applying(page: Int) {}

    // Inside a page's transaction, under both locks, once the swap count
    // was found unmoved.
    open suspend fun writing(page: Int) {}

    // A page was written, or, when a swap ran since the walk opened, not.
    open suspend fun applied(page: Int, written: Boolean) {}

    // A page of an album's or a playlist's songs is about to download.
    open suspend fun downloading(page: Int) {}

    // Inside a page of a download or a put back, holding its Read hold of
    // the merge lock and then `lock`, before its transaction.
    open suspend fun downloadLocked() {}
}

// The download table walked a page at a time: the rules (Liked songs, and
// the playlists kept downloaded) brought in step with what is kept, an
// album's or a playlist's songs downloaded, downloads looked over after a
// sync, and downloads let go of. No step reads more than a page of any
// table at once.
//
// The rules walk reads its streams (the songs the server in use's
// downloads hold, the liked songs, each kept playlist's songs) with no
// lock, each in song id order, and merges them by id in the database's own
// order. A song whose downloads today's rule would change joins a page.
// Each page is written in one transaction, under a shared hold of the
// merge lock and then `lock`, which first checks that no swap ran since
// the walk opened and then reads the page's downloads again, so a change
// a tap made meanwhile is never undone. A swap since the opening starts
// the walk again from its opening; after two walks the last holds the
// merge lock for its whole walk and `lock` a page at a time, so it ends,
// and a tap waits for one page at most.
//
// A download asked for by hand, and its removal, name songs as they are now
// (SongIds): each page takes its Read hold of the merge lock first, reads
// its songs' ids now there, then takes `lock` and writes, so a save's group
// never lands between the read and the write. The hold always comes before
// `lock`, as the walk's pages take them: a page that held `lock` and then
// asked for its hold could wait behind a group that waits for a walk page,
// which waits for `lock`. A song's downloads still on an old id renamed to
// it by follow (the next walk's opening moves them) count as its own.
internal class OfflineRules(
    private val db: CatalogDatabase,
    private val merge: CatalogMerge,
    // The download store's lock: every change of what is kept takes it.
    private val lock: Mutex,
    private val book: DownloadBook,
    private val owners: WorkOwners,
    private val serverInUse: () -> String?,
    // Hears each download deleted once its delete has committed, to stop it
    // if it is running and delete its file.
    private val dropped: (DownloadEntity) -> Unit = {},
    // Hears that new downloads were held back: the account may keep no
    // offline copies from that server.
    private val heldBack: () -> Unit = {},
    // The playlists' item sets, for pinning a playlist's set while it is walked.
    private val sets: PlaylistSets,
    // Lets a walk's pin of a playlist's item set go.
    private val releasePin: suspend (owner: String, playlistId: String) -> Unit = sets::releasePin,
    private val page: Int = RULES_PAGE,
) {
    private val dao = db.downloadDao()
    private val work = db.workDao()
    private val user = db.userDao()
    internal val ids = SongIds(db, merge)

    internal var hooks = RulesHooks()

    // What a walk's opening noted: the swap count then, and the item set
    // pinned for each kept playlist still shown.
    private class Opening(val baseline: Long, val sets: List<PlaylistSet>)

    // A song today's rule may change, with the rules that want it.
    private class RuleSong(val trackId: String, val wanted: Set<String>)

    // What one page's transaction did: the downloads it deleted, whether a
    // new one was held back, and the finished downloads new ones replaced.
    private class Written(val gone: List<DownloadEntity>, val heldBack: Boolean, val replaced: List<DownloadEntity>)

    // What an add did: whether a new download was held back, and the
    // finished downloads under the same keys that new ones replaced.
    private class Added(val heldBack: Boolean, val replaced: List<DownloadEntity>)

    // Brings the server in use's downloads in step with the rules: Liked
    // songs while `keepLiked`, and the songs of each playlist in
    // `playlists`. New downloads never come from `refused`. A save part way
    // shows a song's copy moved before its like, so every walk waits for no
    // save to be part way, and one that a save began or ended across runs
    // again; the last holds the merge lock for its whole walk and goes
    // again only when it found a save part way as it entered.
    suspend fun walk(keepLiked: Boolean, playlists: Set<String>, refused: String?) {
        repeat(FREE_WALKS) {
            merge.awaitWhole()
            val before = merge.wholeStamp()
            if (walkOnce(it + 1, keepLiked, playlists, refused) && merge.wholeStamp() == before) return
        }
        while (true) {
            merge.awaitWhole()
            val done = merge.betweenRebuilds(HoldKind.Walk) { merge.whole && walkOnce(FREE_WALKS + 1, keepLiked, playlists, refused) }
            if (done) return
        }
    }

    // One walk from its opening. Answers false when a swap since the
    // opening stopped it. The item sets it pinned are let go of at its end,
    // however it ends.
    private suspend fun walkOnce(attempt: Int, keepLiked: Boolean, playlists: Set<String>, refused: String?): Boolean {
        val owner = owners.walk()
        val pinned = ArrayList<String>()
        try {
            val source = serverInUse()
            val opening = open(owner, playlists, pinned)
            hooks.opened(attempt)
            return walkFrom(opening, source, keepLiked, refused)
        } finally {
            if (pinned.isNotEmpty()) withContext(NonCancellable) { pinned.forEach { releasePin(owner, it) } }
        }
    }

    // The opening, in one transaction: downloads follow their server songs
    // to the library songs they are now, the swap count is noted, and each
    // kept playlist's item set is pinned, noted in `pinned` as it is.
    private suspend fun open(owner: String, playlists: Set<String>, pinned: MutableList<String>): Opening =
        merge.betweenRebuilds(HoldKind.Page) {
            lock.withLock {
                withContext(WriteLabels.of(RULES_WRITE)) {
                    book.transaction(db) {
                        book.relink()
                        val baseline = work.swapCount()
                        val sets = playlists.toList().chunked(LOOKUP_CHUNK).flatMap { dao.playlistSets(it) }
                        for (set in sets) {
                            user.pinPlaylistSet(owner, set.id, set.itemSet)
                            pinned += set.id
                        }
                        Opening(baseline, sets)
                    }
                }
            }
        }

    // Merges the streams by song id, smallest first in the database's
    // order, gathering the songs today's rule would change into pages.
    // Holds one page of each stream and one page of songs.
    private suspend fun walkFrom(opening: Opening, source: String?, keepLiked: Boolean, refused: String?): Boolean {
        val held = Paged<HeldSong>(page) { last ->
            if (source == null) emptyList() else dao.heldPage(source, last?.trackId ?: "", page).also { hooks.read(RulesStream.Held) }
        }
        val liked = if (!keepLiked) null else Paged<String>(page) { last -> dao.likedPage(last ?: "", page).also { hooks.read(RulesStream.Liked) } }
        val sets = opening.sets.map { set ->
            Reasons.playlist(set.id) to Paged<String>(page) { last ->
                dao.setSongPage(set.id, set.itemSet, last ?: "", page).also { hooks.read(RulesStream.Playlist) }
            }
        }
        val songs = ArrayList<RuleSong>(page)
        var pages = 0
        while (true) {
            val heads = ArrayList<String>(sets.size + 2)
            held.peek()?.let { heads += it.trackId }
            liked?.peek()?.let { heads += it }
            for ((_, stream) in sets) stream.peek()?.let { heads += it }
            val id = heads.minWithOrNull(SqlOrder::text) ?: break
            val reasons = if (held.peek()?.trackId == id) Reasons.parse(held.take().reasons) else null
            val wanted = HashSet<String>()
            if (liked != null && liked.peek() == id) {
                liked.take()
                wanted += Reasons.LIKED
            }
            for ((reason, stream) in sets) {
                if (stream.peek() == id) {
                    stream.take()
                    wanted += reason
                }
            }
            if (changes(id, reasons, wanted)) songs += RuleSong(id, wanted)
            if (songs.size >= page) {
                if (!write(++pages, songs, opening.baseline, source, refused)) return false
                songs.clear()
            }
        }
        return songs.isEmpty() || write(++pages, songs, opening.baseline, source, refused)
    }

    // Whether today's rule (planDownloads) does anything with one song,
    // taking it as one that can be downloaded: a page asks that of the
    // songs it adds.
    private fun changes(trackId: String, held: Set<String>?, wanted: Set<String>): Boolean = !planDownloads(
        held = if (held == null) emptyMap() else mapOf(trackId to held),
        wanted = if (wanted.isEmpty()) emptyMap() else mapOf(trackId to wanted),
        downloadable = setOf(trackId),
    ).isEmpty

    // Writes one page of the walk by today's rule, over the page's
    // downloads as they are now. Answers false, writing nothing, when a
    // swap ran since the walk opened.
    private suspend fun write(number: Int, songs: List<RuleSong>, baseline: Long, source: String?, refused: String?): Boolean {
        hooks.applying(number)
        val written = merge.betweenRebuilds(HoldKind.Page) {
            lock.withLock {
                withContext(WriteLabels.of(RULES_WRITE)) {
                    book.transaction(db) {
                        if (work.swapCount() != baseline || !merge.whole) return@transaction null
                        hooks.writing(number)
                        val held = reasonsOf(songs.map { it.trackId }, source)
                        val wanted = songs.filter { it.wanted.isNotEmpty() }.associate { it.trackId to it.wanted }
                        val fresh = wanted.keys.filter { it !in held }
                        val downloadable = fresh.chunked(LOOKUP_CHUNK).flatMapTo(HashSet()) { dao.downloadable(it) }
                        val plan = planDownloads(held, wanted, downloadable)
                        val gone = plan.remove.flatMap { deleteSong(it) }
                        plan.change.forEach { (trackId, reasons) -> setReasons(trackId, reasons) }
                        val added = add(plan.add, refused)
                        Written(gone, added.heldBack, added.replaced)
                    }
                }
            }
        }
        if (written != null) after(written)
        hooks.applied(number, written != null)
        return written != null
    }

    private fun after(written: Written) {
        written.gone.forEach(dropped)
        replacedFiles(written.replaced)
        if (written.heldBack) heldBack()
    }

    // The files of finished downloads a new download took the place of,
    // once that has committed: the new one downloads its own.
    private fun replacedFiles(rows: List<DownloadEntity>) {
        rows.forEach { if (it.path.isNotEmpty()) File(it.path).delete() }
    }

    // The reasons the server in use's downloads of these songs are kept
    // for, by song, as they are now. Read under `lock`.
    suspend fun reasonsOf(trackIds: Collection<String>, source: String? = serverInUse()): Map<String, Set<String>> {
        if (source == null) return emptyMap()
        val held = HashMap<String, MutableSet<String>>()
        trackIds.distinct().chunked(LOOKUP_CHUNK).forEach { ids ->
            dao.reasonsOf(source, ids).forEach { held.getOrPut(it.trackId, ::HashSet) += Reasons.parse(it.reason) }
        }
        return held
    }

    // Every download of a song, from any server, is kept for `reasons`.
    suspend fun setReasons(trackId: String, reasons: Set<String>) {
        dao.forTrack(trackId).forEach { book.setReason(it.sourceId, it.serverId, Reasons.join(reasons)) }
    }

    // Deletes every download of a song, from any server. Answers the rows,
    // whose files go once the delete has committed.
    private suspend fun deleteSong(trackId: String): List<DownloadEntity> = dao.forTrack(trackId).onEach { book.delete(it) }

    // Queues new downloads, each from the best copy on the server. Answers
    // whether any was held back for coming from `refused`, and the finished
    // downloads that new ones took the place of under the same key (another
    // server's copy kept from before, picked again), whose files go once
    // the caller's transaction has committed.
    private suspend fun add(wanted: Map<String, Set<String>>, refused: String?): Added {
        if (wanted.isEmpty()) return Added(false, emptyList())
        val downloadable = wanted.keys.chunked(LOOKUP_CHUNK).flatMap { dao.downloadable(it) }.toSet()
        val copies = downloadable.toList().chunked(LOOKUP_CHUNK).flatMap { dao.serverCopies(it) }.groupBy { it.mergedId }
        val now = System.currentTimeMillis()
        val rows = wanted.filterKeys { it in downloadable }.mapNotNull { (trackId, reasons) ->
            val copy = copies[trackId]?.reduce { best, next -> if (compareQuality(next, best) > 0) next else best } ?: return@mapNotNull null
            DownloadEntity(
                trackId = trackId,
                sourceId = copy.sourceId,
                serverId = copy.nativeId,
                path = "",
                sizeBytes = 0,
                format = null,
                state = DownloadStatus.Queued,
                progress = 0f,
                addedAt = now,
                reason = Reasons.join(reasons),
                title = copy.title,
                artist = copy.artist,
            )
        }
        val (kept, held) = rows.partition { it.sourceId != refused }
        val replaced = kept.mapNotNull { row -> dao.get(row.sourceId, row.serverId)?.takeIf { it.state == DownloadStatus.Done && it.path.isNotEmpty() } }
        kept.chunked(500).forEach { book.upsert(it) }
        return Added(held.isNotEmpty(), replaced)
    }

    // Downloads these library songs, the ones that can be: on a server and
    // not on the phone. A song already kept is now asked for by hand too.
    suspend fun download(trackIds: List<String>, refused: String?) {
        trackIds.distinct().chunked(page).forEach { downloadPage(it, refused) }
    }

    // One page of a download: one Read hold, then one hold of `lock`, one
    // transaction. The songs go on their ids now, library songs only with
    // `libraryOnly`; a song already kept under any of its ids is now asked
    // for by hand too.
    private suspend fun downloadPage(trackIds: List<String>, refused: String?, libraryOnly: Boolean = false) {
        val added = merge.betweenRebuilds(HoldKind.Read) {
            val now = ids.songs(trackIds).distinct()
            val songs = if (libraryOnly) dao.libraryAmong(now).toSet().let { library -> now.filter { it in library } } else now
            val all = ids.copyIdsOf(songs)
            lock.withLock {
                hooks.downloadLocked()
                withContext(WriteLabels.of(DOWNLOAD_WRITE)) {
                    book.transaction(db) {
                        val kept = reasonsOf(all.values.flatten())
                        val (known, fresh) = songs.partition { song -> all.getValue(song).any { it in kept } }
                        for (song in known) {
                            val held = all.getValue(song).filter { it in kept }
                            val reasons = held.flatMapTo(HashSet()) { kept.getValue(it) }
                            if (Reasons.MANUAL !in reasons) held.forEach { setReasons(it, reasons + Reasons.MANUAL) }
                        }
                        add(fresh.associateWith { setOf(Reasons.MANUAL) }, refused)
                    }
                }
            }
        }
        replacedFiles(added.replaced)
        if (added.heldBack) heldBack()
    }

    // Downloads an album's songs a page at a time in the album's order, so
    // an album as big as the library is never read whole. Each page is
    // read by the album's id now, in one Read hold; an album a save renamed
    // meanwhile is read again from its start (a song already asked for
    // stays as it is).
    suspend fun downloadAlbum(albumId: String, refused: String?) {
        var album = albumId
        var after: AlbumSong? = null
        var number = 0
        while (true) {
            val songs = merge.betweenRebuilds(HoldKind.Read) {
                val now = ids.album(album)
                if (now != album) {
                    album = now
                    after = null
                }
                dao.albumSongPage(album, after?.albumOrder ?: Int.MIN_VALUE, after?.id ?: "", page)
            }
            if (songs.isEmpty()) return
            hooks.downloading(++number)
            downloadPage(songs.map { it.id }, refused)
            if (songs.size < page) return
            after = songs.last()
        }
    }

    // Downloads a playlist's library songs a page at a time in play order,
    // from the item set it shows as this starts, pinned until the end and
    // read as it stood then, so neither a new list of songs written
    // meanwhile nor a move or removal of a row moves this off the songs it
    // began with.
    suspend fun downloadPlaylist(playlistId: String, refused: String?) {
        val owner = owners.walk()
        var pinned = false
        try {
            val set = sets.pin(owner, playlistId)?.also { pinned = true } ?: return
            var afterOrd = Long.MIN_VALUE
            var afterKey = Long.MIN_VALUE
            var number = 0
            while (true) {
                val rows = setPage(set, afterOrd, afterKey)
                if (rows.isEmpty()) return
                hooks.downloading(++number)
                downloadPage(rows.map { it.trackId }.distinct(), refused, libraryOnly = true)
                if (rows.size < page) return
                afterOrd = rows.last().ord
                afterKey = rows.last().itemKey
            }
        } finally {
            if (pinned) withContext(NonCancellable) { releasePin(owner, playlistId) }
        }
    }

    // A page of a pinned item set in play order, as it stood when pinned,
    // from just past (`afterOrd`, `afterKey`).
    private suspend fun setPage(set: PinnedSet, afterOrd: Long, afterKey: Long): List<SetItem> = sets.page(set, afterOrd, afterKey, page)

    // Takes the hand-asked reason off a song's downloads. When no other
    // reason keeps the song, `goes` takes its downloads away instead, given
    // the ids they are under (the song's now, and old ids renamed to it by
    // follow), and its answer is the answer; null otherwise, or when nothing
    // kept it. One Read hold, then `lock`.
    suspend fun <T> unkeep(trackId: String, goes: suspend (List<String>) -> T): T? = merge.betweenRebuilds(HoldKind.Read) {
        val all = ids.copyIds(ids.song(trackId))
        lock.withLock {
            val kept = reasonsOf(all)
            val held = all.filter { it in kept }
            if (held.isEmpty()) return@withLock null
            val left = held.flatMapTo(HashSet()) { kept.getValue(it) } - Reasons.MANUAL
            if (left.isEmpty()) {
                goes(held)
            } else {
                held.forEach { setReasons(it, left) }
                null
            }
        }
    }

    // Puts back downloads a removal took (its Undo), each on the song its
    // id names now: renamed by follow only, as a row keyed by a server's
    // copy follows its song. Each row whose copy was not asked for again
    // meanwhile goes back as `back` makes it, under the lock (its file moved
    // back, or waiting to download again), and each song keeps its reasons
    // and is asked for by hand again. One Read hold, then `lock`, one
    // transaction, as a download page writes.
    suspend fun putBack(rows: List<DownloadEntity>, back: (DownloadEntity) -> DownloadEntity) {
        if (rows.isEmpty()) return
        merge.betweenRebuilds(HoldKind.Read) {
            val now = rows.map { it.trackId }.let { songs -> songs.zip(ids.followed(songs)).toMap() }
            lock.withLock {
                hooks.downloadLocked()
                val returned = rows.filter { dao.get(it.sourceId, it.serverId) == null }.map { back(it).copy(trackId = now.getValue(it.trackId)) }
                withContext(WriteLabels.of(DOWNLOAD_WRITE)) {
                    book.transaction(db) {
                        book.upsert(returned)
                        val songs = now.values.distinct()
                        val held = reasonsOf(songs)
                        songs.forEach { song -> held[song]?.let { setReasons(song, it + Reasons.MANUAL) } }
                    }
                }
            }
        }
    }

    // A download whose server file is now another one (the server swapped
    // it for a better copy, say, under the same id) downloads again; one
    // made before the server's size was noted has it noted. A page at a
    // time, each between rebuilds and under `lock`, in one transaction.
    suspend fun refreshSwapped() {
        val source = serverInUse() ?: return
        var after = ""
        while (true) {
            val files = merge.betweenRebuilds(HoldKind.Page) {
                lock.withLock {
                    withContext(WriteLabels.of(AFTER_SYNC_WRITE)) {
                        book.transaction(db) {
                            dao.doneFilesPage(source, after, page).onEach { file ->
                                when (checkDownload(file)) {
                                    KeptCopyCheck.Keep -> Unit
                                    KeptCopyCheck.Note -> file.serverSize?.let { book.noteFileSize(file.sourceId, file.serverId, it) }
                                    KeptCopyCheck.Refetch -> book.refetch(file.sourceId, file.serverId)
                                }
                            }
                        }
                    }
                }
            }
            if (files.size < page) return
            after = files.last().serverId
        }
    }

    // Deletes every download there is now, a page per hold of `lock`; one
    // asked for while this runs is written after the last noted here, and
    // stays.
    suspend fun removeAll() {
        val last = dao.lastWritten() ?: return
        var after = Long.MIN_VALUE
        while (true) {
            val rows = lock.withLock {
                withContext(WriteLabels.of(DOWNLOAD_WRITE)) {
                    book.transaction(db) { dao.numberedPage(after, last, page).onEach { book.delete(it.row) } }
                }
            }
            rows.forEach { dropped(it.row) }
            if (rows.size < page) return
            after = rows.last().written
        }
    }

    // Deletes a server's downloads, a page per hold of `lock`.
    suspend fun forgetSource(sourceId: String) {
        var after = ""
        while (true) {
            val rows = lock.withLock {
                withContext(WriteLabels.of(DOWNLOAD_WRITE)) {
                    book.transaction(db) { dao.sourcePage(sourceId, after, page).onEach { book.delete(it) } }
                }
            }
            rows.forEach(dropped)
            if (rows.size < page) return
            after = rows.last().serverId
        }
    }
}
