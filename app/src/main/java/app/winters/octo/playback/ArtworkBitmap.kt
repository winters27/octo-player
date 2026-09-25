package app.winters.octo.playback

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.core.net.toUri
import app.winters.octo.catalog.ArtworkRef
import coil3.SingletonImageLoader
import coil3.executeBlocking
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

// A catalog picture as a plain bitmap about `px` wide, for what draws
// outside the app's screens: the notification, a car's screen and the
// player's colours. Null when there is none. Blocks, so never call it on
// the main thread.
fun artworkBitmap(context: Context, ref: String?, px: Int): Bitmap? = when (val art = ArtworkRef.decode(ref)) {
    is ArtworkRef.Device -> runCatching {
        context.contentResolver.loadThumbnail(art.uri.toUri(), Size(px, px), null)
    }.getOrNull()
    // Server covers go through the app's image loader, which signs the
    // address and keeps the download.
    is ArtworkRef.Server -> {
        val request = ImageRequest.Builder(context).data(art).size(px).allowHardware(false).build()
        (SingletonImageLoader.get(context).executeBlocking(request) as? SuccessResult)?.image?.toBitmap()
    }
    null -> null
}
