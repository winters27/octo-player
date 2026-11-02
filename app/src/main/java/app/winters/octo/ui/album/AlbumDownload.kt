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
import app.winters.octo.ui.common.MoreItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AlbumDownloadViewModel @Inject constructor(private val offline: OfflineDownloads) : ViewModel() {
    val kept: StateFlow<Map<String, DownloadEntity>> = offline.byTrack

    fun download(trackIds: List<String>) = offline.download(trackIds)
}

// What the album's download button says: what is left to do, if anything.
// On a page that also lists songs outside the library (`outside`), it
// counts the songs it saves, so it never says the whole album is on the
// phone.
fun albumDownloadLabel(songs: Int, held: Int, done: Int, outside: Int = 0): String = when {
    outside <= 0 && held < songs -> "Download album"
    outside <= 0 && done < songs -> "Downloading"
    outside <= 0 -> "Downloaded"
    held < songs -> "Download ${songCount(songs)} to this phone"
    done < songs -> "Downloading"
    else -> "${songCount(songs)} downloaded"
}

private fun songCount(songs: Int): String = if (songs == 1) "1 song" else "$songs songs"

// "Download album" in an album's More menu, for an album with songs only
// on a server: each such song is downloaded to the phone. Nothing when
// every song is already on it. `outside`: the page's songs outside the
// library, as the page's library note counts them.
@Composable
fun albumDownloadItem(
    tracks: List<TrackEntity>,
    outside: Int = 0,
    vm: AlbumDownloadViewModel = hiltViewModel(),
): MoreItem? {
    val kept by vm.kept.collectAsStateWithLifecycle()
    val serverOnly = tracks.filter { !it.onPhone }
    if (serverOnly.isEmpty()) return null
    val held = serverOnly.count { it.id in kept }
    val done = serverOnly.count { kept[it.id]?.state == DownloadStatus.Done }
    return MoreItem(
        albumDownloadLabel(serverOnly.size, held, done, outside),
        when {
            held < serverOnly.size -> OctoIcons.Download
            done < serverOnly.size -> OctoIcons.Downloading
            else -> OctoIcons.Downloaded
        },
        enabled = held < serverOnly.size,
    ) { vm.download(serverOnly.map { it.id }) }
}
