package app.winters.octo.desktop.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.desktop.library.LibraryIndex
import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.home.Rediscovery
import app.winters.octo.home.rediscovery
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Home's shelves, kept for the session so going back to Home finds them
// in place, with its scroll. Each visit refreshes them quietly behind what
// is showing.
@Stable
class HomeStore(
    private val connection: Connection,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var data by mutableStateOf<HomeData?>(null)
        private set

    // Why the first read failed, while there is nothing to show.
    var failure by mutableStateOf<String?>(null)
        private set

    // The rediscovery shelves, from the library last worked through.
    var rediscovered by mutableStateOf<Rediscovery<Album>?>(null)
        private set

    private var job: Job? = null
    private var worked: LibraryIndex? = null

    fun refresh() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                data = loadHome(connection)
                failure = null
            } catch (e: SubsonicException) {
                // A failed refresh keeps what is showing.
                if (data == null) failure = e.userMessage()
            }
        }
    }

    // Works out the rediscovery shelves from a library, away from the
    // window's thread, once for each time the library is read.
    fun rediscover(index: LibraryIndex) {
        if (index === worked) return
        worked = index
        scope.launch {
            val shelves = withContext(Dispatchers.Default) { rediscovery(index.albums, listeningOf(index), clock(), SHELF_SIZE) }
            if (worked === index) rediscovered = shelves
        }
    }

    // Tried again by hand, after a failure or on an empty Home: everything
    // read afresh.
    fun retry() {
        job?.cancel()
        data = null
        failure = null
        refresh()
    }
}
