package app.winters.octo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.server.QueueSync
import app.winters.octo.server.ScanState
import app.winters.octo.server.ServerControls
import app.winters.octo.subsonic.NowPlayingEntry
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.nav.RadioStationsRoute
import app.winters.octo.ui.nav.SharesRoute
import app.winters.octo.ui.server.minutesAgo
import app.winters.octo.ui.server.scanLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// How often "Listening now" is read again while the card is on screen.
private const val LISTENING_EVERY_MS = 60_000L

@HiltViewModel
class ServerExtrasViewModel @Inject constructor(
    private val controls: ServerControls,
    private val queue: QueueSync,
) : ViewModel() {
    val queueSync: StateFlow<Boolean> = queue.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val admin: StateFlow<Boolean> = controls.admin
    val sharing: StateFlow<Boolean> = controls.sharing
    val scan: StateFlow<ScanState> = controls.scan

    var listening by mutableStateOf<List<NowPlayingEntry>>(emptyList())
        private set

    init {
        // Asks once whether the server shares at all, so the row only shows
        // where links can be made.
        viewModelScope.launch {
            try {
                controls.shares()
            } catch (e: SubsonicException) {
                // Remembered by the controls when sharing is off.
            }
        }
    }

    fun setQueueSync(on: Boolean) = queue.setEnabled(on)

    fun scanNow() = controls.startScan()

    suspend fun readListening() {
        listening = try {
            controls.listening()
        } catch (e: SubsonicException) {
            emptyList()
        }
    }
}

// The server's extras in its card: queue sync, shared links, radio
// stations, a library scan for admins, and who is listening now.
@Composable
internal fun ServerExtras(onOpen: (NavKey) -> Unit, vm: ServerExtrasViewModel = hiltViewModel()) {
    val queueSync by vm.queueSync.collectAsStateWithLifecycle()
    val admin by vm.admin.collectAsStateWithLifecycle()
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        while (true) {
            vm.readListening()
            delay(LISTENING_EVERY_MS)
        }
    }

    SwitchLine(
        label = "Sync play queue with the server",
        detail = "Keeps what's playing on the server, so you can pick up where another device left off.",
        checked = queueSync,
        onChange = vm::setQueueSync,
    )
    if (sharing) LinkLine("Shared links") { onOpen(SharesRoute) }
    LinkLine("Radio stations") { onOpen(RadioStationsRoute) }
    if (admin) ScanLine(scan, vm::scanNow)
    if (vm.listening.isNotEmpty()) {
        Text("Listening now", style = OctoType.label, color = OctoColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
        vm.listening.forEach { ListenerLine(it) }
    }
}

// A line that opens another page.
@Composable
private fun LinkLine(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = OctoType.bodySmall, color = OctoColors.TextSecondary, modifier = Modifier.weight(1f))
        Icon(painterResource(OctoIcons.Chevron), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(18.dp))
    }
}

// "Scan library now", then how far the scan is, then that it finished.
@Composable
private fun ScanLine(state: ScanState, onScan: () -> Unit) {
    val scanning = state is ScanState.Scanning
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !scanning, role = Role.Button, onClick = onScan),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            scanLabel(state),
            style = OctoType.bodySmall,
            color = if (state is ScanState.Failed) OctoColors.Error else OctoColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        if (scanning) CircularProgressIndicator(color = OctoColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
    }
}

// Someone listening: who, the song, and when it started, on which player.
@Composable
private fun ListenerLine(entry: NowPlayingEntry) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val song = listOfNotNull(entry.title.takeIf(String::isNotBlank), entry.artist?.takeIf(String::isNotBlank)).joinToString(" · ")
        Text(song, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val who = listOfNotNull(
            entry.username.takeIf(String::isNotBlank),
            entry.playerName?.takeIf(String::isNotBlank),
            minutesAgo(entry.minutesAgo),
        ).joinToString(" · ")
        Text(who, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
