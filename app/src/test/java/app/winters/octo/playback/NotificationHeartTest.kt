package app.winters.octo.playback

import app.winters.octo.discovery.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationHeartTest {
    private val liked = setOf("t1")

    @Test
    fun aLibrarySongShowsItsHeart() {
        assertEquals(NotificationHeart.Like(true), notificationHeart("t1", liked, emptyMap()))
        assertEquals(NotificationHeart.Like(false), notificationHeart("t2", liked, emptyMap()))
    }

    @Test
    fun aSongFoundOnlineOffersADownloadUntilOneIsAskedFor() {
        assertEquals(NotificationHeart.Download, notificationHeart("find:9", liked, emptyMap()))
        assertEquals(NotificationHeart.None, notificationHeart("find:9", liked, mapOf("find:9" to DownloadState.Requested)))
        assertEquals(NotificationHeart.None, notificationHeart("find:9", liked, mapOf("find:9" to DownloadState.Done)))
    }

    @Test
    fun aStationOrNothingPlayingShowsNeither() {
        assertEquals(NotificationHeart.None, notificationHeart("radio:x", liked, emptyMap()))
        assertEquals(NotificationHeart.None, notificationHeart(null, liked, emptyMap()))
    }
}
