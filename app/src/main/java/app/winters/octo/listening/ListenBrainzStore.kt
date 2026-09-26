package app.winters.octo.listening

import android.content.Context
import android.util.AtomicFile
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.data.CredentialVault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.listenBrainzData by preferencesDataStore("listenbrainz")
private val ENABLED = booleanPreferencesKey("enabled")
private val USER = stringPreferencesKey("user")
private val TOKEN_SEALED = stringPreferencesKey("token_sealed")
private val ATTENTION = booleanPreferencesKey("needs_attention")
private val SEND_PLAYS = stringPreferencesKey("send_plays")
private val NOW_PLAYING = booleanPreferencesKey("now_playing")

// The key the token is sealed with, apart from the server password's, so
// signing out of the server keeps ListenBrainz connected.
private const val TOKEN_KEY = "octo.listenbrainz.token"

// The ListenBrainz settings. `user` is who the saved token belongs to, or
// null when none is saved.
data class ListenBrainzPrefs(
    val enabled: Boolean = false,
    val user: String? = null,
    val needsAttention: Boolean = false,
    val sendPlays: SendPlays = SendPlays.All,
    val nowPlaying: Boolean = true,
) {
    val connected: Boolean get() = user != null

    // Plays are kept while switched on and connected, even while the token
    // needs attention, so none are lost before it is fixed.
    val keepsPlays: Boolean get() = enabled && connected

    val sends: Boolean get() = keepsPlays && !needsAttention
}

// What ListenBrainz scrobbling remembers: its settings and the sealed token
// in DataStore, and the plays waiting to be sent in a file of their own.
@Singleton
class ListenBrainzStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val vault = CredentialVault(TOKEN_KEY)
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Listen.serializer())
    private val file = AtomicFile(File(context.filesDir, "listenbrainz_queue.json"))
    private val queueLock = Mutex()
    private var cached: List<Listen>? = null
    private val _queued = MutableStateFlow(0)

    // How many plays are waiting.
    val queued: StateFlow<Int> = _queued

    val prefs: Flow<ListenBrainzPrefs> = context.listenBrainzData.data.map(::prefsOf).distinctUntilChanged()

    suspend fun current(): ListenBrainzPrefs = prefs.first()

    // The token, opened only for the call that needs it. Null when none is
    // saved, or when its key is gone (a backup restored on another phone).
    suspend fun token(): String? = context.listenBrainzData.data.first()[TOKEN_SEALED]?.let(vault::open)

    // Saves a token that was just accepted. Plays waiting for someone else
    // are let go.
    suspend fun connect(token: String, user: String) {
        val sealed = vault.seal(token)
        var otherUser = false
        context.listenBrainzData.edit { p ->
            otherUser = p[USER] != null && p[USER] != user
            p[TOKEN_SEALED] = sealed
            p[USER] = user
            p[ATTENTION] = false
            p[ENABLED] = true
        }
        if (otherUser) updateQueue { emptyList() }
    }

    suspend fun disconnect() {
        context.listenBrainzData.edit { p ->
            p.remove(TOKEN_SEALED)
            p.remove(USER)
            p.remove(ATTENTION)
            p[ENABLED] = false
        }
        vault.forget()
        updateQueue { emptyList() }
    }

    suspend fun setNeedsAttention(on: Boolean) = context.listenBrainzData.edit { it[ATTENTION] = on }

    suspend fun setEnabled(on: Boolean) = context.listenBrainzData.edit { it[ENABLED] = on }

    suspend fun setSendPlays(mode: SendPlays) = context.listenBrainzData.edit { it[SEND_PLAYS] = mode.name }

    suspend fun setNowPlaying(on: Boolean) = context.listenBrainzData.edit { it[NOW_PLAYING] = on }

    suspend fun queue(): List<Listen> = queueLock.withLock { load() }

    suspend fun addListen(listen: Listen) = updateQueue { it.plusListen(listen) }

    // Takes sent (or refused) plays out of the queue.
    suspend fun removeListens(done: List<Listen>) {
        val gone = done.toSet()
        updateQueue { queue -> queue.filterNot { it in gone } }
    }

    // Reads the count once at start, for the settings.
    suspend fun loadQueue() {
        queueLock.withLock { load() }
    }

    private suspend fun updateQueue(change: (List<Listen>) -> List<Listen>) = queueLock.withLock {
        val next = change(load())
        withContext(Dispatchers.IO) {
            val out = file.startWrite()
            try {
                out.write(json.encodeToString(listSerializer, next).toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        }
        cached = next
        _queued.value = next.size
    }

    private suspend fun load(): List<Listen> {
        cached?.let { return it }
        val read = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString(listSerializer, file.readFully().toString(Charsets.UTF_8)) }.getOrDefault(emptyList())
        }
        cached = read
        _queued.value = read.size
        return read
    }

    private fun prefsOf(p: Preferences) = ListenBrainzPrefs(
        enabled = p[ENABLED] ?: false,
        user = p[USER]?.takeIf { p[TOKEN_SEALED] != null },
        needsAttention = p[ATTENTION] ?: false,
        sendPlays = SendPlays.entries.firstOrNull { it.name == p[SEND_PLAYS] } ?: SendPlays.All,
        nowPlaying = p[NOW_PLAYING] ?: true,
    )
}
