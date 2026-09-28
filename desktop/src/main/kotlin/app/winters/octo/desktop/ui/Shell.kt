package app.winters.octo.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.LocalCovers
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.pages.AlbumPage
import app.winters.octo.desktop.pages.AlbumsPage
import app.winters.octo.desktop.pages.ArtistPage
import app.winters.octo.desktop.pages.ArtistsPage
import app.winters.octo.desktop.pages.FavouritesPage
import app.winters.octo.desktop.pages.FolderPage
import app.winters.octo.desktop.pages.FoldersPage
import app.winters.octo.desktop.pages.GenrePage
import app.winters.octo.desktop.pages.GenresPage
import app.winters.octo.desktop.pages.HistoryPage
import app.winters.octo.desktop.pages.HomePage
import app.winters.octo.desktop.pages.PlaylistPage
import app.winters.octo.desktop.pages.SearchPage
import app.winters.octo.desktop.pages.SettingsPage
import app.winters.octo.desktop.pages.SignInPage
import app.winters.octo.desktop.pages.SongsPage
import app.winters.octo.desktop.pages.SoundPage
import app.winters.octo.desktop.window.Frame
import app.winters.octo.desktop.window.MacLightsRoom
import app.winters.octo.desktop.window.ResizeEdges
import app.winters.octo.desktop.window.TitleBarHeight
import app.winters.octo.desktop.window.TitleStrip
import app.winters.octo.desktop.window.WindowButtons
import app.winters.octo.desktop.window.dragsWindow
import app.winters.octo.player.immersive.WashTuning
import app.winters.octo.design.IconAction
import app.winters.octo.design.LocalPopups
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.PopupLayer
import app.winters.octo.design.Txt
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private val Margin = 10.dp

// The whole window: the stage (the glow, the page) under the glass (the
// sidebar, the now-playing bar, the side panel), the pop-ups over all of
// it, and, when the app draws its own frame, the title bar's buttons and
// the edges to resize by. `frame` is null when the system draws the frame.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Shell(app: AppState, frame: Frame?, onClose: () -> Unit) {
    val backdrop = rememberHazeState()
    val pointer = remember { PointerSpot() }
    val connection = app.connection
    val key = rememberKeyColour(app)
    CompositionLocalProvider(
        LocalPopups provides app.popups,
        LocalPointer provides pointer,
        LocalCovers provides connection?.client,
        LocalKeyColour provides key,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(OctoColors.Background)
                // Where the pointer is, for menus, and the mouse's back and
                // forward buttons, before anything else hears them.
                .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) { pointer.position = it.changes.first().position }
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                    pointer.position = event.changes.first().position
                    if (event.buttons.isBackPressed) app.navigator.back()
                    if (event.buttons.isForwardPressed) app.navigator.forward()
                },
        ) {
            val top = TitleBarHeight
            val panel = app.sidePanel
            // The stage: everything the glass frosts.
            Box(Modifier.fillMaxSize().hazeSource(backdrop)) {
                AmbientGlow(app)
                if (connection != null) {
                    val end = if (panel != null) SidePanelWidth + Margin * 2 else Margin
                    CompositionLocalProvider(LocalBottomRoom provides BarHeight + Margin * 2) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(start = SidebarWidth + Margin * 2, end = end, top = top)
                                .clipToBounds(),
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                app.notice?.let { Notice(it) { app.notice = null } }
                                Box(Modifier.weight(1f)) { PageHost(app) }
                            }
                        }
                    }
                }
            }
            // The glass. The sign-in card frosts the colours behind it, so it
            // sits here, over the stage.
            if (connection == null) Box(Modifier.fillMaxSize().padding(top = top)) { SignInPage(app, backdrop) }
            if (connection != null) {
                Sidebar(
                    app,
                    backdrop,
                    Modifier
                        .padding(start = Margin, top = top, bottom = BarHeight + Margin * 2)
                        .width(SidebarWidth)
                        .fillMaxHeight(),
                )
                if (panel != null) {
                    SidePanelView(
                        app,
                        panel,
                        backdrop,
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = Margin, top = top, bottom = BarHeight + Margin * 2)
                            .width(SidePanelWidth)
                            .fillMaxHeight(),
                    )
                }
                AnimatedVisibility(app.fullPlayer, enter = fadeIn(), exit = fadeOut()) {
                    FullPlayer(app, Modifier.fillMaxSize(), top = top)
                }
                // The full player has its own controls.
                if (!app.fullPlayer) {
                    NowPlayingBar(
                        app,
                        backdrop,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(Margin)
                            .fillMaxWidth()
                            .height(BarHeight),
                    )
                }
            }
            TitleBar(app, frame, onClose)
            PopupLayer(app.popups, backdrop)
            if (frame != null) ResizeEdges(frame)
        }
    }
}

// The strip along the top: back and forward, room to drag the window by,
// and the window's buttons when the app draws its frame.
@Composable
private fun TitleBar(app: AppState, frame: Frame?, onClose: () -> Unit) {
    TitleStrip {
        if (app.mac) Spacer(Modifier.width(MacLightsRoom)) else Spacer(Modifier.width(Margin))
        if (app.connection != null) {
            IconAction(OctoIcons.Back, "Back", { app.navigator.back() }, size = 30.dp, iconSize = 18.dp, enabled = app.navigator.canGoBack)
            IconAction(OctoIcons.Forward, "Forward", { app.navigator.forward() }, size = 30.dp, iconSize = 18.dp, enabled = app.navigator.canGoForward)
        }
        Box(Modifier.weight(1f).fillMaxHeight().then(if (frame != null) Modifier.dragsWindow(frame) else Modifier), contentAlignment = Alignment.CenterStart) {
            if (frame != null) Txt("Octo", OctoType.label, OctoColors.TextMuted, Modifier.padding(start = 12.dp))
        }
        if (frame != null) WindowButtons(frame, onClose)
    }
}

// A single quiet line above the page, closed with its cross.
@Composable
private fun Notice(text: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = PageSide, end = PageSide, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(text, OctoType.bodySmall, OctoColors.TextSecondary, Modifier.weight(1f), maxLines = 2)
        IconAction(OctoIcons.Close, "Dismiss", onClose, size = 28.dp, iconSize = 14.dp, tint = OctoColors.TextSecondary)
    }
}

// The page for where the listener is. Each visit is its own composition, so
// its scroll spot is its own.
@Composable
private fun PageHost(app: AppState) {
    val visit = app.navigator.current
    key(visit.id) {
        when (val page = visit.page) {
            Page.Home -> HomePage(app, visit)
            Page.Search -> SearchPage(app, visit)
            Page.Songs -> SongsPage(app, visit)
            Page.Albums -> AlbumsPage(app, visit)
            Page.Artists -> ArtistsPage(app, visit)
            Page.Genres -> GenresPage(app, visit)
            Page.Folders -> FoldersPage(app, visit)
            Page.Favourites -> FavouritesPage(app, visit)
            Page.History -> HistoryPage(app, visit)
            Page.Settings -> SettingsPage(app, visit)
            Page.Sound -> SoundPage(app, visit)
            is Page.Album -> AlbumPage(app, visit, page.id)
            is Page.Artist -> ArtistPage(app, visit, page.id, page.name)
            is Page.Genre -> GenrePage(app, visit, page.name)
            is Page.Folder -> FolderPage(app, visit, page.id, page.name)
            is Page.Playlist -> PlaylistPage(app, visit, page.id)
        }
    }
}

// The key colour: the main colour of the playing song's cover, or Octo's
// own while nothing with a cover plays.
@Composable
private fun rememberKeyColour(app: AppState): Color {
    val state by app.player.state.collectAsState()
    val settings by app.settings.state.collectAsState()
    val coverId = state.current?.song?.coverArt
    val connection = app.connection
    val wash = settings.appearance.wash
    val tuning = WashTuning(wash.contrast, wash.saturation / 100f, wash.brightnessCap / 100f)
    val key by produceState(OctoKey, coverId, connection, tuning) {
        value = if (coverId == null || connection == null) OctoKey else app.washCovers.prepare(connection.client, coverId, tuning).keyColour()
    }
    return key
}

// A soft wash of the playing song's colours across the top of the window:
// its cover, blurred far past recognition and kept faint. Garnish, and off
// when the listener turns it off. With no cover to show, Octo's own
// colours stand in, still and very dim, so the glass has something behind
// it. Before signing in they move, behind the sign-in card.
@Composable
private fun BoxScope.AmbientGlow(app: AppState) {
    val settings by app.settings.state.collectAsState()
    val look = settings.appearance
    val state by app.player.state.collectAsState()
    val cover = state.current?.song?.coverArt
    if (app.connection == null) {
        OctoAmbience(app, moving = true, veil = 0.2f)
        return
    }
    if (!look.ambientGlow) return
    if (cover == null) {
        OctoAmbience(app, moving = false, veil = 0.8f - 0.3f * look.glowStrength.coerceIn(0f, 1f))
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(520.dp)
            .alpha(0.12f + 0.38f * look.glowStrength.coerceIn(0f, 1f)),
    ) {
        Cover(
            cover,
            Modifier.fillMaxSize().blur(110.dp, BlurredEdgeTreatment.Unbounded),
            shape = RoundedCornerShape(0.dp),
        )
        // Fades into the page below, so there is no edge.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, OctoColors.Background))))
    }
}
