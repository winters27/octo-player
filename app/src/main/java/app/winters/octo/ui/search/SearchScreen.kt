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
import androidx.compose.foundation.lazy.LazyListScope
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
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.GlassInput
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.ArtistCircle
import app.winters.octo.ui.common.DownloadButton
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.nav.AlbumRoute
import app.winters.octo.ui.nav.ArtistRoute
import app.winters.octo.ui.nav.OnlineAlbumRoute
import app.winters.octo.ui.nav.OnlineArtistRoute

@Composable
fun SearchScreen(onOpen: (NavKey) -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val results by vm.results.collectAsStateWithLifecycle()
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val discover by vm.discover.collectAsStateWithLifecycle()
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
        val online = discover
        // Nothing to show online: not looking, or looked and found nothing.
        val nothingOnline = online is DiscoverState.Idle || (online is DiscoverState.Done && online.found.isEmpty)
        when {
            found == null -> Hint(if (signedIn) "Search your music and discover more" else "Search the music on your phone")
            found.isEmpty && nothingOnline -> Hint("No results for \"${vm.text.trim()}\"")
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
                // The server could not be asked, so say the library has none.
                if (found.isEmpty && online is DiscoverState.Failed) {
                    item { Hint("No results for \"${vm.text.trim()}\"") }
                }
                if (!nothingOnline) discoverSection(online, onOpen, onPlay = vm::playFound, top = !found.isEmpty)
            }
        }
    }
}

// What the server has beyond the library: songs, then albums, then artists.
// While it looks, or when it cannot be reached, a quiet line says so.
private fun LazyListScope.discoverSection(
    online: DiscoverState,
    onOpen: (NavKey) -> Unit,
    onPlay: (TrackEntity) -> Unit,
    top: Boolean,
) {
    item(key = "discover") { SectionTitle("Discover", if (top) Modifier.padding(top = 12.dp) else Modifier) }
    when (online) {
        DiscoverState.Loading -> item(key = "discover:looking") { QuietLine("Looking online") }
        DiscoverState.Failed -> item(key = "discover:failed") { QuietLine("Could not reach your server") }
        is DiscoverState.Done -> {
            val found = online.found
            if (found.songs.isNotEmpty()) item(key = "discover:songs:title") { SubTitle("Songs") }
            items(found.songs, key = { "discover:${it.id}" }) { track ->
                SongRow(track, trailing = { DownloadButton(track, size = 40.dp, iconSize = 22.dp) }) { onPlay(track) }
            }
            if (found.albums.isNotEmpty()) {
                item(key = "discover:albums:title") { SubTitle("Albums") }
                item(key = "discover:albums") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(found.albums, key = { it.id }) { album ->
                            AlbumCard(album, onClick = { onOpen(OnlineAlbumRoute(album.id)) })
                        }
                    }
                }
            }
            if (found.artists.isNotEmpty()) {
                item(key = "discover:artists:title") { SubTitle("Artists") }
                item(key = "discover:artists") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(found.artists, key = { it.id }) { artist ->
                            ArtistCircle(artist) { onOpen(OnlineArtistRoute(artist.id)) }
                        }
                    }
                }
            }
        }
        DiscoverState.Idle -> Unit
    }
}

// Names each kind of find under Discover, quieter than a section title.
@Composable
private fun SubTitle(text: String) {
    Text(
        text,
        style = OctoType.bodySmall,
        color = OctoColors.TextMuted,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
    )
}

@Composable
private fun QuietLine(text: String) {
    Text(
        text,
        style = OctoType.bodySmall,
        color = OctoColors.TextMuted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
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
