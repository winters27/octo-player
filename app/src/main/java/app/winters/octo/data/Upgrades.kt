package app.winters.octo.data

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.playback.isLossless
import app.winters.octo.query.isLosslessFormat
import app.winters.octo.server.ServerSync
import app.winters.octo.sort.formatOf
import app.winters.octo.subsonic.LIBRARY_ACTION_UPGRADE
import app.winters.octo.subsonic.LibraryActionResult
import app.winters.octo.subsonic.LibraryActions
import app.winters.octo.subsonic.OCTO_LIBRARY_ACTIONS
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.Upgrade
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.upgrade.UPGRADE_POLL_MS
import app.winters.octo.ui.upgrade.UPGRADE_RELOAD_GAP_MS
import app.winters.octo.ui.upgrade.UpgradeAsk
import app.winters.octo.ui.upgrade.UpgradeFollower
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

// How often to ask how the upgrades are going while the app is in the
// background; in front it is UPGRADE_POLL_MS.
const val UPGRADE_POLL_AWAY_MS = 30_000L

// Whether a FLAC could take the place of this copy of a song on a server:
// its kind is known to be audio, and it loses detail. An m4a is Apple
// Lossless when the server gave it a bit depth.
fun isUpgradableCopy(copy: SourceTrackEntity): Boolean {
    val mime = copy.mimeType?.takeIf { it.startsWith("audio/", ignoreCase = true) } ?: return false
    val format = formatOf(mime) ?: return false
    return !isLossless(mime) && !isLosslessFormat(format, copy.bitDepth)
}

// What following upgrades needs from the rest of the app.
interface UpgradeHost {
    // Asks the server to look for a FLAC of one song, by its id there.
    // Throws when the server cannot be asked.
    suspend fun ask(serverId: String): LibraryActionResult

    // The songs this user asked FLACs for, and how each is going. Throws
    // when the server cannot be asked right now.
    suspend fun upgrades(): List<Upgrade>

    // Copies the library again, for the new files' kind and size.
    suspend fun reload()

    // Says one line to the listener.
    fun say(text: String)
}

// Asks for FLACs and follows them until each is done: every 3 seconds
// while the app is in front, every 30 behind it, and not at all once
// nothing is still on. What each answer means is the shared follower's
// call; this only runs the calls and the timer.
class UpgradeWatch(
    private val host: UpgradeHost,
    private val scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
    reloadGapMs: Long = UPGRADE_RELOAD_GAP_MS,
) {
    private val follower = UpgradeFollower(reloadGapMs, clock)
    private val _pending = MutableStateFlow<Map<String, Upgrade>>(emptyMap())

    // Each song being looked for, by its id on the server.
    val pending: StateFlow<Map<String, Upgrade>> = _pending

    private var watch: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Whether the app is in front. Coming back asks at once.
    @Volatile
    var foreground: Boolean = true
        set(value) {
            val back = value && !field
            field = value
            if (back) wake.trySend(Unit)
        }

    // Sends the songs to the server one at a time, at most a batch, then
    // says how the asking went and follows those it took on.
    fun request(asks: List<UpgradeAsk>) {
        val taken = follower.ask(asks)
        show()
        scope.launch {
            for (ask in taken) {
                try {
                    follower.answered(ask.id, host.ask(ask.id))
                } catch (e: SubsonicException) {
                    follower.answered(ask.id, null, e.userMessage())
                }
                show()
            }
            follower.askedNotice()?.let(host::say)
            follow()
        }
    }

    // Takes on the songs the server is still looking for from before.
    suspend fun adopt() {
        val list = try {
            host.upgrades()
        } catch (e: SubsonicException) {
            return
        }
        follower.adopt(list)
        show()
        follow()
    }

    private fun follow() {
        synchronized(this) {
            if (watch?.isActive == true) return
            watch = scope.launch {
                while (isActive && follower.isPending()) {
                    poll()
                    withTimeoutOrNull(if (foreground) UPGRADE_POLL_MS else UPGRADE_POLL_AWAY_MS) { wake.receive() }
                }
            }
        }
    }

    // One look at the server's list.
    suspend fun poll() {
        val list = try {
            host.upgrades()
        } catch (e: SubsonicException) {
            return
        }
        val news = follower.seen(list)
        show()
        news.notice?.let(host::say)
        if (news.reload) scope.launch { host.reload() }
    }

    private fun show() {
        _pending.value = follower.pending()
    }

    // A different server or none: nothing more is followed.
    fun forget() {
        synchronized(this) { watch?.cancel() }
        follower.forget()
        show()
    }
}

// "Find higher quality" on the signed-in Octo server: whether it can, which
// library songs it could do it for, and following what was asked. Whether
// it can is read from the extensions the session asks for at each start,
// so a server updated since sign-in is known at the next one.
@Singleton
class Upgrades @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
    private val sources: SourceDao,
    private val catalog: CatalogDao,
    private val sync: ServerSync,
    private val feedback: Feedback,
    private val serverDownloads: ServerDownloads,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    // Copies run one after another, so none started for an upgrade is lost.
    private val reloading = Mutex()

    private val _actions = MutableStateFlow<LibraryActions?>(null)

    // Whether the server can really look for a FLAC now.
    val canUpgrade: StateFlow<Boolean> = _actions.map { it?.canUpgrade == true }.stateIn(scope, SharingStarted.Eagerly, false)

    // Where the server looks for a better copy, when it says.
    val source: StateFlow<String?> = _actions.map { it?.upgradeSource }.stateIn(scope, SharingStarted.Eagerly, null)

    private val watch = UpgradeWatch(
        object : UpgradeHost {
            override suspend fun ask(serverId: String): LibraryActionResult {
                val client = session()?.client ?: return LibraryActionResult(serverId, LIBRARY_ACTION_UPGRADE, "skipped", "Not signed in")
                return client.libraryAction(serverId, LIBRARY_ACTION_UPGRADE)
            }

            override suspend fun upgrades(): List<Upgrade> {
                val client = session()?.client ?: return emptyList()
                return client.upgrades()
            }

            // A copy already running may have read the server before the
            // FLAC landed, so then one more runs after it.
            override suspend fun reload() {
                reloading.withLock {
                    val busy = sync.syncing.value
                    sync.syncNowAndWait()
                    if (busy) sync.syncNowAndWait()
                }
            }

            // With the server downloads sheet, the line offers to show it.
            override fun say(text: String) =
                if (serverDownloads.supported.value) feedback.show(text, "Show", onAction = { serverDownloads.open() }) else feedback.show(text)
        },
        scope,
    )

    // Each song being looked for, by its id on the server.
    val pending: StateFlow<Map<String, Upgrade>> = watch.pending

    fun start() {
        if (running) return
        running = true
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(ForegroundCallbacks())
        watch.foreground = false
        scope.launch {
            // A new sign-in, or the same one with its extensions read again.
            sessions.state.distinctUntilChangedBy { (it as? SessionState.SignedIn)?.session }.collect { state ->
                watch.forget()
                _actions.value = null
                val session = (state as? SessionState.SignedIn)?.session ?: return@collect
                if ("$OCTO_LIBRARY_ACTIONS:2" !in session.extensions) return@collect
                _actions.value = try {
                    session.client.libraryActions()
                } catch (e: SubsonicException) {
                    null
                }
                watch.adopt()
            }
        }
    }

    // The library songs among `trackIds` a FLAC could replace, as the
    // server knows them: each one's copy on the server, when that copy
    // loses detail and is not being looked for already. A song with any
    // lossless copy there has its FLAC already.
    suspend fun upgradable(trackIds: List<String>): List<UpgradeAsk> {
        if (!canUpgrade.value) return emptyList()
        val session = session() ?: return emptyList()
        val pending = pending.value
        return trackIds.mapNotNull { trackId ->
            val onServer = sources.copies(trackId).filter { it.sourceId == session.sourceId }
            if (onServer.isEmpty() || !onServer.all(::isUpgradableCopy)) return@mapNotNull null
            val copy = onServer.minBy { it.nativeId }
            UpgradeAsk(copy.nativeId, copy.title).takeIf { it.id !in pending }
        }
    }

    // The same for an album's songs.
    suspend fun upgradableInAlbum(albumId: String): List<UpgradeAsk> = upgradable(catalog.albumTrackIds(albumId))

    fun request(asks: List<UpgradeAsk>) {
        if (!canUpgrade.value || asks.isEmpty()) return
        watch.request(asks)
    }

    private fun session(): Session? = (sessions.state.value as? SessionState.SignedIn)?.session

    // Whether any of the app's screens is in front: the server is asked
    // less often while none is.
    private inner class ForegroundCallbacks : Application.ActivityLifecycleCallbacks {
        private val started = AtomicInteger(0)

        override fun onActivityStarted(activity: Activity) {
            if (started.incrementAndGet() == 1) watch.foreground = true
        }

        override fun onActivityStopped(activity: Activity) {
            if (started.decrementAndGet() <= 0) {
                started.set(0)
                watch.foreground = false
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
