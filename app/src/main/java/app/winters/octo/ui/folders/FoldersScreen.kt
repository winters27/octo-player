package app.winters.octo.ui.folders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.folders.ServerLevel
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.sortFolderSongs
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.EmptyLibraryNote
import app.winters.octo.ui.common.LoadState
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.SortBar
import app.winters.octo.ui.common.TopOnNewOrder
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.songs

// Music as it is filed: folders on the phone and on the server. With both,
// the page starts with one of each; with one, it starts inside it. Back
// goes up a folder; the path at the top goes back to any folder on the way.
@Composable
fun FoldersScreen(onBack: () -> Unit, vm: FoldersViewModel = hiltViewModel()) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val trail by vm.trail.collectAsStateWithLifecycle()
    val levels by vm.levels.collectAsStateWithLifecycle()
    val gathering by vm.gathering.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val order by vm.order.collectAsStateWithLifecycle()

    BackHandler(enabled = trail.isNotEmpty()) { vm.up() }

    Box(Modifier.fillMaxSize()) {
        val known = sources
        val top = known?.top
        when {
            known == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OctoColors.Accent, modifier = Modifier.size(28.dp))
            }
            top == null -> Column(Modifier.fillMaxSize().padding(screenPadding(extraTop = DetailTopGap))) {
                ScreenTitle("Folders")
                EmptyLibraryNote("Folders show here once there is music on this phone or a server is signed in.")
            }
            else -> {
                val stops = listOf(top) + trail
                val here = stops.last()
                val names = stops.map { known.label(it) }
                // This folder's own songs and how many rows come before them.
                val level = (here as? FolderStop.Server)?.let { levels[it.id.orEmpty()] }
                val (songs, songsAt) = when (here) {
                    FolderStop.Top -> null to 0
                    is FolderStop.Phone -> known.phone?.root?.at(here.path)?.let { it.songs to 2 + it.folders.size } ?: (null to 0)
                    is FolderStop.Server -> (level as? LoadState.Ready)?.data?.let { it.songs to 2 + it.folders.size } ?: (null to 0)
                }
                val sortedSongs = remember(songs, order) { songs?.let { sortFolderSongs(it, order) }.orEmpty() }
                // A fresh list for each folder, so each one opens at its top.
                key(here) {
                    val state = rememberLazyListState()
                    TopOnNewOrder(order, state, top = songsAt)
                    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = screenPadding(extraTop = DetailTopGap)) {
                        item(key = "header") {
                            Header(names, onCrumb = { vm.goTo(it - 1) })
                        }
                        when (here) {
                            FolderStop.Top -> topLevel(known, vm::open)
                            is FolderStop.Phone -> phoneLevel(known, here, vm)
                            is FolderStop.Server -> serverLevel(here, level, gathering, note, vm)
                        }
                        songRows(sortedSongs, order, vm)
                    }
                }
                if (here is FolderStop.Server) LaunchedEffect(here.id) { vm.load(here.id) }
            }
        }
        BackButton { if (!vm.up()) onBack() }
    }
}

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
            FolderRow(sources.phoneName, songs(phone.root.songCount)) { open(FolderStop.Phone(emptyList())) }
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
    item(key = "buttons") {
        Buttons(
            details = listOfNotNull(
                node.folders.size.takeIf { it > 0 }?.let { if (it == 1) "1 folder" else "$it folders" },
                songs(node.songCount),
            ).joinToString(" • "),
            onPlay = { vm.playPhone(node, shuffle = false) },
            onShuffle = { vm.playPhone(node, shuffle = true) },
            enabled = node.songCount > 0,
        )
    }
    itemsIndexed(node.folders, key = { _, folder -> "folder:${folder.name}" }) { index, folder ->
        Column {
            if (index > 0) Separator()
            FolderRow(folder.name, songs(folder.songCount)) { vm.open(FolderStop.Phone(here.path + folder.name)) }
        }
    }
}

private fun LazyListScope.serverLevel(
    here: FolderStop.Server,
    state: LoadState<ServerLevel>?,
    gathering: Boolean,
    note: String?,
    vm: FoldersViewModel,
) {
    item(key = "buttons") {
        Buttons(
            details = null,
            onPlay = { vm.playServer(here.id, shuffle = false) },
            onShuffle = { vm.playServer(here.id, shuffle = true) },
            gathering = gathering,
            note = note,
        )
    }
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
        is LoadState.Ready -> {
            val level = state.data
            if (level.folders.isEmpty() && level.songs.isEmpty()) {
                item(key = "empty") { Quiet("Nothing in this folder.") }
            }
            // Keyed by place as well as id, since a server may list a folder twice.
            itemsIndexed(level.folders, key = { index, folder -> "folder:$index:${folder.id}" }) { index, folder ->
                Column {
                    if (index > 0) Separator()
                    FolderRow(folder.name.ifBlank { "Untitled folder" }, null) {
                        vm.open(FolderStop.Server(folder.id, folder.name))
                    }
                }
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

// The folder's name, the path to it, and its details.
@Composable
private fun Header(names: List<String>, onCrumb: (Int) -> Unit) {
    Column {
        ScreenTitle(names.last())
        if (names.size > 1) Breadcrumb(names, onCrumb)
    }
}

// The path as plain words; any but the last goes back there. It scrolls
// sideways when long, starting at the end.
@Composable
private fun Breadcrumb(names: List<String>, onCrumb: (Int) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(names.size) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 20.dp).padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        names.forEachIndexed { index, name ->
            if (index > 0) Text(" / ", style = OctoType.caption, color = OctoColors.TextMuted)
            val last = index == names.lastIndex
            Text(
                name,
                style = OctoType.caption,
                color = if (last) OctoColors.TextPrimary else OctoColors.TextSecondary,
                maxLines = 1,
                modifier = if (last) Modifier else Modifier.clickable(role = Role.Button) { onCrumb(index) },
            )
        }
    }
}

// Play and Shuffle for everything under the folder, as on an album page.
@Composable
private fun Buttons(
    details: String?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    enabled: Boolean = true,
    gathering: Boolean = false,
    note: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
        details?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(bottom = 14.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentButton("Play", onClick = onPlay, enabled = enabled && !gathering, loading = gathering)
            GlazeButton("Shuffle", onClick = onShuffle, enabled = enabled && !gathering)
        }
        note?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(top = 10.dp)) }
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
