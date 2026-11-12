package app.winters.octo.ui.folders

import app.winters.octo.ui.common.playShuffleItems
import app.winters.octo.ui.common.MoreItem
import app.winters.octo.ui.common.LineItem
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.folders.LevelRow
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.ui.common.EmptyLibraryNote
import app.winters.octo.ui.common.LoadState
import app.winters.octo.ui.common.Pickable
import app.winters.octo.ui.common.Refreshable
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.SelectableSongs
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.SortBar
import app.winters.octo.ui.common.TopOnNewOrder
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.PageHeader
import app.winters.octo.ui.common.HeaderDropdown
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.common.showDropdown
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.common.picked
import app.winters.octo.playback.ListSpec
import app.winters.octo.ui.paging.ListItem
import app.winters.octo.ui.paging.PagedPicks
import app.winters.octo.ui.paging.RowPlaceholder
import app.winters.octo.ui.paging.listRows
import app.winters.octo.ui.paging.rememberListRows
import androidx.paging.compose.LazyPagingItems

// Music as it is filed: folders on the phone and on the server. With both,
// the page starts with one of each; with one, it starts inside it. Back
// goes up a folder. The header holds the folder's name and what it holds,
// then the folders on the way to it, any of which goes back there, and Play
// and Shuffle for everything under it.
@Composable
fun FoldersScreen(onBack: () -> Unit, vm: FoldersViewModel = hiltViewModel()) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val trail by vm.trail.collectAsStateWithLifecycle()
    val levels by vm.levels.collectAsStateWithLifecycle()
    val gathering by vm.gathering.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val order by vm.order.collectAsStateWithLifecycle()
    val phoneSongs by vm.songs.collectAsStateWithLifecycle()

    BackHandler(enabled = trail.isNotEmpty()) { vm.up() }

    Refreshable(onRefresh = vm::reloadServer) {
        val known = sources
        val top = known?.top
        when {
            known == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OctoColors.Accent, modifier = Modifier.size(28.dp))
            }
            top == null -> Column(Modifier.fillMaxSize().padding(screenPadding())) {
                PageHeader("Folders", onBack)
                EmptyLibraryNote("Folders show here once there is music on this phone or a server is signed in.")
            }
            else -> {
                val stops = listOf(top) + trail
                val here = stops.last()
                val names = stops.map { known.label(it) }
                // This folder's own songs and how many rows come before them.
                val level = (here as? FolderStop.Server)?.let { levels[it.id.orEmpty()] }
                val serverPage = (level as? LoadState.Ready)?.data
                val phoneNode = (here as? FolderStop.Phone)?.let { known.phone?.root?.at(it.path) }
                val songsAt = when (here) {
                    FolderStop.Top -> 0
                    is FolderStop.Phone -> phoneNode?.let { 1 + it.folders.size } ?: 0
                    is FolderStop.Server -> 0
                }
                // A phone folder's songs are read whole when it has few, else
                // in pages; a server folder's whole level is read in pages,
                // its folders as the server lists them, then its songs in the
                // chosen order.
                val paged = if (here is FolderStop.Phone) (phoneSongs as? FolderSongs.Paged)?.handle else null
                val held = if (here is FolderStop.Phone) (phoneSongs as? FolderSongs.Held)?.songs.orEmpty() else emptyList()
                val problem = if (here is FolderStop.Phone) (phoneSongs as? FolderSongs.Problem)?.message else null
                val head: LazyListScope.() -> Unit = {
                    item(key = "header") {
                        // A server folder read and found empty has nothing to play.
                        val serverCount = serverPage?.handle?.shown?.collectAsStateWithLifecycle()?.value?.count
                        PageHeader(
                            names.last(),
                            onBack = { if (!vm.up()) onBack() },
                            count = phoneNode?.let { folderCount(it.folders.size, it.total) },
                            lead = listOfNotNull(if (names.size > 1) breadcrumbItem(names, onCrumb = { vm.goTo(it - 1) }) else null),
                            tail = when (here) {
                                FolderStop.Top -> emptyList()
                                is FolderStop.Phone -> phoneNode?.let { node ->
                                    playShuffleItems(
                                        { vm.playPhone(here.path, node, shuffle = false) },
                                        { vm.playPhone(here.path, node, shuffle = true) },
                                        enabled = node.total > 0,
                                    )
                                }.orEmpty()
                                is FolderStop.Server -> playShuffleItems(
                                    { vm.playServer(here.id, names.last(), shuffle = false) },
                                    { vm.playServer(here.id, names.last(), shuffle = true) },
                                    enabled = serverCount != 0,
                                    loading = gathering,
                                )
                            },
                        )
                    }
                    // What came of the last Play, while there is something to say.
                    note?.let { item(key = "note") { Quiet(it) } }
                    when (here) {
                        FolderStop.Top -> topLevel(known, vm::open)
                        is FolderStop.Phone -> phoneLevel(known, here, vm)
                        is FolderStop.Server -> serverLevel(here, level, vm)
                    }
                    problem?.let { item(key = "problem") { Quiet(it) } }
                }
                // A fresh list for each folder, so each one opens at its top.
                key(here) {
                    val state = rememberLazyListState()
                    when {
                        paged != null && here is FolderStop.Phone -> {
                            TopOnNewOrder(order, state, top = songsAt)
                            val above = songsAt + (if (note != null) 1 else 0) + 1
                            val items = rememberListRows(paged, state, above = above)
                            PagedPicks(
                                paged,
                                items,
                                pickOf = { key, item -> if (item.row.id == key) Pickable(key, item.row, item.key).picked() else null },
                                base = ListSpec.Folder(here.path.joinToString("/"), recursive = false, order = order),
                            ) {
                                LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = screenPadding()) {
                                    head()
                                    pagedSongRows(items, order, here.path, vm)
                                }
                            }
                        }
                        serverPage != null -> {
                            val above = 1 + (if (note != null) 1 else 0)
                            TopOnNewOrder(order, state, top = above + serverPage.level.folders)
                            val items = rememberListRows(serverPage.handle, state, above = above)
                            val meta by serverPage.handle.shown.collectAsStateWithLifecycle()
                            PagedPicks(
                                serverPage.handle,
                                items,
                                pickOf = { key, item -> (item.row as? LevelRow.Song)?.track?.takeIf { it.id == key }?.let { Pickable(key, it, item.key).picked() } },
                                base = null,
                            ) {
                                LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = screenPadding()) {
                                    head()
                                    serverRows(items, serverPage, empty = meta?.count == 0, order, vm)
                                }
                            }
                        }
                        else -> {
                            TopOnNewOrder(order, state, top = songsAt)
                            val pickable = remember(held) { held.map { Pickable(it.id, it) } }
                            SelectableSongs(pickable) {
                                LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = screenPadding()) {
                                    head()
                                    songRows(held, order, vm)
                                }
                            }
                        }
                    }
                }
                if (here is FolderStop.Server) LaunchedEffect(here.id) { vm.load(here.id) }
            }
        }
    }
}

// What a phone folder holds, for the title line: "3 folders • 42 songs".
internal fun folderCount(folders: Int, songCount: Int): String =
    listOfNotNull(folders.takeIf { it > 0 }?.let { if (it == 1) "1 folder" else "$it folders" }, songs(songCount)).joinToString(" • ")

// What to call a place, in the title and the path.
private fun FolderSources.label(stop: FolderStop): String = when (stop) {
    FolderStop.Top -> "Folders"
    is FolderStop.Phone -> stop.path.lastOrNull() ?: phoneName
    is FolderStop.Server -> stop.name.ifBlank { "Untitled folder" }
}

// The page with both sources: one way into each.
private fun LazyListScope.topLevel(sources: FolderSources, open: (FolderStop) -> Unit) {
    val phone = sources.phone ?: return
    val server = sources.server ?: return
    item(key = "phone") {
        Column {
            SectionTitle("On this phone")
            FolderRow(sources.phoneName, songs(phone.root.total)) { open(FolderStop.Phone(emptyList())) }
        }
    }
    item(key = "server") {
        Column(Modifier.padding(top = 16.dp)) {
            SectionTitle("On $server")
            FolderRow(server, "Browse the server by folder") { open(FolderStop.Server(null, server)) }
        }
    }
}

private fun LazyListScope.phoneLevel(sources: FolderSources, here: FolderStop.Phone, vm: FoldersViewModel) {
    val node = sources.phone?.root?.at(here.path)
    if (node == null) {
        // The phone's music changed and this folder is gone.
        item(key = "gone") { Quiet("This folder is no longer on the phone.") }
        return
    }
    itemsIndexed(node.folders, key = { _, folder -> "folder:${folder.name}" }) { index, folder ->
        Column {
            if (index > 0) Separator()
            FolderRow(folder.name, songs(folder.total)) { vm.open(FolderStop.Phone(here.path + folder.name)) }
        }
    }
}

// A server level on its way or that could not be read; once read, its rows
// come in pages (serverRows).
private fun LazyListScope.serverLevel(here: FolderStop.Server, state: LoadState<ServerLevelPage>?, vm: FoldersViewModel) {
    when (state) {
        null, LoadState.Loading -> item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OctoColors.Accent, modifier = Modifier.size(28.dp))
            }
        }
        is LoadState.Failed -> item(key = "failed") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.message, style = OctoType.bodySmall, color = OctoColors.TextMuted)
                GlazeButton("Try again", onClick = { vm.load(here.id, again = true) })
            }
        }
        is LoadState.Ready -> Unit
    }
}

// A server level a page at a time: its folders as the server lists them,
// then its songs in the chosen order under their sort bar. A folder goes
// in; a song plays the folder's songs from it, and a long press opens its
// menu. Keyed by place as well as id, since a server may list a folder
// twice.
private fun LazyListScope.serverRows(items: LazyPagingItems<ListItem<LevelRow>>, page: ServerLevelPage, empty: Boolean, order: SortOrder, vm: FoldersViewModel) {
    if (empty) {
        item(key = "empty") { Quiet("Nothing in this folder.") }
        return
    }
    val folders = page.level.folders
    items(items.itemCount, key = { at -> items.peek(at)?.key?.token ?: "row:$at" }) { at ->
        val item = items[at]
        when (val row = item?.row) {
            null -> if (at < folders) Box(Modifier.fillMaxWidth().height(56.dp)) else RowPlaceholder()
            is LevelRow.Folder -> Column {
                if (at > 0) Separator()
                FolderRow(row.folder.name.ifBlank { "Untitled folder" }, null) { vm.open(FolderStop.Server(row.folder.id, row.folder.name)) }
            }
            is LevelRow.Song -> Column {
                if (at == folders) SortBar(SortList.FolderSongs, order, vm::setOrder, Modifier.padding(top = 12.dp), title = "Songs")
                SongRow(row.track) { vm.playFrom(page, item) }
            }
        }
    }
}

// A folder's own songs, in the chosen order. A tap plays them from that
// song; a long press opens the song's menu.
private fun LazyListScope.songRows(list: List<TrackEntity>, order: SortOrder, vm: FoldersViewModel) {
    if (list.isEmpty()) return
    item(key = "songs") { SortBar(SortList.FolderSongs, order, vm::setOrder, Modifier.padding(top = 12.dp), title = "Songs") }
    items(list, key = { "song:${it.id}" }) { track ->
        Box(Modifier.animateItem()) { SongRow(track) { vm.playFrom(list, track) } }
    }
}

// A big phone folder's own songs, in pages of its ordered copy.
private fun LazyListScope.pagedSongRows(items: LazyPagingItems<ListItem<TrackEntity>>, order: SortOrder, path: List<String>, vm: FoldersViewModel) {
    if (items.itemCount == 0) return
    item(key = "songs") { SortBar(SortList.FolderSongs, order, vm::setOrder, Modifier.padding(top = 12.dp), title = "Songs") }
    listRows(items, null, heading = {}) { item ->
        if (item == null) RowPlaceholder() else SongRow(item.row) { vm.playFrom(path, item) }
    }
}

// The folder this one is in, as a dropdown on the control line listing
// every folder on the way here, the nearest last; the title already names
// this one. Picking one goes back there.
@Composable
private fun breadcrumbItem(names: List<String>, onCrumb: (Int) -> Unit): LineItem {
    val sheet = LocalChoiceSheet.current
    val above = names.dropLast(1)
    val ask = { showDropdown(sheet, "Go back to", above, -1, onCrumb) }
    return LineItem("way-back", overflow = 0, more = MoreItem("In ${above.last()}", OctoIcons.Folder, detail = "Go back to a folder on the way", onClick = ask)) { _ ->
        HeaderDropdown(above.last(), onClick = ask, icon = OctoIcons.Folder, description = "In ${above.last()}", clickLabel = "Go back to a folder on the way")
    }
}

// A folder line: icon, name, what it holds, and a chevron.
@Composable
private fun FolderRow(name: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .height(56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(OctoIcons.Folder), contentDescription = null, tint = OctoColors.Accent, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1) }
        }
        Icon(painterResource(OctoIcons.Chevron), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(22.dp))
    }
}

// A hairline between folder rows, starting under the names.
@Composable
private fun Separator() {
    Box(
        Modifier
            .padding(start = 60.dp, end = 20.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(OctoColors.TextPrimary.copy(alpha = 0.08f)),
    )
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = OctoType.bodySmall, color = OctoColors.TextMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}
