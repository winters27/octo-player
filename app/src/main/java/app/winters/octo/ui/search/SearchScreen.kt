package app.winters.octo.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.winters.octo.design.GlassInput
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.ArtistCircle
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.ArtistRoute

@Composable
fun SearchScreen(onOpen: (NavKey) -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val results by vm.results.collectAsStateWithLifecycle()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 120.dp

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp)) {
        ScreenTitle("Search")
        GlassInput(
            value = vm.text,
            onValueChange = { vm.text = it },
            placeholder = "Songs, albums, artists",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        val found = results
        when {
            found == null -> Hint("Search the music on your phone")
            found.isEmpty -> Hint("No results for \"${vm.text.trim()}\"")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp, bottom = bottom),
            ) {
                if (found.artists.isNotEmpty()) {
                    item { SectionTitle("Artists") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(found.artists, key = { it.id }) { artist ->
                                ArtistCircle(artist) { onOpen(ArtistRoute(artist.id)) }
                            }
                        }
                    }
                }
                if (found.albums.isNotEmpty()) {
                    item { SectionTitle("Albums", Modifier.padding(top = 12.dp)) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(found.albums, key = { it.id }) { album ->
                                AlbumCard(album, onClick = { onOpen(AlbumRoute(album.id)) })
                            }
                        }
                    }
                }
                if (found.songs.isNotEmpty()) {
                    item { SectionTitle("Songs", Modifier.padding(top = 12.dp)) }
                    items(found.songs, key = { it.id }) { track -> SongRow(track) { vm.playSong(track) } }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = OctoType.bodySmall,
        color = OctoColors.TextMuted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
    )
}
