package app.winters.octo.listening

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

// What keeping in step with each kept server remembers between launches:
// the stars and ratings both sides last agreed on, plays waiting to be sent,
// and plays already sent. Each account's are its own, by the kept server's
// id, since song ids only mean something on the server they came from: a
// switch to another server and back loses none of them. They are rows in
// the catalog database, read and written a song at a time. Older versions
// kept them in a preferences file; those values are taken in once, before
// anything here is read or written.
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ListeningStore internal constructor(
    private val data: DataStore<Preferences>,
    // The account in use, or null with none.
    account: Flow<String?>,
    private val rows: ListeningDao,
) {
    @Inject constructor(@ApplicationContext context: Context, sessions: SessionRepository, rows: ListeningDao) :
        this(context.listeningData, sessions.state.map { it.accountId }, rows)

    private val settle = Mutex()
    @Volatile private var settled = false

    private val importing = Mutex()
    @Volatile private var imported = false

    // The plays sent to the server in use.
    val sent: Flow<Map<String, SentPlays>> = account.distinctUntilChanged().flatMapLatest { who ->
        if (who == null) {
            flowOf(emptyMap())
        } else {
            flow {
                ready()
                emitAll(rows.sentRows(who).map(::sentOf))
            }
        }
    }.distinctUntilChanged()

    suspend fun addSyncedStars(account: String, serverIds: List<String>) {
        ready()
        rows.insertSyncedStars(serverIds.map { SyncedStarEntity(account, it) })
    }

    suspend fun removeSyncedStars(account: String, serverIds: List<String>) {
        ready()
        rows.removeSyncedStars(account, serverIds)
    }

    // One chunk of the stars walk: `synced` replaces the record of the
    // server songs in `chunk`.
    suspend fun settleStars(account: String, chunk: List<String>, synced: Set<String>) {
        ready()
        rows.settleStars(account, chunk, synced.map { SyncedStarEntity(account, it) })
    }

    suspend fun setSyncedRatings(account: String, serverIds: List<String>, rating: Int) {
        ready()
        rows.putSyncedRatings(serverIds.map { SyncedRatingEntity(account, it, rating) })
    }

    suspend fun clearSyncedRatings(account: String, serverIds: List<String>) {
        ready()
        rows.clearSyncedRatings(account, serverIds)
    }

    // One chunk of the ratings walk, as settleStars.
    suspend fun settleRatings(account: String, chunk: List<String>, synced: Map<String, Int>) {
        ready()
        rows.settleRatings(account, chunk, synced.filterValues { it in 1..5 }.map { (id, rating) -> SyncedRatingEntity(account, id, rating) })
    }

    // Oldest first, at most MAX_PENDING_PLAYS.
    suspend fun pending(account: String): List<PendingPlay> {
        ready()
        return rows.pending(account).map { PendingPlay(it.serverId, it.startedAt) }
    }

    suspend fun addPending(account: String, play: PendingPlay) {
        ready()
        rows.addPending(PendingPlayEntity(account, play.serverId, play.startedAt), MAX_PENDING_PLAYS)
    }

    // A waiting play is done with: sent, or refused for good.
    suspend fun finishPending(account: String, play: PendingPlay, sent: Boolean) {
        ready()
        rows.finishPending(PendingPlayEntity(account, play.serverId, play.startedAt), sent)
    }

    // After a sync of the server under `sourceId` (see ListeningDao.foldSent).
    suspend fun foldSent(account: String, sourceId: String) {
        ready()
        rows.foldSent(account, sourceId)
    }

    // Forgets everything kept for a server taken off the list.
    suspend fun forget(account: String) {
        ready()
        rows.forget(account)
        data.edit { it.forgetAccount(account) }
    }

    // Takes in an older version's values on first use, and waits for that.
    // The rows go in with the mark that they are in, in one transaction, and
    // the old values are removed after: a crash in between takes in nothing
    // twice, and the next start just removes them.
    suspend fun ready() {
        if (imported) return
        importing.withLock {
            if (imported) return
            settleOnce()
            if (!rows.imported()) rows.importAll(importOf(data.data.first()), System.currentTimeMillis())
            data.edit { p -> p.asMap().keys.filter { isListeningKey(it.name) }.forEach { p.remove(it) } }
            imported = true
        }
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

// Whether a preferences name is one of an account's listening values.
private fun isListeningKey(name: String): Boolean = '@' in name && name.substringBefore('@') in PLAIN

// An older version's values as rows: each "<name>@<account>" set.
internal fun importOf(prefs: Preferences): ListeningImport {
    val stars = ArrayList<SyncedStarEntity>()
    val ratings = ArrayList<SyncedRatingEntity>()
    val pending = ArrayList<PendingPlayEntity>()
    val sentPlays = ArrayList<SentPlayEntity>()
    val sentRecent = ArrayList<SentRecentEntity>()
    for ((key, value) in prefs.asMap()) {
        if (!isListeningKey(key.name)) continue
        val lines = (value as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: continue
        val account = key.name.substringAfter('@')
        when (key.name.substringBefore('@')) {
            "synced_stars" -> lines.forEach { stars += SyncedStarEntity(account, it) }
            "synced_ratings" -> decodeRatings(lines).forEach { (id, rating) -> ratings += SyncedRatingEntity(account, id, rating) }
            "pending_plays" -> decodePending(lines).forEach { pending += PendingPlayEntity(account, it.serverId, it.startedAt) }
            "sent_plays" -> decodeSent(lines).forEach { (id, plays) ->
                sentPlays += SentPlayEntity(account, id, plays.folded)
                plays.recent.forEach { sentRecent += SentRecentEntity(account, id, it) }
            }
        }
    }
    return ListeningImport(stars, ratings, pending, sentPlays, sentRecent)
}

// The sent plays of one account, by server song, from rows in song order
// with each song's newer starts in time order.
internal fun sentOf(rows: List<SentRow>): Map<String, SentPlays> {
    val sent = LinkedHashMap<String, SentPlays>()
    for (row in rows) {
        val before = sent[row.serverId] ?: SentPlays(row.folded)
        sent[row.serverId] = if (row.startedAt == null) before else before.copy(recent = before.recent + row.startedAt)
    }
    return sent
}
