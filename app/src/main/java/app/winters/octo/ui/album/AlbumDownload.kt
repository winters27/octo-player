package app.winters.octo.ui.album

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.OctoIcons
import app.winters.octo.offline.DownloadEntity
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.ui.common.QuietAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AlbumDownloadViewModel @Inject constructor(private val offline: OfflineDownloads) : ViewModel() {
    val kept: StateFlow<Map<String, DownloadEntity>> = offline.byTrack

    fun download(trackIds: List<String>) = offline.download(trackIds)
}

// What the album's download button says: what is left to do, if anything.
fun albumDownloadLabel(songs: Int, held: Int, done: Int): String = when {
    held < songs -> "Download album"
    done < songs -> "Downloading"
    else -> "Downloaded"
}

// "Download album", a quiet action under an album's buttons, for an album
// with songs only on a server. Each such song is downloaded to the phone.
@Composable
fun AlbumDownloadButton(tracks: List<TrackEntity>, vm: AlbumDownloadViewModel = hiltViewModel()) {
    val kept by vm.kept.collectAsStateWithLifecycle()
    val serverOnly = tracks.filter { !it.onPhone }
    if (serverOnly.isEmpty()) return
    val held = serverOnly.count { it.id in kept }
    val done = serverOnly.count { kept[it.id]?.state == DownloadStatus.Done }
    QuietAction(
        icon = when {
            held < serverOnly.size -> OctoIcons.Download
            done < serverOnly.size -> OctoIcons.Downloading
            else -> OctoIcons.Downloaded
        },
        text = albumDownloadLabel(serverOnly.size, held, done),
        onClick = { vm.download(serverOnly.map { it.id }) },
        enabled = held < serverOnly.size,
    )
}
