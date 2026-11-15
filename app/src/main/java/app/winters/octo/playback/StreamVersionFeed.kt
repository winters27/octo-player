package app.winters.octo.playback

import android.content.Context
import android.content.SharedPreferences
import app.winters.octo.data.Session
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.subsonic.STREAM_VERSION_CHANGES_EXTENSION
import app.winters.octo.subsonic.StreamVersionChanges
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.versions.StreamVersionCursor
import app.winters.octo.versions.planFeed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// The outside songs whose chosen version changed, read from the signed-in
// Octo server (getStreamVersionChanges) so this phone lets go of saved
// copies of a video the server no longer plays: at each sign-in, when the
// app comes to the front, after a pick here, and before a play reads a
// saved copy of an outside song. One read at a time: asking while one runs
// waits for that one. Every outside song's copies go when no cursor was
// kept for the server, when its epoch changed, or when its answer is not
// whole (see planFeed). Where each server's reading stands is kept per
// server.
@Singleton
class StreamVersionFeed internal constructor(
    private val port: FeedPort,
    private val drops: VersionDropper,
    private val scope: CoroutineScope,
) {
    @Inject constructor(sessions: SessionRepository, drops: VersionDrops, @ApplicationContext context: Context) :
        this(
            SessionFeedPort(sessions, context.getSharedPreferences("stream_version_cursors", Context.MODE_PRIVATE)),
            drops,
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

    private val lock = Any()
    private var running: Deferred<Unit>? = null

    // The plays already checked before their first read, by play token.
    private val opened: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Reads at each sign-in, another server's included. Once, at launch.
    fun start() {
        scope.launch { port.signedIn.collect { if (it != null) refresh() } }
    }

    // The app came to the front.
    fun foreground() {
        scope.launch { refresh() }
    }

    // Whether this play (by its token) is checked here for the first time.
    fun firstOpen(play: String): Boolean {
        if (opened.size > OPENS_KEPT) opened.clear()
        return opened.add(play)
    }

    // Reads the changes and lets go of what they list, joining a read that
    // runs. Returns once it is done or failed.
    suspend fun refresh() {
        val read = synchronized(lock) { running?.takeIf { it.isActive } ?: scope.async { read() }.also { running = it } }
        read.join()
    }

    private suspend fun read() {
        val server = port.server() ?: return
        val stored = port.cursor(server)
        val answer = try {
            port.changes(server, stored?.epoch, stored?.seq)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        val plan = planFeed(stored, answer)
        if (plan.dropAll) drops.dropOutside(server)
        if (plan.dropIds.isNotEmpty()) drops.drop(server, plan.dropIds)
        port.keep(server, plan.next)
    }
}

// How many checked plays are remembered before the list starts again.
internal const val OPENS_KEPT = 512

// What the feed needs of the signed-in server and the phone.
interface FeedPort {
    // The server signed in now (its source) when it lists
    // octoStreamVersionChanges, else null, as that changes.
    val signedIn: Flow<String?>

    fun server(): String?

    // The changes after the cursor, from that server; another server signed
    // in by now is a failure.
    suspend fun changes(sourceId: String, epoch: String?, since: Long?): StreamVersionChanges

    fun cursor(sourceId: String): StreamVersionCursor?

    fun keep(sourceId: String, cursor: StreamVersionCursor)
}

internal class SessionFeedPort(private val sessions: SessionRepository, private val prefs: SharedPreferences) : FeedPort {
    private fun reads(session: Session?): Session? =
        session?.takeIf { it.isOcto && "$STREAM_VERSION_CHANGES_EXTENSION:1" in it.extensions }

    private fun session(): Session? = reads((sessions.state.value as? SessionState.SignedIn)?.session)

    override val signedIn: Flow<String?> =
        sessions.state.map { reads((it as? SessionState.SignedIn)?.session)?.sourceId }.distinctUntilChanged()

    override fun server(): String? = session()?.sourceId

    override suspend fun changes(sourceId: String, epoch: String?, since: Long?): StreamVersionChanges {
        val session = session()?.takeIf { it.sourceId == sourceId } ?: throw SubsonicException.Unreachable(IOException("Another server is in use"))
        return session.client.streamVersionChanges(epoch, since)
    }

    override fun cursor(sourceId: String): StreamVersionCursor? =
        prefs.getString(sourceId, null)?.let { runCatching { cursorJson.decodeFromString(StreamVersionCursor.serializer(), it) }.getOrNull() }

    override fun keep(sourceId: String, cursor: StreamVersionCursor) {
        prefs.edit().putString(sourceId, cursorJson.encodeToString(StreamVersionCursor.serializer(), cursor)).apply()
    }
}

private val cursorJson = Json { ignoreUnknownKeys = true }
