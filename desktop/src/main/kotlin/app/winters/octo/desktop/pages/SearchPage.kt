package app.winters.octo.desktop.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlassField
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.Glyph
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.ProgressRing
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.lengthText
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.search.FetchPhase
import app.winters.octo.desktop.search.SearchFilter
import app.winters.octo.desktop.search.SearchState
import app.winters.octo.desktop.search.phaseText
import app.winters.octo.desktop.ui.FailedLine
import app.winters.octo.desktop.ui.LoadingLine
import app.winters.octo.desktop.ui.LocalBottomRoom
import app.winters.octo.desktop.ui.LocalPointer
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.SectionTitle
import app.winters.octo.desktop.ui.ShelfCardWidth
import app.winters.octo.desktop.ui.SongMenu
import app.winters.octo.desktop.ui.onRightClick
import app.winters.octo.desktop.ui.pagePadding
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.subsonic.Song

// Not in the library, in the phone app's words.
const val NotInLibraryText = "Not in your library"

// Search: the same filters as the phone, then the library's
// artists, albums, songs and playlists, and, from an Octo server that can
// fetch music, what it found online with a "+" to add each song.
@Composable
fun SearchPage(app: AppState, visit: Visit) {
    val model = app.search ?: return
    val list = rememberListState(app.navigator, visit)
    LazyColumn(state = list, contentPadding = pagePadding(LocalBottomRoom.current)) {
        // The field itself is in the title bar, on every page.
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.L)) {
                PageTitle("Search", detail = model.text.takeIf(String::isNotBlank)?.let { "Results for \"$it\"" })
                GlazeSegments(SearchFilter.entries, model.filter, { it.label }, model::pick)
            }
        }
        when (val state = model.state) {
            SearchState.Idle -> item(key = "idle") { Txt("Type at least two letters in the search field above.", OctoType.bodySmall, OctoColors.TextMuted, Modifier.padding(top = Space.Page)) }
            SearchState.Looking -> item(key = "looking") { LoadingLine("Searching") }
            is SearchState.Failed -> item(key = "failed") { FailedLine(state.message, model::again) }
            is SearchState.Done -> results(app, state)
        }
    }
}

private fun LazyListScope.results(app: AppState, state: SearchState.Done) {
    val model = app.search ?: return
    val library = state.found.library
    val outside = state.found.outside
    if (library.isEmpty && outside.isEmpty) {
        item(key = "nothing") { NothingHere("Nothing found", "Try other words, or fewer of them.") }
        return
    }
    fun seeAll(more: Boolean, filter: SearchFilter): String? = if (more && model.filter == SearchFilter.All) "See all" else null
    if (library.artists.isNotEmpty()) {
        item(key = "artists") {
            SectionTitle("Artists", action = seeAll(library.moreArtists, SearchFilter.Artists)) { model.pick(SearchFilter.Artists) }
            CardRow(library.artists.size) { i -> ArtistCard(app, library.artists[i]) }
        }
    }
    if (library.albums.isNotEmpty()) {
        item(key = "albums") {
            SectionTitle("Albums", action = seeAll(library.moreAlbums, SearchFilter.Albums)) { model.pick(SearchFilter.Albums) }
            CardRow(library.albums.size) { i -> AlbumCard(app, library.albums[i]) }
        }
    }
    if (library.songs.isNotEmpty()) {
        item(key = "songs-title") { SectionTitle("Songs", action = seeAll(library.moreSongs, SearchFilter.Songs)) { model.pick(SearchFilter.Songs) } }
        itemsIndexed(library.songs, key = { i, s -> "song:$i:${s.id}" }) { index, song -> ResultSong(app, library.songs, index, song, outside = false) }
    }
    if (library.playlists.isNotEmpty()) {
        item(key = "playlists-title") { SectionTitle("Playlists", action = seeAll(library.morePlaylists, SearchFilter.Playlists)) { model.pick(SearchFilter.Playlists) } }
        items(library.playlists, key = { "pl:${it.id}" }) { playlist ->
            Row(
                Modifier.fillMaxWidth().height(44.dp).hoverLift(RoundedCornerShape(8.dp)).clickable { app.navigator.go(Page.Playlist(playlist.id)) }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Glyph(OctoIcons.Playlists, size = 18.dp, tint = OctoColors.TextSecondary)
                Txt(playlist.name, OctoType.bodySmall, modifier = Modifier.weight(1f))
                Txt("${playlist.songCount} songs", OctoType.caption, OctoColors.TextMuted)
            }
        }
    }
    if (!outside.isEmpty) {
        item(key = "outside-title") {
            Column {
                SectionTitle(NotInLibraryText)
                Txt("Found online by your server. Press + to add a song to your library.", OctoType.caption, OctoColors.TextMuted, Modifier.padding(bottom = 8.dp))
            }
        }
        if (outside.songs.isNotEmpty()) {
            itemsIndexed(outside.songs, key = { i, s -> "out:$i:${s.id}" }) { index, song -> ResultSong(app, outside.songs, index, song, outside = true) }
        }
        if (outside.albums.isNotEmpty()) {
            item(key = "out-albums") {
                Txt("Albums", OctoType.label, OctoColors.TextSecondary, Modifier.padding(top = 16.dp, bottom = 4.dp))
                CardRow(outside.albums.size) { i -> AlbumCard(app, outside.albums[i], outside = true) }
            }
        }
        if (outside.artists.isNotEmpty()) {
            item(key = "out-artists") {
                Txt("Artists", OctoType.label, OctoColors.TextSecondary, Modifier.padding(top = 16.dp, bottom = 4.dp))
                CardRow(outside.artists.size) { i -> ArtistCard(app, outside.artists[i], outside = true) }
            }
        }
    }
}

// A row of cards, as many as fit on one line.
@Composable
private fun CardRow(count: Int, card: @Composable (Int) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fit = (maxWidth / ShelfCardWidth).toInt().coerceAtLeast(1)
        Row(Modifier.fillMaxWidth()) {
            (0 until minOf(fit, count)).forEach { i -> Box(Modifier.width(ShelfCardWidth)) { card(i) } }
        }
    }
}

// One song in the results: a double click plays the results from it, a
// right click opens the song menu. A song found online has the "+" in place
// of a heart.
@Composable
private fun ResultSong(app: AppState, songs: List<Song>, index: Int, song: Song, outside: Boolean) {
    val pointer = LocalPointer.current
    val clicks = androidx.compose.runtime.remember { longArrayOf(0L) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .hoverLift(RoundedCornerShape(8.dp), clickable = false)
            .onRightClick { app.popups.showAt(pointer.point) { close -> SongMenu(app, listOf(song), close, outside = outside) } }
            .clickable {
                val now = System.currentTimeMillis()
                if (now - clicks[0] < 400) app.play(songs, index)
                clicks[0] = now
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(song.coverArt, Modifier.size(38.dp), shape = RoundedCornerShape(6.dp), online = outside, placeholder = OctoIcons.Songs)
        Column(Modifier.weight(1f)) {
            Txt(song.title, OctoType.bodySmall)
            Txt(listOfNotNull(song.displayArtist ?: song.artist, song.album).joinToString(" · "), OctoType.caption, OctoColors.TextMuted)
        }
        Txt(lengthText(song.duration), OctoType.caption, OctoColors.TextMuted, Modifier.width(52.dp))
        if (outside) FetchButton(app, song)
    }
}

// The "+" that has the server add a song found online to the library: a
// ring fills while it downloads and a check shows once it is in. A failed
// one shows why and can be tried again.
@Composable
private fun FetchButton(app: AppState, song: Song) {
    val fetches = app.fetches ?: return
    val phases by fetches.phases.collectAsState()
    val phase = phases[song.id] ?: FetchPhase.None
    val canAsk = phase == FetchPhase.None || phase is FetchPhase.Failed
    Box(
        Modifier
            .size(36.dp)
            .hoverLift(CircleShape, clickable = canAsk)
            .clickable(enabled = canAsk) { fetches.request(song.id) }
            .semantics {
                contentDescription = "Add to your library"
                stateDescription = phaseText(phase)
            },
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            FetchPhase.None -> Glyph(OctoIcons.AddToLibrary, size = 22.dp, tint = OctoColors.TextPrimary.copy(alpha = 0.7f))
            FetchPhase.Queued -> ProgressRing(null, size = 20.dp)
            is FetchPhase.Downloading -> ProgressRing(phase.progress, size = 20.dp)
            FetchPhase.Adding -> ProgressRing(1f, size = 20.dp)
            FetchPhase.Done -> Glyph(OctoIcons.Check, size = 20.dp, tint = OctoColors.Accent)
            is FetchPhase.Failed -> Glyph(OctoIcons.Info, size = 20.dp, tint = OctoColors.Error)
        }
    }
    if (phase is FetchPhase.Failed) Txt("Couldn't add it: ${phase.reason}. Press to try again.", OctoType.caption, OctoColors.TextMuted, Modifier.width(180.dp), maxLines = 2)
}
