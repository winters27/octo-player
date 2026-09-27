package app.winters.octo.desktop.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.winters.octo.design.ArtworkShape
import app.winters.octo.design.Glyph
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.artworkRim
import app.winters.octo.subsonic.SubsonicClient
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File

// Covers are asked for at one of these sizes, so one download serves every
// size up to it, as on the phone.
private val Buckets = listOf(150, 300, 600, 1200)

fun coverBucket(px: Int): Int = Buckets.firstOrNull { it >= px } ?: Buckets.last()

// Covers of songs found online used to come with a mark the server drew in
// a corner. Octo sends them plain to a client named "Octo", so they are kept
// under keys of their own, as on the phone.
const val ONLINE_COVER_VERSION = 2

// What a cover is cached under: its server and id and size, never the
// address, which is signed afresh for every request.
fun coverKey(host: String, coverId: String, bucket: Int, online: Boolean = false): String =
    if (online) "online-cover:v$ONLINE_COVER_VERSION:$host:$coverId:$bucket" else "cover:$host:$coverId:$bucket"

// The client covers come from, set once signed in.
val LocalCovers = staticCompositionLocalOf<SubsonicClient?> { null }

// The image loader for the whole app: the app's own HTTP client, a memory
// cache, and a disk cache in the app's cache folder.
fun coverLoader(context: PlatformContext, http: OkHttpClient, cacheDir: File): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(File(cacheDir, "covers").toOkioPath())
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()

// A cover from the signed-in server, filling its box, with the faint rim
// the phone draws. With no cover it shows the tile with a quiet glyph.
@Composable
fun Cover(
    coverId: String?,
    modifier: Modifier = Modifier,
    shape: Shape = ArtworkShape,
    online: Boolean = false,
    placeholder: ImageVector = OctoIcons.Album,
) {
    val client = LocalCovers.current
    val context = LocalPlatformContext.current
    BoxWithConstraints(modifier.clip(shape).background(OctoColors.BackgroundTertiary), contentAlignment = Alignment.Center) {
        val px = with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() }
        if (coverId == null || client == null) {
            Glyph(placeholder, size = (maxWidth * 0.36f).coerceIn(12.dp, 64.dp), tint = OctoColors.TextMuted)
        } else {
            val bucket = coverBucket(px)
            val key = coverKey(client.primaryUrl.host, coverId, bucket, online)
            val request = remember(key) {
                ImageRequest.Builder(context)
                    .data(client.coverArtUrl(coverId, bucket).toString())
                    .memoryCacheKey(key)
                    .diskCacheKey(key)
                    .build()
            }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.matchParentSize().artworkRim(shape))
    }
}
