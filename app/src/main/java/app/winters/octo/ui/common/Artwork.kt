package app.winters.octo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.design.ArtworkShape
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoShapes
import app.winters.octo.design.artworkRim
import coil3.compose.AsyncImage

// Covers smaller than this are drawn without the rim.
private val RimMinSize = 96.dp

// A picture from the catalog, whatever its source, on a quiet tile until
// it arrives. Music with no artwork, or whose cover cannot be had (the
// server has none, or no connection), keeps the tile: a failed picture
// draws nothing and is not asked for again until it is shown again.
// `outside` is for a song, album or artist not in the library: it gets a
// small mark in the corner, and says so to TalkBack. Large covers at the
// top of a page leave it off and say it in words instead.
@Composable
fun Artwork(
    ref: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = ArtworkShape,
    outside: Boolean = false,
) {
    val description = artworkDescription(null, outside)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(OctoColors.BackgroundTertiary)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        val model = remember(ref) { ArtworkRef.decode(ref) }
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        // The rim sits on top of the picture, so it is drawn last. Small
        // covers go without: on a thumbnail it reads as a frame cutting in.
        if (size >= RimMinSize) Box(Modifier.matchParentSize().artworkRim(shape))
        if (outside) NotInLibraryMark(size, round = shape == CircleShape)
    }
}

// Artwork that fills the width it is given, square, with the rounder
// corners a card's picture takes.
@Composable
fun ArtworkFill(ref: String?, modifier: Modifier = Modifier, outside: Boolean = false) {
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1f)) {
        Artwork(ref, maxWidth, shape = OctoShapes.ArtM, outside = outside)
    }
}
