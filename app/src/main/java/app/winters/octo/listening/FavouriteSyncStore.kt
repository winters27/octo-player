package app.winters.octo.listening

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.favouriteSyncData by preferencesDataStore("favourite_stars")
private val SERVER = stringPreferencesKey("server")
private val ALBUMS = stringSetPreferencesKey("synced_albums")
private val ARTISTS = stringSetPreferencesKey("synced_artists")

// Album or artist, for the favourites kept in step with a server.
enum class FavouriteKind { Album, Artist }

// The server album and artist ids both sides last agreed were starred, as
// the song stars' record in ListeningStore. It belongs to one server and user.
@Singleton
class FavouriteSyncStore @Inject constructor(@ApplicationContext private val context: Context) {
    // Starts over when another server or user signs in, since ids only mean
    // something on the server they came from.
    suspend fun useServer(key: String) {
        context.favouriteSyncData.edit { p ->
            if (p[SERVER] != key) {
                p.clear()
                p[SERVER] = key
            }
        }
    }

    suspend fun synced(kind: FavouriteKind): Set<String> = context.favouriteSyncData.data.first()[keyOf(kind)].orEmpty()

    suspend fun updateSynced(kind: FavouriteKind, change: (Set<String>) -> Set<String>) {
        context.favouriteSyncData.edit { it[keyOf(kind)] = change(it[keyOf(kind)].orEmpty()) }
    }

    private fun keyOf(kind: FavouriteKind): Preferences.Key<Set<String>> = if (kind == FavouriteKind.Album) ALBUMS else ARTISTS
}
