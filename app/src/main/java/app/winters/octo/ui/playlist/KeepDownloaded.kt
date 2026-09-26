package app.winters.octo.ui.playlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.offline.DownloadEntity
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.offline.OfflinePrefs
import app.winters.octo.offline.OfflineSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class KeepDownloadedViewModel @Inject constructor(
    private val offline: OfflineDownloads,
    settings: OfflineSettings,
) : ViewModel() {
    val prefs: StateFlow<OfflinePrefs> =
        settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflinePrefs())
    val kept: StateFlow<Map<String, DownloadEntity>> = offline.byTrack

    fun setPlaylistKept(id: String, on: Boolean) = offline.setPlaylistKept(id, on)
    fun setLikedKept(on: Boolean) = offline.setKeepLiked(on)
}

// The line under a switched-on rule: how many of its songs are on the phone.
fun keptSummary(serverOnly: Int, done: Int): String = when {
    serverOnly == 0 -> "Every song is already on this phone"
    done >= serverOnly -> "All $serverOnly downloaded"
    else -> "$done of $serverOnly downloaded"
}

// "Keep downloaded" for a playlist: its songs only on a server are
// downloaded to the phone, and the downloads follow the playlist as songs
// come and go. Shown only when there is something to download or it is on.
@Composable
fun KeepPlaylistDownloaded(playlistId: String, tracks: List<TrackEntity>, vm: KeepDownloadedViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    KeepDownloadedLine(playlistId in prefs.keptPlaylists, tracks, vm) { vm.setPlaylistKept(playlistId, it) }
}

// The same for Liked songs, the switch the Offline settings have too.
@Composable
fun KeepLikedDownloaded(tracks: List<TrackEntity>, vm: KeepDownloadedViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    KeepDownloadedLine(prefs.keepLiked, tracks, vm, vm::setLikedKept)
}

@Composable
private fun KeepDownloadedLine(checked: Boolean, tracks: List<TrackEntity>, vm: KeepDownloadedViewModel, onChange: (Boolean) -> Unit) {
    val kept by vm.kept.collectAsStateWithLifecycle()
    val serverOnly = tracks.filter { !it.onPhone && !isFind(it.id) }
    if (serverOnly.isEmpty() && !checked) return
    val done = serverOnly.count { kept[it.id]?.state == DownloadStatus.Done }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Keep downloaded", style = OctoType.bodySmall, color = OctoColors.TextPrimary)
            Text(
                if (checked) keptSummary(serverOnly.size, done) else "Download the songs only on your server, and keep them in step",
                style = OctoType.caption,
                color = OctoColors.TextMuted,
            )
        }
        OctoSwitch(checked = checked, onCheckedChange = onChange)
    }
}
