package app.winters.octo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.design.OctoIcons
import app.winters.octo.device.Access
import app.winters.octo.device.DEVICE
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.discovery.LIBRARY_ONLY_HELP
import app.winters.octo.player.PlayerSettings
import app.winters.octo.ui.common.accessButtonLabel
import app.winters.octo.ui.common.rememberAccessRequest
import app.winters.octo.ui.settings.rows.ActionRow
import app.winters.octo.ui.settings.rows.InfoRow
import app.winters.octo.ui.settings.rows.NoteRow
import app.winters.octo.ui.settings.rows.SettingsGroup
import app.winters.octo.ui.settings.rows.SettingsPageFrame
import app.winters.octo.ui.settings.rows.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LibrarySettingsViewModel @Inject constructor(
    val library: DeviceLibrary,
    dao: CatalogDao,
    private val player: PlayerSettings,
) : ViewModel() {
    // Whether songs found online are left out everywhere.
    val libraryOnly: StateFlow<Boolean> =
        player.prefs.map { it.libraryOnly }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setLibraryOnly(on: Boolean) {
        viewModelScope.launch { player.setLibraryOnly(on) }
    }

    val songCount: StateFlow<Int> =
        dao.trackCount(DEVICE).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun rescan() {
        viewModelScope.launch { library.rescan() }
    }

    fun setFolderIncluded(folder: String, included: Boolean) {
        viewModelScope.launch { library.setFolderIncluded(folder, included) }
    }
}

// The music on this phone: access to it, how much there is, looking for new
// music, and which folders count.
@Composable
fun LibraryPage(onBack: () -> Unit, highlight: String?, vm: LibrarySettingsViewModel = hiltViewModel()) {
    val access by vm.library.access.collectAsStateWithLifecycle()
    val scanning by vm.library.scanning.collectAsStateWithLifecycle()
    val count by vm.songCount.collectAsStateWithLifecycle()
    val folders by vm.library.folders.collectAsStateWithLifecycle()
    val libraryOnly by vm.libraryOnly.collectAsStateWithLifecycle()
    val requestAccess = rememberAccessRequest(vm.library, access)
    val granted = access == Access.Granted

    SettingsPageFrame("Library", onBack, highlight) {
        SettingsGroup(title = "Music on this phone", icon = OctoIcons.Phone) {
            if (granted) {
                InfoRow(SettingsIndex.PhoneAccess, "Allowed")
            } else {
                ActionRow(SettingsIndex.PhoneAccess, onClick = requestAccess, helper = "Not allowed", trailing = accessButtonLabel(access))
            }
            InfoRow(SettingsIndex.PhoneSongs, "%,d".format(count))
            ActionRow(
                SettingsIndex.Rescan,
                onClick = vm::rescan,
                helper = if (scanning) "Looking for music…" else null,
                busy = scanning,
                enabled = granted,
                chevron = false,
            )
        }
        SettingsGroup(title = "Songs found online", icon = OctoIcons.AddToLibrary) {
            SwitchRow(SettingsIndex.LibraryOnly, libraryOnly, vm::setLibraryOnly, helper = LIBRARY_ONLY_HELP)
        }
        if (folders.isNotEmpty()) {
            SettingsGroup(title = SettingsIndex.MusicFolders.title, icon = OctoIcons.Folder) {
                NoteRow("Switch a folder off to leave it out.", entry = SettingsIndex.MusicFolders)
                folders.forEach { folder ->
                    SwitchRow(
                        null,
                        title = folder.name.ifEmpty { "Phone storage" },
                        checked = folder.included,
                        onChange = { on -> vm.setFolderIncluded(folder.name, on) },
                        helper = "%,d songs".format(folder.songs),
                    )
                }
            }
        }
    }
}
