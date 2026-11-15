package app.winters.octo.playback

import app.winters.octo.catalog.isFind
import app.winters.octo.discovery.DownloadState

// What stands beside the playback buttons in the notification for the song
// on now: its heart; for a song found online, which cannot be liked yet, a
// download while none has been asked for; or nothing, as for a station. A
// song the server owns that the phone lacks (`owned`) has its heart.
sealed interface NotificationHeart {
    data class Like(val liked: Boolean) : NotificationHeart
    data object Download : NotificationHeart
    data object None : NotificationHeart
}

fun notificationHeart(trackId: String?, liked: Boolean, downloads: Map<String, DownloadState>, owned: Set<String> = emptySet()): NotificationHeart = when {
    trackId == null || isRadio(trackId) -> NotificationHeart.None
    !isFind(trackId) || trackId in owned -> NotificationHeart.Like(liked)
    (downloads[trackId] ?: DownloadState.None) == DownloadState.None -> NotificationHeart.Download
    else -> NotificationHeart.None
}
