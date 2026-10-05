package app.winters.octo.ui.admin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.admin.AdminStatus
import app.winters.octo.admin.DownloadRecord
import app.winters.octo.admin.LibraryStatus
import app.winters.octo.admin.OctoAdmin
import app.winters.octo.admin.RadioState
import app.winters.octo.ui.common.LoadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import javax.inject.Inject

// Where the admin pages are: still looking, out of reach, or found.
sealed interface AdminPlace {
    data object Looking : AdminPlace
    data object Away : AdminPlace
    data class Found(val base: HttpUrl) : AdminPlace
}

@HiltViewModel
class OctoAdminViewModel @Inject constructor(private val admin: OctoAdmin) : ViewModel() {
    var place by mutableStateOf<AdminPlace>(AdminPlace.Looking)
        private set

    // Each section loads and fails on its own.
    var status by mutableStateOf<LoadState<AdminStatus>>(LoadState.Loading)
        private set
    var radio by mutableStateOf<LoadState<RadioState>>(LoadState.Loading)
        private set
    var library by mutableStateOf<LoadState<LibraryStatus>>(LoadState.Loading)
        private set
    var downloads by mutableStateOf<LoadState<List<DownloadRecord>>>(LoadState.Loading)
        private set

    // A pull to refresh in progress.
    var refreshing by mutableStateOf(false)
        private set

    // A station refresh asked for here, until the new list is read.
    var refreshingStations by mutableStateOf(false)
        private set
    var stationsProblem by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null
    private var stationsJob: Job? = null

    // Counts the loads, so one that was replaced leaves the spinner alone.
    private var generation = 0

    init {
        open(fresh = true)
        // Another server in use: its own admin pages are looked for.
        viewModelScope.launch {
            admin.server.drop(1).collect {
                stationsJob?.cancel()
                refreshingStations = false
                stationsProblem = null
                open(fresh = true)
            }
        }
    }

    fun tryAgain() = open(fresh = true)

    // Keeps what is shown until the new answers arrive.
    fun refresh() {
        if (!refreshing) open(fresh = false)
    }

    // The full admin pages, for everything the app does not show.
    fun pageUrl(): String? = (place as? AdminPlace.Found)?.let { admin.pageUrl(it.base).toString() }

    fun refreshStations() {
        val base = (place as? AdminPlace.Found)?.base ?: return
        if (refreshingStations) return
        refreshingStations = true
        stationsProblem = null
        stationsJob = viewModelScope.launch {
            try {
                admin.refreshStations(base)
                // Octo rebuilds in the background, so the list is read again
                // once it has had time, and again while it is still busy.
                repeat(REFRESH_CHECKS) {
                    delay(REFRESH_WAIT_MS)
                    val next = read("the stations") { admin.radio(base) }
                    if (next is LoadState.Ready) radio = next
                    if ((next as? LoadState.Ready)?.data?.learning?.refreshing != true) return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stationsProblem = "Octo could not start a refresh just now."
            } finally {
                refreshingStations = false
            }
        }
    }

    private fun open(fresh: Boolean) {
        job?.cancel()
        val mine = ++generation
        if (fresh) place = AdminPlace.Looking
        refreshing = !fresh
        job = viewModelScope.launch {
            try {
                val base = try {
                    admin.locate()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (base == null) {
                    place = AdminPlace.Away
                    return@launch
                }
                if (fresh || place != AdminPlace.Found(base)) {
                    status = LoadState.Loading
                    radio = LoadState.Loading
                    library = LoadState.Loading
                    downloads = LoadState.Loading
                }
                place = AdminPlace.Found(base)
                coroutineScope {
                    launch { status = read("Octo's health") { admin.status(base) } }
                    launch { radio = read("the stations") { admin.radio(base) } }
                    launch { library = read("the library folder") { admin.library(base) } }
                    launch { downloads = read("recent downloads") { admin.downloads(base) } }
                }
            } finally {
                if (mine == generation) refreshing = false
            }
        }
    }

    private suspend fun <T> read(what: String, block: suspend () -> T): LoadState<T> = try {
        LoadState.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoadState.Failed("Couldn't load $what just now. Pull down to try again.")
    }

    private companion object {
        // How long Octo gets between looks at a station refresh, and how many looks.
        const val REFRESH_WAIT_MS = 4_000L
        const val REFRESH_CHECKS = 5
    }
}
