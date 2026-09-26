package app.winters.octo.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistTrack
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.mosaicCovers
import app.winters.octo.design.OctoColors
import app.winters.octo.design.elevation3
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playlists.PlaylistSync
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.DragHandle
import app.winters.octo.ui.common.RemoveBackground
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.screenPadding
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// A playlist as its page shows it. `playlist` is null once it is deleted.
data class PlaylistPage(
    val playlist: PlaylistEntity?,
    val tracks: List<PlaylistTrack>,
) {
    val covers = mosaicCovers(tracks.map { it.track.albumId to it.track.artwork })
    val durationMs = tracks.sumOf { it.track.durationMs }
}

@HiltViewModel(assistedFactory = PlaylistViewModel.Factory::class)
class PlaylistViewModel @AssistedInject constructor(
    @Assisted private val id: String,
    userDao: UserDao,
    private val store: PlaylistStore,
    private val playback: PlaybackConnection,
    sync: PlaylistSync,
) : ViewModel() {
    // Null until first read.
    val page: StateFlow<PlaylistPage?> =
        combine(userDao.playlist(id), userDao.playlistTracks(id), ::PlaylistPage)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // How many of its songs the server's copy goes without, being only on the phone.
    val phoneOnly: StateFlow<Int> =
        userDao.phoneOnlyCount(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Whether a server is signed in, so the playlist could be saved there.
    val serverAvailable: StateFlow<Boolean> =
        sync.available.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private fun ids() = page.value?.tracks.orEmpty().map { it.track.id }

    fun play(index: Int) = playback.playTracks(ids(), index)

    fun shuffle() = playback.playTracks(ids(), shuffle = true)

    fun remove(itemId: Long) = store.remove(id, itemId)

    fun move(itemId: Long, targetId: Long) = store.move(id, itemId, targetId)

    @AssistedFactory
    interface Factory {
        fun create(id: String): PlaylistViewModel
    }
}

private val RowShape = RoundedCornerShape(12.dp)

// One playlist: its songs in order. Drag a song by its handle to move it,
// swipe it left to take it out, tap it to play from there.
@Composable
fun PlaylistScreen(
    id: String,
    onBack: () -> Unit,
    vm: PlaylistViewModel = hiltViewModel<PlaylistViewModel, PlaylistViewModel.Factory> { it.create(id) },
) {
    val page by vm.page.collectAsStateWithLifecycle()
    val phoneOnly by vm.phoneOnly.collectAsStateWithLifecycle()
    val serverAvailable by vm.serverAvailable.collectAsStateWithLifecycle()
    val sheets = LocalPlaylistSheets.current
    // Leaves the page once the playlist is deleted.
    val gone = page?.let { it.playlist == null } ?: false
    LaunchedEffect(gone) { if (gone) onBack() }

    Box(Modifier.fillMaxSize()) {
        val current = page
        val playlist = current?.playlist
        if (current != null && playlist != null) {
            val tracks = current.tracks
            // The rows as drawn. A drag moves them at once; the playlist catches up on drop.
            var rows by remember(tracks) { mutableStateOf(tracks) }
            val haptics = LocalHapticFeedback.current
            val listState = rememberLazyListState()
            val reorder = rememberReorderableLazyListState(listState) { from, to ->
                val a = rows.indexOfFirst { it.itemId == from.key }
                val b = rows.indexOfFirst { it.itemId == to.key }
                if (a >= 0 && b >= 0) {
                    rows = rows.toMutableList().apply { add(b, removeAt(a)) }
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
            }

            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = screenPadding(extraTop = DetailTopGap)) {
                item(key = "header") {
                    ListHeader(
                        title = playlist.name,
                        songCount = tracks.size,
                        durationMs = current.durationMs,
                        onPlay = { vm.play(0) },
                        onShuffle = vm::shuffle,
                        onMore = {
                            val onServer = playlist.sourceId != null
                            val canDownload = tracks.any { !it.track.onPhone && !isFind(it.track.id) }
                            sheets.show(
                                PlaylistSheet.Options(playlist.id, playlist.name, onServer, canSave = serverAvailable && !onServer, canDownload = canDownload),
                            )
                        },
                    ) { modifier, shape -> PlaylistCover(current.covers, 240.dp, modifier, shape) }
                }
                item(key = "keep") { KeepPlaylistDownloaded(playlist.id, tracks.map { it.track }) }
                if (playlist.sourceId != null && phoneOnly > 0) {
                    item(key = "phone-only") { EmptyNote(phoneOnlyNote(phoneOnly)) }
                }
                if (rows.isEmpty()) {
                    item(key = "empty") { EmptyNote("This playlist is empty. Long press any song and choose Add to playlist.") }
                }
                items(rows, key = { it.itemId }) { entry ->
                    ReorderableItem(reorder, key = entry.itemId) { dragging ->
                        // Moves the song into the place of the one it landed on.
                        val drop = {
                            val landed = rows.indexOfFirst { it.itemId == entry.itemId }
                            val target = tracks.getOrNull(landed)?.itemId
                            if (target != null && target != entry.itemId) vm.move(entry.itemId, target)
                        }
                        val swipe = rememberSwipeToDismissBoxState()
                        SwipeToDismissBox(
                            state = swipe,
                            enableDismissFromStartToEnd = false,
                            gesturesEnabled = !dragging,
                            onDismiss = {
                                rows = rows - entry
                                vm.remove(entry.itemId)
                            },
                            backgroundContent = { RemoveBackground(swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart, RowShape) },
                        ) {
                            // Solid under the row, so it hides what it passes over.
                            Box(
                                Modifier
                                    .then(if (dragging) Modifier.elevation3(RowShape) else Modifier)
                                    .background(if (dragging) OctoColors.BackgroundTertiary else OctoColors.Background, RowShape),
                            ) {
                                SongRow(
                                    entry.track,
                                    trailing = { DragHandle(Modifier.draggableHandle(onDragStopped = drop)) },
                                ) { vm.play(tracks.indexOf(entry)) }
                            }
                        }
                    }
                }
            }
        }
        BackButton(onBack)
    }
}

// The quiet line on a playlist kept with the server when some of its songs
// have no copy there.
internal fun phoneOnlyNote(count: Int): String =
    if (count == 1) {
        "1 song is only on this phone and is not in the server's copy"
    } else {
        "$count songs are only on this phone and are not in the server's copy"
    }
