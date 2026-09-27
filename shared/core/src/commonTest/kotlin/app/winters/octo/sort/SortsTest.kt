package app.winters.octo.sort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SortsTest {
    @Test
    fun eachListStartsInItsOwnOrder() {
        assertEquals(SortOrder(SongSort.Title, descending = false), SortList.Songs.default)
        assertEquals(SortOrder(SongSort.DateLiked, descending = true), SortList.Liked.default)
        assertEquals(SortOrder(SongSort.Title, descending = false), SortList.GenreSongs.default)
        assertEquals(SortOrder(SongSort.FolderOrder, descending = false), SortList.FolderSongs.default)
        assertEquals(SortOrder(AlbumSort.Title, descending = false), SortList.Albums.default)
        assertEquals(SortOrder(AlbumSort.Year, descending = true), SortList.ArtistAlbums.default)
        assertEquals(SortOrder(ArtistSort.Name, descending = false), SortList.Artists.default)
        assertEquals(SortOrder(PlaylistSort.RecentlyChanged, descending = true), SortList.Playlists.default)
        assertEquals(SortOrder(DownloadSort.RecentlyDownloaded, descending = true), SortList.Downloads.default)
    }

    @Test
    fun everyDefaultIsOneOfItsListsOptions() {
        SortList.entries.forEach { list -> assertTrue(list.name, list.default.by in list.options) }
    }

    @Test
    fun songsOfferTheWholeSet() {
        assertEquals(
            listOf(
                "Title", "Artist", "Album", "Recently added", "Year", "Length", "Most played", "Recently played", "Rating", "Liked",
            ),
            SortList.Songs.options.map { it.label },
        )
        assertEquals(listOf("Date liked", "Title", "Artist", "Album", "Recently added"), SortList.Liked.options.map { it.label })
        assertEquals(listOf("Year", "Title", "Most played"), SortList.ArtistAlbums.options.map { it.label })
    }

    @Test
    fun everyOrderRoundTripsBothWays() {
        SortList.entries.forEach { list ->
            list.options.forEach { option ->
                listOf(false, true).forEach { descending ->
                    val order = SortOrder(option, descending)
                    assertEquals("${list.name} ${option.id}", order, list.decode(list.encode(order)))
                }
            }
        }
    }

    @Test
    fun savedTextLooksLikeOptionAndDirection() {
        assertEquals("RecentlyAdded:desc", SortList.Songs.encode(SortOrder(SongSort.RecentlyAdded, descending = true)))
        assertEquals("Name:asc", SortList.Artists.encode(SortOrder(ArtistSort.Name, descending = false)))
    }

    @Test
    fun anythingElseSavedFallsBackToTheDefault() {
        val list = SortList.Songs
        listOf(null, "", "Title", "Title:up", "Title:asc:x", "Nope:asc", "title:asc", "DateLiked:desc", "FolderOrder:asc")
            .forEach { saved -> assertEquals("'$saved'", list.default, list.decode(saved)) }
    }

    @Test
    fun pickingAnOptionRunsItsUsualWay() {
        val byTitle = SortOrder(SongSort.Title, descending = true)
        assertEquals(SortOrder(SongSort.RecentlyAdded, descending = true), byTitle.picking(SongSort.RecentlyAdded))
        assertEquals(SortOrder(SongSort.Artist, descending = false), byTitle.picking(SongSort.Artist))
        // Picking the one already chosen keeps its direction.
        assertEquals(byTitle, byTitle.picking(SongSort.Title))
    }

    @Test
    fun onlyNameOrdersFileUnderLetters() {
        assertEquals(
            setOf(SongSort.Title, SongSort.Artist, SongSort.Album),
            SongSort.entries.filter { it.byName }.toSet(),
        )
        assertTrue(ArtistSort.Name.byName)
        assertFalse(AlbumSort.Year.byName)
        assertTrue(AlbumSort.MostPlayed.byListening && ArtistSort.RecentlyPlayed.byListening)
    }

    @Test
    fun optionIdsAreUniqueWithinEachList() {
        SortList.entries.forEach { list ->
            assertEquals(list.name, list.options.size, list.options.map { it.id }.toSet().size)
        }
    }
}
