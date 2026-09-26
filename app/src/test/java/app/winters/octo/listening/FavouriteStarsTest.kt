package app.winters.octo.listening

import app.winters.octo.catalog.CopyCount
import app.winters.octo.catalog.ServerCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouriteStarsTest {
    private val server = "server:music.example"

    private fun row(native: String) = "$server:$native"

    @Test
    fun aServerAlbumMergedIntoAPhoneAlbumIsThatAlbumsCopy() {
        val counts = listOf(CopyCount(row("al1"), "device:album:1", 12))
        assertEquals(listOf(ServerCopy("device:album:1", "al1")), serverCopiesOf(counts, server))
    }

    @Test
    fun aServerAlbumSplitOverTwoLibraryAlbumsBelongsToTheOneWithMostOfItsSongs() {
        // A deluxe edition: 12 songs matched the phone's album, 3 bonus songs
        // stayed in an album of the server's own.
        val counts = listOf(
            CopyCount(row("deluxe"), "device:album:1", 12),
            CopyCount(row("deluxe"), row("deluxe"), 3),
        )
        assertEquals(listOf(ServerCopy("device:album:1", "deluxe")), serverCopiesOf(counts, server))
    }

    @Test
    fun aTieGoesToTheLibraryAlbumThatKeptTheServersId() {
        val counts = listOf(
            CopyCount(row("al1"), "device:album:1", 4),
            CopyCount(row("al1"), row("al1"), 4),
        )
        assertEquals(listOf(ServerCopy(row("al1"), "al1")), serverCopiesOf(counts, server))
    }

    @Test
    fun twoServerAlbumsCanBothBeCopiesOfOneLibraryAlbum() {
        val counts = listOf(
            CopyCount(row("a"), "device:album:1", 5),
            CopyCount(row("b"), "device:album:1", 2),
        )
        val copies = serverCopiesOf(counts, server)
        assertEquals(listOf("a", "b"), copies.serverIdsOf("device:album:1"))
    }

    @Test
    fun madeUpIdsAndOtherServersAreLeftOut() {
        val counts = listOf(
            CopyCount(row("album:some key"), "device:album:1", 5),
            CopyCount(row("artist:some one"), "device:artist:one", 5),
            CopyCount("server:elsewhere:x", "device:album:2", 5),
            CopyCount("device:album:3", "device:album:3", 5),
        )
        assertTrue(serverCopiesOf(counts, server).isEmpty())
    }

    // The planning is the song stars' planning, run on albums and artists.

    private val albums = listOf(ServerCopy("device:album:1", "al1"), ServerCopy("server:x:al2", "al2"))

    @Test
    fun aFavouriteAlbumOnThePhoneStarsItsServerAlbum() {
        val plan = reconcileStars(albums, setOf("device:album:1"), emptySet(), emptySet())
        assertEquals(setOf("al1"), plan.star)
        assertEquals(setOf("al1"), plan.synced)
    }

    @Test
    fun aStarredAlbumOnTheServerBecomesAFavourite() {
        val plan = reconcileStars(albums, emptySet(), setOf("al2"), emptySet())
        assertEquals(setOf("server:x:al2"), plan.like)
        assertTrue(plan.star.isEmpty() && plan.unstar.isEmpty())
    }

    @Test
    fun anAlbumUnstarredOnTheServerStopsBeingAFavourite() {
        val plan = reconcileStars(albums, setOf("device:album:1"), emptySet(), setOf("al1"))
        assertEquals(setOf("device:album:1"), plan.unlike)
        assertTrue(plan.synced.isEmpty())
    }

    @Test
    fun anAlbumRemovedFromFavouritesOnThePhoneIsUnstarred() {
        val plan = reconcileStars(albums, emptySet(), setOf("al1"), setOf("al1"))
        assertEquals(setOf("al1"), plan.unstar)
        assertTrue(plan.like.isEmpty())
    }

    @Test
    fun aPhoneOnlyAlbumIsNeverSentAnywhere() {
        // "device:album:9" has no server copy at all.
        val plan = reconcileStars(albums, setOf("device:album:9"), emptySet(), emptySet())
        assertTrue(plan.star.isEmpty() && plan.unstar.isEmpty() && plan.like.isEmpty() && plan.unlike.isEmpty())
    }

    @Test
    fun artistsFollowTheSameRules() {
        val counts = listOf(
            CopyCount(row("ar1"), "device:artist:radiohead", 40),
            CopyCount(row("ar1"), row("ar1"), 0),
            CopyCount(row("ar2"), row("ar2"), 0),
        )
        val artists = serverCopiesOf(counts, server)
        assertEquals(setOf(ServerCopy("device:artist:radiohead", "ar1"), ServerCopy(row("ar2"), "ar2")), artists.toSet())
        val plan = reconcileStars(artists, setOf("device:artist:radiohead"), setOf("ar2"), emptySet())
        assertEquals(setOf("ar1"), plan.star)
        assertEquals(setOf(row("ar2")), plan.like)
        assertEquals(setOf("ar1", "ar2"), plan.synced)
    }
}
