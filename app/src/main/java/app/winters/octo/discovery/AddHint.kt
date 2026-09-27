package app.winters.octo.discovery

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.addHintPrefs by preferencesDataStore("add_hint")

private val SHOWN = intPreferencesKey("shown")
private val ADDED = booleanPreferencesKey("added")

// How many times the line saying how to add a song shows before it stops.
const val ADD_HINT_SHOWINGS = 5

// How many times the line has been shown, and whether a song has been
// added to the library since it first could be.
data class AddHintState(val shown: Int = 0, val added: Boolean = false)

// Whether the line shows. It goes once a song has been added, and after a
// few showings; once counted on a visit it stays for the rest of it, so it
// does not vanish while being read.
fun showsAddHint(state: AddHintState, countedThisVisit: Boolean): Boolean =
    !state.added && (countedThisVisit || state.shown < ADD_HINT_SHOWINGS)

// The line under songs not in the library that says how to add one: shown
// the first few times, and no more once a song has been added.
@Singleton
class AddHint internal constructor(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.addHintPrefs)

    val state: Flow<AddHintState> = store.data
        .map { AddHintState(shown = it[SHOWN] ?: 0, added = it[ADDED] ?: false) }
        .distinctUntilChanged()

    suspend fun shown() = change { it[SHOWN] = (it[SHOWN] ?: 0) + 1 }

    suspend fun added() = change { it[ADDED] = true }

    // A line of help is not worth failing an add over, so a store that
    // cannot be written is let be.
    private suspend fun change(update: (MutablePreferences) -> Unit) {
        try {
            store.edit { update(it) }
        } catch (e: IOException) {
            Log.w("Octo", "add hint not saved", e)
        }
    }
}
