package app.winters.octo.ui.library

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.ArtistRow
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.indexLetter
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.AlbumsRoute
import app.winters.octo.ui.nav.ArtistRoute
import app.winters.octo.ui.nav.ArtistsRoute
import app.winters.octo.ui.nav.DownloadsRoute
import app.winters.octo.ui.nav.FoldersRoute
import app.winters.octo.ui.nav.GenresRoute
import app.winters.octo.ui.nav.PlaylistsRoute
import app.winters.octo.ui.nav.SongsRoute

// One way into the library, like Albums or Songs.
private class Section(@DrawableRes val icon: Int, val label: String, val route: NavKey)

private val sections = listOf(
    Section(OctoIcons.Playlists, "Playlists", PlaylistsRoute),
    Section(OctoIcons.Artist, "Artists", ArtistsRoute),
    Section(OctoIcons.Album, "Albums", AlbumsRoute),
    Section(OctoIcons.Songs, "Songs", SongsRoute),
    Section(OctoIcons.Genres, "Genres", GenresRoute),
    Section(OctoIcons.Folder, "Folders", FoldersRoute),
    Section(OctoIcons.Downloaded, "Downloads", DownloadsRoute),
)

// The library's front page: a menu of ways in, then the newest albums,
// two covers to a row.
@Composable
fun LibraryScreen(onOpen: (NavKey) -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val recent by vm.recent.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding()) {
        item { ScreenTitle("Library") }
        sections.forEachIndexed { index, section ->
            item(key = section.label) {
                Column {
                    if (index > 0) Separator()
                    SectionRow(section) { onOpen(section.route) }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle("Recently added", Modifier.padding(top = 24.dp)) }
            items(recent.chunked(2), key = { pair -> pair.first().id }) { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    pair.forEach { album ->
                        AlbumCard(album, onClick = { onOpen(AlbumRoute(album.id)) }, modifier = Modifier.weight(1f), width = null)
                    }
                    // An odd last album keeps its half width.
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SectionRow(section: Section, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .height(52.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(section.icon), contentDescription = null, tint = OctoColors.Accent, modifier = Modifier.size(24.dp))
        Text(section.label, style = OctoType.body, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
        Icon(painterResource(OctoIcons.Chevron), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(22.dp))
    }
}

// A hairline between menu rows, starting under the labels.
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

// Every album, as a grid of covers.
@Composable
fun AlbumsScreen(onOpen: (NavKey) -> Unit, onBack: () -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    LibraryPage("Albums", onBack) {
        Loaded(albums) { list ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = ListPadding,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(list, key = { it.id }) { album ->
                    AlbumCard(album, onClick = { onOpen(AlbumRoute(album.id)) }, width = null)
                }
            }
        }
    }
}

// Every artist, A to Z.
@Composable
fun ArtistsScreen(onOpen: (NavKey) -> Unit, onBack: () -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val artists by vm.artists.collectAsStateWithLifecycle()
    LibraryPage("Artists", onBack) {
        Loaded(artists) { list ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = RowsPadding) {
                lettered(list, { it.sortKey }, { it.id }) { artist ->
                    ArtistRow(artist) { onOpen(ArtistRoute(artist.id)) }
                }
            }
        }
    }
}

// Every song, A to Z. A tap plays the list from that song.
@Composable
fun SongsScreen(onBack: () -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    LibraryPage("Songs", onBack) {
        Loaded(songs) { list ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = RowsPadding) {
                lettered(list, { it.sortKey }, { it.id }) { track -> SongRow(track) { vm.playSong(track) } }
            }
        }
    }
}

// Grids keep side margins; lists of rows run edge to edge. Both leave room
// at the bottom for the floating bar.
private val ListPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 140.dp)
private val RowsPadding = PaddingValues(top = 4.dp, bottom = 140.dp)

// A page inside the library: the back button, a title, then its list.
@Composable
private fun LibraryPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Spacer(Modifier.height(DetailTopGap))
            ScreenTitle(title)
            content()
        }
        BackButton(onBack)
    }
}

// Shows a spinner until the first read, a note if there is nothing, and
// the list otherwise.
@Composable
private fun <T> Loaded(items: List<T>?, content: @Composable (List<T>) -> Unit) {
    when {
        items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = OctoColors.Accent, modifier = Modifier.size(28.dp))
        }
        items.isEmpty() -> Text(
            "Nothing here yet",
            style = OctoType.bodySmall,
            color = OctoColors.TextMuted,
            modifier = Modifier.padding(20.dp),
        )
        else -> content(items)
    }
}

// A list broken up under sticky letter headings.
private fun <T> LazyListScope.lettered(
    list: List<T>,
    sortKey: (T) -> String,
    key: (T) -> String,
    row: @Composable (T) -> Unit,
) {
    list.groupBy { indexLetter(sortKey(it)) }.forEach { (letter, group) ->
        stickyHeader(key = "letter:$letter") {
            Text(
                letter.toString(),
                style = OctoType.caption.copy(fontWeight = FontWeight.Bold),
                color = OctoColors.TextMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OctoColors.Background)
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        items(group, key = key) { row(it) }
    }
}
