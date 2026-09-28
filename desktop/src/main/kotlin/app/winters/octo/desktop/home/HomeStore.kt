package app.winters.octo.desktop.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Home's shelves, kept for the session so going back to Home finds them
// in place, with its scroll. Each visit refreshes them quietly behind what
// is showing.
@Stable
class HomeStore(private val connection: Connection, private val scope: CoroutineScope) {
    var data by mutableStateOf<HomeData?>(null)
        private set

    // Why the first read failed, while there is nothing to show.
    var failure by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    fun refresh() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                data = keepRandom(data, loadHome(connection))
                failure = null
            } catch (e: SubsonicException) {
                // A failed refresh keeps what is showing.
                if (data == null) failure = e.userMessage()
            }
        }
    }

    // Tried again by hand, after a failure: everything read afresh.
    fun retry() {
        job?.cancel()
        data = null
        failure = null
        refresh()
    }
}

// Fresh shelves, keeping the random pick already showing so it does not
// reshuffle on every visit. It changes when new albums have come in.
fun keepRandom(shown: HomeData?, fresh: HomeData): HomeData =
    if (shown != null && shown.random.isNotEmpty() && shown.recentlyAdded.map { it.id } == fresh.recentlyAdded.map { it.id }) {
        fresh.copy(random = shown.random)
    } else {
        fresh
    }
