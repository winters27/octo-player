package app.winters.octo.playback

import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.summarize
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
// finish even if the page that asked for them closes.
@Singleton
class PlaylistStore @Inject constructor(
    private val userDao: UserDao,
    private val catalog: CatalogDao,
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
        }
    }

    fun rename(id: String, name: String) {
        scope.launch { userDao.renamePlaylist(id, name.trim(), System.currentTimeMillis()) }
    }

    fun delete(id: String) {
        scope.launch { userDao.deletePlaylist(id) }
    }

    // Adds songs to the end.
    fun add(id: String, trackIds: List<String>) {
        scope.launch { userDao.addToPlaylist(id, catalog.tracksByIds(trackIds), System.currentTimeMillis()) }
    }

    fun remove(id: String, itemId: Long) {
        scope.launch { userDao.removeFromPlaylist(id, itemId, System.currentTimeMillis()) }
    }

    // Moves a song into the place of the one it was dropped on.
    fun move(id: String, itemId: Long, targetId: Long) {
        scope.launch { userDao.moveInPlaylist(id, itemId, targetId, System.currentTimeMillis()) }
    }
}
