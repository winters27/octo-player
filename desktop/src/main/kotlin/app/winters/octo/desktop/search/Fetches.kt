package app.winters.octo.desktop.search

import app.winters.octo.subsonic.Acquisition
import app.winters.octo.subsonic.AcquisitionStage
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Where fetching one song into the library has got to, as the "+" button
// shows it: the plus, a ring filling, a check, or an alert.
sealed interface FetchPhase {
    data object None : FetchPhase
    data object Queued : FetchPhase
    data class Downloading(val progress: Float?) : FetchPhase
    data object Adding : FetchPhase
    data object Done : FetchPhase
    data class Failed(val reason: String) : FetchPhase
}

// What the server's word for a download means, as on the phone. A finished
// download is "adding" until the server has taken it into the library.
fun phaseOf(acquisition: Acquisition): FetchPhase = when (acquisition.stage) {
    AcquisitionStage.Queued, AcquisitionStage.Searching, AcquisitionStage.Unknown -> FetchPhase.Queued
    AcquisitionStage.Downloading -> FetchPhase.Downloading(acquisition.fraction)
    AcquisitionStage.Verifying, AcquisitionStage.Importing -> FetchPhase.Adding
    AcquisitionStage.Done -> FetchPhase.Done
    AcquisitionStage.Failed -> FetchPhase.Failed(acquisition.error?.trim()?.takeIf(String::isNotEmpty) ?: "the server did not say why")
}

// The words a screen reader says for a phase.
fun phaseText(phase: FetchPhase): String = when (phase) {
    FetchPhase.None -> "Not in your library"
    FetchPhase.Queued -> "Queued"
    is FetchPhase.Downloading -> phase.progress?.let { "Downloading, ${(it * 100).toInt()} percent" } ?: "Downloading"
    FetchPhase.Adding -> "Adding to your library"
    FetchPhase.Done -> "In your library"
    is FetchPhase.Failed -> "Could not add it: ${phase.reason}"
}

// How often to ask how the downloads are going while any is on its way.
const val FETCH_POLL_MS = 2_000L

// Has an Octo server fetch songs found online into the library: starring a
// find asks for it, and the server's octoAcquisitions list says how each is
// going. Only used when the server lists that extension.
class Fetches(
    private val client: SubsonicClient,
    private val scope: CoroutineScope,
    // Called when a song has arrived, so the library is read again.
    private val onArrived: () -> Unit = {},
    private val pollMs: Long = FETCH_POLL_MS,
) {
    private val _phases = MutableStateFlow<Map<String, FetchPhase>>(emptyMap())

    // Each song asked for, by its id on the server.
    val phases: StateFlow<Map<String, FetchPhase>> = _phases

    private var watch: Job? = null

    fun phase(id: String): FetchPhase = phases.value[id] ?: FetchPhase.None

    // Asks the server to fetch a song. A failed one can be asked again.
    fun request(id: String) {
        val now = phase(id)
        if (now != FetchPhase.None && now !is FetchPhase.Failed) return
        _phases.update { it + (id to FetchPhase.Queued) }
        scope.launch {
            try {
                client.star(listOf(id))
                follow()
            } catch (e: SubsonicException) {
                _phases.update { it + (id to FetchPhase.Failed("the server could not be asked")) }
            }
        }
    }

    // Asks how things are going until nothing is on its way.
    private fun follow() {
        if (watch?.isActive == true) return
        watch = scope.launch {
            while (isActive && _phases.value.values.any { it.inFlight }) {
                poll()
                delay(pollMs)
            }
        }
    }

    // One look at the server's list. Each song asked for takes the newest
    // entry with its id.
    suspend fun poll() {
        val list = try {
            client.acquisitions()
        } catch (e: SubsonicException) {
            return
        }
        var arrived = false
        _phases.update { phases ->
            phases.mapValues { (id, phase) ->
                if (!phase.inFlight) return@mapValues phase
                val entry = list.filter { it.id == id }.maxByOrNull { it.startedAt.orEmpty() } ?: return@mapValues phase
                phaseOf(entry).also { if (it == FetchPhase.Done) arrived = true }
            }
        }
        if (arrived) onArrived()
    }

    private val FetchPhase.inFlight: Boolean
        get() = this == FetchPhase.Queued || this is FetchPhase.Downloading || this == FetchPhase.Adding
}
