package app.winters.octo.desktop.library

import app.winters.octo.design.LocalReduceMotion
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
import androidx.compose.ui.graphics.FilterQuality
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
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.crossfade
import coil3.size.Scale
import coil3.size.pxOrElse
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.CubicResampler
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import java.io.File

// Covers are asked for at one of these sizes, so one download serves every
// size up to it, as on the phone.
private val Buckets = listOf(150, 300, 600, 1200)

fun coverBucket(px: Int): Int = Buckets.firstOrNull { it >= px } ?: Buckets.last()

// How big a cover drawn `px` wide is fetched: a small one at twice that,
// so it stays sharp shrunk and on a sharper screen; a large one at its
// own size, which is already plenty.
fun coverFetchPx(px: Int): Int = maxOf(px, minOf(px * 2, SharpUpTo))

private const val SharpUpTo = 300

// Covers of songs found online used to come with a mark the server drew in
// a corner. Octo sends them plain to a client named "Octo", so they are kept
// under keys of their own, as on the phone. 3: the server sends them at
// full size, where it had sent soft 600 pixel ones.
const val ONLINE_COVER_VERSION = 3

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
        .components {
            add(SmoothCoverDecoder.Factory())
            add(OkHttpNetworkFetcherFactory(callFactory = { http }))
        }
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
        // The glyph sits under the picture, so a cover the server has none
        // for (or that fails) still shows what it stands for.
        Glyph(placeholder, size = (maxWidth * 0.36f).coerceIn(12.dp, 64.dp), tint = OctoColors.TextMuted)
        if (coverId != null && client != null) {
            val bucket = coverBucket(coverFetchPx(px))
            val key = coverKey(client.primaryUrl.host, coverId, bucket, online)
            // With motion reduced a cover is simply there, without fading in.
            val still = LocalReduceMotion.current
            val request = remember(key, still) {
                ImageRequest.Builder(context)
                    .data(client.coverArtUrl(coverId, bucket).toString())
                    .memoryCacheKey(key)
                    .diskCacheKey(key)
                    .crossfade(!still)
                    .build()
            }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.Medium)
        }
        Box(Modifier.matchParentSize().artworkRim(shape))
    }
}

// Shrinks a cover to the size it is drawn at by averaging its pixels:
// halved again and again, each pixel the mean of four, then a smooth last
// step. The stock decoder keeps only the nearest pixel, so a small cover
// came out jagged, and a playlist's four-cover mosaic lost its pictures.
// Never enlarges.
class SmoothCoverDecoder(private val source: ImageSource, private val options: Options) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val image = Image.makeFromEncoded(bytes)
        val (width, height) = shrunkSize(image.width, image.height, options)
        val bitmap = shrink(image, width, height)
        return DecodeResult(bitmap.asImage(), isSampled = width < image.width || height < image.height)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder =
            SmoothCoverDecoder(result.source, options)
    }
}

// `image` at `width` by `height`, closing it.
private fun shrink(image: Image, width: Int, height: Int): Bitmap {
    var current = image
    while (current.width >= width * 2 && current.height >= height * 2) {
        val half = draw(current, (current.width + 1) / 2, (current.height + 1) / 2, FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE))
        current.close()
        current = Image.makeFromBitmap(half)
    }
    val done = draw(current, width, height, CubicResampler(1f / 3, 1f / 3))
    current.close()
    done.setImmutable()
    return done
}

private fun draw(image: Image, width: Int, height: Int, sampling: SamplingMode): Bitmap {
    val bitmap = Bitmap()
    bitmap.allocN32Pixels(width, height)
    Canvas(bitmap).use { canvas ->
        canvas.drawImageRect(
            image,
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect.makeWH(width.toFloat(), height.toFloat()),
            sampling,
            null,
            true,
        )
    }
    return bitmap
}

// The size a `width` by `height` picture is shrunk to for what it is drawn
// in: filling the box when cropped, fitting inside it otherwise.
internal fun shrunkSize(width: Int, height: Int, options: Options): Pair<Int, Int> {
    val boxWidth = options.size.width.pxOrElse { width }
    val boxHeight = options.size.height.pxOrElse { height }
    val across = boxWidth.toDouble() / width
    val down = boxHeight.toDouble() / height
    val scale = minOf(1.0, if (options.scale == Scale.FILL) maxOf(across, down) else minOf(across, down))
    return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
}
