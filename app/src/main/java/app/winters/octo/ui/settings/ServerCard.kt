package app.winters.octo.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.connection.ConnectionChooser
import app.winters.octo.connection.FolderChoice
import app.winters.octo.connection.Place
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.server.LastSync
import app.winters.octo.server.ServerSync
import app.winters.octo.subsonic.MusicFolder
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ServerViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val sync: ServerSync,
    chooser: ConnectionChooser,
) : ViewModel() {
    val session: StateFlow<SessionState> = sessions.state
    val syncing: StateFlow<Boolean> = sync.syncing
    val problem: StateFlow<String?> = sync.problem
    val last: StateFlow<LastSync?> = sync.last.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val place: StateFlow<Place> = chooser.place

    fun syncNow() = sync.syncNow()

    // The server's library folders, or null when they could not be read.
    suspend fun folders(): List<MusicFolder>? = runCatching { sessions.musicFolders() }.getOrNull()

    // Limits the library to one folder (null for all) and copies it again.
    fun chooseFolder(folder: FolderChoice?) {
        viewModelScope.launch {
            sessions.chooseMusicFolder(folder)
            sync.syncAgain()
        }
    }

    fun disconnect() = sync.disconnect()
}

// The server, if one is connected: where it is, who is signed in, and how
// fresh the copy of its library is.
@Composable
internal fun ServerCard(
    onConnect: () -> Unit,
    onOpenAdmin: () -> Unit,
    onEditConnection: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (NavKey) -> Unit = {},
    vm: ServerViewModel = hiltViewModel(),
) {
    val state by vm.session.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    val last by vm.last.collectAsStateWithLifecycle()
    val place by vm.place.collectAsStateWithLifecycle()
    val prompt = LocalDisconnectPrompt.current
    val sheet = LocalChoiceSheet.current
    val scope = rememberCoroutineScope()
    var folderProblem by remember { mutableStateOf<String?>(null) }

    Card("Server", modifier) {
        when (val current = state) {
            SessionState.Loading -> Unit
            SessionState.SignedOut -> {
                Text(
                    "Add the music on your own server. It joins your library here and plays over the network.",
                    style = OctoType.caption,
                    color = OctoColors.TextMuted,
                )
                AccentButton("Connect a server", onClick = onConnect, modifier = Modifier.padding(top = 8.dp))
            }
            is SessionState.SignedIn -> {
                val client = current.session.client
                val connection = current.session.connection
                val copy = last?.takeIf { it.sourceId == current.session.sourceId }
                Line("Address", client.primaryUrl.toString().removeSuffix("/"))
                // Which address is in use, when there is a choice.
                if (connection.home != null) {
                    Line("Connection", if (place == Place.Home) "Connected at home" else "Connected away")
                }
                Line("User", client.username.ifEmpty { "API key" })
                Line("Last synced", copy?.let { syncedAgo(it.at) } ?: "Not yet")
                if (copy != null) {
                    Line("Songs", "%,d".format(copy.songs))
                    Line("Albums", "%,d".format(copy.albums))
                }
                if (syncing) Line("Status", "Syncing…")
                ValueLine("Music folder", connection.folder?.name ?: "All") {
                    scope.launch {
                        val folders = vm.folders()
                        if (folders == null) {
                            folderProblem = "Couldn't read the server's music folders."
                            return@launch
                        }
                        folderProblem = null
                        val picked = folders.indexOfFirst { it.id == connection.folder?.id } + 1
                        sheet.show(
                            ChoiceRequest(
                                "Music folder",
                                listOf(Choice("All", "Every folder on the server")) + folders.map { Choice(it.name) },
                                picked,
                            ) { index ->
                                val folder = folders.getOrNull(index - 1)?.let { FolderChoice(it.id, it.name) }
                                if (folder?.id != connection.folder?.id) vm.chooseFolder(folder)
                            },
                        )
                    }
                }
                folderProblem?.let { Text(it, style = OctoType.caption, color = OctoColors.Error) }
                OpenLine("Edit connection", onClick = onEditConnection)
                OpenLine("Octo admin", onClick = onOpenAdmin)
                ServerExtras(onOpen)
                problem?.let { Text(it, style = OctoType.caption, color = OctoColors.Error) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccentButton("Sync now", onClick = vm::syncNow, loading = syncing)
                    GlazeButton("Disconnect", onClick = prompt::show)
                }
            }
        }
    }
}

// A setting and its current choice; tapping it opens the options.
@Composable
private fun ValueLine(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = OctoType.bodySmall, color = OctoColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = OctoType.bodySmall, color = OctoColors.TextPrimary, modifier = Modifier.padding(end = 4.dp))
        Icon(painterResource(OctoIcons.Chevron), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(18.dp))
    }
}

// A line that opens another page.
@Composable
private fun OpenLine(label: String, onClick: () -> Unit) {
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

private fun syncedAgo(at: Long): String {
    val now = System.currentTimeMillis()
    return if (now - at < DateUtils.MINUTE_IN_MILLIS) "Just now"
    else DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

// Whether the question before disconnecting is showing. The shell draws
// it over everything, the bar included.
class DisconnectPrompt {
    var open by mutableStateOf(false)
        private set

    fun show() {
        open = true
    }

    fun close() {
        open = false
    }
}

val LocalDisconnectPrompt = staticCompositionLocalOf<DisconnectPrompt> { error("No disconnect prompt") }

// Asks before the server is disconnected.
@Composable
fun DisconnectSheetHost(prompt: DisconnectPrompt, vm: ServerViewModel = hiltViewModel()) {
    GlassSheet(visible = prompt.open, onDismiss = prompt::close) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("Disconnect the server?", style = OctoType.section, color = OctoColors.TextPrimary)
            Text(
                "Its music leaves your library on this phone. Nothing on the server changes, " +
                    "and you can connect again at any time.",
                style = OctoType.bodySmall,
                color = OctoColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentButton("Disconnect", onClick = {
                    vm.disconnect()
                    prompt.close()
                })
                GlazeButton("Cancel", onClick = prompt::close)
            }
        }
    }
}
