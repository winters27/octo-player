package app.winters.octo.ui.home

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.device.Access
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// What Home shows besides the resume card.
data class HomeLayout(
    // The quiet card asking for the music on the phone.
    val askAccess: Boolean,
    // The shelves, drawn from the whole library, phone and server together.
    val shelves: Boolean,
    // The note for a library with no songs at all.
    val empty: Boolean,
)

// Shelves whenever the library has songs, wherever they came from, so a
// listener with only a server still gets a full Home. The phone access card
// shows only while access is missing and they have not said "Not now".
// Nulls are values not read yet: nothing shows for them, so nothing flickers.
fun homeLayout(access: Access, accessDismissed: Boolean?, librarySongs: Int?): HomeLayout =
    HomeLayout(
        askAccess = access != Access.Granted && accessDismissed == false,
        shelves = librarySongs != null && librarySongs > 0,
        empty = librarySongs == 0,
    )

private val Context.homePrefs by preferencesDataStore("home")
private val ACCESS_DISMISSED = booleanPreferencesKey("phone_access_dismissed")

// What Home remembers between runs.
@Singleton
class HomePrefs @Inject constructor(@ApplicationContext private val context: Context) {
    // The listener said "Not now" to the phone access card.
    val accessDismissed: Flow<Boolean> =
        context.homePrefs.data.map { it[ACCESS_DISMISSED] ?: false }.distinctUntilChanged()

    suspend fun dismissAccess() {
        context.homePrefs.edit { it[ACCESS_DISMISSED] = true }
    }
}
