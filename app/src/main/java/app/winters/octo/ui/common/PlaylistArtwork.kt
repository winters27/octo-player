package app.winters.octo.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.covers.CoverSpec
import app.winters.octo.covers.PLAYLIST_COVER_LINE
import app.winters.octo.covers.PlaylistCoverStyle
import app.winters.octo.covers.coverArtKey
import app.winters.octo.covers.backgroundColour
import app.winters.octo.covers.chooseBackground
import app.winters.octo.covers.coverPaletteKey
import app.winters.octo.covers.coverSide
import app.winters.octo.covers.playlistCoverFooter
import app.winters.octo.design.ArtworkShape
import app.winters.octo.design.OctoColors
import app.winters.octo.design.artworkRim
import app.winters.octo.playlists.PlaylistArt
import app.winters.octo.playlists.PlaylistArtEntry
import dagger.hilt.android.EntryPointAccessors

// Covers smaller than this are drawn without the rim, as other artwork is.
private val RimFrom = 96.dp

@Composable
private fun playlistArt(): PlaylistArt {
    val context = LocalContext.current
    return remember(context) { EntryPointAccessors.fromApplication(context.applicationContext, PlaylistArtEntry::class.java).playlistArt() }
}

// Whether playlists show designed covers or their album mosaics.
@Composable
fun playlistCoverStyle(): PlaylistCoverStyle {
    val art = playlistArt()
    val style by art.settings.style.collectAsStateWithLifecycle(PlaylistCoverStyle.Designed)
    return style
}

// A playlist's or live list's picture, as the listener chose: designed from
// its name (`line` says what it is, `footer` how big) on colours from its
// covers, or `mosaic`, its covers in a square.
@Composable
fun PlaylistArtwork(
    id: String,
    name: String,
    covers: List<String>,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = ArtworkShape,
    line: String? = PLAYLIST_COVER_LINE,
    footer: String? = null,
    mosaic: @Composable () -> Unit,
) {
    if (playlistCoverStyle() == PlaylistCoverStyle.Mosaic) {
        mosaic()
        return
    }
    val art = playlistArt()
    val side = coverSide(with(LocalDensity.current) { size.roundToPx() })
    val paletteKey = coverPaletteKey("phone", covers)
    val palette by produceState(art.knownPalette(paletteKey), paletteKey) {
        if (value == null) value = art.palette(id, covers, paletteKey)
    }
    val spec = palette?.let { CoverSpec(id, name, line, footer, it) }
    val key = spec?.let { coverArtKey(it, side) }
    // The last picture stays up while a changed one is drawn.
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    val ready = key?.let(art::known)
    LaunchedEffect(key) {
        if (spec != null && ready == null) shown = art.art(spec, side)
    }
    val ground = remember(spec?.id, palette) {
        spec?.let { s -> Color(backgroundColour(chooseBackground(s.id, s.palette))) } ?: OctoColors.BackgroundTertiary
    }
    Box(modifier.size(size).clip(shape).background(ground)) {
        (ready ?: shown)?.let {
            Image(it, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.High)
        }
        if (size >= RimFrom) Box(Modifier.matchParentSize().artworkRim(shape))
    }
}

// The foot line for one of the phone's own playlists: how many songs.
fun phonePlaylistFooter(songCount: Int): String? = playlistCoverFooter(songCount, null, null)
