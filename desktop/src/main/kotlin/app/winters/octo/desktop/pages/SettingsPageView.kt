package app.winters.octo.desktop.pages

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import app.winters.octo.design.DesktopType
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.scrollbar
import app.winters.octo.design.topScrollFade
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.nav.ScrollSpot
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.LocalBottomRoom
import app.winters.octo.desktop.ui.PageSide
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.windowRect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

// One page of Settings, in the page's place beside the settings list: its
// title alone on the first line, one line about it under that, then its
// titled cards, all scrolling together in a column no wider than reads
// well, with room at the foot so the last card clears the floating player.
// A row asked for (Settings' row target) is brought into view, lit and
// given the keyboard once laid out; one never laid out lights its card
// instead. The scroll is kept
// with the visit, so a detour away and Back returns to the same spot; a
// page picked in its place starts at its top the next time.
@Composable
internal fun SettingsPageView(
    app: AppState,
    visit: Visit,
    key: String,
    title: String,
    lede: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ui = app.settingsUi
    val navigator = app.navigator
    val anchors = remember { SettingsAnchors() }
    val scroll = remember { ScrollState(navigator.scrollOf(visit, scrollKey(key)).index) }
    DisposableEffect(Unit) {
        onDispose {
            val left = navigator.current.id != visit.id
            navigator.keepScroll(visit, if (left) ScrollSpot(scroll.value) else ScrollSpot(), scrollKey(key))
        }
    }
    val viewport = remember { mutableIntStateOf(0) }
    val bottomRoom = LocalBottomRoom.current
    val room by rememberUpdatedState(with(LocalDensity.current) { bottomRoom.roundToPx() to Space.Xl.roundToPx() })
    val aboveTitle by rememberUpdatedState(with(LocalDensity.current) { CardTitleRoom.roundToPx() })
    val still by rememberUpdatedState(LocalReduceMotion.current)
    val flashMs by rememberUpdatedState(LocalRowFlashMs.current)
    LaunchedEffect(anchors) {
        snapshotFlow { ui.target }.filterNotNull().collectLatest { asked ->
            // An ask made for another page is that page's, drawn next.
            if (ui.page != key) return@collectLatest
            ui.aim(null)
            // A new ask lets go of the last one's light, found or not; the
            // light it lights is new by its nonce, so it lights anew.
            anchors.flash = null
            snapshotFlow { anchors.laidOut }.first { it }
            val id = anchors.found(asked.row) ?: run {
                withFrameNanos { }
                anchors.found(asked.row) ?: anchors.cardFor(asked.row)
            } ?: return@collectLatest
            // The keyboard goes to what was asked for first, so it can be
            // changed at once; the scroll below comes after the move it
            // makes to show its control, and has the last word.
            anchors.focus(id)
            withFrameNanos { }
            anchors.placeOf(id)?.let { place ->
                val (foot, margin) = room
                val shown = viewport.intValue - foot
                val now = scroll.value
                // The row's card title comes into view with it, so the row
                // is seen under its heading; a row too far down its card for
                // both is brought in by itself.
                val title = anchors.cardFor(id)?.takeIf { it != id }?.let(anchors::placeOf)?.top
                val top = title?.let { it - aboveTitle } ?: (place.top - margin)
                val inView = top >= now && place.bottom <= now + shown - margin
                if (!inView) {
                    val fits = place.bottom - top <= shown - margin
                    val goal = (if (fits) top else place.top - shown / 4f).roundToInt().coerceIn(0, scroll.maxValue)
                    if (still) scroll.scrollTo(goal) else scroll.animateScrollTo(goal)
                }
            }
            anchors.flash = SettingsFlash(id, asked.nonce)
            delay(flashMs)
            if (anchors.flash?.nonce == asked.nonce) anchors.flash = null
        }
    }
    CompositionLocalProvider(LocalSettingsAnchors provides anchors) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewport.intValue = it.height }
                .onGloballyPositioned { ui.column = it.windowRect() }
                .topScrollFade(scroll, Space.Xl, eased = true),
        ) {
            val side = maxOf(PageSide, (maxWidth - SettingsSize.Column) / 2)
            Column(
                Modifier
                    .fillMaxSize()
                    .scrollbar(scroll, bottomRoom)
                    .verticalScroll(scroll)
                    .onGloballyPositioned {
                        anchors.content = it
                        if (!anchors.laidOut) anchors.laidOut = true
                    }
                    .padding(start = side, end = side, top = Space.Xxl, bottom = bottomRoom + Space.Section),
            ) {
                PageTitle(title, bottom = Space.None)
                if (lede != null) Txt(lede, DesktopType.table, OctoColors.TextSecondary, Modifier.padding(top = Space.S), maxLines = 2)
                Column(Modifier.fillMaxWidth().padding(top = Space.Page), verticalArrangement = Arrangement.spacedBy(Space.Page)) {
                    content()
                }
            }
        }
    }
}

// Where each page of Settings keeps its scroll within the visit.
private fun scrollKey(page: String) = "settings:$page"

// The room above a card's title when a row asked for brings it into view.
private val CardTitleRoom = Space.Page
