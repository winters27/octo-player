package app.winters.octo.desktop.ui

import app.winters.octo.desktop.pages.capsMiddle
import app.winters.octo.desktop.pages.serverUpdateWaits
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.onFocusChanged
import app.winters.octo.design.LocalFocusVisibility
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.winters.octo.design.scrollbar
import app.winters.octo.design.LocalKeyboardHere
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.winters.octo.design.menuKey
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntRect
import app.winters.octo.design.Corner
import app.winters.octo.design.CutTxt
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlassField
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.LocalOctoSurfaces
import app.winters.octo.design.SelectedFill
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconSize
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.TooltipSide
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Separator
import app.winters.octo.design.Space
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.chromeFilm
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.SidePanel
import app.winters.octo.design.ProgressRing
import app.winters.octo.ui.downloads.DOWNLOADS
import app.winters.octo.ui.downloads.DrawerMark
import app.winters.octo.ui.downloads.drawerMark
import app.winters.octo.ui.downloads.drawerState
import app.winters.octo.ui.downloads.overallFraction
import app.winters.octo.desktop.importPlaylistFile
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.nav.SidebarItem
import app.winters.octo.desktop.playlists.choosePlaylistFile
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.subsonic.Playlist
import app.winters.octo.ui.family.FAMILY
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import app.winters.octo.desktop.home.octoLists
import app.winters.octo.desktop.liveradio.LIVE_RADIO
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import app.winters.octo.desktop.placePlaylists
import app.winters.octo.desktop.playlists.SidebarPicks
import app.winters.octo.desktop.playlists.SidebarPlaylistRow
import app.winters.octo.desktop.playlists.sidebarPlaylistRows
import app.winters.octo.desktop.playlists.songsFor
import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.playlists.PlaylistFolderWords

// A place in the sidebar: the page, its name and icon.
private class Place(val page: Page, val label: String, val icon: ImageVector)

private val LibraryPlaces = listOf(
    Place(Page.Songs, "Songs", OctoIcons.Songs),
    Place(Page.Albums, "Albums", OctoIcons.Album),
    Place(Page.Artists, "Artists", OctoIcons.Artist),
    Place(Page.Genres, "Genres", OctoIcons.Genres),
    Place(Page.Folders, "Folders", OctoIcons.Folder),
    Place(Page.LibraryHealth, "Library health", OctoIcons.Check),
)

private val YourPlaces = listOf(
    Place(Page.Favourites, "Favorites", OctoIcons.Like),
    Place(Page.History, "Recently played", OctoIcons.History),
    Place(Page.RecentlyAdded, "Recently added", OctoIcons.AddToLibrary),
)

// The sidebar: part of the frame's glass, down the left from the title bar
// to the player. The search field at the top, then Home on its own, the
// library, what is yours, the playlists (pinned first, with their covers),
// and Sound and Settings at the foot. Groups fold shut by their names.
// Folded to a rail (by the listener, or by a window too narrow for it
// beside the side panel), it shows only icons and covers, each named in a
// tooltip, and search is a button that floats the field out beside it.
// `list` keeps its place across Settings, which takes the sidebar's place a
// while; `film` is false where the frame lays the glass under it instead.
// `settingsRow` and `soundRow` bring the keyboard back to those rows.
@Composable
fun Sidebar(
    app: AppState,
    backdrop: HazeState,
    modifier: Modifier = Modifier,
    rail: Boolean = app.settings.current.frame.sidebarRail,
    list: LazyListState = rememberLazyListState(),
    film: Boolean = true,
    settingsRow: FocusRequester? = null,
    soundRow: FocusRequester? = null,
) {
    val settings by app.settings.state.collectAsState()
    val frame = settings.frame
    val lit = app.navigator.sidebarItem
    val liveLists by app.liveLists.lists.collectAsState()
    val picks = remember { SidebarPicks() }
    fun go(page: Page) = app.navigator.go(page)
    Column(modifier.then(if (film) Modifier.chromeFilm(backdrop) else Modifier).padding(horizontal = Space.M, vertical = Space.M)) {
        if (rail) RailSearch(app) else OmniField(app, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.S))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().scrollbar(list), list) {
            item(key = "home") { NavRow("Home", OctoIcons.Home, lit == SidebarItem.Top(Page.Home), rail) { go(Page.Home) } }
            // The charts, from an Octo server that has them; asked once per server.
            val charts = app.search?.charts
            if (charts != null) {
                item(key = "charts-ask") { LaunchedEffect(charts) { charts.check() } }
                if (charts.offered == true) {
                    item(key = "charts") { NavRow("Charts", OctoIcons.Star, lit == SidebarItem.Top(Page.Charts), rail) { go(Page.Charts) } }
                }
            }
            group(app, "library", "Library", rail, frame.foldedGroups) {
                LibraryPlaces.forEach { place ->
                    item(key = "p:${place.label}") { NavRow(place.label, place.icon, lit == SidebarItem.Top(place.page), rail) { go(place.page) } }
                }
            }
            group(app, "yours", "Your music", rail, frame.foldedGroups) {
                YourPlaces.forEach { place ->
                    item(key = "p:${place.label}") { NavRow(place.label, place.icon, lit == SidebarItem.Top(place.page), rail) { go(place.page) } }
                }
                // Live radio from the radio catalog, on any server.
                item(key = "p:live-radio") { NavRow(LIVE_RADIO, OctoIcons.Radio, lit == SidebarItem.Top(Page.LiveRadio), rail) { go(Page.LiveRadio) } }
                // Only an Octo server imports; the page itself says when one is too old to.
                if (app.connection?.isOcto == true) {
                    item(key = "p:imports") { NavRow("Import", OctoIcons.Download, lit == SidebarItem.Top(Page.Imports), rail) { go(Page.Imports) } }
                }
                // Only while the server has Family on.
                if (app.connection?.family == true) {
                    item(key = "p:family") { NavRow(FAMILY, OctoIcons.Family, lit == SidebarItem.Top(Page.Family), rail) { go(Page.Family) } }
                }
            }
            val pinned = frame.pinnedPlaylists
            val listener = app.connection?.client?.username.orEmpty()
            val isOcto = app.connection?.isOcto == true
            // Octo's own lists have a group of their own, below the listener's,
            // and its library upkeep lists another, closed until opened.
            val fromOcto = octoLists(app.playlists, listener, isOcto).flatMap { it.second }
            val own = app.playlists.filter { it !in fromOcto }
            val upkeep = own.filter { it.octoUpkeep }
            val mine = own.filterNot { it.octoUpkeep }
            // Folders on a server that keeps them (sidebarPlaylistRows).
            val tree = app.folderTree.takeIf { app.playlistFoldersOffered }
            val rows = sidebarPlaylistRows(mine, pinned, tree, frame.openFolders, rail)
            // The playlists in the order their rows show, for shift clicks.
            val order = rows.mapNotNull { (it as? SidebarPlaylistRow.Item)?.playlist?.id }.distinct()
            group(
                app,
                "playlists",
                "Playlists",
                rail,
                frame.foldedGroups,
                action = {
                    val pointer = LocalPointer.current
                    IconAction(OctoIcons.AddToLibrary, "New playlist, folder or live list", { showNewListMenu(app, pointer.point) }, size = ControlSize, iconSize = IconSize.Table, tint = OctoColors.TextSecondary)
                },
                // Playlists dropped on the group's name leave their folders.
                dropPlaylists = if (tree == null) null else ({ dropped -> app.placePlaylists(dropped.map { it.id }, null) }),
            ) {
                // Live lists first, each with its own mark (LiveListParts.kt).
                items(liveLists, key = { "ll:${it.id}" }) { list -> LiveListRow(app, list, lit == SidebarItem.LiveListItem(list.id), rail) }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is SidebarPlaylistRow.Item -> PlaylistRow(app, row.playlist, lit == SidebarItem.PlaylistItem(row.playlist.id), rail, row.pinned, row.depth, picks, order, mine)
                        is SidebarPlaylistRow.Folder -> FolderRow(app, row, rail)
                    }
                }
            }
            if (upkeep.isNotEmpty()) {
                upkeepGroup(app, upkeep, rail, frame.upkeepOpen) { playlist ->
                    PlaylistRow(app, playlist, lit == SidebarItem.PlaylistItem(playlist.id), rail, playlist.id in pinned)
                }
            }
            if (fromOcto.isNotEmpty()) {
                group(app, "from-octo", "From Octo", rail, frame.foldedGroups) {
                    items(fromOcto, key = { "octo:${it.id}" }) { playlist ->
                        PlaylistRow(app, playlist, lit == SidebarItem.PlaylistItem(playlist.id), rail, playlist.id in pinned)
                    }
                }
            }
        }
        Separator(Modifier.padding(vertical = Space.S))
        DownloadsRow(app, rail)
        // Sound opens Settings on its Sound page, and is lit while that shows.
        val onSound = app.inSettings && app.settingsUi.page == SettingsKey.Sound
        NavRow("Sound", OctoIcons.Sound, onSound, rail, modifier = soundRow?.let { Modifier.focusRequester(it) } ?: Modifier) { app.openSettings(SettingsKey.Sound) }
        // A small dot while an update waits to go in, the news itself in
        // Settings > About, or while a newer Octo waits for the server, in Settings > Servers.
        val serverUpdate = serverUpdateWaits(app)
        val mark = when {
            app.updates?.ready != null -> "update ready"
            serverUpdate -> "server update available"
            else -> null
        }
        NavRow(
            "Settings",
            OctoIcons.Settings,
            lit == SidebarItem.Top(Page.Settings) && !onSound,
            rail,
            modifier = settingsRow?.let { Modifier.focusRequester(it) } ?: Modifier,
            mark = mark,
        ) { app.openSettings() }
    }
}

private val ControlSize = RowHeight.Nav - Space.Xs

// The rail's search: a button that opens the search field beside the rail,
// or closes it again. While Settings holds the sidebar's place its title
// marks the spot instead, so the rail leaves the mark alone then, and lets
// go of only its own.
@Composable
private fun RailSearch(app: AppState) {
    val box = app.omnibox
    val mine = remember { arrayOf(IntRect.Zero) }
    DisposableEffect(Unit) { onDispose { if (mine[0] != IntRect.Zero && box.trigger == mine[0]) box.trigger = IntRect.Zero } }
    NavRow(
        if (app.mac) "Search (⌘K)" else "Search (Ctrl+K)",
        OctoIcons.Search,
        selected = box.open,
        rail = true,
        modifier = Modifier.onGloballyPositioned {
            if (app.inSettings) return@onGloballyPositioned
            val at = it.windowRect()
            mine[0] = at
            box.trigger = at
        },
    ) {
        if (box.open) box.open = false else app.openSearch()
    }
}

// A group of rows under its name, which folds it shut or open. On the rail
// there are no names, so a hairline stands between groups instead.
private fun LazyListScope.group(
    app: AppState,
    key: String,
    title: String,
    rail: Boolean,
    folded: Set<String>,
    action: (@Composable () -> Unit)? = null,
    // Playlists dropped on the name, when it takes them (the Playlists
    // group's, where they leave their folders); lit while they are held over it.
    dropPlaylists: ((List<Playlist>) -> Unit)? = null,
    rows: LazyListScope.() -> Unit,
) {
    val shut = key in folded && !rail
    item(key = "g:$key") {
        if (rail) {
            Separator(Modifier.padding(vertical = Space.S))
        } else {
            val target = if (dropPlaylists != null) {
                Modifier
                    .playlistDropTarget(TOP_LEVEL_DROP, PlaylistFolderWords.NOT_IN_A_FOLDER, dropPlaylists)
                    .then(if (isDropOver(TOP_LEVEL_DROP)) Modifier.background(DropLit, Corner.ControlShape) else Modifier)
            } else {
                Modifier
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(target)
                    .padding(top = Space.L, bottom = Space.Xxs)
                    .height(RowHeight.Nav - Space.Xs)
                    .clickable(role = Role.Button) {
                        app.updateFrame { it.copy(foldedGroups = if (shut) it.foldedGroups - key else it.foldedGroups + key) }
                    }
                    // A heading that folds: its name, and whether it is open.
                    .semantics {
                        heading()
                        contentDescription = title
                        stateDescription = if (shut) "Folded" else "Open"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(title.uppercase(), DesktopType.label, OctoColors.TextMuted, Modifier.padding(start = Space.M))
                Glyph(OctoIcons.Chevron, Modifier.padding(start = Space.Xs).rotate(if (shut) 0f else 90f), size = IconSize.Inline - Space.Xxs, tint = OctoColors.TextMuted, exact = true)
                Spacer(Modifier.weight(1f))
                action?.invoke()
            }
        }
    }
    if (!shut) rows()
}

// The id the Playlists group's name takes dropped playlists under.
private const val TOP_LEVEL_DROP = "pf:top"

// Octo's library upkeep lists (its library actions and notices, a family
// member's request lists): a group of their own below the playlists, closed
// until opened, which is remembered. While it is closed, a note under its
// name says which notice lists hold songs ("Review has 3 songs"). They are
// never filed. On the rail its rows always show.
private fun LazyListScope.upkeepGroup(app: AppState, upkeep: List<Playlist>, rail: Boolean, open: Boolean, row: @Composable (Playlist) -> Unit) {
    val shut = !open && !rail
    item(key = "g:upkeep") {
        if (rail) {
            Separator(Modifier.padding(vertical = Space.S))
        } else {
            val note = if (shut) PlaylistFolderWords.upkeepNote(upkeep.filter { it.octoNotice != null }.map { it.name to it.songCount }) else null
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = Space.L, bottom = Space.Xxs)
                        .height(RowHeight.Nav - Space.Xs)
                        .clickable(role = Role.Button) { app.updateFrame { it.copy(upkeepOpen = !it.upkeepOpen) } }
                        .semantics {
                            heading()
                            contentDescription = listOfNotNull(PlaylistFolderWords.LIBRARY_UPKEEP, note).joinToString(", ")
                            stateDescription = if (shut) "Folded" else "Open"
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(PlaylistFolderWords.LIBRARY_UPKEEP.uppercase(), DesktopType.label, OctoColors.TextMuted, Modifier.padding(start = Space.M))
                    Glyph(OctoIcons.Chevron, Modifier.padding(start = Space.Xs).rotate(if (shut) 0f else 90f), size = IconSize.Inline - Space.Xxs, tint = OctoColors.TextMuted, exact = true)
                }
                if (note != null) {
                    // The glyph on the middle of the first line's capitals,
                    // in the accent; the words in the secondary color, as a
                    // form's note.
                    val caps = capsMiddle(DesktopType.meta)
                    Row(
                        Modifier.padding(start = Space.M, end = Space.M, bottom = Space.Xs),
                        horizontalArrangement = Arrangement.spacedBy(Space.Xs),
                    ) {
                        Glyph(OctoIcons.Info, Modifier.alignBy { it.measuredHeight / 2 + caps }, size = IconSize.Inline, tint = OctoColors.Accent)
                        Txt(note, DesktopType.meta, OctoColors.TextSecondary, Modifier.alignBy(FirstBaseline), maxLines = 2)
                    }
                }
            }
        }
    }
    if (!shut) items(upkeep, key = { "up:${it.id}" }) { row(it) }
}

// The downloads drawer's way in, on a server that keeps a log of its
// downloads: it opens and closes the side panel's Downloads tab. A ring
// beside it fills while anything is on its way; when only downloads waiting
// for the person are left, it carries the dot Settings carries when
// something waits there.
@Composable
private fun DownloadsRow(app: AppState, rail: Boolean) {
    val drawer = app.downloads?.takeIf { it.supported == true } ?: return
    val rows by drawer.rows.collectAsState()
    val mark = drawerMark(rows)
    NavRow(
        DOWNLOADS,
        OctoIcons.Download,
        app.shownPanel == SidePanel.Downloads,
        rail,
        mark = drawerState(rows).takeIf { mark == DrawerMark.Waits },
        state = drawerState(rows).takeIf { mark != DrawerMark.Waits },
        trailing = if (mark == DrawerMark.Ring) ({ ProgressRing(overallFraction(rows), size = IconSize.Table) }) else null,
    ) { app.toggleSidePanel(SidePanel.Downloads) }
}

// One place to go: an icon and its name, the chosen one on the darker
// pill. On the rail, the icon alone with its name in a tooltip. `trailing`
// sits at the row's end; the rail has no room for it, so there `state`
// says the same in the tooltip.
@Composable
private fun NavRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    rail: Boolean,
    modifier: Modifier = Modifier,
    // What waits there, which puts a small dot by it.
    mark: String? = null,
    state: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val named = listOfNotNull(label, mark, state).joinToString(", ")
    val row: @Composable () -> Unit = {
        Box(
            modifier
                .fillMaxWidth()
                .height(RowHeight.Nav)
                .hoverLift(Corner.ControlShape, lifted = false)
                .clickable(role = Role.Tab, onClick = onClick)
                .semantics {
                    contentDescription = named
                    this.selected = selected
                },
            contentAlignment = if (rail) Alignment.Center else Alignment.CenterStart,
        ) {
            if (selected) GlazeSelected(Modifier.matchParentSize(), Corner.ControlShape)
            SelectedFill(selected && LocalOctoSurfaces.current.light) {
                Row(Modifier.padding(horizontal = if (rail) Space.None else Space.M), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.L)) {
                    Box {
                        Glyph(icon, size = IconSize.Toolbar, tint = if (selected) OctoColors.TextPrimary else OctoColors.TextSecondary)
                        // On the rail the dot sits on the icon's corner.
                        if (mark != null && rail) NavMark(Modifier.align(Alignment.TopEnd))
                    }
                    if (!rail) Txt(label, DesktopType.body, if (selected) OctoColors.TextPrimary else OctoColors.TextSecondary, Modifier.weight(1f, fill = trailing != null))
                    if (mark != null && !rail) NavMark()
                    if (trailing != null && !rail) trailing()
                }
            }
        }
    }
    if (rail) OctoTooltip(named, side = TooltipSide.Right) { row() } else row()
}

// The quiet dot that says something waits in Settings.
@Composable
private fun NavMark(modifier: Modifier = Modifier) {
    Box(modifier.size(FrameSize.NavMark).background(OctoColors.Accent, CircleShape))
}

// A playlist with its small cover, `depth` folders in; right-click opens its
// menu, to play, pin, file, rename, copy, export or delete it. On the rail,
// the cover alone. With `picks`, ctrl (command on a Mac) and shift clicks
// pick several (`order` is the rows' order, `all` the playlists they are),
// and a drag carries the picks, or the row alone, to a folder.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlaylistRow(
    app: AppState,
    playlist: Playlist,
    selected: Boolean,
    rail: Boolean,
    pinned: Boolean,
    depth: Int = 0,
    picks: SidebarPicks? = null,
    order: List<String> = emptyList(),
    all: List<Playlist> = emptyList(),
) {
    val pointer = LocalPointer.current
    var here by remember { mutableStateOf(false) }
    val keyboard = LocalFocusVisibility.current.keyboard
    // Songs dragged onto a playlist the listener can add to are added to it,
    // songs found online only where they can be kept (songsFor). A row that
    // can't take any of the songs held over the sidebar fades while they are.
    val drag = LocalDrag.current
    val dropId = "pl:${playlist.id}"
    val adds = app.access(playlist).add
    val takes = drag.active && adds && songsFor(playlist, drag.songs, app.playlistSongs).isNotEmpty()
    val refuses = drag.active && adds && !takes
    val lit = takes && isDropOver(dropId)
    val picked = picks != null && playlist.id in picks.picked
    val movable = picks != null && !rail && app.playlistFoldersOffered && PlaylistFolderTree.fileable(playlist.id)
    // Where the row is in the window, to follow a drag of playlists, and the
    // keys held at the last press: 1 picks one more, 2 picks a run.
    var placed by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var held by remember { mutableStateOf(0) }
    // What a drag or the menu takes: the picks when this row is one of them.
    val carried = {
        val ids = picks?.group(playlist.id, order) ?: listOf(playlist.id)
        ids.mapNotNull { id -> all.firstOrNull { it.id == id } }.ifEmpty { listOf(playlist) }
    }
    val latestCarried by rememberUpdatedState(carried)
    val row: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .height(RowHeight.Nav + Space.Xs)
                .alpha(if (refuses) REFUSED_ALPHA else 1f)
                .then(if (takes) Modifier.dropTarget(dropId, "Add to ${playlist.name}") { addDroppedSongs(app, playlist, it, pointer.point) } else Modifier)
                .onGloballyPositioned { placed = it }
                .then(
                    if (movable) {
                        Modifier.pointerInput(playlist.id) {
                            detectDragGestures(
                                onDragStart = { local -> placed?.let { drag.startPlaylists(latestCarried(), it.localToWindow(local)) } },
                                onDragEnd = { drag.drop() },
                                onDragCancel = { drag.cancel() },
                            ) { change, _ ->
                                change.consume()
                                placed?.let { drag.move(it.localToWindow(change.position)) }
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .onPointerEvent(PointerEventType.Press) { event ->
                    val keys = event.keyboardModifiers
                    val toggle = if (app.mac) keys.isMetaPressed else keys.isCtrlPressed
                    held = when {
                        toggle -> 1
                        keys.isShiftPressed -> 2
                        else -> 0
                    }
                }
                .hoverLift(Corner.ControlShape, lifted = false)
                .onRightClick { app.popups.showAt(pointer.point) { close -> PlaylistMenu(app, playlist, close, carried()) } }
                .onFocusChanged { here = it.isFocused }
                .menuKey { at -> app.popups.showUnder(at) { close -> PlaylistMenu(app, playlist, close, carried()) } }
                .clickable(role = Role.Tab) {
                    val how = held
                    held = 0
                    if (movable && how != 0) {
                        picks?.click(playlist.id, toggle = how == 1, range = how == 2, order = order)
                    } else {
                        picks?.clear()
                        app.navigator.go(Page.Playlist(playlist.id))
                    }
                }
                .semantics {
                    contentDescription = listOfNotNull(playlist.name, "playlist", "pinned".takeIf { pinned }, "picked".takeIf { picked }).joinToString(", ")
                    this.selected = selected || picked
                },
            contentAlignment = if (rail) Alignment.Center else Alignment.CenterStart,
        ) {
            if (selected || picked) GlazeSelected(Modifier.matchParentSize(), Corner.ControlShape)
            if (lit) Box(Modifier.matchParentSize().background(DropLit, Corner.ControlShape))
            SelectedFill((selected || picked) && LocalOctoSurfaces.current.light) {
                Row(
                    Modifier.padding(start = if (rail) Space.None else Space.M + FolderIndent * depth, end = if (rail) Space.None else Space.M),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.M + Space.Xxs),
                ) {
                    PlaylistPicture(app, playlist, Modifier.size(FrameSize.PlaylistCover), Corner.ArtSShape)
                    if (!rail) {
                        CompositionLocalProvider(LocalKeyboardHere provides (here && keyboard)) { CutTxt(playlist.name, DesktopType.body, if (selected || picked) OctoColors.TextPrimary else OctoColors.TextSecondary, Modifier.weight(1f)) }
                        if (pinned) Glyph(OctoIcons.Pinned, size = IconSize.Inline - Space.Xxs, tint = OctoColors.TextMuted, exact = true)
                    }
                }
            }
        }
    }
    if (rail) OctoTooltip(playlist.name, side = TooltipSide.Right) { row() } else row()
}

// How far a playlist row that can't take the songs held over it fades.
private const val REFUSED_ALPHA = 0.4f

// A small form for naming a new, empty playlist, in the middle of the
// window, or making one from a playlist file instead.
fun newPlaylist(app: AppState) {
    app.popups.showCentred { close ->
        var name by remember { mutableStateOf("") }
        MenuTitle("New playlist")
        PopupPadding {
            val create = {
                if (name.isNotBlank()) {
                    app.createPlaylist(name, emptyList()) { id -> id?.let { app.navigator.go(Page.Playlist(it)) } }
                    close()
                }
            }
            GlassField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "Name", onSubmit = create, onEscape = close)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.M + Space.Xxs), verticalAlignment = Alignment.CenterVertically) {
                // Or one made from a playlist file, named after it.
                TextAction("Import a file", {
                    close()
                    // After the form has gone, since the file window holds the window's thread.
                    app.scope.launch { choosePlaylistFile(save = false, windows = app.os == DesktopOs.Windows)?.let(app::importPlaylistFile) }
                }, icon = OctoIcons.Folder)
                Spacer(Modifier.weight(1f))
                GlazeCapsule(null, "Cancel", close)
                GlazeCapsule(null, "Create", create, lit = true, enabled = name.isNotBlank())
            }
        }
    }
}
