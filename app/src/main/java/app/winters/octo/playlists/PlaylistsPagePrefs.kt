package app.winters.octo.playlists

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playlistsPage by preferencesDataStore("playlists_page")
private val UPKEEP_OPEN = booleanPreferencesKey("upkeep_open")

// What the Playlists page remembers on this phone: whether the library
// upkeep group is open (closed until opened).
@Singleton
class PlaylistsPagePrefs internal constructor(private val data: DataStore<Preferences>, private val scope: CoroutineScope) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.playlistsPage, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    val upkeepOpen: Flow<Boolean> = data.data.map { it[UPKEEP_OPEN] ?: false }

    fun setUpkeepOpen(open: Boolean) {
        scope.launch { data.edit { it[UPKEEP_OPEN] = open } }
    }
}
