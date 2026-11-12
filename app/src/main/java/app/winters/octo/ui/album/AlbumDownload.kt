package app.winters.octo.ui.album

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.rawQuery
import app.winters.octo.ui.paging.SqlValue
import app.winters.octo.ui.paging.tableChanges
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import app.winters.octo.design.OctoIcons
import app.winters.octo.offline.DownloadEntity
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.ui.common.MoreItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AlbumDownloadViewModel @Inject constructor(
    private val offline: OfflineDownloads,
    private val db: CatalogDatabase,
) : ViewModel() {
    // The download rows of these songs, read again on every download write.
    fun keptAmong(trackIds: List<String>): Flow<Map<String, DownloadEntity>> = offline.keptAmong(trackIds)

    fun download(trackIds: List<String>) = offline.download(trackIds)

    // A big album's download counts, by counts on its songs: those only on a
    // server, those with a download row (following every download write, so
    // a tap shows at once) and those downloaded.
    fun counts(albumId: String): Flow<AlbumKeepCounts> =
        combine(offline.version, offline.doneVersion, db.tableChanges(arrayOf("track")).onStart { emit(Unit) }) { _, _, _ -> }
            .conflate()
            .map {
                val lists = db.listsDao()
                val args = listOf(SqlValue.Text(albumId))
                val base = "SELECT COUNT(*) FROM track t WHERE t.albumId = ? AND t.onPhone = 0"
                AlbumKeepCounts(
                    serverOnly = lists.count(rawQuery(base, args)),
                    held = lists.count(rawQuery("$base AND EXISTS (SELECT 1 FROM download d WHERE d.trackId = t.id)", args)),
                    done = lists.count(rawQuery("$base AND EXISTS (SELECT 1 FROM download d WHERE d.trackId = t.id AND d.state = 'Done')", args)),
                )
            }

    // Downloads a big album's songs only on a server, a page at a time.
    fun downloadAlbum(albumId: String) = offline.downloadAlbum(albumId)
}

data class AlbumKeepCounts(val serverOnly: Int, val held: Int, val done: Int)

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

private fun songCount(songs: Int): String = if (songs == 1) "1 song" else "%,d songs".format(songs)

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
    val serverOnly = remember(tracks) { tracks.filter { !it.onPhone } }
    val kept by remember(serverOnly) { vm.keptAmong(serverOnly.map { it.id }) }.collectAsStateWithLifecycle(emptyMap())
    if (serverOnly.isEmpty()) return null
    val held = serverOnly.count { it.id in kept }
    val done = serverOnly.count { kept[it.id]?.state == DownloadStatus.Done }
    return albumDownloadMore(AlbumKeepCounts(serverOnly.size, held, done), outside) { vm.download(serverOnly.map { it.id }) }
}

// The same for an album too big to hold: its counts by counts on its
// songs, and its download walks the album a page at a time.
@Composable
fun bigAlbumDownloadItem(
    albumId: String,
    outside: Int = 0,
    vm: AlbumDownloadViewModel = hiltViewModel(),
): MoreItem? {
    val counts by remember(albumId) { vm.counts(albumId) }.collectAsStateWithLifecycle(null)
    val known = counts ?: return null
    if (known.serverOnly == 0) return null
    return albumDownloadMore(known, outside) { vm.downloadAlbum(albumId) }
}

private fun albumDownloadMore(counts: AlbumKeepCounts, outside: Int, download: () -> Unit): MoreItem = MoreItem(
    albumDownloadLabel(counts.serverOnly, counts.held, counts.done, outside),
    when {
        counts.held < counts.serverOnly -> OctoIcons.Download
        counts.done < counts.serverOnly -> OctoIcons.Downloading
        else -> OctoIcons.Downloaded
    },
    enabled = counts.held < counts.serverOnly,
    onClick = download,
)
