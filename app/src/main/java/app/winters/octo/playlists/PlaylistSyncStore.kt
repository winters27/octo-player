package app.winters.octo.playlists

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playlistSyncData by preferencesDataStore("playlist_sync")
private val NEW_ON_SERVER = booleanPreferencesKey("new_on_server")
private val SERVER = stringPreferencesKey("server")
private val DELETED = stringSetPreferencesKey("deleted")

// What keeping playlists in step with a server remembers between launches:
// whether new playlists are also made on the server, and server playlists
// deleted on the phone that the server has not deleted yet. The deletions
// belong to one server and user.
@Singleton
class PlaylistSyncStore @Inject constructor(@ApplicationContext private val context: Context) {
    // Off until the listener turns it on: nothing goes to the server unasked.
    val newOnServer: Flow<Boolean> = context.playlistSyncData.data.map { it[NEW_ON_SERVER] ?: false }

    suspend fun setNewOnServer(on: Boolean) {
        context.playlistSyncData.edit { it[NEW_ON_SERVER] = on }
    }

    // Notes which server and user playlists are kept with. Answers whether
    // it changed from the one noted before, in which case the deletions
    // waiting for the old one are dropped.
    suspend fun useServer(key: String): Boolean {
        var changed = false
        context.playlistSyncData.edit { p ->
            val before = p[SERVER]
            if (before != key) {
                changed = before != null
                p.remove(DELETED)
                p[SERVER] = key
            }
        }
        return changed
    }

    // Forgets the server, as after signing out.
    suspend fun forgetServer() {
        context.playlistSyncData.edit { p ->
            p.remove(SERVER)
            p.remove(DELETED)
        }
    }

    suspend fun deleted(): Set<String> = context.playlistSyncData.data.first()[DELETED].orEmpty()

    suspend fun addDeleted(serverId: String) {
        context.playlistSyncData.edit { it[DELETED] = it[DELETED].orEmpty() + serverId }
    }

    suspend fun removeDeleted(serverId: String) {
        context.playlistSyncData.edit { it[DELETED] = it[DELETED].orEmpty() - serverId }
    }
}
