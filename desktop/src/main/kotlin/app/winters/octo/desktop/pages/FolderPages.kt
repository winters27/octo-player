package app.winters.octo.desktop.pages

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.PageSize
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.library.climbFolders
import app.winters.octo.desktop.library.folderCount
import app.winters.octo.desktop.library.folderPath
import app.winters.octo.desktop.library.totalLengthText
import app.winters.octo.desktop.nav.FolderStep
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.ScrollSpot
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.Load
import app.winters.octo.desktop.ui.LoadingLine
import app.winters.octo.desktop.ui.PageSide
import app.winters.octo.desktop.ui.LocalBottomRoom
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.pagePadding
import app.winters.octo.desktop.ui.SongTable
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.desktop.ui.rememberLoad
import app.winters.octo.subsonic.DirectoryRef
import app.winters.octo.subsonic.MusicDirectory
import app.winters.octo.subsonic.SubsonicException

// Browsing the server's own folders, a power feature beside the library's
// artists and albums: breadcrumbs back up, the folders inside with their
// counts when the server gives them, and the songs.

private val FolderColumns = listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Length)

// The server's folders from the top, as it files them.
@Composable
fun FoldersPage(app: AppState, visit: Visit) {
    val connection = app.connection ?: return
    val loaded = rememberLoad(connection) { connection.client.indexes() }
    val list = rememberListState(app.navigator, visit)
    when (val state = loaded.state) {
        Load.Loading -> LoadingLine(modifier = Modifier.padding(horizontal = PageSide))
        is Load.Failed -> Column(Modifier.padding(horizontal = PageSide)) {
            NextStep(
                "The server wouldn't list its folders",
                state.message,
                "Try again" to loaded.retry,
                "Go to Artists" to { app.navigator.go(Page.Artists) },
            )
        }
        is Load.Ready -> {
            val top = state.data
            SongTable(
                app,
                top.songs,
                FolderColumns,
                list,
                id = "folder",
                covers = false,
                empty = {
                    if (top.folders.isEmpty()) {
                        NextStep(
                            "No folders",
                            "This server doesn't list its folders. Your music is still under Artists and Albums.",
                            "Go to Artists" to { app.navigator.go(Page.Artists) },
                            "Go to Albums" to { app.navigator.go(Page.Albums) },
                        )
                    }
                },
            ) {
                item(key = "title") { PageTitle("Folders", detail = folderFacts(top.folders.size, top.songs.size, top.songs.sumOf { it.duration })) }
                items(top.folders, key = { "f:${it.id}" }) { folder -> FolderLine(folder) { app.navigator.go(Page.Folder(folder.id, folder.name)) } }
            }
        }
    }
}

// One folder: the way back up, its name, where it is when the server
// says, the folders in it, then its songs in the server's order. Opened by
// "Show in folder", it starts at that song and says so.
@Composable
fun FolderPage(app: AppState, visit: Visit, id: String, name: String) {
    val connection = app.connection ?: return
    val page = visit.page as? Page.Folder
    val trail = page?.trail.orEmpty()
    val loaded = rememberLoad(connection, id) { connection.client.musicDirectory(id) }
    val list = rememberListState(app.navigator, visit)
    when (val state = loaded.state) {
        Load.Loading -> LoadingLine(modifier = Modifier.padding(horizontal = PageSide))
        is Load.Failed -> Column(Modifier.padding(horizontal = PageSide)) {
            Crumbs(app, trail, name)
            val up = trail.lastOrNull()
            NextStep(
                "The server wouldn't list this folder",
                state.message,
                "Try again" to loaded.retry,
                if (up != null) "Back to ${up.name}" to { app.navigator.go(Page.Folder(up.id, up.name, trail.dropLast(1))) } else "Go to Folders" to { app.navigator.go(Page.Folders) },
            )
        }
        is Load.Ready -> FolderBody(app, visit, state.data, trail, page?.focus, name, list)
    }
}

@Composable
private fun FolderBody(app: AppState, visit: Visit, folder: MusicDirectory, given: List<FolderStep>, focus: String?, fallbackName: String, list: LazyListState) {
    val client = app.connection?.client
    val name = folder.name.ifEmpty { fallbackName }
    // Opened from a song rather than from the folders above, the way back
    // up is found by asking the server for each folder's parent.
    val trail by produceState(given, folder.id, folder.parent) {
        if (given.isEmpty() && folder.parent != null && client != null) {
            value = climbFolders(folder.parent, { id ->
                try {
                    client.musicDirectory(id).let { it.name to it.parent }
                } catch (e: SubsonicException) {
                    null
                }
            })
        }
    }
    val songs = folder.songs
    val shown = remember(folder, focus) { focus?.let { wanted -> songs.firstOrNull { it.id == wanted } } }
    // Straight to the song, before the first frame, unless this visit was
    // already scrolled (coming back to it). The table's rows start after
    // the title, the folders and the table's own heading.
    val fresh = remember(visit.id) { app.navigator.scrollOf(visit) == ScrollSpot() }
    val row = remember(folder, shown) { shown?.let { songs.indexOf(it) } }
    val asked = remember(visit.id) { booleanArrayOf(false) }
    if (fresh && row != null && row >= 0 && !asked[0]) {
        SideEffect {
            asked[0] = true
            list.requestScrollToItem((1 + folder.folders.size + 1 + row - AboveFocus).coerceAtLeast(0))
        }
    }
    val path = remember(folder) { folderPath(songs) }
    Column(Modifier.fillMaxSize()) {
        if (shown != null) QuietLine("Showing ${shown.title}", "Play it", { app.play(songs, songs.indexOf(shown)) })
        SongTable(
            app,
            songs,
            FolderColumns,
            list,
            Modifier.weight(1f),
            id = "folder",
            // Under the quiet line, the page starts closer to the top.
            padding = if (shown != null) pagePadding(LocalBottomRoom.current, top = Space.S) else pagePadding(LocalBottomRoom.current),
            covers = false,
            number = { index, song -> song.track?.toString() ?: "${index + 1}" },
            empty = {
                if (folder.folders.isEmpty()) {
                    val up = trail.lastOrNull()
                    NextStep(
                        "This folder is empty",
                        "The server lists no songs or folders in it.",
                        if (up != null) "Back to ${up.name}" to { app.navigator.go(Page.Folder(up.id, up.name, trail.dropLast(1))) } else "Go to Folders" to { app.navigator.go(Page.Folders) },
                    )
                }
            },
        ) {
            item(key = "title") {
                Column {
                    Crumbs(app, trail, name)
                    PageTitle(
                        name,
                        detail = listOfNotNull(folderFacts(folder.folders.size, songs.size, songs.sumOf { it.duration }), path).joinToString("  ·  ").ifEmpty { null },
                        actions = if (songs.isEmpty()) null else ({
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                                PlayAndShuffle({ app.play(songs) }, { app.play(songs, shuffle = true) })
                            }
                        }),
                    )
                }
            }
            val inside = trail + FolderStep(folder.id, name)
            items(folder.folders, key = { "f:${it.id}" }) { sub -> FolderLine(sub) { app.navigator.go(Page.Folder(sub.id, sub.name, inside)) } }
        }
    }
}

// Rows kept above a song opened by "Show in folder", so it sits below the
// table's heading rather than under it.
private const val AboveFocus = 2

// "Folders › Radiohead › OK Computer": each step opens that folder; the
// last is the one open.
@Composable
private fun Crumbs(app: AppState, trail: List<FolderStep>, current: String) {
    Row(Modifier.offset(x = -Space.S).padding(bottom = Space.S), verticalAlignment = Alignment.CenterVertically) {
        Crumb("Folders") { app.navigator.go(Page.Folders) }
        trail.forEachIndexed { index, step ->
            Glyph(OctoIcons.Chevron, size = IconSize.Inline, tint = OctoColors.TextMuted)
            Crumb(step.name.ifEmpty { "Folder" }) { app.navigator.go(Page.Folder(step.id, step.name, trail.take(index))) }
        }
        Glyph(OctoIcons.Chevron, size = IconSize.Inline, tint = OctoColors.TextMuted)
        Box(Modifier.padding(horizontal = Space.S, vertical = Space.Xs)) { Txt(current, DesktopType.meta, OctoColors.TextPrimary) }
    }
}

@Composable
private fun Crumb(text: String, onOpen: () -> Unit) {
    Box(Modifier.hoverLift(Corner.RowShape).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = Space.S, vertical = Space.Xs)) {
        Txt(text, DesktopType.meta, OctoColors.TextSecondary)
    }
}

// A folder inside: its cover when the server has one, its name, and how
// many songs or albums it holds when the server says.
@Composable
private fun FolderLine(folder: DirectoryRef, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(RowHeight.Regular).hoverLift(Corner.RowShape).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = Space.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.L),
    ) {
        if (folder.coverArt != null) {
            Cover(folder.coverArt, Modifier.size(PageSize.FolderCover), shape = Corner.ArtSShape, placeholder = OctoIcons.Folder)
        } else {
            Box(Modifier.size(PageSize.FolderCover), contentAlignment = Alignment.Center) { Glyph(OctoIcons.Folder, size = IconSize.Toolbar, tint = OctoColors.TextSecondary) }
        }
        Txt(folder.name, DesktopType.tableTitle, modifier = Modifier.weight(1f))
        folderCount(folder.songCount, folder.albumCount)?.let { Txt(it, DesktopType.meta, OctoColors.TextMuted) }
        Glyph(OctoIcons.Chevron, size = IconSize.Table, tint = OctoColors.TextMuted)
    }
}

// "3 folders · 12 songs · 48 min", leaving out what there is none of.
private fun folderFacts(folders: Int, songs: Int, seconds: Int): String? = listOfNotNull(
    folders.takeIf { it > 0 }?.let { if (it == 1) "1 folder" else "$it folders" },
    songs.takeIf { it > 0 }?.let { if (it == 1) "1 song" else "$it songs" },
    seconds.takeIf { songs > 0 }?.let(::totalLengthText),
).joinToString(" · ").ifEmpty { null }
