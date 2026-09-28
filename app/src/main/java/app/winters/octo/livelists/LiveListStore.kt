package app.winters.octo.livelists

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject
import javax.inject.Singleton

private val Context.liveListPrefs by preferencesDataStore("live_lists")

// Whose lists these are: the signed-in account's, or the phone's own when
// no server is signed in. Null while the saved sign-in is still being read.
internal fun accountOf(state: SessionState): String? = when (state) {
    SessionState.Loading -> null
    SessionState.SignedOut -> PHONE_ACCOUNT
    is SessionState.SignedIn -> accountKey(state.session.client.username, state.session.client.primaryUrl.toString())
}

// The lists kept while no server is signed in.
internal const val PHONE_ACCOUNT = "phone"

// The live lists of the account signed in, kept in their own small store
// as the shared lists value (the desktop keeps the same shape in a file).
// Each account's are under its own key, so signing in elsewhere shows
// that account's and keeps these for when it comes back.
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LiveListStore internal constructor(
    private val data: DataStore<Preferences>,
    private val account: Flow<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(@ApplicationContext context: Context, sessions: SessionRepository) :
        this(context.liveListPrefs, sessions.state.mapNotNull(::accountOf).distinctUntilChanged())

    private fun keyOf(account: String) = stringPreferencesKey("lists:$account")

    val lists: Flow<List<LiveList>> = account.flatMapLatest { who ->
        data.data.map { LiveListsJson.decode(it[keyOf(who)]) }
    }.distinctUntilChanged()

    suspend fun byId(id: String): LiveList? = lists.first().firstOrNull { it.id == id }

    // Saves a new list or a changed one, stamped as changed now.
    suspend fun save(list: LiveList): LiveList {
        val now = clock()
        val stamped = list.copy(name = list.name.trim(), changed = now, created = list.created.takeIf { it != 0L } ?: now)
        change { it.saving(stamped) }
        return stamped
    }

    suspend fun remove(id: String) = change { it.removing(id) }

    suspend fun rename(id: String, name: String) {
        val list = byId(id) ?: return
        if (name.isBlank() || name.trim() == list.name) return
        save(list.copy(name = name))
    }

    // A copy just after the list, named "<name> (copy)".
    suspend fun duplicate(list: LiveList): LiveList {
        var made: LiveList? = null
        change { lists -> lists.duplicating(list, clock()).also { made = it.second }.first }
        return made!!
    }

    private suspend fun change(edit: (List<LiveList>) -> List<LiveList>) {
        val key = keyOf(account.first())
        data.edit { prefs -> prefs[key] = LiveListsJson.encode(edit(LiveListsJson.decode(prefs[key]))) }
    }
}
