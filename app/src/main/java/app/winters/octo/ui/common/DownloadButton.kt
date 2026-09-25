package app.winters.octo.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.GlowIcon
import app.winters.octo.design.OctoIcons
import app.winters.octo.discovery.DownloadState
import app.winters.octo.discovery.Downloads
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(private val downloads: Downloads) : ViewModel() {
    val states: StateFlow<Map<String, DownloadState>> = downloads.states

    fun request(track: TrackEntity) {
        viewModelScope.launch { downloads.request(track) }
    }
}

// Where a heart would be for a song found online: tapping it has the server
// download the song into the library. It shows when the download is on its
// way, and when the song has arrived.
@Composable
fun DownloadButton(
    track: TrackEntity,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    vm: DownloadsViewModel = hiltViewModel(),
) {
    val states by vm.states.collectAsStateWithLifecycle()
    val state = states[track.id] ?: DownloadState.None
    Box(
        modifier
            .size(size)
            .clickable(
                interactionSource = null,
                indication = null,
                enabled = state == DownloadState.None,
                role = Role.Button,
            ) { vm.request(track) }
            .semantics {
                contentDescription = "Download"
                stateDescription = when (state) {
                    DownloadState.None -> "Not in your library"
                    DownloadState.Requested -> "Downloading"
                    DownloadState.Done -> "In your library"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        GlowIcon(
            painterResource(
                when (state) {
                    DownloadState.None -> OctoIcons.Download
                    DownloadState.Requested -> OctoIcons.Downloading
                    DownloadState.Done -> OctoIcons.Downloaded
                },
            ),
            tint = if (state == DownloadState.None) Color.White.copy(alpha = 0.6f) else Color.White,
            lit = state != DownloadState.None,
            modifier = Modifier.size(iconSize),
        )
    }
}
