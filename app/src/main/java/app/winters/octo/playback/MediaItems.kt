package app.winters.octo.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Size
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.catalog.TrackEntity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private const val ART_SCHEME = "octo-art"

// Where the app keeps a song's artwork reference, so screens can draw it.
const val EXTRA_ARTWORK = "app.winters.octo.artwork"
const val EXTRA_ALBUM_ID = "app.winters.octo.albumId"
const val EXTRA_ARTIST_ID = "app.winters.octo.artistId"
const val EXTRA_MIME = "app.winters.octo.mime"
const val EXTRA_ALBUM_ORDER = "app.winters.octo.albumOrder"

// Artwork for the lock screen and notification, in a form the playback
// service can turn back into a picture.
private fun artworkUri(ref: String?): Uri? = ref?.let { Uri.fromParts(ART_SCHEME, it, null) }

// A song from the catalog, ready to play.
fun TrackEntity.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMimeType(mimeType)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setTrackNumber(trackNo)
                .setDiscNumber(discNo)
                .setReleaseYear(year)
                .setDurationMs(durationMs)
                .setArtworkUri(artworkUri(artwork))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(
                    Bundle().apply {
                        putString(EXTRA_ARTWORK, artwork)
                        putString(EXTRA_ALBUM_ID, albumId)
                        putString(EXTRA_ARTIST_ID, artistId)
                        putString(EXTRA_MIME, mimeType)
                        putInt(EXTRA_ALBUM_ORDER, albumOrder)
                    },
                )
                .build(),
        )
        .build()

// Just the id: what the app sends to the service, which fills in the rest.
fun songRequest(trackId: String): MediaItem = MediaItem.Builder().setMediaId(trackId).build()

// Turns artwork references back into pictures for the lock screen and
// notification; anything else goes to the standard loader.
@OptIn(UnstableApi::class)
class OctoArtLoader(private val context: Context, private val fallback: BitmapLoader) : BitmapLoader {
    private val worker = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String) = fallback.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != ART_SCHEME) return fallback.loadBitmap(uri)
        val art = ArtworkRef.decode(uri.schemeSpecificPart) as? ArtworkRef.Device
            ?: return Futures.immediateFailedFuture(IllegalArgumentException("No artwork"))
        return worker.submit(
            Callable { context.contentResolver.loadThumbnail(art.uri.toUri(), Size(512, 512), null) },
        )
    }
}

// Reads the artwork reference a song was sent with.
fun MediaMetadata.artworkRef(): String? = (extras ?: Bundle.EMPTY).getString(EXTRA_ARTWORK)

fun MediaMetadata.extra(key: String): String? = (extras ?: Bundle.EMPTY).getString(key)
