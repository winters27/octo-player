package app.winters.octo.ui.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.UserDao
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.offline.DownloadRow
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.offline.Reasons
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.RemoveBackground
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.songs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class OfflineDownloadsViewModel @Inject constructor(
    private val offline: OfflineDownloads,
    private val playback: PlaybackConnection,
    private val feedback: Feedback,
    userDao: UserDao,
) : ViewModel() {
    // Null until first read.
    val rows: StateFlow<List<DownloadRow>?> =
        offline.rows.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Playlist names, for saying which playlist keeps a song.
    val playlistNames: StateFlow<Map<String, String>> = userDao.playlists()
        .map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Removes a download, with an Undo while its file can still come back.
    fun remove(trackId: String) = offline.remove(trackId) { restore -> feedback.undoable("Download removed", restore) }
    fun removeAll() = offline.removeAll()
    fun retry(trackId: String) = offline.retry(trackId)

    // Plays the finished downloads from this one.
    fun play(row: DownloadRow) {
        val done = rows.value.orEmpty().filter { it.state == DownloadStatus.Done }.map { it.trackId }
        playback.playTracks(done, done.indexOf(row.trackId).coerceAtLeast(0))
    }
}

// A size in the units people read: "850 KB", "12.4 MB", "3.1 GB".
fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> "${(bytes + 1023) / 1024} KB"
}

// The line under a download: where it is, or why it is kept.
fun downloadDetail(row: DownloadRow, playlistNames: Map<String, String>): String = when (row.state) {
    DownloadStatus.Queued -> "Waiting"
    DownloadStatus.Downloading -> "Downloading, ${Math.round(row.progress * 100)}%"
    DownloadStatus.Failed -> "Could not download. Tap to try again"
    DownloadStatus.Done -> listOfNotNull(sizeLabel(row.sizeBytes), keptBy(Reasons.parse(row.reason), playlistNames)).joinToString(" · ")
}

// Which rule keeps a download that was not asked for by hand, if one does.
fun keptBy(reasons: Set<String>, playlistNames: Map<String, String>): String? {
    if (Reasons.MANUAL in reasons) return null
    if (Reasons.LIKED in reasons) return "Kept with Liked songs"
    val playlist = reasons.firstNotNullOfOrNull { Reasons.playlistOf(it) } ?: return null
    return playlistNames[playlist]?.let { "Kept with $it" } ?: "Kept with a playlist"
}

private val RowShape = RoundedCornerShape(12.dp)

// Every download: the ones on their way with how far they are, then the
// ones on the phone. Swipe one left, or long press it, to remove it. One a
// rule keeps goes when its rule is switched off.
@Composable
fun DownloadsScreen(onBack: () -> Unit, vm: OfflineDownloadsViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val names by vm.playlistNames.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Spacer(Modifier.height(DetailTopGap))
            ScreenTitle("Downloads")
            val list = rows
            if (list != null) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 140.dp)) {
                    item(key = "summary") {
                        Summary(list, confirming, onRemoveAll = { confirming = true }, onConfirm = {
                            confirming = false
                            vm.removeAll()
                        }, onCancel = { confirming = false })
                    }
                    if (list.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                "Songs you download play without a connection. Long press a song and choose Download, " +
                                    "or keep a playlist downloaded from its page.",
                                style = OctoType.bodySmall,
                                color = OctoColors.TextMuted,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(list, key = { "${it.sourceId}|${it.serverId}" }) { row ->
                        val byHand = Reasons.MANUAL in Reasons.parse(row.reason)
                        // A download a rule still keeps comes back from a swipe as it was.
                        key(row.reason) {
                            DownloadLine(row, downloadDetail(row, names), removable = byHand, onRemove = { vm.remove(row.trackId) }) {
                                when (row.state) {
                                    DownloadStatus.Failed -> vm.retry(row.trackId)
                                    DownloadStatus.Done -> vm.play(row)
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
        BackButton(onBack)
    }
}

// How many downloads and how much room they take, and "Remove all", which
// asks once more before it goes.
@Composable
private fun Summary(rows: List<DownloadRow>, confirming: Boolean, onRemoveAll: () -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    if (rows.isEmpty()) return
    val done = rows.filter { it.state == DownloadStatus.Done }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
        Text(
            "${songs(done.size)} · ${sizeLabel(done.sumOf { it.sizeBytes })}",
            style = OctoType.caption,
            color = OctoColors.TextMuted,
        )
        if (confirming) {
            Text(
                "Remove every download from this phone? Liked songs and playlists stop being kept downloaded too.",
                style = OctoType.bodySmall,
                color = OctoColors.TextSecondary,
                modifier = Modifier.padding(top = 10.dp),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentButton("Remove all", onClick = onConfirm)
                GlazeButton("Cancel", onClick = onCancel)
            }
        } else {
            GlazeButton("Remove all", onClick = onRemoveAll, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun DownloadLine(row: DownloadRow, detail: String, removable: Boolean, onRemove: () -> Unit, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = removable,
        onDismiss = { onRemove() },
        backgroundContent = { RemoveBackground(swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart, RowShape) },
    ) {
        Column(
            Modifier
                .background(OctoColors.Background, RowShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (removable) {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onRemove()
                        }
                    } else {
                        null
                    },
                ),
        ) {
            Row(
                Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Artwork(row.artwork, 44.dp, shape = RoundedCornerShape(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(row.artist, detail).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = OctoType.caption,
                        color = if (row.state == DownloadStatus.Failed) OctoColors.TextSecondary else OctoColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // A hairline of progress under a download on its way.
            if (row.state == DownloadStatus.Downloading) {
                Box(Modifier.padding(start = 76.dp, end = 20.dp).fillMaxWidth().height(2.dp).background(OctoColors.TextPrimary.copy(alpha = 0.08f))) {
                    Box(Modifier.fillMaxWidth(row.progress.coerceIn(0f, 1f)).fillMaxHeight().background(OctoColors.Accent))
                }
            }
        }
    }
}
