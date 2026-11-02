package app.winters.octo.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.winters.octo.home.HomeSection
import app.winters.octo.home.HomeSectionEntry
import app.winters.octo.home.homeSectionOrder
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
        seeAll = { shelf -> MediaItem.Builder().setMediaId("seeall:${shelf.section.id}").build() },
        row = { shelf -> MediaItem.Builder().setMediaId("row:${shelf.section.id}").build() },
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
        val shown = carHomeShelves(order, shelves, libraryEmpty = false).map { it.section }
        assertEquals(listOf(HomeSection.MostPlayed, HomeSection.RecentlyPlayed), shown)
    }

    @Test
    fun anEmptyLibraryShowsTheChartEvenWhenHiddenAndLiveRadio() {
        val order = homeSectionOrder("-charts")
        val shelves = mapOf(HomeSection.Charts to items("c", 3), HomeSection.InternetRadio to items("l", 1), HomeSection.Pinned to items("p", 1))
        val shown = carHomeShelves(order, shelves, libraryEmpty = true).map { it.section }
        assertEquals(listOf(HomeSection.Charts, HomeSection.InternetRadio), shown)
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
    fun aHomeThatNeverFitsBecomesOneRowPerShelf() {
        val shelves = HomeSection.entries.map { CarShelf(it, items(it.id, 20, idLength = 2_000)) }
        val answer = carHomeAnswer(null, shelves, parts, budget = 50_000)
        assertEquals(shelves.map { "row:${it.section.id}" }, answer.map { it.mediaId })
    }
}
