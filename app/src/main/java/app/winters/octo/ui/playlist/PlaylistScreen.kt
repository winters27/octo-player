package app.winters.octo.ui.playlist

import app.winters.octo.covers.playlistGlyph
import app.winters.octo.ui.common.phonePlaylistFooter
import app.winters.octo.ui.common.PlaylistArtwork
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistRowView
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.playlists.PlaylistFolderStore
import app.winters.octo.design.OctoColors
import app.winters.octo.design.elevation3
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playlists.PlaylistSync
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.DragHandle
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.LocalSongSelection
import app.winters.octo.ui.common.Pickable
import app.winters.octo.ui.common.RemoveBackground
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.picked
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.menu.SongMenuContext
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.ui.paging.ListHandle
import app.winters.octo.ui.paging.ListItem
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.paging.PagedPicks
import app.winters.octo.ui.paging.RowPlaceholder
import app.winters.octo.ui.paging.rememberListRows
import app.winters.octo.ui.paging.tableChanges
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@HiltViewModel(assistedFactory = PlaylistViewModel.Factory::class)
class PlaylistViewModel @AssistedInject constructor(
    @Assisted private val id: String,
    userDao: UserDao,
    private val store: PlaylistStore,
    private val playback: PlaybackConnection,
    private val feedback: Feedback,
    sync: PlaylistSync,
    loaders: ListLoaders,
    reads: LibraryReads,
    db: CatalogDatabase,
    owners: WorkOwners,
    folders: PlaylistFolderStore,
) : ViewModel() {
    // Whether playlist folders are offered.
    val foldersOffered: StateFlow<Boolean> = folders.offered.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), folders.offeredNow())
    // The playlist's row; null once it is deleted, and before first read.
    val playlist: StateFlow<Loaded<PlaylistEntity?>> =
        userDao.playlist(id).map { Loaded(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loaded(null, read = false))

    // Its count, length and covers.
    val summary: StateFlow<PlaylistSummary?> =
        store.summary(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Its songs in pages, each generation reading the set it pinned when it
    // began, under a walk of its own.
    val rows: ListHandle<Unit, PlaylistRowView> = ListHandle(
        viewModelScope,
        Unit,
        { loaders.playlist(id, owners.walk()) },
        reads,
        { _, loader -> db.tableChanges(loader.tables) },
    )

    // How many of its songs the server's copy goes without, being only on the phone.
    val phoneOnly: StateFlow<Int> =
        userDao.phoneOnlyCount(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Whether a server is signed in, so the playlist could be saved there.
    val serverAvailable: StateFlow<Boolean> =
        sync.available.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Whether songs can be taken out of this playlist here: not from a family
    // playlist unless the last sync found the listener may.
    fun takesOut(playlist: PlaylistEntity): Boolean = !playlist.octoImport && (!playlist.family || playlist.familyMayRemove)

    private fun name() = summary.value?.name ?: playlist.value.value?.name

    fun play(item: ListItem<PlaylistRowView>?) =
        playback.playList(ListSpec.Playlist(id), item?.let { ListStart.Row(it.row.track.id, it.key) }, source = name())

    fun shuffle() = playback.playList(ListSpec.Playlist(id), null, shuffle = true, source = name())

    // Takes a song out by its row's key, with an Undo that puts it back in
    // its place.
    fun remove(itemKey: Long) {
        val name = name() ?: "the playlist"
        store.remove(id, itemKey) { row ->
            feedback.undoable("Removed from $name") { store.restore(id, row) }
        }
    }

    // Takes picked songs out, with an Undo that puts them all back.
    fun removeMany(itemKeys: List<Long>) {
        if (itemKeys.isEmpty()) return
        val name = name() ?: "the playlist"
        store.removeAll(id, itemKeys) { rows ->
            val text = if (rows.size == 1) "Removed from $name" else "Removed ${rows.size} songs from $name"
            feedback.undoable(text) { store.restoreAll(id, rows) }
        }
    }

    fun move(itemKey: Long, targetKey: Long) = store.move(id, itemKey, targetKey)

    override fun onCleared() {
        rows.close()
    }

    @AssistedFactory
    interface Factory {
        fun create(id: String): PlaylistViewModel
    }
}

// A value once read: `read` false before the first read.
data class Loaded<T>(val value: T, val read: Boolean = true)

private val RowShape = RoundedCornerShape(12.dp)

// One playlist: its songs in order, in pages. Drag a song by its handle to
// move it (not in a family playlist, which keeps the server's order), swipe
// it left to take it out, tap it to play from there.
@Composable
fun PlaylistScreen(
    id: String,
    onBack: () -> Unit,
    vm: PlaylistViewModel = hiltViewModel<PlaylistViewModel, PlaylistViewModel.Factory> { it.create(id) },
    keep: KeepDownloadedViewModel = hiltViewModel(),
) {
    val counts by remember(id) { keep.playlistCounts(id) }.collectAsStateWithLifecycle(null)
    val loaded by vm.playlist.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val phoneOnly by vm.phoneOnly.collectAsStateWithLifecycle()
    val serverAvailable by vm.serverAvailable.collectAsStateWithLifecycle()
    val foldersOn by vm.foldersOffered.collectAsStateWithLifecycle()
    val sheets = LocalPlaylistSheets.current
    // Leaves the page once the playlist is deleted.
    val gone = loaded.read && loaded.value == null
    LaunchedEffect(gone) { if (gone) onBack() }

    Box(Modifier.fillMaxSize()) {
        val playlist = loaded.value
        if (playlist != null) {
            val takesOut = vm.takesOut(playlist)
            val songCount = summary?.songCount ?: 0
            val covers = summary?.covers.orEmpty()
            val haptics = LocalHapticFeedback.current
            val listState = rememberLazyListState()
            val items = rememberListRows(vm.rows, listState, above = 1)
            // A drag moves the row on screen at once, from one place to
            // another among the rows read; the playlist catches up on drop.
            var drag by remember { mutableStateOf<Pair<Int, Int>?>(null) }
            fun shownAt(i: Int): Int {
                val (from, to) = drag ?: return i
                return when {
                    i == to -> from
                    from < to && i in from until to -> i + 1
                    from > to && i in (to + 1)..from -> i - 1
                    else -> i
                }
            }
            val reorder = rememberReorderableLazyListState(listState) { from, to ->
                val a = from.index - 1
                val b = to.index - 1
                if (a >= 0 && b >= 0) {
                    drag = ((drag?.first ?: a) to b)
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
            }
            val drop = {
                drag?.let { (from, to) ->
                    val moving = items.peek(from)
                    val target = items.peek(to)
                    if (moving != null && target != null && moving.key != target.key) vm.move(moving.key.id.toLong(), target.key.id.toLong())
                }
                drag = null
            }

            // Songs are picked by their row, since a song can be here twice.
            PagedPicks(
                vm.rows,
                items,
                pickOf = { key, item -> if (item.key.id == key) Pickable(key, item.row.track, item.key).picked() else null },
                base = ListSpec.Playlist(id),
                idOf = { it.key },
                freshKey = { loader, picked -> picked.rowKey?.let { loader.rekey(it) } },
                onRemove = if (!takesOut) null else ({ picked -> vm.removeMany(picked.mapNotNull { it.key.toLongOrNull() }) }),
            ) {
                val selecting = LocalSongSelection.current?.active == true
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = screenPadding(extraTop = DetailTopGap)) {
                    item(key = "header") {
                        ListHeader(
                            title = playlist.name,
                            songCount = songCount,
                            durationMs = summary?.durationMs ?: 0,
                            onPlay = { vm.play(null) },
                            onShuffle = vm::shuffle,
                            onMore = {
                                val onServer = playlist.sourceId != null
                                sheets.show(
                                    PlaylistSheet.Options(
                                        playlist.id,
                                        playlist.name,
                                        onServer,
                                        canSave = serverAvailable && !onServer,
                                        canDownload = (counts?.serverOnly ?: 0) > 0,
                                        keep = true,
                                        family = playlist.family,
                                        canRename = playlist.renamesHere,
                                        canDelete = playlist.deletesHere,
                                        placeKey = playlist.placeKey.takeIf { foldersOn && !playlist.upkeep },
                                    ),
                                )
                            },
                            // How far Keep downloaded has got, and the songs the
                            // server's copy lacks, join the size and length.
                            facts = listOfNotNull(
                                if (playlist.family) "By Family" else null,
                                keepPlaylistProgress(playlist.id),
                                if (playlist.sourceId != null && phoneOnly > 0) phoneOnlyNote(phoneOnly) else null,
                            ),
                        ) { modifier, shape ->
                            PlaylistArtwork(playlist.id, playlist.name, covers, 240.dp, modifier, shape, footer = phonePlaylistFooter(songCount), glyph = playlistGlyph(playlist.octoNotice, null)) {
                                PlaylistCover(covers, 240.dp, modifier, shape)
                            }
                        }
                    }
                    if (summary != null && songCount == 0) {
                        item(key = "empty") { EmptyNote("This playlist is empty. Long press any song and choose Add to playlist.") }
                    }
                    items(items.itemCount, key = { i -> items.peek(shownAt(i))?.key?.token ?: "row:$i" }) { i ->
                        val entry = items[shownAt(i)]
                        if (entry == null) {
                            RowPlaceholder()
                            return@items
                        }
                        ReorderableItem(reorder, key = entry.key.token) { dragging ->
                            val swipe = rememberSwipeToDismissBoxState()
                            SwipeToDismissBox(
                                state = swipe,
                                enableDismissFromStartToEnd = false,
                                gesturesEnabled = !dragging && !selecting && takesOut,
                                onDismiss = { vm.remove(entry.key.id.toLong()) },
                                backgroundContent = { RemoveBackground(swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart, RowShape) },
                            ) {
                                // Solid under the row while it moves, so it hides what it
                                // passes over; clear at rest, so the page's glow shows.
                                val swiping = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
                                Box(
                                    Modifier
                                        .then(if (dragging) Modifier.elevation3(RowShape) else Modifier)
                                        .background(
                                            when {
                                                dragging -> OctoColors.BackgroundTertiary
                                                swiping -> OctoColors.Background
                                                else -> Color.Transparent
                                            },
                                            RowShape,
                                        ),
                                ) {
                                    SongRow(
                                        entry.row.track,
                                        // No moving songs while picking them, or in a family playlist.
                                        trailing = if (selecting || playlist.family || playlist.octoImport) null else ({ DragHandle(Modifier.draggableHandle(onDragStopped = { drop() })) }),
                                        menuContext = SongMenuContext(playlistId = playlist.id, playlistItemId = entry.key.id.toLong().takeIf { takesOut }),
                                        selectKey = entry.key.id,
                                        // A swipe left takes the song out here.
                                        queueSwipe = false,
                                    ) { vm.play(entry) }
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

// The quiet line on a playlist kept with the server when some of its songs
// have no copy there.
internal fun phoneOnlyNote(count: Int): String =
    if (count == 1) {
        "1 song only on this phone, not in the server's copy"
    } else {
        "%,d songs only on this phone, not in the server's copy".format(count)
    }
