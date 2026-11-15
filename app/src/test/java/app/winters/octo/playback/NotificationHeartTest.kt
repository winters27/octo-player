package app.winters.octo.playback

import app.winters.octo.discovery.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationHeartTest {
    @Test
    fun aLibrarySongShowsItsHeart() {
        assertEquals(NotificationHeart.Like(true), notificationHeart("t1", true, emptyMap()))
        assertEquals(NotificationHeart.Like(false), notificationHeart("t2", false, emptyMap()))
    }

    @Test
    fun aSongFoundOnlineOffersADownloadUntilOneIsAskedFor() {
        assertEquals(NotificationHeart.Download, notificationHeart("find:9", false, emptyMap()))
        assertEquals(NotificationHeart.None, notificationHeart("find:9", false, mapOf("find:9" to DownloadState.Requested)))
        assertEquals(NotificationHeart.None, notificationHeart("find:9", false, mapOf("find:9" to DownloadState.Done)))
    }

    @Test
    fun aSongTheServerOwnsHasItsHeart() {
        assertEquals(NotificationHeart.Like(true), notificationHeart("find:9", true, emptyMap(), owned = setOf("find:9")))
        assertEquals(NotificationHeart.Like(false), notificationHeart("find:9", false, emptyMap(), owned = setOf("find:9")))
    }

    @Test
    fun aStationOrNothingPlayingShowsNeither() {
        assertEquals(NotificationHeart.None, notificationHeart("radio:x", false, emptyMap()))
        assertEquals(NotificationHeart.None, notificationHeart(null, false, emptyMap()))
    }
}
