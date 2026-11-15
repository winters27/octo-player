package app.winters.octo.playlists

import android.util.Log
import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.FOLDER_OP_DELETE
import app.winters.octo.catalog.FOLDER_OP_PLACE
import app.winters.octo.catalog.FOLDER_OP_SAVE
import app.winters.octo.catalog.FolderOpEntity
import app.winters.octo.catalog.PlaylistFolderEntity
import app.winters.octo.catalog.PlaylistPlaceEntity
import app.winters.octo.data.Session
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.subsonic.PLAYLIST_FOLDERS_EXTENSION
import app.winters.octo.subsonic.PlaylistFolder
import app.winters.octo.subsonic.PlaylistFolders
import app.winters.octo.subsonic.PlaylistPlace
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.deletePlaylistFolder
import app.winters.octo.subsonic.playlistFolders
import app.winters.octo.subsonic.savePlaylistFolder
import app.winters.octo.subsonic.setPlaylistFolder
import app.winters.octo.ui.common.Feedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

// How long after a folder change it is sent, so quick changes go as one.
private const val FOLDER_SEND_DELAY_MS = 1_500L

// The folder calls a push and a pull make: the server's, or a test's.
interface FolderCalls {
    suspend fun folders(): PlaylistFolders
    suspend fun save(id: String, name: String, parent: String?): PlaylistFolders
    suspend fun delete(id: String): PlaylistFolders
    suspend fun place(playlistIds: List<String>, folderId: String?): PlaylistFolders
}

// The signed-in server's folder calls.
class ClientFolderCalls(private val client: SubsonicClient) : FolderCalls {
    override suspend fun folders(): PlaylistFolders = client.playlistFolders()
    override suspend fun save(id: String, name: String, parent: String?): PlaylistFolders = client.savePlaylistFolder(id, name, parent)
    override suspend fun delete(id: String): PlaylistFolders = client.deletePlaylistFolder(id)
    override suspend fun place(playlistIds: List<String>, folderId: String?): PlaylistFolders = client.setPlaylistFolder(playlistIds, folderId)
}

// The listener's playlist folders on this phone, per account: the kept
// server's id, or "" with none in use. Folders are offered on the phone
// alone and with a server listing octoPlaylistFolders. Each change is
// written with the change it sends in one transaction (folder ids are made
// here, so nothing waits for the server), and sent 1.5 s after the last
// one and at every playlists freshen, oldest first, only the signed-in
// account's. A send that can't reach the server stops and waits; a refused
// sign-in (40, 41) waits for the next sign-in; any other refusal drops
// that change, logs it, and tells the listener once a send. A pull (after
// every send) takes the server's folders and places in place of the
// account's, then the changes still waiting on top, in one transaction; a
// placement whose folder is gone after that goes. A playlist only on the
// phone is placed under "local:<row id>" and never sent.
@Singleton
class PlaylistFolderStore internal constructor(
    private val db: CatalogDatabase,
    private val state: StateFlow<SessionState>,
    private val notice: (String) -> Unit,
    private val callsOf: (Session) -> FolderCalls,
    private val scope: CoroutineScope,
    private val sendDelayMs: Long = FOLDER_SEND_DELAY_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(db: CatalogDatabase, sessions: SessionRepository, feedback: Feedback) : this(
        db,
        sessions.state,
        { words -> feedback.show(words) },
        { session -> ClientFolderCalls(session.client) },
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val dao = db.playlistFolderDao()
    private val syncing = Mutex()
    private val waitLock = Any()
    private var waiting: Job? = null

    // A sign-in the server refused; nothing is sent again until another.
    @Volatile private var paused: Session? = null

    // The account whose folders show.
    val space: Flow<String> = state.map { spaceOf(it) }.distinctUntilChanged()

    // Whether folders are offered now.
    val offered: Flow<Boolean> = state.map { offeredWith(it) }.distinctUntilChanged()

    // The account's folders as a tree, as they change.
    @OptIn(ExperimentalCoroutinesApi::class)
    val tree: Flow<PlaylistFolderTree> = space.flatMapLatest { space ->
        combine(dao.folders(space), dao.places(space)) { folders, places -> treeOf(folders, places) }
    }

    fun offeredNow(): Boolean = offeredWith(state.value)

    // A new folder in `parent` (null: the top); answers its id, or null for
    // a name that won't do or a parent that can't hold one more level.
    suspend fun create(name: String, parent: String?): String? {
        val clean = PlaylistFolderTree.cleanName(name) ?: return null
        val space = spaceOf(state.value)
        val id = PlaylistFolderTree.newId()
        val made = db.withTransaction {
            val tree = treeNow(space)
            val under = parent?.takeIf { tree.folder(it) != null }
            if (!tree.canHoldFolder(under)) return@withTransaction false
            dao.putFolder(PlaylistFolderEntity(space, id, clean, under, Instant.ofEpochMilli(now()).toString()))
            queue(space, FolderOpEntity(space = space, kind = FOLDER_OP_SAVE, folderId = id, name = clean, parent = under, playlistKeys = "", at = now()))
            true
        }
        if (!made) return null
        later()
        return id
    }

    suspend fun rename(id: String, name: String) {
        val clean = PlaylistFolderTree.cleanName(name) ?: return
        val space = spaceOf(state.value)
        val renamed = db.withTransaction {
            val folder = dao.folder(space, id) ?: return@withTransaction false
            dao.renameFolder(space, id, clean)
            queue(space, FolderOpEntity(space = space, kind = FOLDER_OP_SAVE, folderId = id, name = clean, parent = folder.parent, playlistKeys = "", at = now()))
            true
        }
        if (renamed) later()
    }

    // Moves a folder into `parent` (null: the top); answers why it can't, or null once moved.
    suspend fun move(id: String, parent: String?): FolderMoveRefusal? {
        val space = spaceOf(state.value)
        var moved = false
        val refusal = db.withTransaction {
            val tree = treeNow(space)
            val folder = tree.folder(id) ?: return@withTransaction null
            tree.moveRefusal(id, parent)?.let { return@withTransaction it }
            dao.moveFolder(space, id, parent)
            queue(space, FolderOpEntity(space = space, kind = FOLDER_OP_SAVE, folderId = id, name = folder.name, parent = parent, playlistKeys = "", at = now()))
            moved = true
            null
        }
        if (moved) later()
        return refusal
    }

    // Deletes a folder: its folders and playlists move to its parent.
    suspend fun delete(id: String) {
        val space = spaceOf(state.value)
        val deleted = db.withTransaction {
            val folder = dao.folder(space, id) ?: return@withTransaction false
            applyDelete(space, folder)
            queue(space, FolderOpEntity(space = space, kind = FOLDER_OP_DELETE, folderId = id, name = null, parent = null, playlistKeys = "", at = now()))
            true
        }
        if (deleted) later()
    }

    // Puts playlists (by placement key) in a folder, or at the top with none.
    suspend fun place(keys: List<String>, folderId: String?) {
        if (keys.isEmpty()) return
        val space = spaceOf(state.value)
        val sent = keys.filterNot(PlaylistFolderTree::isLocalKey)
        val placed = db.withTransaction {
            if (folderId != null && dao.folder(space, folderId) == null) return@withTransaction false
            keys.forEach { applyPlace(space, it, folderId) }
            if (sent.isNotEmpty()) {
                queue(space, FolderOpEntity(space = space, kind = FOLDER_OP_PLACE, folderId = folderId, name = null, parent = null, playlistKeys = sent.joinToString("\n"), at = now()))
            }
            true
        }
        if (placed && sent.isNotEmpty()) later()
    }

    // A playlist deleted here: its placement goes, with nothing sent (the
    // server lets a deleted playlist's placement go itself).
    suspend fun forget(key: String) = dao.forgetPlaces(key)

    // Sends the waiting changes a moment after the last one.
    fun later() {
        synchronized(waitLock) {
            waiting?.cancel()
            waiting = scope.launch {
                delay(sendDelayMs)
                sync()
            }
        }
    }

    // Sends the waiting changes and takes the server's folders now.
    fun freshen() {
        scope.launch { sync() }
    }

    // Sends the signed-in account's changes in order, then pulls.
    internal suspend fun sync() = syncing.withLock {
        val session = (state.value as? SessionState.SignedIn)?.session ?: return@withLock
        if (!offeredWith(state.value) || session.id.isEmpty() || paused === session) return@withLock
        val space = session.id
        val calls = callsOf(session)
        var told = false
        while (true) {
            val op = dao.firstOp(space) ?: break
            try {
                send(calls, op)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubsonicException.Unreachable) {
                return@withLock
            } catch (e: SubsonicException.NotSubsonic) {
                return@withLock
            } catch (e: SubsonicException.WrongCredentials) {
                paused = session
                return@withLock
            } catch (e: SubsonicException.AuthNotSupported) {
                paused = session
                return@withLock
            } catch (e: SubsonicException) {
                Log.w("Octo", "folder change dropped: ${op.kind} (${e.javaClass.simpleName})")
                if (!told) {
                    told = true
                    notice(PlaylistFolderWords.FOLDER_CHANGE_FAILED)
                }
            }
            dao.deleteOp(op.seq)
        }
        val answer = try {
            calls.folders()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SubsonicException) {
            return@withLock
        }
        applyPull(space, answer)
    }

    // The server's folders and places in place of the account's (its
    // phone-only placements stay), the changes still waiting replayed on
    // top, and placements whose folder is gone dropped, in one transaction.
    internal suspend fun applyPull(space: String, answer: PlaylistFolders) = db.withTransaction {
        dao.clearServerPlaces(space)
        dao.clearFolders(space)
        dao.putFolders(answer.folders.map { PlaylistFolderEntity(space, it.id, it.name, it.parent, it.created) })
        dao.putPlaces(answer.places.map { PlaylistPlaceEntity(space, it.playlist, it.folder, localOnly = false) })
        for (op in dao.ops(space)) replay(space, op)
        dao.dropHomeless(space)
    }

    private suspend fun send(calls: FolderCalls, op: FolderOpEntity) {
        val id = op.folderId
        when (op.kind) {
            FOLDER_OP_SAVE -> if (id != null) calls.save(id, op.name.orEmpty(), op.parent)
            FOLDER_OP_DELETE -> if (id != null) calls.delete(id)
            FOLDER_OP_PLACE -> calls.place(keysOf(op), id)
        }
    }

    // A waiting change applied again over the server's folders, as the
    // server would: a missing parent or folder means the top.
    private suspend fun replay(space: String, op: FolderOpEntity) {
        val id = op.folderId
        when (op.kind) {
            FOLDER_OP_SAVE -> if (id != null) {
                val parent = op.parent?.takeIf { it != id && dao.folder(space, it) != null }
                val known = dao.folder(space, id)
                dao.putFolder(
                    known?.copy(name = op.name ?: known.name, parent = parent)
                        ?: PlaylistFolderEntity(space, id, op.name.orEmpty(), parent, Instant.ofEpochMilli(op.at).toString()),
                )
            }
            FOLDER_OP_DELETE -> if (id != null) dao.folder(space, id)?.let { applyDelete(space, it) }
            FOLDER_OP_PLACE -> {
                val folder = id?.takeIf { dao.folder(space, it) != null }
                keysOf(op).forEach { applyPlace(space, it, folder) }
            }
        }
    }

    private suspend fun applyDelete(space: String, folder: PlaylistFolderEntity) {
        dao.moveChildren(space, folder.id, folder.parent)
        val parent = folder.parent
        if (parent != null) dao.movePlaces(space, folder.id, parent) else dao.unplaceAll(space, folder.id)
        dao.deleteFolder(space, folder.id)
    }

    private suspend fun applyPlace(space: String, key: String, folderId: String?) {
        if (folderId == null) {
            dao.deletePlace(space, key)
        } else {
            dao.putPlace(PlaylistPlaceEntity(space, key, folderId, localOnly = PlaylistFolderTree.isLocalKey(key)))
        }
    }

    // Changes made with no server in use stay on the phone.
    private suspend fun queue(space: String, op: FolderOpEntity) {
        if (space.isNotEmpty()) dao.insertOp(op)
    }

    private suspend fun treeNow(space: String): PlaylistFolderTree = treeOf(dao.foldersNow(space), dao.placesNow(space))

    private fun keysOf(op: FolderOpEntity): List<String> = op.playlistKeys.split('\n').filter { it.isNotEmpty() }

    private fun spaceOf(state: SessionState): String = (state as? SessionState.SignedIn)?.session?.id.orEmpty()

    private fun offeredWith(state: SessionState): Boolean = when (state) {
        is SessionState.SignedIn -> state.session.extensions.any { it.startsWith("$PLAYLIST_FOLDERS_EXTENSION:") }
        else -> true
    }
}

private fun treeOf(folders: List<PlaylistFolderEntity>, places: List<PlaylistPlaceEntity>): PlaylistFolderTree =
    PlaylistFolderTree(
        folders.map { PlaylistFolder(it.id, it.name, it.parent, it.created) },
        places.map { PlaylistPlace(it.playlistKey, it.folderId) },
    )
