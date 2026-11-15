package app.winters.octo.playlists

import androidx.room.withTransaction
import app.winters.octo.catalog.ADD_KIND
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FamilyRights
import app.winters.octo.catalog.Flip
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.PHONE_SLOT
import app.winters.octo.catalog.PinnedSet
import app.winters.octo.catalog.PlaylistEdits
import app.winters.octo.catalog.findId
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.REMOVE_KIND
import app.winters.octo.catalog.SEND_SLOT
import app.winters.octo.catalog.SET_PAGE
import app.winters.octo.catalog.TwinRow
import app.winters.octo.catalog.WholeCheck
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.stableRead
import app.winters.octo.catalog.write
import app.winters.octo.listening.parseServerTime
import app.winters.octo.subsonic.FamilyMe
import app.winters.octo.subsonic.NO_VALUES
import app.winters.octo.subsonic.PLAYLIST_SONGS_PER_CALL
import app.winters.octo.subsonic.PagedValues
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.access
import app.winters.octo.subsonic.isFamilyPlaylist
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

// The work tables a sync step fills; every step empties them as it starts
// and as it ends.
internal val PLAYLIST_WORK_TABLES = listOf(
    "playlist_entry", "playlist_key", "playlist_match", "playlist_lookup",
    "playlist_local", "playlist_next", "playlist_send", "playlist_change",
)

// How many tries a step's write-back makes: the first two free, the last
// inside betweenRebuilds, where no swap can land.
private const val TRIES = 3

// How many changes or base songs one page of a family push takes.
private const val CHANGE_PAGE = 500

// How many skipped songs one read of their rows takes (two lists of them,
// under SQLite's 999 values).
private const val SKIPPED_CHUNK = 400

// How a step's emptying of its work tables is told apart when writes are
// counted, and how many rows each of its transactions deletes.
internal const val CLEAR_WRITE = "playlist work clear"
internal const val CLEAR_ROWS = 500

// Empties a step's work tables CLEAR_ROWS rows a transaction, so a big
// read's work rows never hold the write lock for longer than a page's write.
// A step runs it at its start (rows an earlier step or launch left: the
// library's open deletes none) and at its end.
internal suspend fun clearPlaylistWork(db: CatalogDatabase, tables: List<String> = PLAYLIST_WORK_TABLES) = withContext(WriteLabels.of(CLEAR_WRITE)) {
    for (table in tables) {
        while (true) {
            val gone = db.withTransaction { db.write("DELETE FROM `$table` WHERE rowid IN (SELECT rowid FROM `$table` LIMIT $CLEAR_ROWS)") }
            if (gone < CLEAR_ROWS) break
        }
    }
}

// The signed-in server a sync works with.
internal class SyncServer(
    val client: SubsonicClient,
    val sourceId: String,
    val account: String,
    val formPost: Boolean,
    // Whether family playlist rows say who may rename or delete them and the server takes removals by song.
    val familyRules: Boolean,
    // Whether the server lists octoPlaylistSongs: the listener's own
    // playlists hold songs found online, a push sends the server's own ids
    // for them, every replace carries octoIfStamp and octoOwnIds, removals
    // go by song, and a playlist changed on both sides takes the phone's own
    // changes on top of the server's.
    val playlistSongs: Boolean = false,
)

// Points in a step where a test can step in.
internal open class StepHooks {
    // A page of a write-back's new rows was written; pages count from 1.
    open suspend fun wrotePage(playlistId: String, attempt: Int, page: Int) {}

    // A write-back's new rows and base are written and about to be shown.
    open suspend fun beforeFlip(playlistId: String, attempt: Int) {}

    // A write-back's try ended as `flip` says.
    open suspend fun flipped(playlistId: String, attempt: Int, flip: Flip) {}

    // A change call of a family push went through.
    open suspend fun sent(call: Int) {}

    // A push's songs went to the server; its write-back is next.
    open suspend fun pushed(playlistId: String) {}

    // The base step read a playlist's songs; its base is written next.
    open suspend fun seeding(playlistId: String) {}
}

// The database side of each playlist sync step (PlaylistSync runs them):
// a server playlist read into the work tables and resolved once, the
// phone's rows walked a page at a time, and a write-back as a new item set
// shown in one compare-and-set, so no step holds a playlist in memory and
// no transaction writes more than a page. Every step reads, sends and
// writes what today's step would; see PlaylistPlan.kt for the rules.
//
// A write-back's try opens with the playlist's set mutex and one short
// transaction that pins the set it shows and notes the swap count; its
// flip compares the swap count again, so a swap since means the library's
// ids moved under the rows it wrote, and it tries again from the snapshot
// (never resolution, whose ids the swap followed). The third try runs
// inside betweenRebuilds, where no swap lands, so it ends.
internal class PlaylistSteps(
    private val db: CatalogDatabase,
    private val sets: PlaylistSets,
    private val merge: CatalogMerge,
    private val reads: PlaylistReads,
    private val walks: PlaylistWalks,
    // The listener's family details, read only when a step needs them.
    private val familyPlan: suspend () -> FamilyMe?,
    // A server playlist the phone deleted is no longer waiting to go.
    private val forgetDeleted: suspend (account: String, serverId: String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    // Tells the listener something: songs the server skipped, a playlist
    // kept aside as "(from this phone)".
    private val notice: (String) -> Unit = {},
    // A folder placement changed here and waits to go to the server.
    private val foldersChanged: () -> Unit = {},
) {
    private val user = db.userDao()
    private val work = db.playlistWorkDao()
    private val state = db.workDao()
    private val folderDao = db.playlistFolderDao()
    private val edits = PlaylistEdits(db, sets)
    private val reading = AtomicLong()

    internal var hooks = StepHooks()

    private fun nextRead(): Long = reading.incrementAndGet()

    // Runs one step. `counts` are the song counts the list of playlists
    // gave, by server id, for the room a read asks for.
    suspend fun run(server: SyncServer, step: PlaylistStep, me: FamilyMe? = null, counts: Map<String, Int> = emptyMap()) {
        when (step) {
            is PlaylistStep.Import -> withWork { import(server, step.serverId, step.stamp, me, counts[step.serverId]) }
            is PlaylistStep.Pull -> withWork { pull(server, step, counts[step.serverId]) }
            is PlaylistStep.Push -> user.playlistRow(step.localId)?.let { push(server, it) }
            is PlaylistStep.Create -> user.playlistRow(step.localId)?.let { create(server, it) }
            is PlaylistStep.Remove -> {
                val playlist = user.playlistRow(step.localId) ?: return
                // Changed here after all: it stays, only on the phone.
                if (playlist.edited) sets.unlinkPlaylist(playlist.id) else sets.deletePlaylist(playlist.id, playlist.updatedAt)
            }
            is PlaylistStep.Unlink -> sets.unlinkPlaylist(step.localId)
            is PlaylistStep.DeleteOnServer -> deleteOnServer(server, step.serverId)
            is PlaylistStep.ForgetDeletion -> forgetDeleted(server.account, step.serverId)
        }
    }

    // Sends the phone's songs and name; for PlaylistSync's sends.
    suspend fun push(server: SyncServer, playlist: PlaylistEntity) {
        val serverId = playlist.serverId ?: return
        // A family playlist is never replaced whole: its own changes go on top.
        if (playlist.family) return withWork { pushFamily(server, playlist, serverId) }
        val linked = playlist.linked() ?: return
        val client = server.client
        val listing = client.playlists().firstOrNull { it.id == serverId }
        val listed = listing?.let { ServerPlaylist(it.id, it.name, stampOf(it.changed, it.songCount)) }
        if (listing == null || listed == null || listed.stamp != playlist.serverStamp) {
            // Changed on both sides, on a server listing octoPlaylistSongs:
            // with a base kept, the phone's own changes go on top of the
            // server's; with none (only across the upgrade), the phone's
            // version is kept aside and the playlist takes the server's.
            if (listed != null && server.playlistSongs && playlist.edited && !linked.pullOnly) {
                if (playlist.syncedSongsKept) return withWork { pushFamily(server, playlist, serverId) }
                return fromThisPhone(server, playlist, serverId, listed)
            }
            // When the server changed too since both last agreed, it is
            // settled like a sync would instead.
            planLinked(listed, linked)?.let { run(server, it) }
            return
        }
        withWork { pushSongs(server, playlist, serverId, listing.songCount, listed.stamp) }
    }

    // Makes a phone playlist on the server and links the two.
    suspend fun create(server: SyncServer, playlist: PlaylistEntity) {
        if (playlist.serverId != null || playlist.sourceId != server.sourceId) return
        withWork { createSongs(server, playlist) }
    }

    suspend fun deleteOnServer(server: SyncServer, serverId: String) {
        try {
            server.client.deletePlaylist(serverId)
        } catch (_: SubsonicException.NotFound) {
            // Already gone.
        } catch (e: SubsonicException.Server) {
            // Not allowed (a family playlist for anyone but a manager): never
            // tried again; it comes back to the phone with the next sync.
            if (e.code != 50) throw e
        }
        forgetDeleted(server.account, serverId)
    }

    // Empties the work tables before and after `block`, however it ends.
    private suspend fun <T> withWork(block: suspend () -> T): T {
        clear(PLAYLIST_WORK_TABLES)
        try {
            return block()
        } finally {
            withContext(NonCancellable) { clear(PLAYLIST_WORK_TABLES) }
        }
    }

    private suspend fun clear(tables: List<String>) = clearPlaylistWork(db, tables)

    // Makes a phone copy of a server playlist. A copy left on the phone by
    // an earlier sign-in, with the same name and songs, is linked again
    // instead of made twice.
    private suspend fun import(server: SyncServer, serverId: String, stamp: String, me: FamilyMe?, songCount: Int?) {
        val read = reads.read(server.client, serverId, nextRead(), songCount)
        if (!reads.resolve(read.number)) return
        val full = read.head
        val isFamily = isFamilyPlaylist(full.owner, server.client.username, full.octoFamily)
        val access = full.access(server.client.username, me, server.familyRules)
        // An imported playlist's copy is never a phone playlist's twin.
        val twin = if (full.octoImport) null else twinOf(full.name, read)
        if (twin != null) {
            var linkedTwin = false
            sets.withSet(twin.id) {
                val old = user.playlistRow(twin.id)?.baseSet ?: return@withSet
                val base = sets.newBaseSet(twin.id) ?: return@withSet
                walks.writeBase(twin.id, base, read.number, read.odd > 0)
                val rights = if (isFamily) FamilyRights(access.add, access.remove, access.rename, access.delete) else null
                val linked = sets.flipTwin(twin.id, old, base, serverId, server.sourceId, twin.updatedAt, full.name, stamp, isFamily, rights)
                sets.deleteBaseSet(twin.id, if (linked) old else base)
                linkedTwin = linked
            }
            // Its folder placement on the phone goes to the server with it.
            if (linkedTwin && folderDao.placeOnServer(server.account, twin.id, serverId, now())) foldersChanged()
            return
        }
        val at0 = now()
        val at = parseServerTime(full.changed)?.coerceAtMost(at0) ?: at0
        val row = PlaylistEntity(
            id = UUID.randomUUID().toString(),
            name = full.name,
            createdAt = parseServerTime(full.created)?.coerceAtMost(at) ?: at,
            updatedAt = at,
            serverId = serverId,
            sourceId = server.sourceId,
            syncedAt = at,
            syncedName = full.name,
            serverStamp = stamp,
            family = isFamily,
            familyMayAdd = isFamily && access.add,
            familyMayRemove = isFamily && access.remove,
            familyMayRename = isFamily && access.rename,
            familyMayDelete = isFamily && access.delete,
            upkeep = full.octoUpkeep,
            upkeepKind = full.octoUpkeepKind,
            octoImport = full.octoImport,
        )
        // The playlist shows empty until its songs are written and shown in one step.
        user.insertPlaylist(row)
        sets.withSet(row.id) { writeBack(row, row, read, rows = { walks.entryPages(read.number, it) }, prepare = {}) }
    }

    // A phone playlist of the same name whose songs are the read's, in the
    // order the phone's rows come; the first such in the order playlists
    // were made.
    private suspend fun twinOf(name: String, read: Read): TwinRow? {
        for (twin in work.phoneTwins(name)) {
            val (set, count) = sets.pin(SYNC_OWNER, twin.id) { work.setCount(twin.id, it.itemSet) } ?: continue
            try {
                if (count.toLong() == read.size && walks.sameAsRead(set, read.number)) return twin
            } finally {
                withContext(NonCancellable) { sets.releasePin(SYNC_OWNER, twin.id) }
            }
        }
        return null
    }

    // Takes the server's songs into the phone's copy, keeping songs only on
    // the phone where they were. Skipped if the playlist is edited
    // meanwhile; the next sync settles it.
    private suspend fun pull(server: SyncServer, step: PlaylistStep.Pull, songCount: Int?) {
        val before = user.playlistRow(step.localId) ?: return
        val read = reads.read(server.client, step.serverId, nextRead(), songCount)
        if (!reads.resolve(read.number)) return
        val full = read.head
        val updated = if (step.keepName) {
            // Still counts as changed here until the name reaches the server.
            before.copy(serverStamp = step.stamp)
        } else {
            val at = maxOf(now(), before.updatedAt + 1)
            before.copy(name = full.name, updatedAt = at, syncedAt = at, syncedName = full.name, serverStamp = step.stamp)
        }
        if (!remap(server, before, updated, read, keep = before.family)) return
        if (step.keepName) {
            server.client.updatePlaylist(step.serverId, name = before.name)
            val stamp = listingStamp(server, step.serverId) ?: return
            user.markSynced(before.id, before.updatedAt, before.name, stamp)
        }
    }

    // A linked playlist's songs and name go to the server when they differ
    // from the server's.
    // On a server listing octoPlaylistSongs the replace carries the stamp
    // the phone checked (octoIfStamp) and octoOwnIds, sends the server's own
    // ids for songs found online, and ends with the family write-back. A
    // replace the server refuses because its list changed since
    // (octoConflict) is settled like a playlist changed on both sides.
    private suspend fun pushSongs(server: SyncServer, playlist: PlaylistEntity, serverId: String, songCount: Int, stamp: String) {
        val client = server.client
        val there = reads.read(client, serverId, nextRead(), songCount)
        snapshotInto(server, playlist.id, SEND_SLOT, outside = server.playlistSongs)
        var sent = false
        val skipped = ArrayList<String>()
        if (!walks.sameAsSend(there.number)) {
            try {
                client.replacePlaylistSongs(
                    serverId,
                    walks.sendValues(SEND_SLOT, work.sendCount(SEND_SLOT)),
                    server.formPost,
                    ifStamp = if (server.playlistSongs) changedOf(playlist.serverStamp) else null,
                    ownIds = server.playlistSongs,
                    skipped = { skipped += it },
                )
            } catch (e: SubsonicException.Server) {
                if (!e.conflict) throw e
                val now = user.playlistRow(playlist.id) ?: return
                if (now.syncedSongsKept) pushFamily(server, now, serverId) else fromThisPhone(server, now, serverId, null)
                return
            }
            sent = true
        }
        if (there.head.name != playlist.name) {
            client.updatePlaylist(serverId, name = playlist.name)
            sent = true
        }
        if (!server.playlistSongs) {
            val after = if (sent) listingStamp(server, serverId) ?: return else stamp
            user.markSynced(playlist.id, playlist.updatedAt, playlist.name, after)
            return
        }
        hooks.pushed(playlist.id)
        pushWriteBack(server, playlist, serverId, skipped)
    }

    // Sends a family playlist's own changes since both sides last agreed, on
    // top of everyone else's, as familySend decides: songs taken out here,
    // songs added here at the end, then its name; then the phone takes the
    // server's list and name, as PlaylistSync says. Without a form body a
    // send of more than one call's worth goes a call at a time, removals
    // first from the end of the list, each landed call taken into the base
    // at once, so a send that fails part way is never sent twice.
    // The listener's own playlist (one changed on both sides, on a server
    // listing octoPlaylistSongs) goes the same way: its songs by the
    // server's ids, adds and removals both, removals by song. Songs the
    // server skipped leave the playlist after the write-back.
    private suspend fun pushFamily(server: SyncServer, playlist: PlaylistEntity, serverId: String) {
        val client = server.client
        val current = reads.read(client, serverId, nextRead())
        val there = current.head
        val own = !playlist.family
        val phoneOdd = snapshotInto(server, playlist.id, PHONE_SLOT, outside = own && server.playlistSongs)
        val bySong = server.familyRules || (own && server.playlistSongs)
        val pullOnly = !own && !playlist.familyMayAdd && !playlist.familyMayRemove
        var base = playlist.baseSet
        var held = false
        val skipped = ArrayList<String>()
        // Without a base kept, the base is the phone's songs: nothing is wanted.
        if (!pullOnly) {
            if (playlist.syncedSongsKept) {
                walks.familyChanges(playlist.id, base, current.number)
                var sendRemovals = false
                var after = BaseAfter.Phone
                if (work.changeCount(REMOVE_KIND) > 0) {
                    val cleanOnly: Boolean?
                    val listedCount: Int?
                    if (bySong) {
                        cleanOnly = false
                        listedCount = current.size.toInt()
                    } else {
                        cleanOnly = familyPlan()?.abilities?.cleanOnly
                        listedCount = if (cleanOnly != false) null else client.playlists().firstOrNull { it.id == serverId }?.songCount
                    }
                    when {
                        // A listener who hears only clean songs is shown too few to count from.
                        cleanOnly == true -> do {
                            val dropped = db.withTransaction { work.deleteChanges(REMOVE_KIND, CHANGE_PAGE) }
                        } while (dropped == CHANGE_PAGE)
                        cleanOnly == null || listedCount?.toLong() != current.size -> {
                            held = true
                            after = BaseAfter.BaseAndAdds
                        }
                        else -> sendRemovals = true
                    }
                }
                val removals = if (sendRemovals) work.changeCount(REMOVE_KIND) else 0
                val adds = work.changeCount(ADD_KIND)
                if (removals > 0 || adds > 0) {
                    if (bySong && removals > 0) walks.copyNumbers(current.number)
                    base = send(server, playlist.id, serverId, current, base, removals, adds, after, phoneOdd > 0, bySong, skipped)
                }
            }
            walks.dropRead(current.number)
            if (playlist.name != playlist.syncedName && playlist.name != there.name) {
                try {
                    client.updatePlaylist(serverId, name = playlist.name)
                } catch (e: SubsonicException.Server) {
                    // Only its maker or a family manager may rename it; the
                    // server's name comes back below.
                    if (e.code != 50) throw e
                }
            }
        }
        val stamp = listingStamp(server, serverId) ?: return
        val full = reads.read(client, serverId, nextRead())
        if (!reads.resolve(full.number)) return
        if (held) walks.markHeld(full.number)
        val at = maxOf(now(), playlist.updatedAt + 1)
        val updated = playlist.copy(
            name = full.head.name,
            updatedAt = at,
            // Still changed here while a removal waits, so it is sent again.
            syncedAt = if (!held) at else playlist.syncedAt,
            syncedName = full.head.name,
            serverStamp = stamp,
        )
        remap(server, playlist.copy(baseSet = base), updated, full, keep = true)
        dropSkipped(playlist.id, skipped)
    }

    // Sends a family push's changes and keeps the base after them. Answers
    // the base set the playlist shows then.
    private suspend fun send(
        server: SyncServer,
        playlistId: String,
        serverId: String,
        current: Read,
        startBase: Long,
        removals: Int,
        adds: Int,
        after: BaseAfter,
        phoneOdd: Boolean,
        bySong: Boolean,
        skipped: MutableList<String>,
    ): Long {
        val client = server.client
        if (server.formPost || (removals <= PLAYLIST_SONGS_PER_CALL && adds <= PLAYLIST_SONGS_PER_CALL)) {
            val kinds = if (removals > 0) setOf(ADD_KIND, REMOVE_KIND) else setOf(ADD_KIND)
            sendChecked(server, serverId, current, kinds, null) {
                if (bySong) {
                    client.updatePlaylist(
                        serverId,
                        songIdsToAdd = walks.changeValues(ADD_KIND, adds),
                        songsToRemove = if (removals > 0) walks.changeValues(REMOVE_KIND, removals, asCopies = true) else NO_VALUES,
                        formPost = server.formPost,
                        skipped = { skipped += it },
                    )
                } else {
                    client.updatePlaylist(
                        serverId,
                        songIdsToAdd = walks.changeValues(ADD_KIND, adds),
                        songIndexesToRemove = if (removals > 0) walks.changeValues(REMOVE_KIND, removals, asPlaces = true) else NO_VALUES,
                        formPost = server.formPost,
                        skipped = { skipped += it },
                    )
                }
            }
            hooks.sent(1)
            // Kept before anything else can fail, so nothing is sent twice.
            return if (after == BaseAfter.Phone) phoneBase(playlistId, startBase, phoneOdd) else addsOnBase(playlistId, startBase)
        }
        var calls = 0
        if (removals > 0) {
            var before = Long.MAX_VALUE
            while (true) {
                val chunk = work.changesDown(REMOVE_KIND, before, PLAYLIST_SONGS_PER_CALL).reversed()
                if (chunk.isEmpty()) break
                val positions = chunk.mapTo(LinkedHashSet()) { it.position }
                sendChecked(server, serverId, current, setOf(REMOVE_KIND), mapOf(REMOVE_KIND to positions)) {
                    if (bySong) {
                        client.updatePlaylist(serverId, songIdsToAdd = NO_VALUES, songsToRemove = PagedValues.of(chunk.map { "${it.songId}@${it.copy}" }))
                    } else {
                        client.updatePlaylist(serverId, songIdsToAdd = NO_VALUES, songIndexesToRemove = PagedValues.of(chunk.map { "${it.position}" }))
                    }
                }
                tookIn(playlistId, startBase, REMOVE_KIND, positions)
                hooks.sent(++calls)
                before = chunk.first().position
            }
        }
        var afterAdd = -1L
        while (true) {
            val chunk = work.changesUp(ADD_KIND, afterAdd, PLAYLIST_SONGS_PER_CALL)
            if (chunk.isEmpty()) break
            val positions = chunk.mapTo(LinkedHashSet()) { it.position }
            sendChecked(server, serverId, current, setOf(ADD_KIND), mapOf(ADD_KIND to positions)) {
                client.updatePlaylist(serverId, songIdsToAdd = PagedValues.of(chunk.map { it.songId }), skipped = { skipped += it })
            }
            tookIn(playlistId, startBase, ADD_KIND, positions)
            hooks.sent(++calls)
            afterAdd = chunk.last().position
        }
        return if (after == BaseAfter.Phone) phoneBase(playlistId, startBase, phoneOdd) else startBase
    }

    // One landed call of a send a call at a time: the base takes its
    // changes in, and they are marked sent, in one transaction.
    private suspend fun tookIn(playlistId: String, base: Long, kind: Int, positions: Set<Long>) = sets.withSet(playlistId) {
        db.withTransaction {
            walks.changeBase(playlistId, base, kind, if (kind == REMOVE_KIND) -1 else 1, positions)
            work.markDone(kind, positions.toList())
        }
    }

    // Runs one change call. A call whose answer was lost may still have
    // reached the server; it is checked against the server's list now, so
    // the same songs are never added twice, and taken as sent when it landed.
    private suspend fun sendChecked(
        server: SyncServer,
        serverId: String,
        current: Read,
        kinds: Set<Int>,
        positions: Map<Int, Set<Long>>?,
        call: suspend () -> Unit,
    ) {
        try {
            call()
        } catch (e: SubsonicException.Unreachable) {
            val now = reads.read(server.client, serverId, nextRead())
            val landed = walks.landed(current.number, now.number, kinds, positions)
            walks.dropRead(now.number)
            if (!landed) throw e
        }
    }

    // The base becomes the phone's songs: a new base set, then shown.
    private suspend fun phoneBase(playlistId: String, from: Long, odd: Boolean): Long = sets.withSet(playlistId) {
        val base = sets.newBaseSet(playlistId) ?: return@withSet from
        walks.writeBase(playlistId, base, null, odd)
        if (sets.flipBase(playlistId, from, base)) {
            sets.deleteBaseSet(playlistId, from)
            base
        } else {
            sets.deleteBaseSet(playlistId, base)
            from
        }
    }

    // The base takes the adds on top: in place for half a page of songs or
    // fewer (two rows written for each), in one transaction; otherwise a new
    // base set, the old one's songs copied in first, then shown.
    private suspend fun addsOnBase(playlistId: String, from: Long): Long = sets.withSet(playlistId) {
        if (walks.distinctChanges(ADD_KIND, CHANGE_PAGE / 2 + 1) <= CHANGE_PAGE / 2) {
            db.withTransaction { walks.changeBase(playlistId, from, ADD_KIND, 1) }
            return@withSet from
        }
        val base = sets.newBaseSet(playlistId) ?: return@withSet from
        walks.copyBase(playlistId, from, base)
        walks.changeBase(playlistId, base, ADD_KIND, 1)
        if (sets.flipBase(playlistId, from, base)) {
            sets.deleteBaseSet(playlistId, from)
            base
        } else {
            sets.deleteBaseSet(playlistId, base)
            from
        }
    }

    // Makes the server's copy of a phone playlist and links the two.
    // A folder placement it had on the phone goes to the server in the
    // transaction that links it. On a server listing octoPlaylistSongs its
    // songs found online go by their own ids and it ends with the family
    // write-back, so it keeps a base like every linked playlist.
    private suspend fun createSongs(server: SyncServer, playlist: PlaylistEntity) {
        snapshotInto(server, playlist.id, SEND_SLOT, outside = server.playlistSongs)
        val skipped = ArrayList<String>()
        val made = server.client.createPlaylist(
            playlist.name,
            walks.sendValues(SEND_SLOT, work.sendCount(SEND_SLOT)),
            server.formPost,
            skipped = { skipped += it },
        )
        val serverId = made?.id ?: newestNamed(server, playlist.name) ?: return
        // Linked at once, so a failure from here on can never make it twice.
        val first = made?.let { stampOf(it.changed, it.songCount) }.orEmpty()
        val placed = db.withTransaction {
            user.linkPlaylist(playlist.id, serverId, server.sourceId, playlist.updatedAt, playlist.name, first)
            folderDao.placeOnServer(server.account, playlist.id, serverId, now())
        }
        if (placed) foldersChanged()
        if (server.playlistSongs) {
            val linked = user.playlistRow(playlist.id) ?: return
            pushWriteBack(server, linked, serverId, skipped)
            return
        }
        listingStamp(server, serverId)?.let { user.markSynced(playlist.id, playlist.updatedAt, playlist.name, it) }
    }

    // The phone's rows of a playlist as one state of the library, into
    // playlist_local, and the SEND or PHONE list from them. Answers how
    // many of the list's ids hold a line break. A save part way would show
    // an item on an old id whose copy moved, which the send leaves out, so
    // the snapshot waits for no save to be part way.
    private suspend fun snapshotInto(server: SyncServer, playlistId: String, slot: Int, outside: Boolean = false): Int = stableRead(merge, HoldKind.Walk, {}, WholeCheck(merge)) {
        clear(listOf("playlist_local", "playlist_send"))
        val set = sets.pin(SYNC_OWNER, playlistId)
        try {
            if (set != null) walks.snapshot(set)
        } finally {
            if (set != null) withContext(NonCancellable) { sets.releasePin(SYNC_OWNER, playlistId) }
        }
        walks.sends(server.sourceId, slot, outside)
    }

    // A write-back of `expected`'s rows from `read` (pull and a family
    // push's last step): each try snapshots the rows the playlist shows,
    // merges the songs only on the phone into the read's, keeps keys with
    // `keep`, writes the new set and base, and shows them. Answers whether
    // they were shown.
    private suspend fun remap(server: SyncServer, expected: PlaylistEntity, updated: PlaylistEntity, read: Read, keep: Boolean): Boolean =
        sets.withSet(expected.id) {
            writeBack(expected, updated, read, rows = { walks.nextPages(keep, it) }) { set ->
                walks.snapshot(set)
                walks.anchor(read.number, server.sourceId)
                walks.merge(read.number)
                if (keep) walks.keepKeys()
            }
        }

    // The tries of one write-back, under the playlist's set mutex: a try
    // that a swap refused runs again, one the playlist's own change refused
    // ends it (the next sync settles it). The old sets go once the new ones
    // show. Answers whether they show. A save part way would show an item
    // on an old id whose copy moved, so each try waits for no save to be
    // part way and its flip is refused while one is; the last, inside the
    // merge lock, goes again only when it found one part way as it entered.
    private suspend fun writeBack(
        expected: PlaylistEntity,
        updated: PlaylistEntity,
        read: Read,
        rows: suspend (suspend (List<NewItem>) -> Unit) -> Unit,
        prepare: suspend (PinnedSet) -> Unit,
    ): Boolean {
        for (attempt in 1..TRIES) {
            val flip = if (attempt < TRIES) {
                merge.awaitWhole()
                tryOnce(expected, updated, read, attempt, rows, prepare)
            } else {
                var last: Flip? = null
                while (last == null) {
                    merge.awaitWhole()
                    last = merge.betweenRebuilds(HoldKind.Walk) { if (merge.whole) tryOnce(expected, updated, read, attempt, rows, prepare) else null }
                }
                last
            }
            hooks.flipped(expected.id, attempt, flip)
            when (flip) {
                Flip.Shown -> {
                    sets.deleteSet(expected.id, expected.itemSet)
                    sets.deleteBaseSet(expected.id, expected.baseSet)
                    return true
                }
                Flip.Changed -> return false
                Flip.Swapped -> Unit
            }
        }
        return false
    }

    // One try of a write-back: the opening pins the set the playlist shows
    // and notes the swap count in one transaction, `prepare` fills the work
    // tables from it, the rows and base go into new sets, and the flip
    // shows them. The new sets go again unless shown.
    private suspend fun tryOnce(
        expected: PlaylistEntity,
        updated: PlaylistEntity,
        read: Read,
        attempt: Int,
        rows: suspend (suspend (List<NewItem>) -> Unit) -> Unit,
        prepare: suspend (PinnedSet) -> Unit,
    ): Flip {
        clear(listOf("playlist_local", "playlist_next"))
        val (set, baseline) = sets.pin(SYNC_OWNER, expected.id) { state.swapCount() } ?: return Flip.Changed
        var itemSet: Long? = null
        var baseSet: Long? = null
        var flip = Flip.Changed
        try {
            if (set.itemSet != expected.itemSet) return Flip.Changed
            prepare(set)
            val items = sets.newItemSet(expected.id) ?: return Flip.Changed
            itemSet = items
            val bases = sets.newBaseSet(expected.id) ?: return Flip.Changed
            baseSet = bases
            var rank = 0L
            var pages = 0
            rows { page ->
                for (chunk in page.chunked(SET_PAGE)) {
                    sets.writeRows(expected.id, items, rank, chunk)
                    rank += chunk.size
                    hooks.wrotePage(expected.id, attempt, ++pages)
                }
            }
            walks.writeBase(expected.id, bases, read.number, read.odd > 0)
            hooks.beforeFlip(expected.id, attempt)
            // A save part way refuses the flip as a swap would.
            flip = sets.flipSync(expected, updated, items, bases, baseline) { if (merge.whole) state.swapCount() else -1L }
            return flip
        } finally {
            withContext(NonCancellable) {
                sets.releasePin(SYNC_OWNER, expected.id)
                if (flip != Flip.Shown) {
                    itemSet?.let { sets.deleteSet(expected.id, it) }
                    baseSet?.let { sets.deleteBaseSet(expected.id, it) }
                }
            }
        }
    }

    // The one-time base step for a server listing octoPlaylistSongs, the
    // first thing every holder of the sync lock runs (SyncLock): until the
    // server's mark is written, each of the listener's own linked playlists
    // of this server that is unedited since its last sync gets a base, the
    // songs the phone would send for it under the outside rule. A row edited
    // or unlinked while the step runs keeps no base (its flip is refused).
    // The mark is written after every row, so a failure part way (a full
    // disk) leaves the step for the next holder.
    suspend fun ensureBase(server: SyncServer) {
        if (!server.playlistSongs || user.baseSeeded(server.sourceId)) return
        withWork {
            for (row in user.serverPlaylists(server.sourceId)) {
                if (row.family || row.octoImport || row.serverId == null || row.syncedAt != row.updatedAt) continue
                seedBase(server, row)
            }
        }
        user.markBaseSeeded(server.sourceId, now())
    }

    private suspend fun seedBase(server: SyncServer, row: PlaylistEntity) {
        val odd = snapshotInto(server, row.id, PHONE_SLOT, outside = true)
        hooks.seeding(row.id)
        sets.withSet(row.id) {
            val base = sets.newBaseSet(row.id) ?: return@withSet
            walks.writeBase(row.id, base, null, odd > 0)
            if (sets.flipSeeded(row, base)) sets.deleteBaseSet(row.id, row.baseSet) else sets.deleteBaseSet(row.id, base)
        }
    }

    // The family write-back after a push: the server's list read after it
    // becomes the playlist's songs (songs only on the phone kept where they
    // were) and its base, in one step, refused when the playlist was
    // changed here during the push. Then only the base moves, to the songs
    // pushed less those the server skipped, and the server's stamp stays as
    // it was, so the change made meanwhile goes on top of the server's list
    // next time, keeping anything another device added. Songs the server
    // skipped leave the playlist either way.
    private suspend fun pushWriteBack(server: SyncServer, playlist: PlaylistEntity, serverId: String, skipped: List<String>) {
        val stamp = listingStamp(server, serverId) ?: return
        val full = reads.read(server.client, serverId, nextRead())
        if (!reads.resolve(full.number)) return
        val at = maxOf(now(), playlist.updatedAt + 1)
        val updated = playlist.copy(name = full.head.name, updatedAt = at, syncedAt = at, syncedName = full.head.name, serverStamp = stamp)
        if (!remap(server, playlist, updated, full, keep = true)) {
            val odd = walks.pushedLessSkipped(skipped)
            phoneBase(playlist.id, playlist.baseSet, odd > 0)
        }
        dropSkipped(playlist.id, skipped)
    }

    // Songs the server skipped (octoSkipped) leave the playlist, and the
    // listener hears it once; nothing when it skipped none.
    private suspend fun dropSkipped(playlistId: String, skipped: List<String>) {
        if (skipped.isEmpty()) return
        val keys = skipped.distinct().chunked(SKIPPED_CHUNK).flatMap { ids -> user.rowKeysOf(playlistId, ids, ids.map(::findId)) }
        if (keys.isNotEmpty()) edits.removeAll(playlistId, keys.distinct(), now())
        notice(PlaylistFolderWords.SKIPPED_SONGS)
    }

    // A playlist changed on both sides with no base kept (only across the
    // upgrade): the phone's version is kept as a playlist only on the
    // phone, "<name> (from this phone)", the linked one takes the server's
    // list, and the listener is told. A copy holding songs found online goes
    // to the server at once, so they keep playing.
    private suspend fun fromThisPhone(server: SyncServer, playlist: PlaylistEntity, serverId: String, known: ServerPlaylist?) {
        val listed = known ?: server.client.playlists().firstOrNull { it.id == serverId }?.let { ServerPlaylist(it.id, it.name, stampOf(it.changed, it.songCount)) }
            ?: return
        val copyId = copyAside(playlist, PlaylistFolderWords.fromThisPhone(playlist.name)) ?: return
        notice(PlaylistFolderWords.bothChanged(playlist.name))
        run(server, PlaylistStep.Pull(playlist.id, serverId, listed.stamp, keepName = false))
        if (user.holdsFindNow(copyId)) {
            user.keepOnServer(copyId, server.sourceId)
            user.playlistRow(copyId)?.let { create(server, it) }
        }
    }

    // A new playlist only on the phone holding a playlist's rows as they
    // are, each with the server id it came from. Answers its id.
    private suspend fun copyAside(playlist: PlaylistEntity, name: String): String? {
        val pinned = sets.pin(SYNC_OWNER, playlist.id) ?: return null
        val id = UUID.randomUUID().toString()
        val at = now()
        val made = try {
            sets.create(PlaylistEntity(id, name, createdAt = at, updatedAt = at)) { emit ->
                sets.walk(pinned) { page -> emit(page.map { NewItem(it.trackId, it.relinkKey, it.serverSongId) }) }
            }
        } finally {
            withContext(NonCancellable) { sets.releasePin(SYNC_OWNER, playlist.id) }
        }
        return id.takeIf { made }
    }

    // The playlist's stamp as the server lists it now, or null when the
    // server no longer lists it. Always from the list of playlists, the
    // same place a sync compares with.
    private suspend fun listingStamp(server: SyncServer, serverId: String): String? =
        server.client.playlists().firstOrNull { it.id == serverId }?.let { stampOf(it.changed, it.songCount) }

    // For a server too old to answer with the playlist it made: the newest
    // one of the user's own by that name that no phone playlist is linked to
    // (never a family playlist).
    private suspend fun newestNamed(server: SyncServer, name: String): String? {
        val linked = user.serverPlaylists(server.sourceId).mapNotNullTo(HashSet()) { it.serverId }
        return newestNamedCandidates(server.client.playlists(), name, server.client.username, linked)
            .maxByOrNull { it.created.orEmpty() }?.id
    }
}

// `ownRules`: the server lists octoPlaylistSongs, so the listener's own
// playlist changed on both sides is pushed with the family rule.
internal fun PlaylistEntity.linked(ownRules: Boolean = false): LinkedPlaylist? =
    serverId?.let {
        LinkedPlaylist(
            id, it, name, edited, syncedName, serverStamp, family,
            pullOnly = octoImport || (family && !familyMayAdd && !familyMayRemove),
            ownRules = ownRules && !family && !octoImport,
        )
    }
