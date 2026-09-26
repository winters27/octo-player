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
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.PlaybackConnection
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
) : ViewModel() {
    val liked: StateFlow<Set<String>> = likes.liked

    fun play(ids: List<String>) = playback.playTracks(ids, 0)
    fun playNext(ids: List<String>) = playback.playNext(ids)
    fun addToQueue(ids: List<String>) = playback.playLast(ids)

    // Downloads the ones only on a server; the rest are on the phone already.
    fun download(ids: List<String>) = offline.download(ids)

    // Likes every song, or takes the like off every one.
    fun setLiked(ids: List<String>, like: Boolean) {
        ids.filter { (it in liked.value) != like }.forEach(likes::toggle)
    }
}

// The bar that acts on picked songs, in place of the bottom bar while a
// list is picking. Back, the close button, or taking the last song off ends
// the picking; so does any action.
@Composable
fun SelectionBarHost(bar: SelectionBarState, vm: SelectionBarViewModel = hiltViewModel()) {
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
            SelectionBar(shown, vm)
        }
    }
}

@Composable
private fun SelectionBar(target: SelectionTarget, vm: SelectionBarViewModel) {
    val liked by vm.liked.collectAsStateWithLifecycle()
    val sheets = LocalPlaylistSheets.current
    val picked = target.picked()
    val ids = picked.map { it.track.id }
    val library = ids.filterNot(::isFind)
    val allLiked = library.isNotEmpty() && library.all { it in liked }
    val canDownload = picked.any { !it.track.onPhone && !isFind(it.track.id) }
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
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val each = Modifier.weight(1f)
                BarButton(OctoIcons.Play, "Play", each) { act { vm.play(ids) } }
                BarButton(OctoIcons.PlayNext, "Play next", each) { act { vm.playNext(ids) } }
                BarButton(OctoIcons.AddToQueue, "Add to queue", each) { act { vm.addToQueue(ids) } }
                BarButton(OctoIcons.AddToPlaylist, "Add to playlist", each, enabled = library.isNotEmpty()) {
                    act { sheets.show(PlaylistSheet.Pick(library)) }
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
