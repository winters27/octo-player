package app.winters.octo.desktop.pages

import app.winters.octo.design.LocalReduceMotion
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import app.winters.octo.desktop.ui.PageSide
import app.winters.octo.desktop.ui.ShelfCardWidth
import app.winters.octo.design.Corner
import app.winters.octo.design.CutTxt
import app.winters.octo.design.DesktopType
import app.winters.octo.design.PageSize
import app.winters.octo.design.Space
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
        // In from the page's side, where the page's own words start.
        LibraryState.Idle, LibraryState.Loading -> LoadingLine("Reading your library", Modifier.padding(horizontal = PageSide))
        is LibraryState.Failed -> FailedLine(s.message, store::load, Modifier.padding(horizontal = PageSide))
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
    val still = LocalReduceMotion.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, OctoType.headline, modifier = Modifier.weight(1f))
            IconAction(OctoIcons.Back, "Scroll back", { scope.launch { if (still) state.scrollBy(-step) else state.animateScrollBy(-step) } }, size = 30.dp, iconSize = 18.dp, enabled = state.canScrollBackward)
            IconAction(OctoIcons.Forward, "Scroll on", { scope.launch { if (still) state.scrollBy(step) else state.animateScrollBy(step) } }, size = 30.dp, iconSize = 18.dp, enabled = state.canScrollForward)
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
// a line of details, and Play and Shuffle as two glass capsules. Laid out
// as an album's heading is (EntityHeader), so the two pages match.
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
    // Draws the name instead of the plain title, like one renamed by clicking it.
    titleContent: (@Composable () -> Unit)? = null,
    // Draws the picture instead of the cover, like a playlist's designed one.
    picture: (@Composable (Modifier) -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val art = headerArt(maxWidth)
        Row(Modifier.fillMaxWidth().padding(bottom = Space.Xl), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.Page)) {
            if (picture != null) {
                picture(Modifier.size(art))
            } else {
                Cover(
                    coverId,
                    Modifier.size(art),
                    shape = if (round) CircleShape else Corner.ArtLShape,
                    online = online,
                    placeholder = if (round) OctoIcons.Artist else OctoIcons.Album,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Xs)) {
                Txt(kind.uppercase(), DesktopType.label, OctoColors.TextMuted)
                if (titleContent != null) titleContent() else CutTxt(title, DesktopType.pageTitle, maxLines = 2)
                subtitle?.invoke()
                if (details != null) Txt(details, DesktopType.meta, OctoColors.TextMuted)
                // Play and Shuffle stay, greyed, on an empty list, so the row keeps its place.
                HeaderActions {
                    PlayAndShuffle(onPlay, onShuffle, enabled = playable)
                    extras()
                }
            }
        }
    }
}

// "1 album", "2,500 songs": a count and what it counts, with thousands marked.
fun countText(count: Int, one: String, many: String = one + "s"): String = "%,d %s".format(count, if (count == 1) one else many)

fun songsLine(count: Int, seconds: Int): String =
    listOf(countText(count, "song"), totalLengthText(seconds)).joinToString(" · ")

@Composable
fun NothingHere(title: String, detail: String? = null) = EmptyLine(title, detail)
