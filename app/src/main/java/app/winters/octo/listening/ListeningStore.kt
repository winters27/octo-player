package app.winters.octo.listening

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.listeningData by preferencesDataStore("listening")
private val SERVER = stringPreferencesKey("server")
private val SYNCED = stringSetPreferencesKey("synced_stars")
private val PENDING = stringSetPreferencesKey("pending_plays")
private val SENT = stringSetPreferencesKey("sent_plays")

// What keeping in step with a server remembers between launches: the stars
// both sides last agreed on, plays waiting to be sent, and plays already
// sent. All of it belongs to one server and user.
@Singleton
class ListeningStore @Inject constructor(@ApplicationContext private val context: Context) {
    val sent: Flow<Map<String, SentPlays>> =
        context.listeningData.data.map { decodeSent(it[SENT].orEmpty()) }.distinctUntilChanged()

    // Starts over when another server or user signs in, since song ids only
    // mean something on the server they came from.
    suspend fun useServer(key: String) {
        context.listeningData.edit { p ->
            if (p[SERVER] != key) {
                p.clear()
                p[SERVER] = key
            }
        }
    }

    suspend fun synced(): Set<String> = context.listeningData.data.first()[SYNCED].orEmpty()

    suspend fun updateSynced(change: (Set<String>) -> Set<String>) {
        context.listeningData.edit { it[SYNCED] = change(it[SYNCED].orEmpty()) }
    }

    suspend fun pending(): List<PendingPlay> = decodePending(context.listeningData.data.first()[PENDING].orEmpty())

    suspend fun addPending(play: PendingPlay) {
        context.listeningData.edit { it[PENDING] = encodePending(decodePending(it[PENDING].orEmpty()).plusPlay(play)) }
    }

    // A waiting play is done with: sent, or refused for good.
    suspend fun finishPending(play: PendingPlay, sent: Boolean) {
        context.listeningData.edit { p ->
            p[PENDING] = encodePending(decodePending(p[PENDING].orEmpty()) - play)
            if (sent) p[SENT] = encodeSent(decodeSent(p[SENT].orEmpty()).plusSent(play.serverId, play.startedAt))
        }
    }

    suspend fun updateSent(change: (Map<String, SentPlays>) -> Map<String, SentPlays>) {
        context.listeningData.edit { it[SENT] = encodeSent(change(decodeSent(it[SENT].orEmpty()))) }
    }
}
