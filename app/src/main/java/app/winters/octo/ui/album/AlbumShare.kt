package app.winters.octo.ui.album

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.design.OctoIcons
import app.winters.octo.server.ServerControls
import app.winters.octo.ui.common.QuietAction
import app.winters.octo.ui.server.LocalShareSheet
import app.winters.octo.ui.server.ShareRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AlbumShareViewModel @Inject constructor(private val controls: ServerControls) : ViewModel() {
    val sharing: StateFlow<Boolean> = controls.sharing

    suspend fun serverAlbumId(albumId: String): String? = controls.serverAlbumId(albumId)
}

// "Share", a quiet action under an album's buttons, for an album that is on
// a server that shares. It shares the server's album, so the link holds all
// of it.
@Composable
fun AlbumShareButton(albumId: String, title: String, vm: AlbumShareViewModel = hiltViewModel()) {
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    val serverId by produceState<String?>(null, albumId, sharing) { value = if (sharing) vm.serverAlbumId(albumId) else null }
    val sheet = LocalShareSheet.current
    val id = serverId ?: return
    QuietAction(OctoIcons.Share, "Share", onClick = { sheet.show(ShareRequest(listOf(id), title)) })
}
