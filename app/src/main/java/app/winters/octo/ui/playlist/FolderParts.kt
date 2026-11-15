package app.winters.octo.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.covers.playlistGlyph
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ArtworkShape
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.player.capMiddle
import app.winters.octo.player.rememberCapHeightPx
import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.playlists.PlaylistFolderWords
import app.winters.octo.ui.common.AnswerRow
import app.winters.octo.ui.common.GlassMenuAction
import app.winters.octo.ui.common.GlassMenuBack
import app.winters.octo.ui.common.GlassMenuHeading
import app.winters.octo.ui.common.GlassMenuNote
import app.winters.octo.ui.common.GlassMenuSeparator
import app.winters.octo.ui.common.ListRowSide
import app.winters.octo.ui.common.MenuWidth
import app.winters.octo.ui.common.PlaylistArtwork
import app.winters.octo.ui.common.PopupNameField
import app.winters.octo.ui.common.phonePlaylistFooter
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.nav.PlaylistRoute
import java.text.Collator
import kotlin.math.roundToInt

// What Move to folder moves: playlists by placement key, or a folder.
sealed interface FolderMoveTarget {
    val title: String

    data class Playlists(val keys: List<String>, override val title: String) : FolderMoveTarget

    data class Folder(val id: String, override val title: String) : FolderMoveTarget
}

// A playlist folder's picture, in a cover's place: its glyph in the accent
// on the raised surface.
@Composable
internal fun FolderTile(size: Dp) {
    Box(Modifier.size(size).clip(ArtworkShape).background(OctoColors.SurfaceRaised), contentAlignment = Alignment.Center) {
        Icon(painterResource(OctoIcons.PlaylistFolder), contentDescription = null, tint = OctoColors.Accent, modifier = Modifier.size(size * 0.45f))
    }
}

// A folder's row, as tall as a playlist's: its picture, its name and its line.
@Composable
internal fun FolderRow(line: FolderLine, onClick: () -> Unit, onLongClick: () -> Unit) {
    PlaylistLine(line.folder.name, line.subtitle, onClick = onClick, onLongClick = onLongClick) { FolderTile(56.dp) }
}

// A playlist's row on the Playlists page and a folder's page.
@Composable
internal fun OwnPlaylistLine(playlist: PlaylistSummary, onOpen: (NavKey) -> Unit, onLongClick: () -> Unit) {
    PlaylistLine(
        playlist.name,
        if (playlist.family) "By Family · ${songs(playlist.songCount)}" else songs(playlist.songCount),
        onClick = { onOpen(PlaylistRoute(playlist.id)) },
        onServer = playlist.onServer,
        onLongClick = onLongClick,
    ) {
        PlaylistArtwork(playlist.id, playlist.name, playlist.covers, 56.dp, footer = phonePlaylistFooter(playlist.songCount), glyph = playlistGlyph(playlist.octoNotice, null)) {
            PlaylistCover(playlist.covers, 56.dp)
        }
    }
}

// The space between one topic and the next (folders, then playlists).
@Composable
internal fun TopicGap() {
    Spacer(Modifier.height(20.dp))
}

// The library upkeep group, after the playlists: its heading with a
// chevron, closed until opened (remembered on this phone); while closed, a
// note names each notice list holding songs. Its playlists show only open.
internal fun LazyListScope.upkeepRows(
    upkeep: List<PlaylistSummary>,
    note: String?,
    open: Boolean,
    onToggle: (Boolean) -> Unit,
    row: @Composable LazyItemScope.(PlaylistSummary) -> Unit,
) {
    if (upkeep.isEmpty()) return
    item(key = "upkeep:head") { UpkeepHeader(open, note.takeIf { !open }) { onToggle(!open) } }
    if (open) items(upkeep, key = { "upkeep:${it.id}" }) { row(it) }
}

// The group's heading in the section style, as "From Octo" after it, the
// chevron on its line, and the note in the helper style (13, the secondary
// words, the info glyph in the accent on the first line's capitals).
@Composable
private fun UpkeepHeader(open: Boolean, note: String?, onClick: () -> Unit) {
    val cap = rememberCapHeightPx(OctoType.formDescription)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .clickable(role = Role.Button, onClickLabel = if (open) "Close" else "Open", onClick = onClick)
            .padding(horizontal = ListRowSide + 8.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                PlaylistFolderWords.LIBRARY_UPKEEP,
                style = OctoType.section,
                color = OctoColors.TextPrimary,
                modifier = Modifier.weight(1f).semantics {
                    heading()
                    stateDescription = if (open) "Open" else "Closed"
                },
            )
            Icon(
                painterResource(OctoIcons.Chevron),
                contentDescription = null,
                tint = OctoColors.TextMuted,
                modifier = Modifier.size(20.dp).rotate(if (open) 90f else 0f),
            )
        }
        if (note != null) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(OctoIcons.Info), contentDescription = null, tint = OctoColors.Accent, modifier = Modifier.alignBy { it.measuredHeight / 2 }.size(14.dp))
                Text(note, style = OctoType.formDescription, color = OctoColors.TextSecondary, modifier = Modifier.weight(1f).alignBy { capMiddle(it[FirstBaseline].toFloat(), cap).roundToInt() })
            }
        }
    }
}

// An empty folder's page: "Nothing here yet" and its note.
@Composable
internal fun EmptyFolderNote() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(PlaylistFolderWords.NOTHING_HERE_YET, style = OctoType.formName, color = OctoColors.TextPrimary, textAlign = TextAlign.Center)
        val cap = rememberCapHeightPx(OctoType.formDescription)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(OctoIcons.Info), contentDescription = null, tint = OctoColors.Accent, modifier = Modifier.alignBy { it.measuredHeight / 2 }.size(14.dp))
            Text(
                PlaylistFolderWords.EMPTY_FOLDER_NOTE,
                style = OctoType.formDescription,
                color = OctoColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false).alignBy { capMiddle(it[FirstBaseline].toFloat(), cap).roundToInt() },
            )
        }
    }
}

// Move to folder, as a page of a glass pop-up: "Not in a folder", then
// every folder indented by depth, the current place filled in the accent
// with a check. Moving a folder, the folder and its own folders (and any
// that would go past four deep) are dimmed and say why on a tap. "New
// folder" in the footer makes one at the top level and moves the playlists
// or folder into it.
@Composable
internal fun MoveToFolderPage(target: FolderMoveTarget, onBack: (() -> Unit)?, onDone: () -> Unit, vm: PlaylistSheetsViewModel) {
    val folders by vm.folderView.collectAsStateWithLifecycle()
    val tree = folders.tree
    var naming by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    // One playlist or a folder has a current place; several playlists may not share one.
    val current: String? = when (target) {
        is FolderMoveTarget.Playlists -> target.keys.singleOrNull()?.let(tree::folderOf)
        is FolderMoveTarget.Folder -> tree.parentOf(target.id)
    }
    val known = target !is FolderMoveTarget.Playlists || target.keys.size == 1
    val pick: (String?) -> Unit = { folderId ->
        when (target) {
            is FolderMoveTarget.Playlists -> {
                vm.place(target.keys, folderId)
                onDone()
            }
            is FolderMoveTarget.Folder -> {
                val refusal = tree.moveRefusal(target.id, folderId)
                if (refusal != null) {
                    said = refusal.words
                } else {
                    vm.moveFolder(target.id, folderId)
                    onDone()
                }
            }
        }
    }
    Column(Modifier.width(MenuWidth).padding(6.dp)) {
        if (onBack != null) GlassMenuBack(PlaylistFolderWords.MOVE_TO_FOLDER, onBack) else GlassMenuHeading(PlaylistFolderWords.MOVE_TO_FOLDER)
        LazyColumn(Modifier.weight(1f, fill = false)) {
            item(key = "top") {
                FolderChoice(PlaylistFolderWords.NOT_IN_A_FOLDER, depth = 0, selected = known && current == null, dimmed = false) { pick(null) }
            }
            items(tree.inTreeOrder(), key = { it.first.id }) { (folder, depth) ->
                val dimmed = target is FolderMoveTarget.Folder && tree.moveRefusal(target.id, folder.id) != null
                FolderChoice(folder.name, depth, selected = known && current == folder.id, dimmed = dimmed) { pick(folder.id) }
            }
        }
        said?.let { GlassMenuNote(it, color = OctoColors.TextSecondary) }
        GlassMenuSeparator()
        if (naming) {
            PopupNameField(
                PlaylistFolderWords.FOLDER_NAME,
                "Create",
                onDone = { name ->
                    vm.newFolder(name, parent = null, placing = (target as? FolderMoveTarget.Playlists)?.keys.orEmpty(), moving = (target as? FolderMoveTarget.Folder)?.id)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                ready = { PlaylistFolderTree.cleanName(it) != null },
            )
        } else {
            GlassMenuAction(OctoIcons.PlaylistFolder, PlaylistFolderWords.NEW_FOLDER, onClick = { naming = true })
        }
    }
}

// One place in Move to folder: indented by its depth, filled in the accent
// with a check when it is the current place, dimmed when it can't be chosen.
@Composable
private fun FolderChoice(label: String, depth: Int, selected: Boolean, dimmed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) OctoColors.AccentSelected else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 14.dp + 16.dp * (depth - 1).coerceAtLeast(0), end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (depth > 0) {
            Icon(
                painterResource(OctoIcons.PlaylistFolder),
                contentDescription = null,
                tint = if (dimmed) OctoColors.TextMuted else OctoColors.Accent,
                modifier = Modifier.size(20.dp),
            )
        } else {
            // "Not in a folder": the playlists glyph, as on the other apps.
            Icon(painterResource(OctoIcons.Playlists), contentDescription = null, tint = OctoColors.TextSecondary, modifier = Modifier.size(20.dp))
        }
        Text(
            label,
            style = OctoType.bodySmall.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
            color = if (dimmed) OctoColors.TextMuted else OctoColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(painterResource(OctoIcons.Check), contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(18.dp))
        } else {
            Spacer(Modifier.size(18.dp))
        }
    }
}

// Add playlists, as a page of a glass pop-up: the playlists not in this
// folder that can be filed, A to Z, each with a check box (picked: filled
// in the accent with a check), and "Move here (N)" in the footer, one change
// for all of them.
@Composable
internal fun AddPlaylistsPage(folderId: String, onBack: (() -> Unit)?, onDone: () -> Unit, vm: PlaylistSheetsViewModel) {
    val all by vm.playlists.collectAsStateWithLifecycle()
    val folders by vm.folderView.collectAsStateWithLifecycle()
    val choices = remember(all, folders, folderId) {
        val collator = Collator.getInstance()
        all.filter { it.fileable && folders.tree.folderOf(it.placeKey) != folderId }.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    Column(Modifier.width(MenuWidth).padding(6.dp)) {
        if (onBack != null) GlassMenuBack(PlaylistFolderWords.ADD_PLAYLISTS, onBack) else GlassMenuHeading(PlaylistFolderWords.ADD_PLAYLISTS)
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(choices, key = { it.id }) { playlist ->
                val key = playlist.placeKey
                CheckRow(playlist, checked = key in picked) { on -> picked = if (on) picked + key else picked - key }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.End) {
            AccentButton(PlaylistFolderWords.moveHere(picked.size), onClick = {
                vm.place(picked.toList(), folderId)
                onDone()
            }, enabled = picked.isNotEmpty())
        }
    }
}

// One playlist of Add playlists: its check box, cover, name and size.
@Composable
private fun CheckRow(playlist: PlaylistSummary, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CheckMark(checked)
        PlaylistArtwork(playlist.id, playlist.name, playlist.covers, 36.dp, shape = RoundedCornerShape(6.dp), glyph = playlistGlyph(playlist.octoNotice, null)) {
            PlaylistCover(playlist.covers, 36.dp, shape = RoundedCornerShape(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(songs(playlist.songCount), style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1)
        }
    }
}

// A round check box: picked, filled in the accent with a check; else an outline.
@Composable
private fun CheckMark(checked: Boolean) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked) OctoColors.Accent else Color.Transparent)
            .border(1.5.dp, if (checked) OctoColors.Accent else OctoColors.TextMuted, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(painterResource(OctoIcons.Check), contentDescription = null, tint = OctoColors.OnAccent, modifier = Modifier.size(14.dp))
    }
}

// Asks before a folder is deleted, saying where its playlists go.
@Composable
internal fun ConfirmDeleteFolder(name: String, parentName: String?, onCancel: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.width(MenuWidth).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp)) {
        Text(PlaylistFolderWords.deleteTitle(name), style = OctoType.body, color = OctoColors.TextPrimary, modifier = Modifier.semantics { heading() })
        Text(PlaylistFolderWords.deleteBody(parentName), style = OctoType.bodySmall, color = OctoColors.TextMuted, modifier = Modifier.padding(top = 6.dp))
        // The answers as every question's: side by side while they fit,
        // stacked at large text, so neither is cut.
        AnswerRow(
            main = { AccentButton(PlaylistFolderWords.DELETE_FOLDER, onClick = onDelete, fill = OctoColors.Destructive) },
            other = { GlazeButton(PlaylistFolderWords.CANCEL, onClick = onCancel) },
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}
