package app.winters.octo.ui.offline

import app.winters.octo.ui.common.sortItem
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.ui.common.SongTitle
import app.winters.octo.catalog.UserDao
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.offline.DownloadRow
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.offline.Reasons
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.SortSettings
import app.winters.octo.sort.Sorted
import app.winters.octo.sort.sortDownloads
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.ListRowSide
import app.winters.octo.ui.common.SongRowCover
import app.winters.octo.ui.common.SongRowCoverCorner
import app.winters.octo.ui.common.SongRowGap
import app.winters.octo.ui.common.SongRowMinHeight
import androidx.compose.foundation.layout.heightIn
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.RemoveBackground
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.MoreItem
import app.winters.octo.ui.common.PageHeader
import app.winters.octo.ui.common.PopupQuestion
import app.winters.octo.ui.common.rememberOpenedBeside
import app.winters.octo.design.GlassPopup
import app.winters.octo.design.OctoIcons
import app.winters.octo.ui.common.TopOnNewOrder
import app.winters.octo.ui.common.songs
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListItem
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.paging.RowPlaceholder
import app.winters.octo.ui.paging.listRows
import app.winters.octo.ui.paging.rememberListRows
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// What the Downloads page lists: the server in use's downloads, in an order.
data class DownloadsQuestion(val order: SortOrder, val sourceId: String?)

@HiltViewModel
class OfflineDownloadsViewModel @Inject constructor(
    private val offline: OfflineDownloads,
    private val playback: PlaybackConnection,
    private val feedback: Feedback,
    private val sorting: SortSettings,
    userDao: UserDao,
    private val loaders: ListLoaders,
    reads: LibraryReads,
    sessions: SessionRepository,
) : ViewModel() {
    private val source = sessions.state.map { (it as? SessionState.SignedIn)?.session?.sourceId }.distinctUntilChanged()

    // The page's rows in pages, the ones on their way always first, read
    // again on every write of a download but a progress tick. Null until
    // the order is known.
    private var handle: ListHandle<DownloadsQuestion, DownloadRow>? = null
    val pages: StateFlow<ListHandle<DownloadsQuestion, DownloadRow>?> =
        combine(sorting.order(SortList.Downloads), source, ::DownloadsQuestion)
            .map { question ->
                handle?.also { it.ask(question) } ?: ListHandle(
                    viewModelScope,
                    question,
                    { q -> loaders.downloads(q.order, q.sourceId) },
                    reads,
                    { _, _ -> offline.version.drop(1) },
                ).also { handle = it }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Whether the server in use has any download, on its way or done.
    val any: StateFlow<Boolean?> = combine(offline.version, source) { _, s -> s }.conflate()
        .map { loaders.downloadTotals().any(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // How many are on the phone and the room they take, by counts on the
    // done rows, following the set of downloaded songs.
    val done: StateFlow<Pair<Int, Long>?> = combine(offline.doneVersion, source) { _, s -> s }.conflate()
        .map { loaders.downloadTotals().doneTotals(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // How far a download on its way has come, as it ticks.
    fun progressOf(row: DownloadRow): Flow<Float> = offline.progressOf(row.sourceId, row.serverId)

    override fun onCleared() {
        handle?.close()
    }

    // Playlist names, for saying which playlist keeps a song.
    val playlistNames: StateFlow<Map<String, String>> = userDao.playlists()
        .map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Removes a download, with an Undo while its file can still come back.
    fun remove(trackId: String) = offline.remove(trackId) { restore -> feedback.undoable("Download removed", restore) }
    fun removeAll() = offline.removeAll()
    fun retry(trackId: String) = offline.retry(trackId)

    fun setOrder(order: SortOrder) {
        viewModelScope.launch { sorting.set(SortList.Downloads, order) }
    }

    // Plays the finished downloads, in the order shown, from this one.
    fun play(item: ListItem<DownloadRow>) {
        val order = pages.value?.asked?.value?.order ?: return
        playback.playList(ListSpec.Downloads(order), ListStart.Row(item.row.trackId, item.key))
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
// rule keeps goes when its rule is switched off. How many and how much room
// they take sit on the title line; Remove all is in More and asks first.
@Composable
fun DownloadsScreen(onBack: () -> Unit, vm: OfflineDownloadsViewModel = hiltViewModel()) {
    val handle by vm.pages.collectAsStateWithLifecycle()
    val names by vm.playlistNames.collectAsStateWithLifecycle()
    val anyKnown by vm.any.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }

    val state = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp)) {
            val paged = handle
            val asked = paged?.asked?.collectAsStateWithLifecycle()?.value
            val any = anyKnown == true && asked != null
            PageHeader(
                "Downloads",
                onBack,
                count = if (anyKnown == true) done?.let { (songs, bytes) -> downloadsCount(songs, bytes) } else null,
                shortCount = done?.let { (kept, _) -> songs(kept) },
                lead = if (!any) emptyList() else listOf(sortItem(SortList.Downloads, asked!!.order, vm::setOrder)),
                more = if (!any) emptyList() else listOf(MoreItem(REMOVE_ALL, OctoIcons.Delete, destructive = true, opensPage = true) { confirming = true }),
            )
            if (paged != null && asked != null && anyKnown != null) {
                TopOnNewOrder(asked.order, state)
                val items = rememberListRows(paged, state, above = if (anyKnown == false) 1 else 0)
                LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = 4.dp, bottom = 140.dp)) {
                    if (anyKnown == false) {
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
                    listRows(items, null, heading = {}) { item ->
                        if (item == null) {
                            RowPlaceholder()
                        } else {
                            val row = item.row
                            val byHand = Reasons.MANUAL in Reasons.parse(row.reason)
                            // A download a rule still keeps comes back from a swipe as it was.
                            key(row.reason) {
                                DownloadLine(
                                    row,
                                    progressOf = vm::progressOf,
                                    detail = { progress -> downloadDetail(row.copy(progress = progress), names) },
                                    removable = byHand,
                                    onRemove = { vm.remove(row.trackId) },
                                    modifier = Modifier.animateItem(),
                                ) {
                                    when (row.state) {
                                        DownloadStatus.Failed -> vm.retry(row.trackId)
                                        DownloadStatus.Done -> vm.play(item)
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        GlassPopup(
            visible = confirming,
            anchor = rememberOpenedBeside(confirming),
            onDismiss = { confirming = false },
            backdrop = LocalHaze.current,
            title = REMOVE_ALL,
        ) {
            PopupQuestion(
                "Remove every download?",
                REMOVE_ALL_DETAIL,
                REMOVE_ALL,
                onConfirm = {
                    confirming = false
                    vm.removeAll()
                },
                onCancel = { confirming = false },
            )
        }
    }
}

const val REMOVE_ALL = "Remove all"
const val REMOVE_ALL_DETAIL = "Every download leaves this phone. Liked songs and playlists stop being kept downloaded too."

// How many songs are on the phone and how much room they take, for the
// title line: "120 songs · 1.2 GB". Nothing while there are none.
fun downloadsCount(rows: List<DownloadRow>): String? {
    if (rows.isEmpty()) return null
    val done = rows.filter { it.state == DownloadStatus.Done }
    return downloadsCount(done.size, done.sumOf { it.sizeBytes })
}

// The same from the done rows' counts, while there is any download.
fun downloadsCount(done: Int, bytes: Long): String = "${songs(done)} · ${sizeLabel(bytes)}"

@Composable
private fun DownloadLine(
    row: DownloadRow,
    progressOf: (DownloadRow) -> Flow<Float>,
    detail: (progress: Float) -> String,
    removable: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // A download on its way follows its own progress; the list reads
    // again only when its state changes.
    val ticking = row.state == DownloadStatus.Downloading
    val progress by remember(row.sourceId, row.serverId, ticking) { if (ticking) progressOf(row) else flowOf(row.progress) }
        .collectAsStateWithLifecycle(row.progress)
    val haptics = LocalHapticFeedback.current
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = removable,
        onDismiss = { onRemove() },
        backgroundContent = { RemoveBackground(swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart, RowShape) },
    ) {
        // Solid only while swiped, to hide the red behind; clear at rest.
        val swiping = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
        // At a song row's size and insets, so the covers line up with
        // every other song list's.
        Box(
            Modifier
                .background(if (swiping) OctoColors.Background else Color.Transparent, RowShape)
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
                Modifier.fillMaxWidth().heightIn(min = SongRowMinHeight).padding(horizontal = ListRowSide, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SongRowGap),
            ) {
                Artwork(row.artwork, SongRowCover, shape = RoundedCornerShape(SongRowCoverCorner))
                Column(Modifier.weight(1f)) {
                    SongTitle(row.title, explicit = row.explicit == true, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
                    Text(
                        listOf(row.artist, detail(progress)).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = OctoType.caption,
                        color = if (row.state == DownloadStatus.Failed) OctoColors.TextSecondary else OctoColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // A hairline of progress at the foot of a download on its way,
            // under its words, inside the row so the row keeps its height.
            if (row.state == DownloadStatus.Downloading) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = ListRowSide + SongRowCover + SongRowGap, end = ListRowSide)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(OctoColors.TextPrimary.copy(alpha = 0.08f)),
                ) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(OctoColors.Accent))
                }
            }
        }
    }
}
