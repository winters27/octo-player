package app.winters.octo.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.onFocusChanged
import app.winters.octo.design.LocalFocusVisibility
import app.winters.octo.design.LocalKeyboardHere
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.winters.octo.design.menuKey
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import app.winters.octo.design.Corner
import app.winters.octo.design.CutTxt
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.Glyph
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.deleteLiveList
import app.winters.octo.desktop.duplicateLiveList
import app.winters.octo.desktop.liveListSongs
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.renameLiveList
import app.winters.octo.desktop.saveLiveListToServer
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.liveListQueryFrom
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryRule
import app.winters.octo.sort.SortOrder
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A live list's own mark, in place of a cover: the rules glyph lit in the
// key colour, on a faint wash of it, so a live list reads apart from a
// playlist's grey placeholder at a glance.
@Composable
fun LiveMark(size: Dp, modifier: Modifier = Modifier) {
    val key = LocalKeyColour.current
    Box(modifier.size(size).clip(Corner.ArtSShape).background(key.copy(alpha = MarkWash)), contentAlignment = Alignment.Center) {
        Glyph(OctoIcons.Filter, size = size * MarkGlyph, tint = key)
    }
}

private const val MarkWash = 0.18f
private const val MarkGlyph = 0.62f

// A live list in the sidebar, among the playlists: its mark and name, the
// open one on the darker pill; right-click opens its menu. On the rail,
// the mark alone.
@Composable
fun LiveListRow(app: AppState, list: LiveList, selected: Boolean, rail: Boolean) {
    val pointer = LocalPointer.current
    var here by remember { mutableStateOf(false) }
    val keyboard = LocalFocusVisibility.current.keyboard
    val row: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .height(RowHeight.Nav + Space.Xs)
                .hoverLift(Corner.ControlShape, lifted = false)
                .onRightClick { app.popups.showAt(pointer.point) { close -> LiveListMenu(app, list, close) } }
                .onFocusChanged { here = it.isFocused }
                .menuKey { at -> app.popups.showUnder(at) { close -> LiveListMenu(app, list, close) } }
                .clickable(role = Role.Tab) { app.navigator.go(Page.LiveList(list.id)) }
                .semantics {
                    contentDescription = "${list.name}, live list"
                    this.selected = selected
                },
            contentAlignment = if (rail) Alignment.Center else Alignment.CenterStart,
        ) {
            if (selected) GlazeSelected(Modifier.matchParentSize(), Corner.ControlShape)
            Row(Modifier.padding(horizontal = if (rail) Space.None else Space.M), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M + Space.Xxs)) {
                LiveListPicture(app, list, Modifier.size(FrameSize.PlaylistCover), Corner.ArtSShape) { LiveMark(FrameSize.PlaylistCover) }
                CompositionLocalProvider(LocalKeyboardHere provides (here && keyboard)) { if (!rail) CutTxt(list.name, DesktopType.body, if (selected) OctoColors.TextPrimary else OctoColors.TextSecondary, Modifier.weight(1f)) }
            }
        }
    }
    if (rail) OctoTooltip("${list.name}, live list") { row() } else row()
}

// A live list's menu, in the sidebar and on its page: play it, change its
// rules or name, copy it, save what it holds now to the server as a
// playlist, or delete it (asked first, in the menu itself).
@Composable
fun ColumnScope.LiveListMenu(app: AppState, list: LiveList, close: () -> Unit, onEdit: (() -> Unit)? = null) {
    var deleting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var name by remember(list.name) { mutableStateOf(list.name) }
    if (deleting) {
        Question("Delete \"${list.name}\"?", "It can't be undone. The songs stay in your library.")
        MenuRow("Delete", { app.deleteLiveList(list); close() }, OctoIcons.Delete, destructive = true)
        MenuRow("Cancel", { deleting = false }, OctoIcons.Close)
        return
    }
    // The songs it picks now, worked out off the window's thread.
    fun withSongs(use: (List<Song>) -> Unit) {
        close()
        app.scope.launch {
            val songs = withContext(Dispatchers.Default) { app.liveListSongs(list) }.orEmpty()
            if (songs.isEmpty()) app.notice = "\"${list.name}\" has no songs right now." else use(songs)
        }
    }
    MenuTitle(list.name)
    MenuRow("Play", { withSongs { app.play(it, source = list.name) } }, OctoIcons.Play)
    MenuRow("Shuffle", { withSongs { app.play(it, shuffle = true, source = list.name) } }, OctoIcons.Shuffle)
    MenuRow("Play next", { withSongs { app.playNext(it) } }, OctoIcons.PlayNext)
    MenuRow("Add to queue", { withSongs { app.addToQueue(it) } }, OctoIcons.AddToQueue)
    MenuSeparator()
    MenuRow("Edit rules", {
        close()
        if (onEdit != null) onEdit() else app.navigator.go(Page.LiveList(list.id, editing = true))
    }, OctoIcons.Filter)
    if (renaming) {
        val save = {
            if (name.isNotBlank()) {
                app.renameLiveList(list.id, name)
                close()
            }
        }
        NameField(name, { name = it }, "Live list name", "Save", save) { renaming = false }
    } else {
        MenuRow("Rename", { renaming = true }, OctoIcons.Rename)
    }
    MenuRow("Duplicate", { app.duplicateLiveList(list); close() }, OctoIcons.AddToPlaylist)
    MenuSeparator()
    // A snapshot on the server: an ordinary playlist of the songs it holds now.
    MenuRow("Save a copy as a playlist", { app.saveLiveListToServer(list); close() }, OctoIcons.Cloud)
    MenuSeparator()
    MenuRow("Delete", { deleting = true }, OctoIcons.Delete, destructive = true, more = true)
}

// Opens the live list editor with nothing picked yet.
fun newLiveList(app: AppState) {
    app.fullPlayer = false
    app.navigator.go(Page.NewLiveList())
}

// The sidebar's + : a new playlist, or a new live list.
fun showNewListMenu(app: AppState, at: IntOffset) {
    app.popups.showAt(at) { close ->
        MenuRow("New playlist", { close(); newPlaylist(app) }, OctoIcons.Playlists)
        MenuRow("New live list", { close(); newLiveList(app) }, OctoIcons.Filter)
    }
}

// Opens the editor on a live list made from a page's filters: `page` is the
// page's own rule (a genre, Favourites), `order` the page's order.
fun saveAsLiveList(app: AppState, page: List<QueryRule>, filters: LibraryQuery, order: SortOrder?) {
    app.navigator.go(Page.NewLiveList(liveListQueryFrom(page, filters, order)))
}
