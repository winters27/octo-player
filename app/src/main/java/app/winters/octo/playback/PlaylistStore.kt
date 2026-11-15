package app.winters.octo.playback

import app.winters.octo.catalog.AddedRows
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.PlaylistEdits
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistItemEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.SongIds
import app.winters.octo.catalog.SongToAdd
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.playlists.PlaylistSync
import app.winters.octo.catalog.isFind
import app.winters.octo.playlists.PlaylistFolderStore
import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.playlists.PlaylistFolderWords
import app.winters.octo.subsonic.PLAYLIST_SONGS_EXTENSION
import app.winters.octo.ui.common.Feedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// The listener's playlists. Changes run on the store's own scope, so they
// finish even if the page that asked for them closes. A playlist kept with
// the server sends each change there.
//
// Songs go in on the ids they name now (SongIds): each page of songs an
// add or a new playlist writes is read on its ids now and written inside
// one Read hold of the merge lock, a hold a page, so a save's group lands
// between pages and never between a page's read and its write; a row a
// page wrote on an id a later group renames moves with that group. The
// page's hold is taken inside the playlist's set mutex, which no holder of
// the merge lock waits for (a playlist sync step takes the mutex before
// its hold).
@Singleton
class PlaylistStore @Inject constructor(
    private val userDao: UserDao,
    private val catalog: CatalogDao,
    private val sync: PlaylistSync,
    private val sessions: SessionRepository,
    db: CatalogDatabase,
    private val owners: WorkOwners,
    private val sets: PlaylistSets,
    private val edits: PlaylistEdits,
    private val ids: SongIds,
    private val findMap: FindMap,
    private val folders: PlaylistFolderStore,
    private val feedback: Feedback,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val online = db.onlineDao()
    private val songs = PlaylistSongs(catalog, ids, keptFinds = { found -> keptFinds(found) })

    private val summaries = PlaylistSummaries(
        db,
        owners,
        sessions.state.map { (it as? SessionState.SignedIn)?.session?.sourceId },
        releasePin = sets::releasePin,
    )

    // The phone's own playlists and the server in use's, each with its song
    // count, length and covers. Another kept server's wait for it to be in
    // use again.
    val playlists: Flow<List<PlaylistSummary>> = summaries.all

    // One playlist's summary, as it changes; null while it is not shown.
    fun summary(id: String): Flow<PlaylistSummary?> = summaries.one(id)

    // Whether the server in use keeps songs found online in the listener's
    // playlists (octoPlaylistSongs): only then do they go in.
    val findsOpen: Boolean
        get() = (sessions.state.value as? SessionState.SignedIn)?.session?.extensions?.any { it.startsWith("$PLAYLIST_SONGS_EXTENSION:") } == true

    // Whether a playlist holds a song found online now.
    suspend fun holdsFind(id: String): Boolean = userDao.holdsFindNow(id)

    // The finds among `found` whose rows are written, from the server in
    // use, once each one's keep has landed.
    private suspend fun keptFinds(found: List<String>): Set<String> {
        if (found.isEmpty()) return emptySet()
        found.forEach { findMap.awaitKept(it) }
        val source = (sessions.state.value as? SessionState.SignedIn)?.session?.sourceId ?: return emptySet()
        return found.chunked(PAGE).flatMap { online.byIds(it) }.filter { it.sourceId == source }.mapTo(HashSet()) { it.id }
    }

    // A playlist only on the phone that now holds a song found online goes
    // to the server, as Save to server does, so the song keeps playing and
    // binds to its download there; the listener is told.
    private suspend fun saveForFinds(id: String) {
        if (!findsOpen) return
        val row = userDao.playlistRow(id) ?: return
        if (!savesForFinds(row.sourceId, userDao.holdsFindNow(id), findsOpen)) return
        sync.saveToServer(id)
        feedback.show(PlaylistFolderWords.SAVED_FOR_FINDS)
    }

    // Makes a playlist, with the songs in it if any are given (library songs
    // only, in the order given), and gives back the id it will have.
    fun create(name: String, trackIds: List<String> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        scope.launch {
            val now = System.currentTimeMillis()
            if (trackIds.isEmpty()) {
                userDao.insertPlaylist(PlaylistEntity(id, name.trim(), createdAt = now, updatedAt = now))
                sync.created(id)
            } else {
                createWith(id, name, now) { emit -> songPages(trackIds).invoke { emit(it.map { s -> NewItem(s.trackId, s.relinkKey) }) } }
                if (trackIds.any(::isFind)) saveForFinds(id)
            }
        }
        return id
    }

    // Makes a playlist named `name` with the rows `pages` gives, as
    // createFrom does, and answers its id.
    private suspend fun createWith(id: String, name: String, now: Long, pages: suspend (suspend (List<NewItem>) -> Unit) -> Unit) {
        if (sets.create(PlaylistEntity(id, name.trim(), createdAt = now, updatedAt = now), songs.heldItems(pages))) sync.created(id)
    }

    private fun songPages(trackIds: List<String>): suspend (suspend (List<SongToAdd>) -> Unit) -> Unit = { page -> songs.pages(trackIds, page, finds = findsOpen) }

    private fun heldSongs(pages: suspend (suspend (List<SongToAdd>) -> Unit) -> Unit) = songs.heldSongs(pages)

    // A new playlist of the songs `pages` hands, each song once: a song
    // already written is left out by a probe of the new rows. Answers its
    // id and how many songs went in.
    suspend fun createDistinct(name: String, pages: suspend (suspend (List<SongToAdd>) -> Unit) -> Unit): Pair<String, Int> {
        val id = UUID.randomUUID().toString()
        var count = 0
        createWith(id, name, System.currentTimeMillis()) { emit ->
            heldSongs(pages).invoke { songs ->
                val seen = HashSet<String>()
                val fresh = songs.filter { seen.add(it.trackId) && !edits.holdsAny(id, it.trackId) }
                count += fresh.size
                emit(fresh.map { NewItem(it.trackId, it.relinkKey) })
            }
        }
        saveForFinds(id)
        return id to count
    }

    // A copy of a playlist named `name`: its songs in its order as it stood
    // when the copy began, library songs only and repeats kept, read a page
    // at a time from its set pinned for the copy, so an edit or a sync
    // meanwhile changes nothing it reads.
    // While the server keeps songs found online in playlists, the copy keeps
    // its finds too (with the server ids they came from), and a copy holding
    // one goes to the server.
    suspend fun duplicate(sourceId: String, name: String): String? {
        val owner = owners.walk()
        val pinned = sets.pin(owner, sourceId) ?: return null
        val finds = findsOpen
        val id = try {
            createFrom(name) { emit ->
                sets.walk(pinned) { page ->
                    val library = catalog.tracksByIdsUnordered(page.map { it.trackId }.distinct()).associateBy { it.id }
                    emit(
                        page.mapNotNull { row ->
                            library[row.trackId]?.let { NewItem(it.id, it.relinkKey) }
                                ?: row.takeIf { finds && isFind(it.trackId) }?.let { NewItem(it.trackId, it.relinkKey, it.serverSongId) }
                        },
                    )
                }
            }
        } finally {
            withContext(NonCancellable) { sets.releasePin(owner, sourceId) }
        }
        if (finds) saveForFinds(id)
        return id
    }

    // Makes a playlist named `name` from songs too many to hold: `pages`
    // hands its rows in play order a page at a time (Save as playlist, a
    // backup, an M3U file, Duplicate). They are written as a new item set,
    // a page to a transaction, and shown in one step, so a reader never
    // sees part of them; the playlist shows empty until then. Answers the
    // new playlist's id.
    suspend fun createFrom(name: String, pages: suspend (suspend (List<NewItem>) -> Unit) -> Unit): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        if (sets.create(PlaylistEntity(id, name.trim(), createdAt = now, updatedAt = now), songs.heldItems(pages))) sync.created(id)
        return id
    }

    fun rename(id: String, name: String) {
        scope.launch {
            userDao.renamePlaylist(id, name.trim(), System.currentTimeMillis())
            sync.changed(id)
        }
    }

    // It leaves every list at once; its songs go a page at a time, and any a
    // walk still reads (a download, a duplicate, a queue being built) once
    // that walk lets them go.
    fun delete(id: String) {
        scope.launch {
            val playlist = userDao.playlistRow(id) ?: return@launch
            if (sets.deletePlaylist(id)) {
                folders.forget(playlist.serverId ?: PlaylistFolderTree.localKey(id))
                sync.deleted(playlist)
            }
        }
    }

    // Adds songs to the end, library songs only, in the order given.
    // `added` hears what went in, to take it back out.
    fun add(id: String, trackIds: List<String>, added: (AddedRows) -> Unit = {}) {
        scope.launch {
            val rows = edits.add(id, songPages(trackIds), distinct = false, newOnly = false, now = System.currentTimeMillis())
                ?: return@launch
            sync.changed(id)
            if (trackIds.any(::isFind)) saveForFinds(id)
            added(rows)
        }
    }

    // Adds the songs `pages` hands, a page at a time, each song once: all
    // of them, or with `newOnly` only those not on the playlist yet.
    suspend fun addPages(id: String, newOnly: Boolean, pages: suspend (suspend (List<SongToAdd>) -> Unit) -> Unit): AddedRows? {
        val rows = edits.add(id, heldSongs(pages), distinct = true, newOnly = newOnly, now = System.currentTimeMillis()) ?: return null
        sync.changed(id)
        saveForFinds(id)
        return rows
    }

    // Takes back an add: the rows it made go again, wherever they are now.
    fun undoAdd(id: String, added: AddedRows) {
        scope.launch {
            edits.undoAdd(id, added, System.currentTimeMillis())
            sync.changed(id)
        }
    }

    // Takes a song out by its row's key. `removed` hears the row as it was,
    // to put it back.
    fun remove(id: String, itemKey: Long, removed: (PlaylistItemEntity) -> Unit = {}) {
        scope.launch {
            val row = edits.remove(id, itemKey, System.currentTimeMillis()) ?: return@launch
            sync.changed(id)
            removed(row)
        }
    }

    // Takes several songs out by their rows' keys. `removed` hears the rows
    // as they were, to put them back.
    fun removeAll(id: String, itemKeys: List<Long>, removed: (List<PlaylistItemEntity>) -> Unit = {}) {
        scope.launch {
            val rows = edits.removeAll(id, itemKeys, System.currentTimeMillis())
            if (rows.isEmpty()) return@launch
            sync.changed(id)
            removed(rows)
        }
    }

    // Puts songs taken out back, each with its own key and place.
    fun restoreAll(id: String, rows: List<PlaylistItemEntity>) {
        scope.launch {
            edits.restoreAll(id, rows, System.currentTimeMillis())
            sync.changed(id)
        }
    }

    // Puts a song taken out back in the place it had.
    fun restore(id: String, row: PlaylistItemEntity) {
        scope.launch {
            edits.restore(id, row, System.currentTimeMillis())
            sync.changed(id)
        }
    }

    // Moves a song into the place of the one it was dropped on, by their
    // rows' keys.
    fun move(id: String, itemKey: Long, targetKey: Long) {
        scope.launch {
            edits.move(id, itemKey, targetKey, System.currentTimeMillis())
            sync.changed(id)
        }
    }

    // Makes a playlist only on the phone on the server too, and keeps them in step.
    fun saveToServer(id: String) = sync.saveToServer(id)

    // Brings in what changed in the server's playlists since the last look.
    fun freshen() = sync.freshen()
}

// How many songs one page of an add or a new playlist reads.
private const val PAGE = 500

// Whether a playlist goes to the server because of a song found online in
// it: one only on the phone (no server), holding a find, while the server
// in use keeps such songs in playlists.
internal fun savesForFinds(sourceId: String?, holdsFind: Boolean, findsOpen: Boolean): Boolean = findsOpen && holdsFind && sourceId == null

// The songs a playlist's add or new playlist writes, each page on the ids
// its songs name now and handed to the write inside one Read hold
// (SongIds), a hold a page.
internal class PlaylistSongs(
    private val catalog: CatalogDao,
    private val ids: SongIds,
    private val page: Int = PAGE,
    // The finds among these whose rows are written, waited for outside any hold.
    private val keptFinds: suspend (List<String>) -> Set<String> = { emptySet() },
) {
    // The library songs among `trackIds`, in their order, a page at a time;
    // with `finds`, the songs found online among them whose rows are
    // written too, where they stand.
    suspend fun pages(trackIds: List<String>, emit: suspend (List<SongToAdd>) -> Unit, finds: Boolean = false) {
        for (chunk in trackIds.chunked(page)) {
            val kept = if (finds) keptFinds(chunk.filter(::isFind).distinct()) else emptySet()
            ids.write {
                val now = ids.songs(chunk)
                val found = catalog.tracksByIdsUnordered(now.distinct()).associateBy { it.id }
                emit(now.mapNotNull { id -> found[id]?.let { t -> SongToAdd(t.id, t.relinkKey) } ?: id.takeIf { it in kept }?.let { SongToAdd(it, "") } })
            }
        }
    }

    // `pages` with each page's rows put on the songs they land on now.
    fun heldItems(pages: suspend (suspend (List<NewItem>) -> Unit) -> Unit): suspend (suspend (List<NewItem>) -> Unit) -> Unit = { emit ->
        pages { items ->
            ids.write {
                val now = ids.songRows(items.map { SongToAdd(it.trackId, it.relinkKey) })
                emit(items.mapIndexed { i, item -> item.copy(trackId = now[i].trackId) })
            }
        }
    }

    fun heldSongs(pages: suspend (suspend (List<SongToAdd>) -> Unit) -> Unit): suspend (suspend (List<SongToAdd>) -> Unit) -> Unit = { emit ->
        pages { songs -> ids.write { emit(ids.songRows(songs)) } }
    }
}
