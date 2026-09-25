package app.winters.octo.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.ArtistRow
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.Segments
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.indexLetter
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.ArtistRoute

private val segments = listOf("Albums", "Artists", "Songs")

@Composable
fun LibraryScreen(onOpen: (NavKey) -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    var segment by rememberSaveable { mutableIntStateOf(0) }
    val bottom = PaddingValues(
        top = 12.dp,
        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 120.dp,
    )

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp)) {
        ScreenTitle("Library")
        Segments(segments, segment, { segment = it }, Modifier.padding(horizontal = 20.dp))
        when (segment) {
            0 -> {
                val albums by vm.albums.collectAsStateWithLifecycle()
                Loaded(albums) { list ->
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = bottom.calculateTopPadding(),
                            bottom = bottom.calculateBottomPadding(),
                        ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(list, key = { it.id }) { album ->
                            AlbumCard(album, onClick = { onOpen(AlbumRoute(album.id)) }, width = null)
                        }
                    }
                }
            }
            1 -> {
                val artists by vm.artists.collectAsStateWithLifecycle()
                Loaded(artists) { list ->
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
                        lettered(list, { it.sortKey }, { it.id }) { artist ->
                            ArtistRow(artist) { onOpen(ArtistRoute(artist.id)) }
                        }
                    }
                }
            }
            else -> {
                val songs by vm.songs.collectAsStateWithLifecycle()
                Loaded(songs) { list ->
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
                        lettered(list, { it.sortKey }, { it.id }) { SongRow(it) }
                    }
                }
            }
        }
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
