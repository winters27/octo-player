package app.winters.octo.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import app.winters.octo.covers.CoverSpec
import app.winters.octo.covers.LIVE_LIST_COVER_LINE
import app.winters.octo.covers.PLAYLIST_COVER_LINE
import app.winters.octo.covers.PlaylistCoverStyle
import app.winters.octo.covers.STATION_COVER_LINE
import app.winters.octo.covers.coverArtKey
import app.winters.octo.covers.backgroundColour
import app.winters.octo.covers.chooseBackground
import app.winters.octo.covers.coverPaletteKey
import app.winters.octo.covers.coverSide
import app.winters.octo.covers.coverSources
import app.winters.octo.covers.playlistCoverFooter
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.artworkRim
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.CoverOrder
import app.winters.octo.desktop.library.LibraryState
import app.winters.octo.desktop.library.LocalCovers
import app.winters.octo.desktop.liveListSongs
import app.winters.octo.livelists.LiveList
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.PlaylistWithSongs
import app.winters.octo.subsonic.RadioStation
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Whether playlists show designed covers or their album mosaics, as set in
// Settings > Appearance.
@Composable
fun playlistCoverStyle(app: AppState): PlaylistCoverStyle {
    val settings by app.settings.state.collectAsState()
    return settings.appearance.playlistCovers
}

// A designed cover filling its box, drawn at the size shown. Until it is
// ready the box holds the colour it will open with, so nothing flashes.
@Composable
fun DesignedCover(app: AppState, order: CoverOrder, modifier: Modifier, shape: Shape) {
    val client = LocalCovers.current
    BoxWithConstraints(modifier.clip(shape)) {
        val px = with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() }
        val side = coverSide(px)
        val store = app.playlistArt
        val paletteKey = coverPaletteKey(client?.primaryUrl?.host.orEmpty(), order.sources + listOfNotNull(order.stamp))
        val palette by produceState(store.knownPalette(paletteKey), paletteKey, client) {
            if (value == null) value = store.palette(client, order, paletteKey)
        }
        val spec = palette?.let { CoverSpec(order.id, order.name, order.line, order.footer, it) }
        val key = spec?.let { coverArtKey(it, side) }
        // The last picture stays up while a changed one is drawn.
        var shown by remember { mutableStateOf<ImageBitmap?>(null) }
        val ready = key?.let(store::known)
        LaunchedEffect(key) {
            if (spec != null && ready == null) shown = store.art(spec, side)
        }
        val ground = remember(spec?.id, palette) {
            spec?.let { s -> Color(backgroundColour(chooseBackground(s.id, s.palette))) } ?: OctoColors.BackgroundTertiary
        }
        Box(Modifier.matchParentSize().background(ground))
        (ready ?: shown)?.let {
            Image(it, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.High)
        }
        Box(Modifier.matchParentSize().artworkRim(shape))
    }
}

// A playlist's order: its name, "Playlist", and its size or whose it is,
// its colours from the server's picture of its first albums.
fun playlistOrder(playlist: Playlist, you: String?) = CoverOrder(
    playlist.id,
    playlist.name,
    PLAYLIST_COVER_LINE,
    playlistCoverFooter(playlist.songCount, playlist.owner, you),
    listOfNotNull(playlist.coverArt),
    quarters = true,
    stamp = playlist.changed,
)

// A playlist read with its songs, as the list of playlists has it.
fun PlaylistWithSongs.summary() = Playlist(id, name, comment, owner, public, songCount, duration, coverArt, changed, readonly, created)

// A playlist's picture, as the listener chose: designed, or the server's mosaic.
@Composable
fun PlaylistPicture(app: AppState, playlist: Playlist, modifier: Modifier, shape: Shape) {
    if (playlistCoverStyle(app) == PlaylistCoverStyle.Mosaic) {
        Cover(playlist.coverArt, modifier, shape = shape, placeholder = OctoIcons.Playlists)
    } else {
        DesignedCover(app, playlistOrder(playlist, app.connection?.client?.username), modifier, shape)
    }
}

// A live list's covers for its colours, from the songs it picks now.
fun liveListOrder(list: LiveList, songs: List<Song>) =
    CoverOrder(list.id, list.name, LIVE_LIST_COVER_LINE, null, coverSources(songs.map { it.albumId to it.coverArt }))

// A live list's picture: designed from its songs, or, as a mosaic, its own
// mark (`mosaic`, which the caller draws).
@Composable
fun LiveListPicture(app: AppState, list: LiveList, modifier: Modifier, shape: Shape, songs: List<Song>? = null, mosaic: @Composable () -> Unit) {
    if (playlistCoverStyle(app) == PlaylistCoverStyle.Mosaic) {
        mosaic()
        return
    }
    // Worked out again once the library is read, and whenever it changes.
    val index = app.library?.let { store -> (store.state.collectAsState().value as? LibraryState.Ready)?.index }
    val picked by produceState(songs, list, index, songs) {
        if (songs == null) value = withContext(Dispatchers.Default) { app.liveListSongs(list) }
    }
    val found = picked
    if (found == null) {
        Box(modifier.clip(shape).background(OctoColors.BackgroundTertiary))
        return
    }
    DesignedCover(app, liveListOrder(list, found), modifier, shape)
}

// A station Octo runs: designed, with its name, when covers are designed;
// the server's picture otherwise.
@Composable
fun StationPicture(app: AppState, station: RadioStation, modifier: Modifier, shape: Shape) {
    if (playlistCoverStyle(app) == PlaylistCoverStyle.Mosaic) {
        Cover(station.coverArt ?: station.id, modifier, shape = shape)
    } else {
        DesignedCover(app, CoverOrder(station.id, station.name, STATION_COVER_LINE, null, listOfNotNull(station.coverArt ?: station.id), quarters = true), modifier, shape)
    }
}
