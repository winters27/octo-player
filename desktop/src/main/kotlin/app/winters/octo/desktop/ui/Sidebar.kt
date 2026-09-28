package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlassField
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconSize
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Separator
import app.winters.octo.design.Space
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.chromeFilm
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.importPlaylistFile
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.SidebarItem
import app.winters.octo.desktop.playlists.choosePlaylistFile
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.subsonic.Playlist
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

// A place in the sidebar: the page, its name and icon.
private class Place(val page: Page, val label: String, val icon: ImageVector)

private val LibraryPlaces = listOf(
    Place(Page.Songs, "Songs", OctoIcons.Songs),
    Place(Page.Albums, "Albums", OctoIcons.Album),
    Place(Page.Artists, "Artists", OctoIcons.Artist),
    Place(Page.Genres, "Genres", OctoIcons.Genres),
    Place(Page.Folders, "Folders", OctoIcons.Folder),
)

private val YourPlaces = listOf(
    Place(Page.Favourites, "Favourites", OctoIcons.Like),
    Place(Page.History, "Recently played", OctoIcons.History),
    Place(Page.RecentlyAdded, "Recently added", OctoIcons.AddToLibrary),
)

// The sidebar: part of the frame's glass, down the left from the title bar
// to the player. Home on its own, then the library, then what is yours,
// then the playlists (pinned first, with their covers), and Sound and
// Settings at the foot. Groups fold shut by their names. Folded to a rail,
// it shows only icons and covers, each named in a tooltip.
@Composable
fun Sidebar(app: AppState, backdrop: HazeState, modifier: Modifier = Modifier) {
    val settings by app.settings.state.collectAsState()
    val frame = settings.frame
    val rail = frame.sidebarRail
    val lit = app.navigator.sidebarItem
    fun go(page: Page) = app.navigator.go(page)
    Column(modifier.chromeFilm(backdrop).padding(horizontal = Space.M, vertical = Space.M)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item(key = "home") { NavRow("Home", OctoIcons.Home, lit == SidebarItem.Top(Page.Home), rail) { go(Page.Home) } }
            group(app, "library", "Library", rail, frame.foldedGroups) {
                LibraryPlaces.forEach { place ->
                    item(key = "p:${place.label}") { NavRow(place.label, place.icon, lit == SidebarItem.Top(place.page), rail) { go(place.page) } }
                }
            }
            group(app, "yours", "Your music", rail, frame.foldedGroups) {
                YourPlaces.forEach { place ->
                    item(key = "p:${place.label}") { NavRow(place.label, place.icon, lit == SidebarItem.Top(place.page), rail) { go(place.page) } }
                }
            }
            val pinned = frame.pinnedPlaylists
            val playlists = app.playlists.sortedBy { pinned.indexOf(it.id).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
            group(app, "playlists", "Playlists", rail, frame.foldedGroups, action = { IconAction(OctoIcons.AddToLibrary, "New playlist", { newPlaylist(app) }, size = ControlSize, iconSize = IconSize.Table, tint = OctoColors.TextSecondary) }) {
                items(playlists, key = { "pl:${it.id}" }) { playlist ->
                    PlaylistRow(app, playlist, lit == SidebarItem.PlaylistItem(playlist.id), rail, playlist.id in pinned)
                }
            }
        }
        Separator(Modifier.padding(vertical = Space.S))
        NavRow("Sound", OctoIcons.Sound, lit == SidebarItem.Top(Page.Sound), rail) { go(Page.Sound) }
        NavRow("Settings", OctoIcons.Settings, lit == SidebarItem.Top(Page.Settings), rail) { go(Page.Settings) }
    }
}

private val ControlSize = RowHeight.Nav - Space.Xs

// A group of rows under its name, which folds it shut or open. On the rail
// there are no names, so a hairline stands between groups instead.
private fun LazyListScope.group(
    app: AppState,
    key: String,
    title: String,
    rail: Boolean,
    folded: Set<String>,
    action: (@Composable () -> Unit)? = null,
    rows: LazyListScope.() -> Unit,
) {
    val shut = key in folded && !rail
    item(key = "g:$key") {
        if (rail) {
            Separator(Modifier.padding(vertical = Space.S))
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.L, bottom = Space.Xxs)
                    .height(RowHeight.Nav - Space.Xs)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        app.updateFrame { it.copy(foldedGroups = if (shut) it.foldedGroups - key else it.foldedGroups + key) }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(title.uppercase(), DesktopType.label, OctoColors.TextMuted, Modifier.padding(start = Space.M))
                Glyph(OctoIcons.Chevron, Modifier.padding(start = Space.Xs).rotate(if (shut) 0f else 90f), size = IconSize.Inline - Space.Xxs, tint = OctoColors.TextMuted)
                Spacer(Modifier.weight(1f))
                action?.invoke()
            }
        }
    }
    if (!shut) rows()
}

// One place to go: an icon and its name, the chosen one on the darker
// pill. On the rail, the icon alone with its name in a tooltip.
@Composable
private fun NavRow(label: String, icon: ImageVector, selected: Boolean, rail: Boolean, onClick: () -> Unit) {
    val row: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .height(RowHeight.Nav)
                .hoverLift(Corner.ControlShape, lifted = false)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick),
            contentAlignment = if (rail) Alignment.Center else Alignment.CenterStart,
        ) {
            if (selected) GlazeSelected(Modifier.matchParentSize(), Corner.ControlShape)
            Row(Modifier.padding(horizontal = if (rail) Space.None else Space.M), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.L)) {
                Glyph(icon, size = IconSize.Toolbar - Space.Xxs, tint = if (selected) OctoColors.TextPrimary else OctoColors.TextSecondary)
                if (!rail) Txt(label, DesktopType.body, if (selected) OctoColors.TextPrimary else OctoColors.TextSecondary)
            }
        }
    }
    if (rail) OctoTooltip(label) { row() } else row()
}

// A playlist with its small cover; right-click opens its menu, to play,
// pin, rename, copy, export or delete it. On the rail, the cover alone.
@Composable
private fun PlaylistRow(app: AppState, playlist: Playlist, selected: Boolean, rail: Boolean, pinned: Boolean) {
    val pointer = LocalPointer.current
    // Songs dragged onto the listener's own playlist are added to it.
    val drag = LocalDrag.current
    val dropId = "pl:${playlist.id}"
    val takes = drag.active && app.canEdit(playlist)
    val lit = takes && isDropOver(dropId)
    val row: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .height(RowHeight.Nav + Space.Xs)
                .then(if (takes) Modifier.dropTarget(dropId, "Add to ${playlist.name}") { addDroppedSongs(app, playlist, it, pointer.point) } else Modifier)
                .hoverLift(Corner.ControlShape, lifted = false)
                .onRightClick { app.popups.showAt(pointer.point) { close -> PlaylistMenu(app, playlist, close) } }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { app.navigator.go(Page.Playlist(playlist.id)) },
            contentAlignment = if (rail) Alignment.Center else Alignment.CenterStart,
        ) {
            if (selected) GlazeSelected(Modifier.matchParentSize(), Corner.ControlShape)
            if (lit) Box(Modifier.matchParentSize().background(DropLit, Corner.ControlShape))
            Row(Modifier.padding(horizontal = if (rail) Space.None else Space.M), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M + Space.Xxs)) {
                Cover(playlist.coverArt, Modifier.size(FrameSize.PlaylistCover), shape = Corner.ArtSShape, placeholder = OctoIcons.Playlists)
                if (!rail) {
                    Txt(playlist.name, DesktopType.body, if (selected) OctoColors.TextPrimary else OctoColors.TextSecondary, Modifier.weight(1f))
                    if (pinned) Glyph(OctoIcons.Pin, size = IconSize.Inline - Space.Xxs, tint = OctoColors.TextMuted)
                }
            }
        }
    }
    if (rail) OctoTooltip(playlist.name) { row() } else row()
}

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
