package app.winters.octo.playback

import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistItemEntity
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.summarize
import app.winters.octo.playlists.PlaylistSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// The listener's playlists. Changes run on the store's own scope, so they
// finish even if the page that asked for them closes. A playlist kept with
// the server sends each change there.
@Singleton
class PlaylistStore @Inject constructor(
    private val userDao: UserDao,
    private val catalog: CatalogDao,
    private val sync: PlaylistSync,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val playlists: Flow<List<PlaylistSummary>> =
        combine(userDao.playlists(), userDao.playlistEntries(), ::summarize)

    // Makes a playlist, with the songs in it if any are given.
    fun create(name: String, trackIds: List<String> = emptyList()) {
        val id = UUID.randomUUID().toString()
        scope.launch {
            val now = System.currentTimeMillis()
            userDao.insertPlaylist(PlaylistEntity(id, name.trim(), createdAt = now, updatedAt = now))
            if (trackIds.isNotEmpty()) userDao.addToPlaylist(id, catalog.tracksByIds(trackIds), now)
            sync.created(id)
        }
    }

    fun rename(id: String, name: String) {
        scope.launch {
            userDao.renamePlaylist(id, name.trim(), System.currentTimeMillis())
            sync.changed(id)
        }
    }

    fun delete(id: String) {
        scope.launch {
            val playlist = userDao.playlistRow(id) ?: return@launch
            userDao.deletePlaylist(id)
            sync.deleted(playlist)
        }
    }

    // Adds songs to the end. `added` hears the new rows, to take them back out.
    fun add(id: String, trackIds: List<String>, added: (List<Long>) -> Unit = {}) {
        scope.launch {
            val rows = userDao.addToPlaylist(id, catalog.tracksByIds(trackIds), System.currentTimeMillis())
            sync.changed(id)
            if (rows.isNotEmpty()) added(rows)
        }
    }

    // Takes a song out. `removed` hears the row as it was, to put it back.
    fun remove(id: String, itemId: Long, removed: (PlaylistItemEntity) -> Unit = {}) {
        scope.launch {
            val row = userDao.removeFromPlaylist(id, itemId, System.currentTimeMillis()) ?: return@launch
            sync.changed(id)
            removed(row)
        }
    }

    // Takes several songs out, one after another. `removed` hears the rows
    // as they were, in the order they went, to put them back.
    fun removeAll(id: String, itemIds: List<Long>, removed: (List<PlaylistItemEntity>) -> Unit = {}) {
        scope.launch {
            val now = System.currentTimeMillis()
            val rows = itemIds.mapNotNull { userDao.removeFromPlaylist(id, it, now) }
            if (rows.isEmpty()) return@launch
            sync.changed(id)
            removed(rows)
        }
    }

    // Puts songs taken out together back, the last taken out first, so each
    // lands in the place it had.
    fun restoreAll(id: String, rows: List<PlaylistItemEntity>) {
        scope.launch {
            val now = System.currentTimeMillis()
            rows.asReversed().forEach { userDao.restoreToPlaylist(id, it, now) }
            sync.changed(id)
        }
    }

    // Takes back an add: the rows it made go again.
    fun removeRows(id: String, itemIds: List<Long>) {
        scope.launch {
            userDao.removeItemsFromPlaylist(id, itemIds, System.currentTimeMillis())
            sync.changed(id)
        }
    }

    // Puts a song taken out back in the place it had.
    fun restore(id: String, row: PlaylistItemEntity) {
        scope.launch {
            userDao.restoreToPlaylist(id, row, System.currentTimeMillis())
            sync.changed(id)
        }
    }

    // Moves a song into the place of the one it was dropped on.
    fun move(id: String, itemId: Long, targetId: Long) {
        scope.launch {
            userDao.moveInPlaylist(id, itemId, targetId, System.currentTimeMillis())
            sync.changed(id)
        }
    }

    // Makes a playlist only on the phone on the server too, and keeps them in step.
    fun saveToServer(id: String) = sync.saveToServer(id)
}
