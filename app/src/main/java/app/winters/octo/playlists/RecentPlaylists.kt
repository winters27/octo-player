package app.winters.octo.playlists

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.ui.playlist.recentPlaylists
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recentPlaylistPrefs by preferencesDataStore("recent_playlists")
private val RECENT = stringPreferencesKey("recent")

// The playlists songs were added to lately, newest first, by id, for "Add
// to last playlist" and the top of the playlist picker. The desktop keeps
// the same list, the same way (shared recentPlaylists).
@Singleton
class RecentPlaylists @Inject constructor(@ApplicationContext private val context: Context) {
    val ids: Flow<List<String>> = context.recentPlaylistPrefs.data
        .map { stored -> stored[RECENT].orEmpty().split('\n').filter(String::isNotBlank) }
        .distinctUntilChanged()

    suspend fun used(id: String) {
        context.recentPlaylistPrefs.edit { p ->
            p[RECENT] = recentPlaylists(p[RECENT].orEmpty().split('\n').filter(String::isNotBlank), id).joinToString("\n")
        }
    }
}
