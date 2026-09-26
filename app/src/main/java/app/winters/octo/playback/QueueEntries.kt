package app.winters.octo.playback

import android.os.Bundle
import androidx.media3.common.MediaItem

// Each song in the queue carries its own entry id, given by the player as
// it goes in. The same song queued twice is two entries, and a list can
// point at one entry even while songs around it come and go.
const val EXTRA_QUEUE_ENTRY = "app.winters.octo.queueEntry"

// A song Autoplay added once the listener's own queue ran out.
const val EXTRA_AUTOPLAY = "app.winters.octo.autoplay"

val MediaItem.entryId: String? get() = mediaMetadata.extras?.getString(EXTRA_QUEUE_ENTRY)

val MediaItem.isAutoplay: Boolean get() = mediaMetadata.extras?.getBoolean(EXTRA_AUTOPLAY, false) == true

fun MediaItem.withEntry(id: String): MediaItem = withExtras { putString(EXTRA_QUEUE_ENTRY, id) }

fun MediaItem.markedAutoplay(): MediaItem = withExtras { putBoolean(EXTRA_AUTOPLAY, true) }

// The same song with its extras changed, keeping everything else.
private fun MediaItem.withExtras(change: Bundle.() -> Unit): MediaItem {
    val extras = Bundle(mediaMetadata.extras ?: Bundle.EMPTY).apply(change)
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
}
