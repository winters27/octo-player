package app.winters.octo.desktop.pages

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.LibraryIndex
import app.winters.octo.desktop.library.LibraryState
import app.winters.octo.desktop.library.totalLengthText
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.ui.AlbumMenu
import app.winters.octo.desktop.ui.EmptyLine
import app.winters.octo.desktop.ui.FailedLine
import app.winters.octo.desktop.ui.LoadingLine
import app.winters.octo.desktop.ui.LocalPointer
import app.winters.octo.desktop.ui.MediaCard
import app.winters.octo.desktop.ui.ShelfCardWidth
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.IconAction
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Txt
import app.winters.octo.subsonic.Album
import kotlinx.coroutines.launch

// Shows the library once it has been read, or where reading it has got to.
@Composable
fun WithLibrary(app: AppState, content: @Composable (LibraryIndex) -> Unit) {
    val store = app.library ?: return
    val state by store.state.collectAsState()
    when (val s = state) {
        LibraryState.Idle, LibraryState.Loading -> LoadingLine("Reading your library")
        is LibraryState.Failed -> FailedLine(s.message, store::load)
        is LibraryState.Ready -> content(s.index)
    }
}

// A shelf of cards that scrolls sideways, with arrows for a mouse wheel
// that only scrolls down.
@Composable
fun <T> Shelf(title: String, items: List<T>, key: (T) -> Any, card: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { (ShelfCardWidth * 3).toPx() }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, OctoType.headline, modifier = Modifier.weight(1f))
            IconAction(OctoIcons.Back, "Scroll back", { scope.launch { state.animateScrollBy(-step) } }, size = 30.dp, iconSize = 18.dp, enabled = state.canScrollBackward)
            IconAction(OctoIcons.Forward, "Scroll on", { scope.launch { state.animateScrollBy(step) } }, size = 30.dp, iconSize = 18.dp, enabled = state.canScrollForward)
        }
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = 0.dp)) {
            items(items, key = key) { item -> Box(Modifier.width(ShelfCardWidth)) { card(item) } }
        }
    }
}

// An album as a card, opening its page, with the album menu on a right click.
@Composable
fun AlbumCard(app: AppState, album: Album, modifier: Modifier = Modifier, outside: Boolean = false, badge: @Composable (() -> Unit)? = null) {
    val pointer = LocalPointer.current
    MediaCard(
        album.name,
        listOfNotNull(album.displayArtist ?: album.artist.takeIf(String::isNotEmpty), album.year?.takeIf { it > 0 }?.toString()).joinToString(" · ").ifEmpty { null },
        album.coverArt,
        onOpen = { app.navigator.go(Page.Album(album.id)) },
        modifier = modifier,
        online = outside,
        onMenu = { app.popups.showAt(pointer.point) { close -> AlbumMenu(app, album, close, outside) } },
        badge = badge,
    )
}

// The top of a page that plays a list: the picture, what it is, its name,
// a line of details, and Play and Shuffle as two glass capsules.
@Composable
fun ListHeader(
    kind: String,
    title: String,
    coverId: String?,
    details: String?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: @Composable (() -> Unit)? = null,
    round: Boolean = false,
    playable: Boolean = true,
    online: Boolean = false,
    extras: @Composable () -> Unit = {},
    art: Dp = 200.dp,
    // Draws the name instead of the plain title, like one renamed by clicking it.
    titleContent: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Cover(
            coverId,
            Modifier.size(art),
            shape = if (round) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(10.dp),
            online = online,
            placeholder = if (round) OctoIcons.Artist else OctoIcons.Album,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(kind, OctoType.caption, OctoColors.TextMuted)
            if (titleContent != null) titleContent() else Txt(title, OctoType.display, maxLines = 2)
            subtitle?.invoke()
            if (details != null) Txt(details, OctoType.bodySmall, OctoColors.TextMuted)
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (playable) {
                    GlazeCapsule(OctoIcons.Play, "Play", onPlay, lit = true, modifier = Modifier.width(150.dp))
                    GlazeCapsule(OctoIcons.Shuffle, "Shuffle", onShuffle, modifier = Modifier.width(150.dp))
                }
                extras()
            }
        }
    }
}

fun songsLine(count: Int, seconds: Int): String =
    listOf(if (count == 1) "1 song" else "$count songs", totalLengthText(seconds)).joinToString(" · ")

@Composable
fun NothingHere(title: String, detail: String? = null) = EmptyLine(title, detail)
