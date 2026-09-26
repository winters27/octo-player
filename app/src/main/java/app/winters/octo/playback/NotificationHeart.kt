package app.winters.octo.playback

import app.winters.octo.catalog.isFind
import app.winters.octo.discovery.DownloadState

// What stands beside the playback buttons in the notification for the song
// on now: its heart; for a song found online, which cannot be liked yet, a
// download while none has been asked for; or nothing, as for a station.
sealed interface NotificationHeart {
    data class Like(val liked: Boolean) : NotificationHeart
    data object Download : NotificationHeart
    data object None : NotificationHeart
}

fun notificationHeart(trackId: String?, liked: Set<String>, downloads: Map<String, DownloadState>): NotificationHeart = when {
    trackId == null || isRadio(trackId) -> NotificationHeart.None
    !isFind(trackId) -> NotificationHeart.Like(trackId in liked)
    (downloads[trackId] ?: DownloadState.None) == DownloadState.None -> NotificationHeart.Download
    else -> NotificationHeart.None
}
