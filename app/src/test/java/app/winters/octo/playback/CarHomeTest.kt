package app.winters.octo.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.winters.octo.discovery.Station
import app.winters.octo.home.HomeSection
import app.winters.octo.home.HomeSectionEntry
import app.winters.octo.home.homeSectionOrder
import app.winters.octo.ui.liveradio.LIVE_NEAR_TITLE
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CarHomeTest {
    private fun items(prefix: String, count: Int, idLength: Int = 0) = List(count) {
        MediaItem.Builder().setMediaId("$prefix$it" + "x".repeat(idLength))
            .setMediaMetadata(MediaMetadata.Builder().setTitle("$prefix $it").setIsBrowsable(true).build()).build()
    }

    private fun groupOf(item: MediaItem): String? = item.mediaMetadata.extras?.getString("group")

    private val parts = CarHomeParts(
        grouped = { item, title ->
            item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(Bundle().apply { putString("group", title) }).build()).build()
        },
        seeAll = { shelf -> MediaItem.Builder().setMediaId("seeall:${shelf.id}").build() },
        row = { shelf -> MediaItem.Builder().setMediaId("row:${shelf.id}").build() },
    )

    @Test
    fun shelvesFollowTheListenersOrderAndHiddenOnesStayHidden() {
        val order = homeSectionOrder("mostPlayed,-madeForYou,recentlyPlayed")
        val shelves = mapOf(
            HomeSection.MostPlayed to items("m", 2),
            HomeSection.MadeForYou to items("f", 2),
            HomeSection.RecentlyPlayed to items("r", 2),
            HomeSection.Charts to emptyList(),
        )
        val shown = carHomeShelves(order, shelves, libraryEmpty = false).map { it.id }
        assertEquals(listOf(HomeSection.MostPlayed.id, HomeSection.RecentlyPlayed.id), shown)
    }

    @Test
    fun anEmptyLibraryShowsTheChartEvenWhenHiddenAndLiveRadio() {
        val order = homeSectionOrder("-charts")
        val shelves = mapOf(HomeSection.Charts to items("c", 3), HomeSection.InternetRadio to items("l", 1), HomeSection.Pinned to items("p", 1))
        val shown = carHomeShelves(order, shelves, libraryEmpty = true).map { it.id }
        assertEquals(listOf(HomeSection.Charts.id, HomeSection.InternetRadio.id), shown)
    }

    @Test
    fun theRadioTabGroupsAsThePhoneAndLeavesOutWhatIsEmpty() {
        val shelves = carRadioShelves(items("s", 3), emptyList(), items("n", 2))
        assertEquals(listOf(HomeSection.Stations.id, NEAR_SHELF), shelves.map { it.id })
        assertEquals(listOf(HomeSection.Stations.title, LIVE_NEAR_TITLE), shelves.map { it.title })
        assertEquals(listOf(HomeSection.Stations.id, HomeSection.InternetRadio.id, NEAR_SHELF), carRadioShelves(items("s", 1), items("l", 1), items("n", 1)).map { it.id })
    }

    @Test
    fun aStationCarriesTheSongCountTheServersListsGiveIt() {
        val stations = listOf(Station("or1", "Logic Radio", null), Station("live1", "Jazz", null, streamUrl = "https://example.invalid/jazz"))
        val got = carRadioStations(stations, listOf(Station("or1", "Logic Radio", null, songCount = 47)))
        assertEquals(listOf(Station("or1", "Logic Radio", null, songCount = 47)), got)
    }

    @Test
    fun aRadioTabTooBigForOneAnswerKeepsItsGroupsAndNeverSplitsIntoParts() {
        // The walk's Radio tab: 266 stations, cut into "1 to 96" and so on.
        val shelves = carRadioShelves(items("s", 5, idLength = 400), items("l", 200, idLength = 400), items("n", 61, idLength = 400))
        val budget = 40_000
        assertTrue(shelves.sumOf { shelf -> shelf.items.sumOf(::carBytes) } > budget)
        val answer = carHomeAnswer(null, shelves, parts, budget)
        assertTrue(answer.sumOf(::carBytes) <= budget)
        assertTrue(answer.none { parseCarId(it.mediaId) is CarNode.Part })
        assertEquals(listOf(HomeSection.Stations.title, HomeSection.InternetRadio.title, LIVE_NEAR_TITLE), answer.mapNotNull(::groupOf).distinct())
        assertEquals(listOf("seeall:${HomeSection.InternetRadio.id}", "seeall:$NEAR_SHELF"), answer.map { it.mediaId }.filter { it.startsWith("seeall:") })
    }

    @Test
    fun eightShowThenSeeAllUnderTheShelfsTitle() {
        val answer = carHomeAnswer(null, listOf(CarShelf(HomeSection.RecentlyAdded, items("a", 20))), parts)
        assertEquals(9, answer.size)
        assertEquals("seeall:recentlyAdded", answer.last().mediaId)
        assertTrue(answer.all { groupOf(it) == HomeSection.RecentlyAdded.title })
    }

    @Test
    fun theResumeRowLeads() {
        val resume = MediaItem.Builder().setMediaId("resume").build()
        val answer = carHomeAnswer(resume, listOf(CarShelf(HomeSection.Pinned, items("p", 2))), parts)
        assertEquals("resume", answer.first().mediaId)
    }

    @Test
    fun capsStepDownUntilHomeFits() {
        val shelves = HomeSection.entries.map { CarShelf(it, items(it.id, 20, idLength = 200)) }
        val budget = shelves.size * 5 * carBytes(shelves.first().items.first()) + 4_000
        val answer = carHomeAnswer(null, shelves, parts, budget)
        assertTrue(answer.sumOf(::carBytes) <= budget)
        assertTrue(answer.none { it.mediaId.startsWith("row:") })
        assertTrue(answer.size < shelves.size * 9)
    }

    @Test
    fun onlyTheShelvesHomeShowsAreFilled() {
        val wanted = carHomeWanted(homeSectionOrder("mostPlayed,-madeForYou,recentlyPlayed"), libraryEmpty = false)
        assertTrue(HomeSection.MostPlayed in wanted && HomeSection.RecentlyPlayed in wanted)
        assertTrue(HomeSection.MadeForYou !in wanted)
        assertEquals(setOf(HomeSection.Charts, HomeSection.InternetRadio), carHomeWanted(homeSectionOrder("-charts"), libraryEmpty = true))
        assertEquals(setOf(HomeSection.Charts), carHomeWanted(homeSectionOrder("-internetRadio"), libraryEmpty = true))
    }

    @Test
    fun randomPicksStayUntilTheLibraryChanges() = runTest {
        var picks = 0
        val picker = CarPicks { ++picks }
        assertEquals(1, picker.forKey(100 to 1L))
        assertEquals(1, picker.forKey(100 to 1L))
        assertEquals(2, picker.forKey(101 to 1L))
        assertEquals(3, picker.forKey(101 to 2L))
    }

    @Test
    fun aHomeThatNeverFitsBecomesOneRowPerShelf() {
        val shelves = HomeSection.entries.map { CarShelf(it, items(it.id, 20, idLength = 2_000)) }
        val answer = carHomeAnswer(null, shelves, parts, budget = 50_000)
        assertEquals(shelves.map { "row:${it.id}" }, answer.map { it.mediaId })
    }
}
