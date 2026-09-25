package app.winters.octo.device

import android.content.Context
import androidx.core.net.toUri
import android.util.Size
import app.winters.octo.catalog.ArtworkRef
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Draws the artwork embedded in a file on the phone, using the thumbnail
// the phone's media library already keeps.
class DeviceArtworkFetcher(
    private val context: Context,
    private val art: ArtworkRef.Device,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val px = options.size.width.pxOrElse { 512 }.coerceIn(96, 1024)
        val bitmap = runCatching {
            context.contentResolver.loadThumbnail(art.uri.toUri(), Size(px, px), null)
        }.getOrNull() ?: return@withContext null
        ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val context: Context) : Fetcher.Factory<ArtworkRef.Device> {
        override fun create(data: ArtworkRef.Device, options: Options, imageLoader: ImageLoader): Fetcher =
            DeviceArtworkFetcher(context, data, options)
    }
}

// One album's tracks share one cached picture.
class DeviceArtworkKeyer : Keyer<ArtworkRef.Device> {
    override fun key(data: ArtworkRef.Device, options: Options) = "device-art:${data.key}"
}
