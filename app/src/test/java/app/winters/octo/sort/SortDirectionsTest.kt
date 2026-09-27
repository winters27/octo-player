package app.winters.octo.sort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SortDirectionsTest {
    private fun labels(option: SortOption) = directionChoices(option).map { it.label }

    @Test
    fun namesRunAToZFirst() {
        assertEquals(listOf("A to Z", "Z to A"), labels(SongSort.Title))
        assertEquals(listOf("A to Z", "Z to A"), labels(AlbumSort.Artist))
        assertEquals(listOf("A to Z", "Z to A"), labels(ArtistSort.Name))
        assertEquals(listOf("A to Z", "Z to A"), labels(PlaylistSort.Name))
        assertEquals(listOf("A to Z", "Z to A"), labels(DownloadSort.Title))
        assertEquals(listOf("A to Z", "Z to A"), labels(FavouriteSort.Name))
    }

    @Test
    fun datesRunNewestFirst() {
        assertEquals(listOf("Newest", "Oldest"), labels(SongSort.RecentlyAdded))
        assertEquals(listOf("Newest", "Oldest"), labels(SongSort.Year))
        assertEquals(listOf("Newest", "Oldest"), labels(SongSort.DateLiked))
        assertEquals(listOf("Newest", "Oldest"), labels(PlaylistSort.RecentlyChanged))
        assertEquals(listOf("Newest", "Oldest"), labels(DownloadSort.RecentlyDownloaded))
        assertEquals(listOf("Newest", "Oldest"), labels(FavouriteSort.DateAdded))
    }

    @Test
    fun countsAndPlaysRunMostFirst() {
        assertEquals(listOf("Most", "Least"), labels(SongSort.MostPlayed))
        assertEquals(listOf("Most", "Least"), labels(AlbumSort.SongCount))
        assertEquals(listOf("Most", "Least"), labels(ArtistSort.AlbumCount))
        assertEquals(listOf("Most", "Least"), labels(PlaylistSort.SongCount))
    }

    @Test
    fun theRestSayWhatTheyMean() {
        assertEquals(listOf("Longest", "Shortest"), labels(SongSort.Length))
        assertEquals(listOf("Longest", "Shortest"), labels(AlbumSort.Length))
        assertEquals(listOf("Highest", "Lowest"), labels(SongSort.Rating))
        assertEquals(listOf("Largest", "Smallest"), labels(DownloadSort.Size))
        assertEquals(listOf("Liked first", "Liked last"), labels(SongSort.Liked))
        assertEquals(listOf("In order", "Reversed"), labels(SongSort.FolderOrder))
    }

    @Test
    fun eachLabelMatchesItsDirection() {
        // Newest is the descending one for a date; A to Z the ascending one
        // for a name.
        assertEquals(listOf(true, false), directionChoices(SongSort.RecentlyAdded).map { it.descending })
        assertEquals(listOf(false, true), directionChoices(SongSort.Title).map { it.descending })
        assertEquals("Newest", SortScale.Date.label(descending = true))
        assertEquals("A to Z", SortScale.Name.label(descending = false))
    }

    @Test
    fun theFallbackIsThePlainWords() {
        assertEquals("Ascending", SortScale.Plain.label(descending = false))
        assertEquals("Descending", SortScale.Plain.label(descending = true))
    }

    @Test
    fun everyListOffersTwoDifferentDirectionsForEveryOption() {
        SortList.entries.flatMap { it.options }.forEach { option ->
            val choices = directionChoices(option)
            assertEquals(2, choices.size)
            // The first is the way the option runs when picked.
            assertEquals(option.startsDescending, choices[0].descending)
            assertNotEquals(choices[0].descending, choices[1].descending)
            assertNotEquals(choices[0].label, choices[1].label)
        }
    }
}
