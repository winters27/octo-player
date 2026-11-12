package app.winters.octo.ui.playlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.rawQuery
import app.winters.octo.design.OctoIcons
import app.winters.octo.ui.common.MoreItem
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.offline.OfflinePrefs
import app.winters.octo.offline.OfflineSettings
import app.winters.octo.ui.paging.SqlValue
import app.winters.octo.ui.paging.tableChanges
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// How many of a list's songs are only on a server, and how many of those
// have a finished download: two counts, never the songs.
data class KeepCounts(val serverOnly: Int, val done: Int)

// Songs only on a server that are not found online, and those of them
// with a finished download, over the rows `from` names (`t` the song).
private fun keepCountsSql(from: String, where: String) =
    "SELECT COUNT(*) FROM $from WHERE $where AND t.onPhone = 0 AND t.id NOT LIKE 'find:%'" to
        "SELECT COUNT(*) FROM $from WHERE $where AND t.onPhone = 0 AND t.id NOT LIKE 'find:%' " +
        "AND EXISTS (SELECT 1 FROM download d WHERE d.trackId = t.id AND d.state = 'Done')"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class KeepDownloadedViewModel @Inject constructor(
    private val offline: OfflineDownloads,
    settings: OfflineSettings,
    private val db: CatalogDatabase,
) : ViewModel() {
    val prefs: StateFlow<OfflinePrefs> =
        settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflinePrefs())

    fun setPlaylistKept(id: String, on: Boolean) = offline.setPlaylistKept(id, on)
    fun setLikedKept(on: Boolean) = offline.setKeepLiked(on)

    // Read again when a download finishes or goes, and when the list's own
    // rows change.
    private fun counts(tables: Array<String>, sql: Pair<String, String>, args: List<SqlValue>): Flow<KeepCounts> =
        combine(offline.doneVersion, db.tableChanges(tables).onStart { emit(Unit) }) { _, _ -> }.conflate().map {
            val dao = db.listsDao()
            KeepCounts(dao.count(rawQuery(sql.first, args)), dao.count(rawQuery(sql.second, args)))
        }

    fun likedCounts(): Flow<KeepCounts> =
        counts(arrayOf("liked_track", "track"), keepCountsSql("liked_track l CROSS JOIN track t ON t.id = l.trackId", "1"), emptyList())

    // Over the playlist's rows, each counted, a song there twice twice.
    fun playlistCounts(id: String): Flow<KeepCounts> = counts(
        arrayOf("playlist", "playlist_item", "track"),
        keepCountsSql(
            "playlist p CROSS JOIN playlist_item i ON i.playlistId = p.id AND i.itemSet = p.itemSet CROSS JOIN track t ON t.id = i.trackId",
            "p.id = ?",
        ),
        listOf(SqlValue.Text(id)),
    )
}

// The line under a switched-on rule: how many of its songs are on the phone.
fun keptSummary(serverOnly: Int, done: Int): String = when {
    serverOnly == 0 -> "Every song is already on this phone"
    done >= serverOnly -> "All $serverOnly downloaded"
    else -> "$done of $serverOnly downloaded"
}

// The words of a Keep downloaded switch: its quiet line, how many are on
// the phone while it is on and what it does while it is off.
const val KEEP_DOWNLOADED = "Keep downloaded"
const val KEEP_DOWNLOADED_OFF = "Download the songs only on your server, and keep them in step"

fun keepDetail(checked: Boolean, serverOnly: Int, done: Int): String = if (checked) keptSummary(serverOnly, done) else KEEP_DOWNLOADED_OFF

// "Keep downloaded" for a playlist, as a switch in its More menu: its
// songs only on a server are downloaded to the phone, and the downloads
// follow the playlist as songs come and go. Nothing when there is nothing
// to download and it is off.
@Composable
fun keepPlaylistItem(playlistId: String, vm: KeepDownloadedViewModel = hiltViewModel()): MoreItem? {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val counts by remember(playlistId) { vm.playlistCounts(playlistId) }.collectAsStateWithLifecycle(null)
    return keepItem(playlistId in prefs.keptPlaylists, counts) { vm.setPlaylistKept(playlistId, it) }
}

// The same for Liked songs, the switch the Offline settings have too.
@Composable
fun keepLikedItem(vm: KeepDownloadedViewModel = hiltViewModel()): MoreItem? {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val counts by remember { vm.likedCounts() }.collectAsStateWithLifecycle(null)
    return keepItem(prefs.keepLiked, counts, vm::setLikedKept)
}

private fun keepItem(checked: Boolean, counts: KeepCounts?, onChange: (Boolean) -> Unit): MoreItem? {
    val known = counts ?: return null
    if (known.serverOnly == 0 && !checked) return null
    return MoreItem(KEEP_DOWNLOADED, OctoIcons.Download, detail = keepDetail(checked, known.serverOnly, known.done), checked = checked) { onChange(!checked) }
}

// While Keep downloaded is on and songs are still coming, how far it has
// got, for a page's details line. Nothing once they are all on the phone.
@Composable
fun keepPlaylistProgress(playlistId: String, vm: KeepDownloadedViewModel = hiltViewModel()): String? {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val counts by remember(playlistId) { vm.playlistCounts(playlistId) }.collectAsStateWithLifecycle(null)
    if (playlistId !in prefs.keptPlaylists) return null
    val known = counts ?: return null
    return keepingNote(known.serverOnly, known.done)
}

// The progress note while songs are still on their way, or nothing.
fun keepingNote(serverOnly: Int, done: Int): String? = if (serverOnly > 0 && done < serverOnly) keptSummary(serverOnly, done) else null
