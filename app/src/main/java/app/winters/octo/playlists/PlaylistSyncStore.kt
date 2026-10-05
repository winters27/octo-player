package app.winters.octo.playlists

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.data.accountOfServerKey
import app.winters.octo.data.forgetAccount
import app.winters.octo.data.moveToAccount
import app.winters.octo.server.serverSourceId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playlistSyncData by preferencesDataStore("playlist_sync")
private val NEW_ON_SERVER = booleanPreferencesKey("new_on_server")

// Where an older version kept its one server, and its deletions.
private val SERVER = stringPreferencesKey("server")
private val PLAIN = listOf("deleted")

private fun deletedKey(account: String) = stringSetPreferencesKey("deleted@$account")

// Which account the playlists linked with a server's address belong to.
private fun linkedByKey(sourceId: String) = stringPreferencesKey("linked_by@$sourceId")

// What keeping playlists in step with the servers remembers between
// launches: whether new playlists are also made on the server, and, for
// each kept server by its id, its playlists deleted on the phone that it
// has not deleted yet.
@Singleton
class PlaylistSyncStore internal constructor(private val data: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.playlistSyncData)

    private val settle = Mutex()
    @Volatile private var settled = false

    // Off until the listener turns it on: nothing goes to the server unasked.
    val newOnServer: Flow<Boolean> = data.data.map { it[NEW_ON_SERVER] ?: false }

    suspend fun setNewOnServer(on: Boolean) {
        data.edit { it[NEW_ON_SERVER] = on }
    }

    // Notes that this account's playlists are the ones linked with its
    // server's address. Answers whether another account's were, whose
    // links then go: two accounts on one address share its playlist links.
    suspend fun claim(sourceId: String, account: String): Boolean {
        settleOnce()
        var changed = false
        data.edit { p ->
            val before = p[linkedByKey(sourceId)]
            if (before != account) {
                changed = before != null
                p[linkedByKey(sourceId)] = account
            }
        }
        return changed
    }

    suspend fun deleted(account: String): Set<String> {
        settleOnce()
        return data.data.first()[deletedKey(account)].orEmpty()
    }

    suspend fun addDeleted(account: String, serverId: String) {
        settleOnce()
        data.edit { it[deletedKey(account)] = it[deletedKey(account)].orEmpty() + serverId }
    }

    suspend fun removeDeleted(account: String, serverId: String) {
        settleOnce()
        data.edit { it[deletedKey(account)] = it[deletedKey(account)].orEmpty() - serverId }
    }

    // Forgets a server taken off the list, and its claim on its address.
    suspend fun forget(account: String, sourceId: String?) {
        settleOnce()
        data.edit { p ->
            p.forgetAccount(account)
            if (sourceId != null && p[linkedByKey(sourceId)] == account) p.remove(linkedByKey(sourceId))
        }
    }

    // An older version's deletions belong to the account it noted, whose
    // playlists are the ones linked with its address.
    private suspend fun settleOnce() {
        if (settled) return
        settle.withLock {
            if (settled) return
            data.edit { p ->
                val server = p[SERVER] ?: return@edit
                val account = accountOfServerKey(server)
                p.moveToAccount(PLAIN, account)
                server.substringAfterLast('@').toHttpUrlOrNull()?.let { url ->
                    if (p[linkedByKey(serverSourceId(url))] == null) p[linkedByKey(serverSourceId(url))] = account
                }
                p.remove(SERVER)
            }
            settled = true
        }
    }
}
