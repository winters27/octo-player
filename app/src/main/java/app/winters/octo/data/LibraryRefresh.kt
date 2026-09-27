package app.winters.octo.data

import app.winters.octo.device.DeviceLibrary
import app.winters.octo.discovery.Downloads
import app.winters.octo.server.ServerSync
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// What a pull on a library page does: rescans the phone and, when a server
// is signed in, copies its library afresh, both at once, and asks how the
// downloads on their way are going.
@Singleton
class LibraryRefresh @Inject constructor(
    private val device: DeviceLibrary,
    private val sessions: SessionRepository,
    private val serverSync: ServerSync,
    private val downloads: Downloads,
) {
    // Returns once both are done, with why the server copy failed, in
    // words, or null when it worked or there is no server.
    suspend fun refresh(): String? = coroutineScope {
        launch { downloads.refreshProgress() }
        val phone = async { device.rescan() }
        val server = async {
            if (sessions.state.value is SessionState.SignedIn) serverSync.syncNowAndWait() else null
        }
        phone.await()
        server.await()
    }
}
