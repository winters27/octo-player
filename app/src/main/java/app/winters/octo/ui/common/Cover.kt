package app.winters.octo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import app.winters.octo.design.ArtworkShape
import app.winters.octo.design.OctoColors
import app.winters.octo.design.artworkRim
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

@Composable
fun Cover(
    coverId: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = ArtworkShape,
) {
    val client = LocalSubsonic.current
    val context = LocalPlatformContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    // One image serves every size in its bucket.
    val bucket = listOf(150, 300, 600, 1200).firstOrNull { it >= px } ?: 1200
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(OctoColors.BackgroundTertiary),
    ) {
        if (coverId != null) {
            // The address is signed with a new salt every time, so the
            // cache is keyed by what the picture is, not where it came from.
            val key = "cover:${client.baseUrl.host}:$coverId:$bucket"
            val request = remember(key) {
                ImageRequest.Builder(context)
                    .data(client.coverArtUrl(coverId, bucket).toString())
                    .memoryCacheKey(key)
                    .diskCacheKey(key)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        // The rim sits on top of the picture, so it is drawn last.
        Box(Modifier.matchParentSize().artworkRim(shape))
    }
}
