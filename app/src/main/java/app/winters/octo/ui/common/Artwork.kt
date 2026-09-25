package app.winters.octo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.design.ArtworkShape
import app.winters.octo.design.OctoColors
import app.winters.octo.design.artworkRim
import coil3.compose.AsyncImage

// A picture from the catalog, whatever its source, on a quiet tile until
// it arrives. Music with no artwork keeps the tile.
@Composable
fun Artwork(
    ref: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = ArtworkShape,
) {
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(OctoColors.BackgroundTertiary),
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
        // The rim sits on top of the picture, so it is drawn last.
        Box(Modifier.matchParentSize().artworkRim(shape))
    }
}

// Artwork that fills the width it is given, square.
@Composable
fun ArtworkFill(ref: String?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1f)) {
        Artwork(ref, maxWidth)
    }
}
