package app.winters.octo.discovery

import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.subsonic.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MadeForYouTest {
    // Only the lists Octo marks, in kind order, each with its cover and how
    // many songs it holds.
    @Test
    fun theListsComeInKindOrderWithTheirSongCounts() {
        val playlists = listOf(
            Playlist("mine", "Road trip", songCount = 12),
            Playlist("og-deep", "Deep Cuts", songCount = 30, readonly = true, octoList = "deepCuts"),
            Playlist("og-new", "New Releases", songCount = 24, coverArt = "og-new-cover", readonly = true, octoList = "newReleases"),
            Playlist("og-re", "Rediscover", songCount = 50, readonly = true, octoList = "rediscover"),
        )
        val lists = madeForYouLists(playlists, "src1")
        assertEquals(listOf("og-new", "og-re", "og-deep"), lists.map { it.id })
        assertEquals(listOf(24, 50, 30), lists.map { it.songCount })
        assertEquals(ArtworkRef.Server("src1", "og-new-cover").encode(), lists[0].artwork)
        assertEquals("a list without a cover is drawn from its id", ArtworkRef.Server("src1", "og-re").encode(), lists[1].artwork)
    }

    @Test
    fun likedSongsComesFirst() {
        val playlists = listOf(
            Playlist("og-new", "New Releases", readonly = true, octoList = "newReleases"),
            Playlist("og-liked", "Liked Songs", readonly = true, octoList = "liked"),
        )
        assertEquals(listOf("og-liked", "og-new"), madeForYouLists(playlists, "src1").map { it.id })
    }

    @Test
    fun aServerWithoutTheListsHasNone() {
        assertTrue(madeForYouLists(listOf(Playlist("a"), Playlist("b", readonly = true)), "src1").isEmpty())
    }
}
