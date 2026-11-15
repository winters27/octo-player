package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.winters.octo.design.Corner
import app.winters.octo.design.CutTxt
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlassField
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.MenuBack
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Space
import app.winters.octo.design.TooltipSide
import app.winters.octo.design.hoverLift
import app.winters.octo.design.menuKey
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.createFolder
import app.winters.octo.desktop.deleteFolder
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.placePlaylists
import app.winters.octo.desktop.playlists.SidebarPlaylistRow
import app.winters.octo.desktop.renameFolder
import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.playlists.PlaylistFolderWords
import app.winters.octo.subsonic.Playlist

// Playlist folders in the sidebar: a folder's row, the pop-up a folder opens
// on the rail, its right-click menu, Move to folder for playlists, and the
// form for a new folder.

// The folder menu's way to make a folder inside the one it is for.
private const val NEW_FOLDER_INSIDE = "New folder inside"

// A folder's row, as tall as a playlist's: the chevron that opens or closes
// it, its glyph on a 32 dp tile and its name, 12 dp further in for each
// folder above it. Playlists dragged from the sidebar onto it go in it;
// songs never do. Right-click opens its menu. On the rail it is the tile
// alone, named in a tooltip, and a click opens a pop-up of what it holds.
@Composable
internal fun FolderRow(app: AppState, row: SidebarPlaylistRow.Folder, rail: Boolean) {
    val pointer = LocalPointer.current
    val drag = LocalDrag.current
    val folder = row.folder
    val dropId = "pf:${folder.id}"
    val takes = drag.movingPlaylists
    val lit = takes && isDropOver(dropId)
    val subtitle = PlaylistFolderWords.folderSubtitle(row.playlists, row.folders)
    val content: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .height(RowHeight.Nav + Space.Xs)
                .then(if (takes) Modifier.playlistDropTarget(dropId, "Move to ${folder.name}") { dropped -> app.placePlaylists(dropped.map { it.id }, folder.id) } else Modifier)
                .hoverLift(Corner.ControlShape, lifted = false)
                .onRightClick { app.popups.showAt(pointer.point) { close -> FolderMenu(app, folder.id, close) } }
                .menuKey { at -> app.popups.showUnder(at) { close -> FolderMenu(app, folder.id, close) } }
                .clickable(role = Role.Button) {
                    if (rail) {
                        app.popups.showAt(pointer.point) { close -> FolderContents(app, folder.id, close) }
                    } else {
                        app.updateFrame { it.copy(openFolders = if (row.open) it.openFolders - folder.id else it.openFolders + folder.id) }
                    }
                }
                .semantics {
                    contentDescription = "${folder.name}, ${PlaylistFolderWords.PLAYLIST_FOLDER.lowercase()}, $subtitle"
                    if (!rail) stateDescription = if (row.open) "Open" else "Folded"
                },
            contentAlignment = if (rail) Alignment.Center else Alignment.CenterStart,
        ) {
            if (lit) Box(Modifier.matchParentSize().background(DropLit, Corner.ControlShape))
            Row(
                Modifier.padding(start = if (rail) Space.None else Space.M + FolderIndent * (row.depth - 1), end = if (rail) Space.None else Space.M),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.M + Space.Xxs),
            ) {
                FolderTile(Modifier.size(FrameSize.PlaylistCover))
                // The chevron after the name, as the sidebar's group names
                // carry theirs, so a folder's tile lines up with the covers
                // beside it and the rows it holds sit one step further in.
                if (!rail) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Xs)) {
                        CutTxt(folder.name, DesktopType.body, OctoColors.TextSecondary, Modifier.weight(1f, fill = false))
                        Glyph(OctoIcons.Chevron, Modifier.rotate(if (row.open) 90f else 0f), size = IconSize.Inline - Space.Xxs, tint = OctoColors.TextMuted, exact = true)
                    }
                }
            }
        }
    }
    if (rail) OctoTooltip(folder.name, side = TooltipSide.Right) { content() } else content()
}

// How much further in each folder level sits in the sidebar, for folders
// and playlists alike.
internal val FolderIndent = Space.Xl

// The playlist folder glyph on its tile, as a folder's picture: the accent
// on the raised surface, so the tile steps off the sidebar like a cover.
@Composable
internal fun FolderTile(modifier: Modifier) {
    Box(modifier.background(OctoColors.SurfaceRaised, Corner.ArtSShape), contentAlignment = Alignment.Center) {
        Glyph(OctoIcons.PlaylistFolder, size = IconSize.Table, tint = OctoColors.Accent)
    }
}

// What a folder holds, as the rail's pop-up: its folders, which open in
// place with a way back, then its playlists, which open their pages.
@Composable
internal fun ColumnScope.FolderContents(app: AppState, folderId: String, close: () -> Unit) {
    var at by remember { mutableStateOf(folderId) }
    val tree = app.folderTree
    val folder = tree.folder(at) ?: return
    if (at == folderId) MenuTitle(folder.name) else MenuBack(folder.name) { at = tree.parentOf(at) ?: folderId }
    val folders = tree.children(at)
    val playlists = app.playlists.filter { tree.folderOf(it.id) == at && !it.octoUpkeep }
    if (folders.isEmpty() && playlists.isEmpty()) {
        Question(PlaylistFolderWords.NOTHING_HERE_YET, PlaylistFolderWords.EMPTY_FOLDER_NOTE)
        return
    }
    // A folder's glyph in a box as wide as a playlist's picture, so every
    // name in the pop-up starts on one line.
    folders.forEach { inner ->
        MenuRow(inner.name, { at = inner.id }, more = true, leading = {
            Box(Modifier.size(FrameSize.MenuArt), contentAlignment = Alignment.Center) {
                Glyph(OctoIcons.PlaylistFolder, size = IconSize.Table, tint = OctoColors.TextPrimary)
            }
        })
    }
    if (folders.isNotEmpty() && playlists.isNotEmpty()) MenuSeparator()
    playlists.forEach { playlist ->
        MenuRow(playlist.name, { app.navigator.go(Page.Playlist(playlist.id)); close() }, leading = {
            PlaylistPicture(app, playlist, Modifier.size(FrameSize.MenuArt), Corner.ArtSShape)
        })
    }
}

private enum class FolderPage { Main, Naming, Renaming, Deleting }

// A folder's menu: a new folder inside it (while it is not four deep),
// rename it in place, or delete it, asked first in the menu itself: its
// playlists move to its parent, and no playlist is deleted.
@Composable
internal fun ColumnScope.FolderMenu(app: AppState, folderId: String, close: () -> Unit) {
    var page by remember { mutableStateOf(FolderPage.Main) }
    val tree = app.folderTree
    val folder = tree.folder(folderId) ?: return
    var name by remember(page) { mutableStateOf(if (page == FolderPage.Renaming) folder.name else "") }
    if (page == FolderPage.Deleting) {
        val parent = tree.parentOf(folderId)?.let(tree::folder)?.name
        Question(PlaylistFolderWords.deleteTitle(folder.name), PlaylistFolderWords.deleteBody(parent))
        MenuRow(PlaylistFolderWords.DELETE_FOLDER, { app.deleteFolder(folderId); close() }, OctoIcons.Delete, destructive = true)
        MenuRow(PlaylistFolderWords.CANCEL, { page = FolderPage.Main }, OctoIcons.Close)
        return
    }
    MenuTitle(folder.name)
    when (page) {
        FolderPage.Naming -> {
            val create = {
                if (PlaylistFolderTree.cleanName(name) != null) {
                    app.createFolder(name, folderId)
                    close()
                }
            }
            NameField(name, { name = it }, PlaylistFolderWords.FOLDER_NAME, "Create", create) { page = FolderPage.Main }
        }
        else -> MenuRow(NEW_FOLDER_INSIDE, { page = FolderPage.Naming }, OctoIcons.PlaylistFolder, enabled = tree.canHoldFolder(folderId))
    }
    when (page) {
        FolderPage.Renaming -> {
            val save = {
                if (PlaylistFolderTree.cleanName(name) != null) {
                    app.renameFolder(folderId, name)
                    close()
                }
            }
            NameField(name, { name = it }, PlaylistFolderWords.FOLDER_NAME, "Save", save) { page = FolderPage.Main }
        }
        else -> MenuRow(PlaylistFolderWords.RENAME, { page = FolderPage.Renaming }, OctoIcons.Rename)
    }
    MenuSeparator()
    MenuRow(PlaylistFolderWords.DELETE_FOLDER, { page = FolderPage.Deleting }, OctoIcons.Delete, destructive = true, more = true)
}

// Move to folder for one playlist, or for several picked together: "Not in
// a folder", then every folder in tree order, set in 12 dp for each folder
// above it, the playlists' own place picked with the accent; last, a new
// folder at the top level, which they then move into.
@Composable
internal fun ColumnScope.MoveToFolderMenu(app: AppState, playlists: List<Playlist>, close: () -> Unit, back: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val tree = app.folderTree
    val ids = playlists.map { it.id }.filter(PlaylistFolderTree::fileable)
    // The one place they all share, if they share one.
    val places = ids.map(tree::folderOf).distinct()
    MenuBack(PlaylistFolderWords.MOVE_TO_FOLDER, back)
    MenuRow(PlaylistFolderWords.NOT_IN_A_FOLDER, { app.placePlaylists(ids, null); close() }, OctoIcons.Playlists, selected = places == listOf(null))
    tree.inTreeOrder().forEach { (folder, depth) ->
        MenuRow(folder.name, { app.placePlaylists(ids, folder.id); close() }, selected = places == listOf(folder.id), leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (depth > 1) Spacer(Modifier.width(Space.L * (depth - 1)))
                Glyph(OctoIcons.PlaylistFolder, size = IconSize.Table, tint = OctoColors.TextPrimary)
            }
        })
    }
    MenuSeparator()
    if (naming) {
        val create = {
            if (PlaylistFolderTree.cleanName(name) != null) {
                app.createFolder(name, null) { id -> app.placePlaylists(ids, id) }
                close()
            }
        }
        NameField(name, { name = it }, PlaylistFolderWords.FOLDER_NAME, "Create", create) { naming = false }
    } else {
        MenuRow(PlaylistFolderWords.NEW_FOLDER, { naming = true }, OctoIcons.AddToLibrary)
    }
}

// A small form for naming a new folder in `parent` (null: the top level),
// in the middle of the window.
fun newFolder(app: AppState, parent: String?) {
    app.popups.showCentred { close ->
        var name by remember { mutableStateOf("") }
        MenuTitle(PlaylistFolderWords.NEW_FOLDER)
        PopupPadding {
            val create = {
                if (PlaylistFolderTree.cleanName(name) != null) {
                    app.createFolder(name, parent)
                    close()
                }
            }
            GlassField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = PlaylistFolderWords.FOLDER_NAME, onSubmit = create, onEscape = close)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.M, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                GlazeCapsule(null, PlaylistFolderWords.CANCEL, close)
                GlazeCapsule(null, "Create", create, lit = true, enabled = PlaylistFolderTree.cleanName(name) != null)
            }
        }
    }
}
