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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.winters.octo.design.Ambience
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlassField
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconSize
import app.winters.octo.design.LocalPopups
import app.winters.octo.design.MenuFilm
import app.winters.octo.design.MenuFrost
import app.winters.octo.design.MenuShape
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.PopupLayer
import app.winters.octo.design.Space
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.chromeFilm
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.NoticeAction
import app.winters.octo.desktop.actionFor
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
import app.winters.octo.desktop.pages.LiveListPage
import app.winters.octo.desktop.pages.NewLiveListPage
import app.winters.octo.desktop.pages.HomePage
import app.winters.octo.desktop.pages.PlaylistPage
import app.winters.octo.desktop.pages.RecentlyAddedPage
import app.winters.octo.desktop.pages.ShelfPage
import app.winters.octo.desktop.pages.SearchPage
import app.winters.octo.desktop.pages.SettingsPage
import app.winters.octo.desktop.pages.SignInPage
import app.winters.octo.desktop.pages.SongsPage
import app.winters.octo.desktop.pages.SoundPage
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.desktop.search.omniPanelPlace
import app.winters.octo.desktop.search.railFieldPlace
import app.winters.octo.desktop.window.Frame
import app.winters.octo.desktop.window.MacLightsRoom
import app.winters.octo.desktop.window.ResizeEdges
import app.winters.octo.desktop.window.TitleBarHeight
import app.winters.octo.desktop.window.TitleStrip
import app.winters.octo.desktop.window.WindowButtons
import app.winters.octo.desktop.window.dragsWindow
import app.winters.octo.player.immersive.WashTuning
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

// The whole window. Behind everything, the window's colours (the playing
// song's, blurred), which the frame frosts. Over them, one frame of dark
// glass: the title bar across the top, the sidebar down the left and the
// side panel down the right when open, meeting at hairlines. The page is
// the one open surface in the middle, with the player floating at its foot;
// the page's lists leave room for it at their end. The full player covers it all when open, with
// the title bar's buttons still over it. `frame` is null when the system
// draws the window's frame.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Shell(app: AppState, frame: Frame?, onClose: () -> Unit) {
    val backdrop = rememberHazeState()
    val pointer = remember { PointerSpot() }
    val drag = remember { DragState() }
    val focus = LocalFocusManager.current
    val connection = app.connection
    val key = rememberKeyColour(app)
    CompositionLocalProvider(
        LocalPopups provides app.popups,
        LocalPointer provides pointer,
        LocalCovers provides connection?.client,
        LocalKeyColour provides key,
        LocalDrag provides drag,
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
                    // A click outside the search box closes its list.
                    if (app.omnibox.open && !app.omnibox.holds(pointer.point.x, pointer.point.y)) {
                        app.omnibox.open = false
                        focus.clearFocus()
                    }
                    if (event.buttons.isBackPressed) app.navigator.back()
                    if (event.buttons.isForwardPressed) app.navigator.forward()
                },
        ) {
            // The colours the glass frosts. Only these: the page is not under
            // glass, so scrolling never makes the frame blur again.
            Box(Modifier.fillMaxSize().hazeSource(backdrop)) { AmbientGlow(app) }
            if (connection == null) {
                // The sign-in card frosts the colours behind it.
                Box(Modifier.fillMaxSize().padding(top = FrameSize.TitleBar)) { SignInPage(app, backdrop) }
            } else {
                SignedInFrame(app, backdrop)
                AnimatedVisibility(app.fullPlayer, enter = fadeIn(), exit = fadeOut()) {
                    FullPlayer(app, Modifier.fillMaxSize(), top = FrameSize.TitleBar)
                }
            }
            if (connection != null && app.omnibox.open && !app.fullPlayer) OmniboxOver(app, backdrop)
            TitleBar(app, frame, onClose)
            PopupLayer(app.popups, backdrop)
            DragLabel(drag)
            if (frame != null) ResizeEdges(frame)
        }
    }
}

// The frame and the page inside it.
@Composable
private fun SignedInFrame(app: AppState, backdrop: HazeState) {
    val settings by app.settings.state.collectAsState()
    val saved = settings.frame
    // Widths follow a drag at once, and are saved when it ends.
    var sidebar by remember { mutableStateOf(saved.sidebarWidth.dp) }
    var panelWidth by remember { mutableStateOf(saved.panelWidth.dp) }
    val sideWidth = if (saved.sidebarRail) FrameSize.SidebarRail else sidebar.coerceIn(FrameSize.SidebarMin, FrameSize.SidebarMax)
    val panel = app.sidePanel
    // The window's width, for the player's.
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    Column(Modifier.fillMaxSize()) {
        // Room for the title bar, in the frame's glass; its buttons are drawn
        // over it, last, so they stay over the full player too.
        Box(Modifier.fillMaxWidth().height(FrameSize.TitleBar).chromeFilm(backdrop))
        Seam(vertical = false)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.width(sideWidth).fillMaxHeight()) {
                Sidebar(app, backdrop, Modifier.fillMaxSize())
                if (!saved.sidebarRail) {
                    ResizeHandle(
                        onDrag = { sidebar = (sidebar + it).coerceIn(FrameSize.SidebarMin, FrameSize.SidebarMax) },
                        onDone = { app.updateFrame { f -> f.copy(sidebarWidth = sidebar.value) } },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            Seam(vertical = true)
            CompositionLocalProvider(LocalBottomRoom provides FrameSize.Player + FrameSize.PlayerGap) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize().clipToBounds()) {
                        app.notice?.let { Notice(it, app.noticeDetail, app.actionFor(it)) { app.notice = null; app.noticeDetail = null } }
                        Box(Modifier.weight(1f)) { PageHost(app) }
                    }
                    // A third of the window, in the middle of the page.
                    val room = maxWidth - FrameSize.PlayerGap * 2
                    val width = (window / 3).coerceIn(minOf(FrameSize.PlayerMin, room), room)
                    PlayerBar(app, backdrop, Modifier.align(Alignment.BottomCenter).padding(bottom = FrameSize.PlayerGap).width(width).height(FrameSize.Player))
                }
            }
            if (panel != null) {
                Seam(vertical = true)
                val width = panelWidth.coerceIn(FrameSize.PanelMin, FrameSize.PanelMax)
                Box(Modifier.width(width).fillMaxHeight()) {
                    ContextPanel(app, panel, backdrop, Modifier.fillMaxSize())
                    ResizeHandle(
                        onDrag = { panelWidth = (panelWidth - it).coerceIn(FrameSize.PanelMin, FrameSize.PanelMax) },
                        onDone = { app.updateFrame { f -> f.copy(panelWidth = panelWidth.value) } },
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
            }
        }
    }
}

// What the open search box lays over the page: its list, under the field
// and lined up with its start, reaching over the page as far as the window
// allows. With the sidebar folded to a rail, the field floats beside the
// rail's search button too.
@Composable
private fun OmniboxOver(app: AppState, backdrop: HazeState) {
    val box = app.omnibox
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize.width
    val settings by app.settings.state.collectAsState()
    if (settings.frame.sidebarRail) {
        val gap = with(density) { Space.M.roundToPx() }
        val inset = with(density) { Space.Xs.roundToPx() }
        FloatingGlaze(
            backdrop,
            Modifier
                .offset { railFieldPlace(box.trigger, gap).let { IntOffset(it.x, it.y - inset) } }
                .width(FrameSize.SearchWidth),
            shape = MenuShape,
            film = MenuFilm,
            frost = MenuFrost,
            halo = true,
        ) {
            OmniField(app, Modifier.fillMaxWidth().padding(Space.Xs))
        }
        // The field is new each time it floats in, so the keyboard goes to it here.
        LaunchedEffect(Unit) { runCatching { app.searchFocus.requestFocus() } }
    }
    val place = with(density) { omniPanelPlace(box.field, FrameSize.OmniWidth.roundToPx(), window, Space.S.roundToPx(), Space.M.roundToPx()) }
    OmniPanel(
        app,
        backdrop,
        Modifier
            .offset { IntOffset(place.left, place.top) }
            .width(with(density) { place.width.toDp() })
            .onGloballyPositioned { box.panel = it.windowRect() },
    )
}

// The strip along the top: back and forward, room to drag the window by,
// and the window's buttons when the app draws its frame. It paints nothing
// itself; the frame's glass is under it.
@Composable
private fun TitleBar(app: AppState, frame: Frame?, onClose: () -> Unit) {
    TitleStrip {
        if (app.mac) Spacer(Modifier.width(MacLightsRoom)) else Spacer(Modifier.width(Space.M))
        if (app.connection != null) {
            IconAction(OctoIcons.Back, "Back", { app.navigator.back() }, size = ControlHeight.S, iconSize = IconSize.Toolbar, enabled = app.navigator.canGoBack)
            IconAction(OctoIcons.Forward, "Forward", { app.navigator.forward() }, size = ControlHeight.S, iconSize = IconSize.Toolbar, enabled = app.navigator.canGoForward)
        }
        Box(Modifier.weight(1f).fillMaxHeight().then(if (frame != null) Modifier.dragsWindow(frame) else Modifier))
        if (frame != null) WindowButtons(frame, onClose)
    }
}

// A single quiet line above the page, closed with its cross. When there
// is more to say (the engine's own words for a failure), Details shows it;
// an `action` (Undo after a queue edit) sits beside the words.
@Composable
private fun Notice(text: String, detail: String?, action: NoticeAction?, onClose: () -> Unit) {
    var open by remember(text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = PageSide, end = PageSide, top = Space.M)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            Txt(text, OctoType.bodySmall, OctoColors.TextSecondary, Modifier.weight(1f), maxLines = 2)
            if (action != null) TextAction(action.label, action.run)
            if (detail != null) TextAction(if (open) "Hide details" else "Details", { open = !open })
            IconAction(OctoIcons.Close, "Dismiss", onClose, size = ControlHeight.S, iconSize = IconSize.Inline, tint = OctoColors.TextSecondary)
        }
        if (open && detail != null) Txt(detail, OctoType.caption, OctoColors.TextMuted, maxLines = 4)
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
            Page.RecentlyAdded -> RecentlyAddedPage(app, visit)
            Page.Settings -> SettingsPage(app, visit)
            Page.Sound -> SoundPage(app, visit)
            is Page.Album -> AlbumPage(app, visit, page.id)
            is Page.Artist -> ArtistPage(app, visit, page.id, page.name)
            is Page.Genre -> GenrePage(app, visit, page.name)
            is Page.Folder -> FolderPage(app, visit, page.id, page.name)
            is Page.Playlist -> PlaylistPage(app, visit, page.id)
            is Page.Shelf -> ShelfPage(app, visit, page.shelf)
            is Page.LiveList -> LiveListPage(app, visit, page.id, page.editing)
            is Page.NewLiveList -> NewLiveListPage(app, visit, page)
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

// The window's colours, behind everything. The glow: a soft wash of the
// playing song's colours across the top of the window, its cover blurred
// far past recognition and kept faint. Garnish, and off when the listener
// turns it off. With no cover to show, Octo's own colours stand in, still
// and very dim, so the glass has something behind it. Before signing in
// they move, behind the sign-in card. Immersive instead lays the full
// player's moving wash behind the whole window.
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
    if (look.ambience == AmbienceStyle.Immersive) {
        ImmersiveAmbience(app)
        return
    }
    if (cover == null) {
        OctoAmbience(app, moving = false, veil = 1f - quietOpacity(look.glowStrength))
        return
    }
    CoverGlow(look.glowStrength) { blurred -> Cover(cover, blurred, shape = RectangleShape) }
}

// The glow itself: `picture` draws the cover into the blurred modifier it
// is given, faint and fading into the page below.
@Composable
internal fun CoverGlow(strength: Float, picture: @Composable (Modifier) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(Ambience.Height)
            .alpha(0.12f + 0.38f * strength.coerceIn(0f, 1f)),
    ) {
        picture(Modifier.fillMaxSize().blur(Ambience.Blur, BlurredEdgeTreatment.Unbounded))
        // Fades into the page below, so there is no edge.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, OctoColors.Background))))
    }
}
