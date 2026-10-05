package app.winters.octo.desktop.upgrade

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.subsonic.LIBRARY_ACTION_UPGRADE
import app.winters.octo.subsonic.LibraryActions
import app.winters.octo.subsonic.OCTO_LIBRARY_ACTIONS
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.Upgrade
import app.winters.octo.ui.upgrade.UPGRADE_POLL_MS
import app.winters.octo.ui.upgrade.UPGRADE_RELOAD_GAP_MS
import app.winters.octo.ui.upgrade.UpgradeAsk
import app.winters.octo.ui.upgrade.UpgradeFollower
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// How often to ask again whether the server can look for FLACs: a server
// updated while the app is open gains the action without a new sign-in.
const val UPGRADE_RECHECK_MS = 10 * 60_000L

// "Find higher quality" for one server: whether it can, the songs being looked
// for, and the line said when some are done. The server queues each song
// and answers at once; its getUpgrades list says how each goes, asked
// only while something is still on. Made with each connection and closed
// with it, so nothing here outlives the server it asks.
@Stable
class UpgradeModel(
    private val client: SubsonicClient,
    parent: CoroutineScope,
    // Reads the library again, for the new files' kind and size.
    private val reload: () -> Unit,
    // Shows one line in the window's notice: what went wrong asking, and,
    // when the two below are not given, every other line too.
    private val notify: (String) -> Unit,
    // Told when the server took songs on, and when some are done. With a
    // downloads drawer these lines belong there, not in a notice that stays.
    private val asked: ((String) -> Unit)? = null,
    private val done: ((String) -> Unit)? = null,
    private val pollMs: Long = UPGRADE_POLL_MS,
    private val recheckMs: Long = UPGRADE_RECHECK_MS,
    reloadGapMs: Long = UPGRADE_RELOAD_GAP_MS,
    clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val follower = UpgradeFollower(reloadGapMs, clock)
    private var watch: Job? = null

    // What the server lets this user do, asked live: the extension list
    // saved at sign-in would miss a server updated since. Null until it has
    // said, and on a server without version 2.
    var actions by mutableStateOf<LibraryActions?>(null)
        private set

    // Each song being looked for, by id, as the server last said.
    var pending by mutableStateOf<Map<String, Upgrade>>(emptyMap())
        private set

    val canUpgrade: Boolean get() = actions?.canUpgrade == true

    // Where the server looks for a better copy, when it says.
    val source: String? get() = actions?.upgradeSource

    // Asks whether the server can look for FLACs now and every ten minutes
    // after, and takes on any songs it is still looking for from before.
    fun start() {
        scope.launch {
            var first = true
            while (isActive) {
                check()
                if (first && actions != null) {
                    first = false
                    adopt()
                }
                delay(recheckMs)
            }
        }
    }

    private suspend fun check() {
        actions = try {
            when (client.supportsIfKnown(OCTO_LIBRARY_ACTIONS, 2)) {
                true -> client.libraryActions()
                false -> null
                null -> actions
            }
        } catch (e: SubsonicException) {
            // Kept as it was: a moment without the server changes nothing.
            actions
        }
    }

    private suspend fun adopt() {
        val list = try {
            client.upgrades()
        } catch (e: SubsonicException) {
            return
        }
        follower.adopt(list)
        show()
        follow()
    }

    // Asks the server to look for a FLAC of each song, one at a time, at
    // most a batch at once. Songs already being looked for are left alone.
    fun request(songs: List<Song>) {
        if (!canUpgrade) return
        val asks = follower.ask(songs.map { UpgradeAsk(it.id, it.title) })
        show()
        scope.launch {
            for (ask in asks) {
                try {
                    follower.answered(ask.id, client.libraryAction(ask.id, LIBRARY_ACTION_UPGRADE))
                } catch (e: SubsonicException) {
                    follower.answered(ask.id, null, e.userMessage())
                }
                show()
            }
            if (asked == null) {
                follower.askedNotice()?.let(notify)
            } else {
                val lines = follower.askedLines()
                lines.asked?.let(asked)
                lines.problems?.let(notify)
            }
            follow()
        }
    }

    // Asks how things are going until nothing is still on.
    private fun follow() {
        if (watch?.isActive == true) return
        watch = scope.launch {
            while (isActive && follower.isPending()) {
                poll()
                delay(pollMs)
            }
        }
    }

    // One look at the server's list.
    suspend fun poll() {
        val list = try {
            client.upgrades()
        } catch (e: SubsonicException) {
            return
        }
        val news = follower.seen(list)
        show()
        news.notice?.let(done ?: notify)
        if (news.reload) reload()
    }

    private fun show() {
        pending = follower.pending()
    }

    // The server is left: nothing more is asked of it.
    fun close() {
        scope.cancel()
        follower.forget()
        pending = emptyMap()
        actions = null
    }
}
