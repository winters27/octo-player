package app.winters.octo.desktop.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.winters.octo.audio.NvidiaChange
import app.winters.octo.audio.NvidiaOverlay
import app.winters.octo.audio.keepNvidiaOverlayOff
import app.winters.octo.audio.nvidiaOverlay
import app.winters.octo.desktop.pages.ActionRow
import app.winters.octo.desktop.pages.InfoRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

// NVIDIA's driver treats an app it does not know that draws through Direct3D
// as a game, and its overlay pops up on Octo each time it opens. NVIDIA marks
// desktop and media apps (Spotify, Apple Music) as not games in its own
// settings; only an administrator may add Octo, so Settings offers it as a
// button and Windows asks first (audio-engine nvidia_profile).

const val NVIDIA_OVERLAY_TITLE = "NVIDIA overlay"
const val NVIDIA_OFFER = "Stops NVIDIA's game overlay popping up when Octo opens. Windows asks first."
const val NVIDIA_UNDONE = "NVIDIA's settings no longer keep it off Octo."
const val NVIDIA_NOT_ALLOWED = "Windows didn't allow the change."
const val NVIDIA_FAILED = "NVIDIA's settings couldn't be changed."
const val NVIDIA_REOPEN = "Takes effect after you quit and reopen Octo."
const val NVIDIA_KEEP_OFF = "Keep it off Octo"
const val NVIDIA_WAITING = "Waiting for Windows"
const val NVIDIA_KEPT_OFF = "Kept off Octo"

// What the NVIDIA row shows.
sealed interface NvidiaRow {
    // No NVIDIA driver, no helper in this build, or run from source.
    data object Hidden : NvidiaRow

    // The button, with a line about the last try or the setting being undone.
    data class Offer(val note: String?) : NvidiaRow

    // Windows' prompt or the helper is still going.
    data object Waiting : NvidiaRow

    // Kept off; `justNow` until Octo next opens, as the change waits for it.
    data class Kept(val justNow: Boolean) : NvidiaRow
}

// The row for a reading of the driver's settings.
internal fun rowFor(state: NvidiaOverlay?): NvidiaRow = when (state) {
    NvidiaOverlay.ON -> NvidiaRow.Offer(null)
    NvidiaOverlay.UNDONE -> NvidiaRow.Offer(NVIDIA_UNDONE)
    NvidiaOverlay.KEPT_OFF -> NvidiaRow.Kept(justNow = false)
    NvidiaOverlay.NO_NVIDIA, null -> NvidiaRow.Hidden
}

// The row after a try, given what the settings read afterwards.
internal fun rowAfter(change: NvidiaChange, after: () -> NvidiaOverlay?): NvidiaRow = when (change) {
    NvidiaChange.DONE -> if (after() == NvidiaOverlay.KEPT_OFF) NvidiaRow.Kept(justNow = true) else NvidiaRow.Offer(NVIDIA_FAILED)
    NvidiaChange.CANCELLED, NvidiaChange.REFUSED -> NvidiaRow.Offer(NVIDIA_NOT_ALLOWED)
    NvidiaChange.NO_NVIDIA -> NvidiaRow.Hidden
    NvidiaChange.ELSEWHERE, NvidiaChange.FAILED -> NvidiaRow.Offer(NVIDIA_FAILED)
}

// The installed Octo's own exe, as its launcher names it; null when run
// from source (that process is the JDK's java.exe, never to be added).
internal fun octoExe(path: String? = System.getProperty("jpackage.app-path")): String? =
    path?.takeIf(String::isNotBlank)?.takeIf { File(it).isFile }

// The helper beside the app's native libraries; null when this build has none.
internal fun nvidiaHelper(resources: String? = System.getProperty(APP_RESOURCES_PROPERTY)): File? =
    resources?.takeIf(String::isNotBlank)?.let { File(it, "octo-nvidia.exe") }?.takeIf(File::isFile)

// The row's state for the whole app, so closing Settings while Windows asks
// loses nothing and a second click while waiting does nothing.
class NvidiaOverlayHolder(
    private val exe: () -> String?,
    private val helper: () -> File?,
    private val read: (String) -> NvidiaOverlay,
    private val change: (String, String) -> NvidiaChange,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow<NvidiaRow>(NvidiaRow.Hidden)
    val row: StateFlow<NvidiaRow> = state

    private fun readNow(path: String): NvidiaOverlay? = runCatching { read(path) }.getOrNull()

    // Reads the driver's settings again, unless a change is under way or
    // was just made.
    fun refresh() {
        val path = exe() ?: return
        if (helper() == null) return
        scope.launch {
            val now = rowFor(readNow(path))
            state.update { current -> if (current is NvidiaRow.Waiting || current == NvidiaRow.Kept(justNow = true)) current else now }
        }
    }

    // Asks Windows to keep the overlay off Octo; only from the button.
    fun keepOff() {
        val path = exe() ?: return
        val tool = helper() ?: return
        val before = state.value
        if (before !is NvidiaRow.Offer || !state.compareAndSet(before, NvidiaRow.Waiting)) return
        scope.launch {
            val done = runCatching { change(tool.path, path) }.getOrDefault(NvidiaChange.FAILED)
            state.value = rowAfter(done) { readNow(path) }
        }
    }

    // A line for bug reports.
    fun reportLine(): String {
        val path = exe() ?: return "NVIDIA overlay: not installed"
        return "NVIDIA overlay: " + when (readNow(path)) {
            NvidiaOverlay.KEPT_OFF -> "kept off"
            NvidiaOverlay.ON -> "on"
            NvidiaOverlay.UNDONE -> "undone"
            NvidiaOverlay.NO_NVIDIA -> "none"
            null -> "?"
        }
    }
}

// The app's one holder.
val NvidiaOverlays: NvidiaOverlayHolder by lazy {
    NvidiaOverlayHolder(
        exe = { octoExe() },
        helper = { nvidiaHelper() },
        read = ::nvidiaOverlay,
        change = ::keepNvidiaOverlayOff,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
}

// The row in Settings > System; read again each time the page opens.
@Composable
fun NvidiaOverlaySetting(holder: NvidiaOverlayHolder = NvidiaOverlays) {
    LaunchedEffect(holder) { holder.refresh() }
    val row by holder.row.collectAsState()
    NvidiaOverlayRow(row, holder::keepOff)
}

@Composable
fun NvidiaOverlayRow(row: NvidiaRow, onKeepOff: () -> Unit) {
    when (row) {
        NvidiaRow.Hidden -> Unit
        is NvidiaRow.Offer -> ActionRow(NVIDIA_OVERLAY_TITLE, row.note ?: NVIDIA_OFFER, NVIDIA_KEEP_OFF, onKeepOff)
        NvidiaRow.Waiting -> ActionRow(NVIDIA_OVERLAY_TITLE, NVIDIA_OFFER, NVIDIA_WAITING, {}, enabled = false)
        is NvidiaRow.Kept -> InfoRow(NVIDIA_OVERLAY_TITLE, if (row.justNow) NVIDIA_REOPEN else null, NVIDIA_KEPT_OFF)
    }
}
