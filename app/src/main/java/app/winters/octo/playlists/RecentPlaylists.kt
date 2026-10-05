package app.winters.octo.playlists

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.accountId
import app.winters.octo.data.forgetAccount
import app.winters.octo.ui.playlist.recentPlaylists
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recentPlaylistPrefs by preferencesDataStore("recent_playlists")

// The one list an older version kept, which each account starts from.
private val RECENT = stringPreferencesKey("recent")

// Whose list it is while no server is in use.
private const val PHONE = "phone"

private fun recentKey(account: String) = stringPreferencesKey("recent@$account")

// The playlists songs were added to lately, newest first, by id, for "Add
// to last playlist" and the top of the playlist picker, for each kept
// server by its id (its own playlists are only shown while it is in use).
// The desktop keeps the same list, the same way (shared recentPlaylists).
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RecentPlaylists internal constructor(
    private val data: DataStore<Preferences>,
    // The account in use, or PHONE with none.
    private val account: Flow<String>,
) {
    @Inject constructor(@ApplicationContext context: Context, sessions: SessionRepository) : this(
        context.recentPlaylistPrefs,
        sessions.state.filter { it !is SessionState.Loading }.map { it.accountId ?: PHONE }.distinctUntilChanged(),
    )

    val ids: Flow<List<String>> = account.flatMapLatest { who -> data.data.map { lines(it, who) } }.distinctUntilChanged()

    suspend fun used(id: String) {
        val who = account.first()
        data.edit { p -> p[recentKey(who)] = recentPlaylists(lines(p, who), id).joinToString("\n") }
    }

    // Forgets the list of a server taken off the list.
    suspend fun forget(account: String) {
        data.edit { it.forgetAccount(account) }
    }

    private fun lines(p: Preferences, who: String): List<String> =
        (p[recentKey(who)] ?: p[RECENT]).orEmpty().split('\n').filter(String::isNotBlank)
}
