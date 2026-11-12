package app.winters.octo.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
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
import app.winters.octo.covers.CoverGlyph
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
// covers, or `mosaic`, its covers in a square. `glyph` marks one of Octo's
// own lists, drawn in place of the words.
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
    glyph: CoverGlyph? = null,
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
    val spec = palette?.let { CoverSpec(id, name, line, footer, it, glyph = glyph) }
    val key = spec?.let { coverArtKey(it, side) }
    // The last picture stays up while a changed one is drawn.
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    val ready = key?.let(art::known)
    LaunchedEffect(key) {
        if (spec != null && ready != null) art.shown(spec, ready)
        if (spec != null && ready == null) shown = art.art(spec, side)
    }
    // Until its own is drawn, the list's picture as last shown anywhere (its
    // thumbnail), so a page opens on the same art as its row.
    val standIn = remember(id, name, line) { art.standIn(id, name, line) }
    val picture = ready ?: shown
    // A picture that arrives after the cover is up fades in over what was
    // there; one ready at once shows at once.
    val still = LocalReduceMotion.current
    val fade = remember { Animatable(if (picture != null) 1f else 0f) }
    LaunchedEffect(picture != null) {
        if (picture != null && fade.value < 1f) {
            if (still) fade.snapTo(1f) else fade.animateTo(1f, tween(ArtFadeMs))
        }
    }
    val fallback = OctoColors.BackgroundTertiary
    val ground = remember(spec?.id, palette, fallback) {
        spec?.let { s -> Color(backgroundColour(chooseBackground(s.id, s.palette))) } ?: fallback
    }
    Box(modifier.size(size).clip(shape).background(ground)) {
        if (standIn != null && standIn !== picture) {
            Image(standIn, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.High)
        }
        picture?.let {
            Image(
                it,
                contentDescription = null,
                modifier = Modifier.matchParentSize().graphicsLayer { alpha = fade.value },
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.High,
            )
        }
        if (size >= RimFrom) Box(Modifier.matchParentSize().artworkRim(shape))
    }
}

// How long a cover's art takes to fade in over its stand-in.
const val ArtFadeMs = 200

// The foot line for one of the phone's own playlists: how many songs.
fun phonePlaylistFooter(songCount: Int): String? = playlistCoverFooter(songCount, null, null)
