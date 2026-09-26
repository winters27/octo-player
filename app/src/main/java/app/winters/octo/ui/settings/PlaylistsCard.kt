package app.winters.octo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.playlists.PlaylistSync
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PlaylistsCardViewModel @Inject constructor(private val sync: PlaylistSync) : ViewModel() {
    val available: StateFlow<Boolean> = sync.available.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val newOnServer: StateFlow<Boolean> = sync.newOnServer.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setNewOnServer(on: Boolean) = sync.setNewOnServer(on)
}

// How playlists go with the server, shown while one is connected.
@Composable
internal fun PlaylistsCard(modifier: Modifier = Modifier, vm: PlaylistsCardViewModel = hiltViewModel()) {
    val available by vm.available.collectAsStateWithLifecycle()
    val newOnServer by vm.newOnServer.collectAsStateWithLifecycle()
    if (!available) return

    Card("Playlists", modifier) {
        SwitchLine(
            label = "Save new playlists to the server",
            detail = "Playlists you make here are made on your server too. Others stay on this phone " +
                "until you choose Save to server.",
            checked = newOnServer,
            onChange = vm::setNewOnServer,
        )
    }
}
