package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class CarTabsTest {
    private val four = listOf(CarNode.Home, CarNode.Radio, CarNode.Playlists, CarNode.Library)

    @Test
    fun aCarThatSaysNothingOrNonsenseGetsFourTabs() {
        listOf(null, 0, -1, 4, 10).forEach { limit ->
            assertEquals("limit $limit", CarTabs(four, emptyList()), carTabs(limit))
        }
    }

    @Test
    fun aCarWithRoomForThreeKeepsHomePlaylistsAndMore() {
        val tabs = carTabs(3)
        assertEquals(listOf(CarNode.Home, CarNode.Playlists, CarNode.More), tabs.tabs)
        assertEquals(listOf(CarNode.Library, CarNode.Radio), tabs.more)
        assertEquals("More", moreTitle(tabs))
    }

    @Test
    fun aCarWithRoomForTwoKeepsHomeAndMore() {
        val tabs = carTabs(2)
        assertEquals(listOf(CarNode.Home, CarNode.More), tabs.tabs)
        assertEquals(listOf(CarNode.Playlists, CarNode.Library, CarNode.Radio), tabs.more)
    }

    @Test
    fun aCarWithRoomForOneGetsOctoHoldingEverything() {
        val tabs = carTabs(1)
        assertEquals(listOf(CarNode.More), tabs.tabs)
        assertEquals(listOf(CarNode.Home, CarNode.Playlists, CarNode.Library, CarNode.Radio), tabs.more)
        assertEquals("Octo", moreTitle(tabs))
    }
}
