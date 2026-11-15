package app.winters.octo.ui.common

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.catalog.isFind
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import androidx.lifecycle.viewModelScope
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playback.QUEUE_WINDOW
import app.winters.octo.playback.queueAddWords
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.winters.octo.health.DELETE_FROM_DISK
import app.winters.octo.ui.menu.DiskDeleteHost
import app.winters.octo.ui.menu.DiskDeleteViewModel
import app.winters.octo.ui.menu.PhoneFileActions
import app.winters.octo.ui.playlist.LocalPlaylistSheets
import app.winters.octo.ui.playlist.PlaylistSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

private val BarShape = RoundedCornerShape(28.dp)

@HiltViewModel
class SelectionBarViewModel @Inject constructor(
    private val playback: PlaybackConnection,
    private val likes: LikeStore,
    private val offline: OfflineDownloads,
    private val feedback: Feedback,
    private val playlists: PlaylistStore,
) : ViewModel() {
    // Whether songs found online can go in a playlist on this server.
    val playlistAdds: Boolean get() = playlists.findsOpen
    // Which of these songs are liked, as that changes.
    fun likedAmong(ids: List<String>): Flow<Set<String>> = likes.likedAmong(ids)

    // The picked rows play as part of the list they were picked on, in its
    // order now, from the first; a list held whole plays them as they are.
    fun play(target: SelectionTarget) {
        viewModelScope.launch {
            val picked = target.ordered()
            val first = picked.firstOrNull() ?: return@launch
            playback.playList(specOf(target, picked), ListStart.First(first.trackId, first.rowKey))
        }
    }

    fun add(target: SelectionTarget, next: Boolean) {
        viewModelScope.launch {
            val picked = target.ordered()
            if (picked.isEmpty()) return@launch
            playback.addList(specOf(target, picked), next, inserted = { count, undo -> feedback.undoable(queueAddWords(count, next), undo) })
        }
    }

    private fun specOf(target: SelectionTarget, picked: List<Picked>): ListSpec =
        target.spec?.let { ListSpec.Selection(it, picked.map(Picked::key)) } ?: ListSpec.Ids(picked.map(Picked::trackId).take(QUEUE_WINDOW))

    // The picked library songs in the list's order, for a playlist.
    fun withOrdered(target: SelectionTarget, use: (List<Picked>) -> Unit) {
        viewModelScope.launch { use(target.ordered()) }
    }

    // Downloads the ones only on a server; the rest are on the phone already.
    fun download(ids: List<String>) = offline.download(ids)

    // Likes every song, or takes the like off every one, as they stand.
    fun setLiked(ids: List<String>, like: Boolean) {
        viewModelScope.launch {
            val liked = likes.likedAmong(ids).first()
            ids.filter { (it in liked) != like }.forEach(likes::toggle)
        }
    }
}

// The bar that acts on picked songs, in place of the bottom bar while a
// list is picking. Back, the close button, or taking the last song off ends
// the picking; so does any action. `phoneFiles` acts on the songs' files
// on the phone; `disk` asks before deleting them from the server's disk.
@Composable
fun SelectionBarHost(
    bar: SelectionBarState,
    phoneFiles: PhoneFileActions,
    vm: SelectionBarViewModel = hiltViewModel(),
    disk: DiskDeleteViewModel = hiltViewModel(),
) {
    val target = bar.selecting
    BackHandler(enabled = target != null) { target?.selection?.clear() }
    // Keeps the last list drawn while the bar slides away.
    var last by remember { mutableStateOf<SelectionTarget?>(null) }
    if (target != null) last = target
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = target != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            val shown = last ?: return@AnimatedVisibility
            SelectionBar(shown, phoneFiles, vm, disk)
        }
    }
    DiskDeleteHost(disk)
}

@Composable
private fun SelectionBar(target: SelectionTarget, phoneFiles: PhoneFileActions, vm: SelectionBarViewModel, disk: DiskDeleteViewModel) {
    val sheets = LocalPlaylistSheets.current
    val picked = target.picks
    val ids = picked.map { it.trackId }
    val library = ids.filterNot(::isFind)
    val liked by remember(library) { vm.likedAmong(library) }.collectAsStateWithLifecycle(emptySet())
    val allLiked = library.isNotEmpty() && library.all { it in liked }
    val canDownload = picked.any { !it.onPhone && !isFind(it.trackId) }
    // Files are shared or deleted only when every picked song has one.
    val allOnPhone = picked.isNotEmpty() && picked.all { it.onPhone && !isFind(it.trackId) }
    // Deleted from the server's disk only when every picked song has a
    // copy there and the server lets this user.
    val canRemove by disk.canRemove.collectAsStateWithLifecycle()
    val onDisk by produceState(emptyList<String>(), library, canRemove) { value = if (canRemove) disk.deletable(library) else emptyList() }
    val allOnDisk = library.isNotEmpty() && library.size == ids.size && onDisk.size == library.size
    val choices = LocalChoiceSheet.current
    val askDisk = { disk.ask(library, picked.map { it.title }) }
    val remove = target.remove
    // Every action ends the picking once done.
    val act = { action: () -> Unit ->
        action()
        target.selection.clear()
    }

    FloatingGlaze(
        LocalHaze.current,
        Modifier
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
            .widthIn(max = 520.dp)
            .fillMaxWidth(),
        shape = BarShape,
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarButton(OctoIcons.Close, "Stop selecting", Modifier.size(44.dp)) { target.selection.clear() }
                Text(
                    if (picked.size == 1) "1 selected" else "${picked.size} selected",
                    style = OctoType.bodySmall,
                    color = OctoColors.TextPrimary,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                // Beside the count, where there is room, for songs on the phone.
                if (allOnPhone) {
                    BarButton(OctoIcons.ShareFile, if (picked.size == 1) "Share file" else "Share files", Modifier.size(44.dp)) { act { phoneFiles.share(ids) } }
                }
                // One delete button; when the songs are both on the phone and
                // on the server's disk, it asks which.
                when {
                    allOnPhone && allOnDisk -> BarButton(OctoIcons.Delete, "Delete", Modifier.size(44.dp).choiceAnchor(choices)) {
                        act {
                            choices.show(
                                ChoiceRequest("Delete", listOf(Choice("Delete from phone"), Choice(DELETE_FROM_DISK)), selected = -1) { index ->
                                    if (index == 0) phoneFiles.delete(ids) else askDisk()
                                },
                            )
                        }
                    }
                    allOnPhone -> BarButton(OctoIcons.Delete, "Delete from phone", Modifier.size(44.dp)) { act { phoneFiles.delete(ids) } }
                    allOnDisk -> BarButton(OctoIcons.Delete, DELETE_FROM_DISK, Modifier.size(44.dp)) { act { askDisk() } }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val each = Modifier.weight(1f)
                BarButton(OctoIcons.Play, "Play", each) { act { vm.play(target) } }
                BarButton(OctoIcons.PlayNext, "Play next", each) { act { vm.add(target, next = true) } }
                BarButton(OctoIcons.AddToQueue, "Add to queue", each) { act { vm.add(target, next = false) } }
                BarButton(OctoIcons.AddToPlaylist, "Add to playlist", each, enabled = playlistPickIds(ids, vm.playlistAdds).isNotEmpty()) {
                    act { vm.withOrdered(target) { rows -> sheets.show(PlaylistSheet.Pick(playlistPickIds(rows.map { it.trackId }, vm.playlistAdds))) } }
                }
                BarButton(OctoIcons.Download, "Download", each, enabled = canDownload) { act { vm.download(library) } }
                BarButton(
                    if (allLiked) OctoIcons.Liked else OctoIcons.Like,
                    if (allLiked) "Remove from Liked songs" else "Add to Liked songs",
                    each,
                    enabled = library.isNotEmpty(),
                ) { act { vm.setLiked(library, like = !allLiked) } }
                if (remove != null) {
                    BarButton(OctoIcons.RemoveFromPlaylist, "Remove from this playlist", each) { act { remove(picked) } }
                }
            }
        }
    }
}

// The picked songs that can go in a playlist: library songs, and songs
// found online while the server keeps them in playlists (`finds`).
internal fun playlistPickIds(ids: List<String>, finds: Boolean): List<String> = if (finds) ids else ids.filterNot(::isFind)

// One of the bar's buttons: an icon, named for screen readers. One that
// cannot act on these songs is dimmed.
@Composable
private fun BarButton(
    @DrawableRes icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(44.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (enabled) OctoColors.TextPrimary else OctoColors.TextMuted.copy(alpha = 0.35f),
            modifier = Modifier.size(22.dp),
        )
    }
}
