package app.winters.octo.ui.menu

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.ArtistRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

// Which song the menu is open for, if any. Any song on any screen can
// open it: a long press on a row, or the more button in the player.
class SongMenuState {
    var trackId by mutableStateOf<String?>(null)
        private set

    // The last song opened, kept after closing so the sheet can still show
    // it while it slides away.
    var lastTrackId by mutableStateOf<String?>(null)
        private set

    fun open(trackId: String) {
        this.trackId = trackId
        lastTrackId = trackId
    }

    fun close() {
        trackId = null
    }
}

val LocalSongMenu = staticCompositionLocalOf<SongMenuState> { error("No song menu") }

@HiltViewModel
class SongMenuViewModel @Inject constructor(
    private val catalog: CatalogDao,
    private val likes: LikeStore,
    private val playback: PlaybackConnection,
) : ViewModel() {
    val liked: StateFlow<Set<String>> = likes.liked

    fun track(id: String): Flow<TrackEntity?> = catalog.trackFlow(id)

    fun playNext(id: String) = playback.playNext(listOf(id))
    fun playLast(id: String) = playback.playLast(listOf(id))
    fun toggleLike(id: String) = likes.toggle(id)
}

// The menu itself, drawn over everything, the player included. `onOpen`
// goes to a page, and is expected to close the player if it is open.
@Composable
fun SongMenuHost(state: SongMenuState, onOpen: (NavKey) -> Unit, vm: SongMenuViewModel = hiltViewModel()) {
    GlassSheet(visible = state.trackId != null, onDismiss = state::close) {
        val trackId = state.lastTrackId ?: return@GlassSheet
        val track by remember(trackId) { vm.track(trackId) }.collectAsStateWithLifecycle(null)
        val liked by vm.liked.collectAsStateWithLifecycle()
        val song = track ?: return@GlassSheet
        val isLiked = trackId in liked

        SongHeader(song)
        Spacer(Modifier.height(8.dp))
        MenuRow(OctoIcons.PlayNext, "Play next") {
            vm.playNext(trackId)
            state.close()
        }
        MenuRow(OctoIcons.AddToQueue, "Add to queue") {
            vm.playLast(trackId)
            state.close()
        }
        MenuRow(if (isLiked) OctoIcons.Liked else OctoIcons.Like, if (isLiked) "Remove from Liked songs" else "Add to Liked songs") {
            vm.toggleLike(trackId)
        }
        MenuRow(OctoIcons.Album, "Go to album") {
            state.close()
            onOpen(AlbumRoute(song.albumId))
        }
        MenuRow(OctoIcons.Artist, "Go to artist") {
            state.close()
            onOpen(ArtistRoute(song.artistId))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SongHeader(song: TrackEntity) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(song.artwork, 48.dp, shape = RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = OctoType.body, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// One choice in the menu: an icon and what it does.
@Composable
fun MenuRow(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .height(52.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = OctoColors.TextSecondary, modifier = Modifier.size(22.dp))
        Text(label, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
    }
}
