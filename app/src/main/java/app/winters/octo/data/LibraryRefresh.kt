package app.winters.octo.data

import app.winters.octo.device.DeviceLibrary
import app.winters.octo.discovery.Downloads
import app.winters.octo.server.ServerSync
import app.winters.octo.ui.common.Feedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// A pull: the phone is rescanned while the pull waits, and the server's
// copy (`copy`, null with no server) starts behind it in `behind`, where
// its failure is `said` once it ends, even after the page is left.
internal suspend fun pull(rescan: suspend () -> Unit, copy: (suspend () -> String?)?, behind: CoroutineScope, said: (String) -> Unit) {
    if (copy != null) behind.launch { copy()?.let(said) }
    rescan()
}

// What a pull on a library page does: rescans the phone and, when a server
// is signed in, copies its library afresh (a whole copy already running is
// waited for instead), and asks how the downloads on their way are going.
// The pull ends with the rescan: a copy can take minutes, and shows its own
// progress.
@Singleton
class LibraryRefresh @Inject constructor(
    private val device: DeviceLibrary,
    private val sessions: SessionRepository,
    private val serverSync: ServerSync,
    private val downloads: Downloads,
    private val feedback: Feedback,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun refresh() = coroutineScope {
        launch { downloads.refreshProgress() }
        val copy = if (sessions.state.value is SessionState.SignedIn) serverSync::copyAfreshAndWait else null
        pull({ device.rescan() }, copy, scope, feedback::show)
    }
}
