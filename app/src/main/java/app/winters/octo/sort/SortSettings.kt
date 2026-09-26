package app.winters.octo.sort

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

private val Context.sortPrefs by preferencesDataStore("sort")

// The order each list was last left in, kept across restarts. One entry
// per list, under the list's key.
@Singleton
class SortSettings internal constructor(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.sortPrefs)

    fun order(list: SortList): Flow<SortOrder> =
        store.data.map { list.decode(it[keyOf(list)]) }.distinctUntilChanged()

    suspend fun set(list: SortList, order: SortOrder) {
        store.edit { it[keyOf(list)] = list.encode(order) }
    }

    private fun keyOf(list: SortList) = stringPreferencesKey(list.key)
}
