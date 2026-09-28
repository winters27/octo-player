package app.winters.octo.ui.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.searchPrefs by preferencesDataStore("search")

private val RECENT = stringPreferencesKey("recent")

// The last searches that led somewhere: saved when a result is opened or
// played, not while typing.
@Singleton
class RecentSearches internal constructor(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.searchPrefs)

    val all: Flow<List<String>> = store.data.map { decodeRecent(it[RECENT]) }.distinctUntilChanged()

    suspend fun add(query: String) = change { withRecent(it, query) }

    suspend fun remove(query: String) = change { withoutRecent(it, query) }

    suspend fun clear() = change { emptyList() }

    private suspend fun change(update: (List<String>) -> List<String>) {
        store.edit { it[RECENT] = encodeRecent(update(decodeRecent(it[RECENT]))) }
    }
}
