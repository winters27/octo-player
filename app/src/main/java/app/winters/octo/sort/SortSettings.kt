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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sortPrefs by preferencesDataStore("sort")

// The saved orders, by list name, that this app can use: a list it has,
// with an order that list offers.
fun restorableOrders(saved: Map<String, String>): Map<SortList, SortOrder> =
    SortList.entries.mapNotNull { list ->
        val value = saved[list.key] ?: return@mapNotNull null
        list.decode(value).takeIf { list.encode(it) == value }?.let { list to it }
    }.toMap()

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

    // The orders chosen so far, by list name, for a backup. Lists never
    // reordered, or holding an order they no longer offer, are left out.
    suspend fun saved(): Map<String, String> {
        val stored = store.data.first()
        return restorableOrders(SortList.entries.mapNotNull { list -> stored[keyOf(list)]?.let { list.key to it } }.toMap())
            .entries.associate { (list, order) -> list.key to list.encode(order) }
    }

    // Puts back the orders from a backup. Lists this app does not have, and
    // orders a list does not offer, are left as they are.
    suspend fun restore(saved: Map<String, String>) {
        val orders = restorableOrders(saved)
        if (orders.isEmpty()) return
        store.edit { p -> orders.forEach { (list, order) -> p[keyOf(list)] = list.encode(order) } }
    }

    private fun keyOf(list: SortList) = stringPreferencesKey(list.key)
}
