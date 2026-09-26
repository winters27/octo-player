package app.winters.octo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
import app.winters.octo.design.OctoType
import app.winters.octo.playlists.PlaylistSync
import app.winters.octo.server.LastSync
import app.winters.octo.server.ServerSync
import app.winters.octo.subsonic.MusicFolder
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.nav.EditConnectionRoute
import app.winters.octo.ui.nav.OctoAdminRoute
import app.winters.octo.ui.nav.SignInRoute
import app.winters.octo.ui.settings.rows.ActionRow
import app.winters.octo.ui.settings.rows.ChoiceRow
import app.winters.octo.ui.settings.rows.InfoRow
import app.winters.octo.ui.settings.rows.NoteRow
import app.winters.octo.ui.settings.rows.SettingsGroup
import app.winters.octo.ui.settings.rows.SettingsPageFrame
import app.winters.octo.ui.settings.rows.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val sync: ServerSync,
    private val playlists: PlaylistSync,
    chooser: ConnectionChooser,
) : ViewModel() {
    val session: StateFlow<SessionState> = sessions.state
    val syncing: StateFlow<Boolean> = sync.syncing
    val problem: StateFlow<String?> = sync.problem
    val last: StateFlow<LastSync?> = sync.last.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val place: StateFlow<Place> = chooser.place

    // Whether playlists go to the server, shown while one that keeps them is connected.
    val playlistsAvailable: StateFlow<Boolean> = playlists.available.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val newPlaylistsOnServer: StateFlow<Boolean> = playlists.newOnServer.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setNewPlaylistsOnServer(on: Boolean) = playlists.setNewOnServer(on)

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

// The server, if one is connected: where it is, who is signed in, how fresh
// the copy of its library is, and what else it offers.
@Composable
fun ServerPage(onOpen: (NavKey) -> Unit, onBack: () -> Unit, highlight: String?, vm: ServerViewModel = hiltViewModel()) {
    val state by vm.session.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    val last by vm.last.collectAsStateWithLifecycle()
    val place by vm.place.collectAsStateWithLifecycle()
    val playlistsAvailable by vm.playlistsAvailable.collectAsStateWithLifecycle()
    val newPlaylistsOnServer by vm.newPlaylistsOnServer.collectAsStateWithLifecycle()
    val prompt = LocalDisconnectPrompt.current
    val sheet = LocalChoiceSheet.current
    val scope = rememberCoroutineScope()
    var folderProblem by remember { mutableStateOf<String?>(null) }

    SettingsPageFrame("Server and sync", onBack, highlight) {
        when (val current = state) {
            SessionState.Loading -> Unit
            SessionState.SignedOut -> SettingsGroup(
                footer = "Add the music on your own server. It joins your library here and plays over the network.",
            ) {
                ActionRow(SettingsIndex.ConnectServer, onClick = { onOpen(SignInRoute) })
            }
            is SessionState.SignedIn -> {
                val client = current.session.client
                val connection = current.session.connection
                val copy = last?.takeIf { it.sourceId == current.session.sourceId }

                SettingsGroup(title = "Connection") {
                    InfoRow(SettingsIndex.ServerAddress, client.primaryUrl.toString().removeSuffix("/"))
                    // Which address is in use, when there is a choice.
                    if (connection.home != null) {
                        InfoRow(SettingsIndex.ServerConnection, if (place == Place.Home) "Connected at home" else "Connected away")
                    }
                    InfoRow(SettingsIndex.ServerUser, client.username.ifEmpty { "API key" })
                    ChoiceRow(SettingsIndex.ServerMusicFolder, value = connection.folder?.name ?: "All", onClick = {
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
                                    SettingsIndex.ServerMusicFolder.title,
                                    listOf(Choice("All", "Every folder on the server")) + folders.map { Choice(it.name) },
                                    picked,
                                ) { index ->
                                    val folder = folders.getOrNull(index - 1)?.let { FolderChoice(it.id, it.name) }
                                    if (folder?.id != connection.folder?.id) vm.chooseFolder(folder)
                                },
                            )
                        }
                    })
                    folderProblem?.let { NoteRow(it, color = OctoColors.Error) }
                    ActionRow(SettingsIndex.EditConnection, onClick = { onOpen(EditConnectionRoute) })
                    ActionRow(SettingsIndex.OctoAdmin, onClick = { onOpen(OctoAdminRoute) })
                }

                SettingsGroup(title = "Sync") {
                    InfoRow(SettingsIndex.LastSynced, copy?.let { timeAgo(it.at, System.currentTimeMillis()) } ?: "Not yet")
                    if (copy != null) {
                        InfoRow(null, "%,d".format(copy.songs), title = "Songs")
                        InfoRow(null, "%,d".format(copy.albums), title = "Albums")
                    }
                    ActionRow(SettingsIndex.SyncNow, onClick = vm::syncNow, busy = syncing, chevron = false)
                    problem?.let { NoteRow(it, color = OctoColors.Error) }
                    ServerQueueRow()
                    if (playlistsAvailable) {
                        SwitchRow(
                            SettingsIndex.PlaylistsToServer,
                            checked = newPlaylistsOnServer,
                            onChange = vm::setNewPlaylistsOnServer,
                            helper = "Playlists you make here are made on your server too. Others stay on this phone " +
                                "until you choose Save to server.",
                        )
                    }
                }

                ServerExtras(onOpen)

                SettingsGroup {
                    ActionRow(SettingsIndex.Disconnect, onClick = prompt::show, destructive = true, chevron = false)
                }
            }
        }
    }
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
