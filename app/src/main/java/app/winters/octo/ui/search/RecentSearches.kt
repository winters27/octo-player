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

// How many past searches are kept.
const val RECENT_SEARCHES = 10

private val RECENT = stringPreferencesKey("recent")
private val spaces = Regex("\\s+")

// A search as it is kept: trimmed, with runs of spaces made one.
fun tidySearch(query: String): String = query.trim().replace(spaces, " ")

// The recent searches with one more, newest first. Searching again for one
// already there (whatever its case) moves it to the top rather than adding
// it twice, and only the newest ten are kept.
fun withRecent(recent: List<String>, query: String): List<String> {
    val tidy = tidySearch(query)
    if (tidy.isEmpty()) return recent
    return (listOf(tidy) + recent.filterNot { it.equals(tidy, ignoreCase = true) }).take(RECENT_SEARCHES)
}

fun withoutRecent(recent: List<String>, query: String): List<String> =
    recent.filterNot { it.equals(tidySearch(query), ignoreCase = true) }

// Kept as one search per line, newest first.
fun encodeRecent(recent: List<String>): String = recent.joinToString("\n")

fun decodeRecent(text: String?): List<String> =
    text.orEmpty().split("\n").filter { it.isNotBlank() }.take(RECENT_SEARCHES)

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
