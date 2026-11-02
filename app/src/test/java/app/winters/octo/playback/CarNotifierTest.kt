package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarNotifierTest {
    private val told = mutableListOf<String>()
    private val nobody = mutableSetOf<String>()
    private val notifier = CarNotifier { id -> if (id in nobody) false else true.also { told += id } }

    private val home = carId(CarNode.Home)
    private val radio = carId(CarNode.Radio)
    private val albums = carId(CarNode.Albums)

    @Test
    fun aChangeReachesOnlyTheOpenListsThatReadIt() {
        notifier.opened(home)
        notifier.opened(radio)
        notifier.changed(CarInput.HomeOrder)
        assertEquals(listOf(home), told)
        notifier.changed(CarInput.Kept)
        assertEquals(listOf(home, radio), told)
    }

    @Test
    fun aListIsToldOnceUntilItIsReadThenAgainForWhatCameMeanwhile() {
        notifier.opened(albums)
        notifier.changed(CarInput.Library)
        notifier.changed(CarInput.Library)
        notifier.changed(CarInput.Sorts)
        assertEquals(listOf(albums), told)
        assertFalse(notifier.readStarted(albums))
        notifier.readEnded(albums)
        assertEquals(listOf(albums, albums), told)
        assertFalse(notifier.readStarted(albums))
        notifier.readEnded(albums)
        assertEquals(2, told.size)
        assertTrue(notifier.readStarted(albums))
    }

    @Test
    fun aReadThatFailedStillLetsTheHeldChangeGo() {
        notifier.opened(albums)
        notifier.changed(CarInput.Library)
        notifier.changed(CarInput.Library)
        notifier.readStarted(albums)
        // The read ended in an error: readEnded runs in its finally.
        notifier.readEnded(albums)
        assertEquals(2, told.size)
    }

    @Test
    fun aListNobodyHasOpenTakesOneTellAndIsLetGo() {
        notifier.opened(albums)
        nobody += albums
        notifier.changed(CarInput.Library)
        notifier.changed(CarInput.Library)
        assertTrue(told.isEmpty())
        assertTrue(albums !in notifier.openIds)
    }

    @Test
    fun aLibraryChangeWaitsForACopyToEnd() {
        notifier.opened(albums)
        notifier.opened(home)
        notifier.syncing(true)
        notifier.changed(CarInput.Library)
        assertTrue(told.isEmpty())
        notifier.changed(CarInput.Shelves)
        assertEquals(listOf(home), told)
        notifier.syncing(false)
        assertEquals(listOf(home, albums), told)
    }

    @Test
    fun aListChangedOnItsOwnIsToldOnlyWhileOpen() {
        val album = carId(CarNode.Album("a1"))
        notifier.changedNode(album)
        assertTrue(told.isEmpty())
        notifier.opened(album)
        notifier.changedNode(album)
        assertEquals(listOf(album), told)
    }

    @Test
    fun aReadOfItsOwnAccordIsKnown() {
        notifier.opened(home)
        assertTrue(notifier.readStarted(home))
        notifier.readEnded(home)
        notifier.changed(CarInput.Pins)
        assertFalse(notifier.readStarted(home))
    }
}
