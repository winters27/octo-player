package app.winters.octo.listening

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.data.accountOfServerKey
import app.winters.octo.data.forgetAccount
import app.winters.octo.data.moveToAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.favouriteSyncData by preferencesDataStore("favourite_stars")

// Where an older version kept its one server's values, and which it was.
private val SERVER = stringPreferencesKey("server")
private val PLAIN = listOf("synced_albums", "synced_artists")

// Album or artist, for the favourites kept in step with a server.
enum class FavouriteKind { Album, Artist }

// The server album and artist ids both sides last agreed were starred, as
// the song stars' record in ListeningStore, for each kept server by its id:
// ids only mean something on the server they came from.
@Singleton
class FavouriteSyncStore internal constructor(private val data: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.favouriteSyncData)

    private val settle = Mutex()
    @Volatile private var settled = false

    suspend fun synced(account: String, kind: FavouriteKind): Set<String> {
        settleOnce()
        return data.data.first()[keyOf(account, kind)].orEmpty()
    }

    suspend fun updateSynced(account: String, kind: FavouriteKind, change: (Set<String>) -> Set<String>) {
        settleOnce()
        data.edit { it[keyOf(account, kind)] = change(it[keyOf(account, kind)].orEmpty()) }
    }

    // Forgets everything kept for a server taken off the list.
    suspend fun forget(account: String) {
        settleOnce()
        data.edit { it.forgetAccount(account) }
    }

    private fun keyOf(account: String, kind: FavouriteKind): Preferences.Key<Set<String>> =
        stringSetPreferencesKey((if (kind == FavouriteKind.Album) "synced_albums" else "synced_artists") + "@$account")

    // An older version's values belong to the account it noted.
    private suspend fun settleOnce() {
        if (settled) return
        settle.withLock {
            if (settled) return
            data.edit { p ->
                val server = p[SERVER] ?: return@edit
                p.moveToAccount(PLAIN, accountOfServerKey(server))
                p.remove(SERVER)
            }
            settled = true
        }
    }
}
