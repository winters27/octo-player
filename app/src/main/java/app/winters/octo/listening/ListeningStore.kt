package app.winters.octo.listening

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.accountId
import app.winters.octo.data.accountOfServerKey
import app.winters.octo.data.forgetAccount
import app.winters.octo.data.moveToAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.listeningData by preferencesDataStore("listening")

// Where an older version kept its one server's values, and which it was.
private val SERVER = stringPreferencesKey("server")
private val PLAIN = listOf("synced_stars", "pending_plays", "sent_plays", "synced_ratings")

private fun syncedKey(account: String) = stringSetPreferencesKey("synced_stars@$account")
private fun pendingKey(account: String) = stringSetPreferencesKey("pending_plays@$account")
private fun sentKey(account: String) = stringSetPreferencesKey("sent_plays@$account")
private fun ratingsKey(account: String) = stringSetPreferencesKey("synced_ratings@$account")

// What keeping in step with each kept server remembers between launches:
// the stars and ratings both sides last agreed on, plays waiting to be sent,
// and plays already sent. Each account's are its own, by the kept server's
// id, since song ids only mean something on the server they came from: a
// switch to another server and back loses none of them.
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ListeningStore internal constructor(
    private val data: DataStore<Preferences>,
    // The account in use, or null with none.
    account: Flow<String?>,
) {
    @Inject constructor(@ApplicationContext context: Context, sessions: SessionRepository) :
        this(context.listeningData, sessions.state.map { it.accountId })

    private val settle = Mutex()
    @Volatile private var settled = false

    // The plays sent to the server in use.
    val sent: Flow<Map<String, SentPlays>> = account.distinctUntilChanged().flatMapLatest { who ->
        if (who == null) {
            flowOf(emptyMap())
        } else {
            flow {
                settleOnce()
                emitAll(data.data.map { decodeSent(it[sentKey(who)].orEmpty()) })
            }
        }
    }.distinctUntilChanged()

    suspend fun synced(account: String): Set<String> = read()[syncedKey(account)].orEmpty()

    suspend fun updateSynced(account: String, change: (Set<String>) -> Set<String>) =
        edit { it[syncedKey(account)] = change(it[syncedKey(account)].orEmpty()) }

    suspend fun syncedRatings(account: String): Map<String, Int> = decodeRatings(read()[ratingsKey(account)].orEmpty())

    suspend fun updateSyncedRatings(account: String, change: (Map<String, Int>) -> Map<String, Int>) =
        edit { it[ratingsKey(account)] = encodeRatings(change(decodeRatings(it[ratingsKey(account)].orEmpty()))) }

    suspend fun pending(account: String): List<PendingPlay> = decodePending(read()[pendingKey(account)].orEmpty())

    suspend fun addPending(account: String, play: PendingPlay) =
        edit { it[pendingKey(account)] = encodePending(decodePending(it[pendingKey(account)].orEmpty()).plusPlay(play)) }

    // A waiting play is done with: sent, or refused for good.
    suspend fun finishPending(account: String, play: PendingPlay, sent: Boolean) = edit { p ->
        p[pendingKey(account)] = encodePending(decodePending(p[pendingKey(account)].orEmpty()) - play)
        if (sent) p[sentKey(account)] = encodeSent(decodeSent(p[sentKey(account)].orEmpty()).plusSent(play.serverId, play.startedAt))
    }

    suspend fun updateSent(account: String, change: (Map<String, SentPlays>) -> Map<String, SentPlays>) =
        edit { it[sentKey(account)] = encodeSent(change(decodeSent(it[sentKey(account)].orEmpty()))) }

    // Forgets everything kept for a server taken off the list.
    suspend fun forget(account: String) = edit { it.forgetAccount(account) }

    private suspend fun read(): Preferences {
        settleOnce()
        return data.data.first()
    }

    private suspend fun edit(change: (MutablePreferences) -> Unit) {
        settleOnce()
        data.edit { change(it) }
    }

    // An older version's values belong to the account it noted.
    private suspend fun settleOnce() {
        if (settled) return
        settle.withLock {
            if (settled) return
            data.edit { p ->
                val server = p[SERVER] ?: return@edit
                p.moveToAccount(PLAIN, accountOfServerKey(server))
                p.remove(SERVER)
            }
            settled = true
        }
    }
}
