package app.winters.octo.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.winters.octo.design.CutTxt
import app.winters.octo.design.DesktopType
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Space
import app.winters.octo.design.Spinner
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.nav.Navigator
import app.winters.octo.desktop.nav.ScrollSpot
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.subsonic.SubsonicException

// What a page's data has come to.
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

// A page's data, loaded when the page opens (and again when a key
// changes), with a way to try again after a failure.
class Loaded<T>(val state: Load<T>, val retry: () -> Unit)

@Composable
fun <T> rememberLoad(vararg keys: Any?, load: suspend () -> T): Loaded<T> {
    var round by remember(*keys) { mutableIntStateOf(0) }
    val state by produceState<Load<T>>(Load.Loading, *keys, round) {
        value = Load.Loading
        value = try {
            Load.Ready(load())
        } catch (e: SubsonicException) {
            Load.Failed(e.userMessage())
        }
    }
    return Loaded(state) { round++ }
}

// A list's scroll kept with the visit it belongs to, so going back returns
// to the same spot.
@Composable
fun rememberListState(navigator: Navigator, visit: Visit): LazyListState {
    val state = remember(visit.id) { navigator.scrollOf(visit).let { LazyListState(it.index, it.offset) } }
    DisposableEffect(visit.id) {
        onDispose { navigator.keepScroll(visit, ScrollSpot(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)) }
    }
    return state
}

// A grid's is kept apart from a list's, for a page that has both.
@Composable
fun rememberGridState(navigator: Navigator, visit: Visit): LazyGridState {
    val state = remember(visit.id) { navigator.scrollOf(visit, GRID).let { LazyGridState(it.index, it.offset) } }
    DisposableEffect(visit.id) {
        onDispose { navigator.keepScroll(visit, ScrollSpot(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset), GRID) }
    }
    return state
}

private const val GRID = "grid"

// Room a page leaves at its foot for the now-playing bar floating over it.
val LocalBottomRoom = staticCompositionLocalOf { 0.dp }

// The side margin every page keeps.
val PageSide = 28.dp

fun pagePadding(bottom: Dp, top: Dp = 20.dp) = PaddingValues(start = PageSide, end = PageSide, top = top, bottom = bottom + 16.dp)

// Where the pointer last was in the window, for opening a menu where a
// right click landed.
@Stable
class PointerSpot {
    var position: Offset = Offset.Zero

    val point: IntOffset get() = IntOffset(position.x.toInt(), position.y.toInt())
}

val LocalPointer = staticCompositionLocalOf { PointerSpot() }

// Calls `action` on a right click, before anything under it hears the press.
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.onRightClick(action: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
        if (event.buttons.isSecondaryPressed) action()
    }

@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(modifier.padding(bottom = Space.Xl)) {
        Txt(text, DesktopType.pageTitle)
        if (detail != null) Txt(detail, DesktopType.meta, OctoColors.TextMuted, Modifier.padding(top = Space.Xs))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, OctoType.headline, modifier = Modifier.weight(1f))
        if (action != null) TextAction(action, onAction)
    }
}

// A page that is still loading, or failed, or has nothing to show.
@Composable
fun LoadingLine(text: String = "Loading", modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Spinner(size = 16.dp, color = OctoColors.TextSecondary)
        Txt(text, OctoType.bodySmall, OctoColors.TextSecondary)
    }
}

@Composable
fun FailedLine(message: String, retry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(message, OctoType.bodySmall, OctoColors.TextSecondary, maxLines = 3)
        GlazeCapsule(null, "Try again", retry)
    }
}

@Composable
fun EmptyLine(title: String, detail: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(title, OctoType.headline)
        if (detail != null) Txt(detail, OctoType.bodySmall, OctoColors.TextMuted, maxLines = 3)
    }
}

// Shows a load's data, or the loading or failed line in its place.
@Composable
fun <T> Loaded<T>.show(modifier: Modifier = Modifier, loadingText: String = "Loading", content: @Composable (T) -> Unit) {
    when (val s = state) {
        Load.Loading -> LoadingLine(loadingText, modifier)
        is Load.Failed -> FailedLine(s.message, retry, modifier)
        is Load.Ready -> content(s.data)
    }
}

// An album, artist or station as a card: the picture and a line or two
// under it. It lifts under the pointer; a right click opens `onMenu`.
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    coverId: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    online: Boolean = false,
    onMenu: (() -> Unit)? = null,
    badge: @Composable (() -> Unit)? = null,
) {
    val shape = if (round) CircleShape else RoundedCornerShape(8.dp)
    Column(
        modifier
            .hoverLift(RoundedCornerShape(12.dp))
            .then(if (onMenu != null) Modifier.onRightClick(onMenu) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onOpen)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            Cover(
                coverId,
                Modifier.fillMaxWidth().aspectRatio(1f),
                shape = shape,
                online = online,
                placeholder = if (round) OctoIcons.Artist else OctoIcons.Album,
            )
            if (badge != null) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { badge() }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start) {
            CutTxt(title, OctoType.label)
            if (subtitle != null) CutTxt(subtitle, OctoType.caption, OctoColors.TextMuted)
        }
    }
}

// A card's width in a shelf that scrolls sideways.
val ShelfCardWidth = 176.dp

// A blank that swallows clicks and the wheel, so a click or scroll on empty
// space does nothing, and nothing lying under it hears them.
fun Modifier.swallowClicks(): Modifier = pointerInput(Unit) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }
}

@Composable
fun Gap(width: Dp) = Box(Modifier.width(width))

// Whether a list (a song table) has the keyboard, so the window leaves the
// arrow keys and Enter to it.
@Stable
class ListFocus {
    var active by mutableStateOf(false)
}

val LocalListFocus = staticCompositionLocalOf { ListFocus() }
