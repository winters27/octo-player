package app.winters.octo.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// What each menu offers, group by group, in the words shown.
class MenuActionsTest {
    private fun labels(count: Int, place: SongPlace = SongPlace.Library, outside: Boolean = false, owns: Boolean = false, starred: Boolean = false) =
        songMenuActions(count, place, outside, owns).map { group -> group.map { songActionLabel(it, starred) } }

    @Test
    fun oneLibrarySongOffersEveryGroupButRemoval() {
        assertEquals(
            listOf(
                listOf("Play", "Play next", "Add to queue", "Start radio"),
                listOf("Add to playlist", "Add to favourites", "Rate"),
                listOf("Go to album", "Go to artist"),
                listOf("Song details"),
            ),
            labels(1),
        )
    }

    @Test
    fun severalSongsHaveNoRadioDetailsOrGoTo() {
        assertEquals(
            listOf(
                listOf("Play", "Play next", "Add to queue"),
                listOf("Add to playlist", "Remove from favourites", "Rate"),
            ),
            labels(3, starred = true),
        )
    }

    @Test
    fun songsInTheListenersOwnPlaylistCanComeOutOfIt() {
        val place = SongPlace.Playlist("p1", listOf(4, 7))
        assertEquals(listOf("Remove from this playlist"), labels(2, place, owns = true).last())
        // Someone else's playlist, or one the server keeps, cannot be changed.
        assertEquals(listOf("Add to playlist", "Add to favourites", "Rate"), labels(2, place, owns = false).last())
    }

    @Test
    fun songsInTheQueueCanComeOutOfIt() {
        val groups = labels(1, SongPlace.Queue(listOf(12L)))
        assertEquals(listOf("Remove from the queue"), groups.last())
        assertEquals(listOf("Song details"), groups[groups.size - 2])
    }

    @Test
    fun songsFoundOnlineAreNotFavouritedRatedOrOpened() {
        assertEquals(
            listOf(
                listOf("Play", "Play next", "Add to queue", "Start radio"),
                listOf("Add to playlist"),
                listOf("Song details"),
            ),
            labels(1, outside = true),
        )
    }

    @Test
    fun ratingWordsMatchThePhone() {
        assertNull(starsLabel(0))
        assertEquals("1 star", starsLabel(1))
        assertEquals("4 stars", starsLabel(4))
        assertEquals(3, sharedRating(listOf(3, 3)))
        assertNull(sharedRating(listOf(3, 0)))
    }

    @Test
    fun collectionMenusKeepTheirGroups() {
        val playing = listOf(CollectionAction.Play, CollectionAction.Shuffle, CollectionAction.PlayNext, CollectionAction.AddToQueue)
        assertEquals(
            listOf(playing + CollectionAction.StartRadio, listOf(CollectionAction.AddToPlaylist, CollectionAction.Favourite), listOf(CollectionAction.GoToArtist)),
            albumMenuActions(),
        )
        assertEquals(listOf(playing + CollectionAction.StartRadio, listOf(CollectionAction.AddToPlaylist)), albumMenuActions(outside = true))
        assertEquals(listOf(playing + CollectionAction.StartRadio, listOf(CollectionAction.Favourite)), artistMenuActions())
        assertEquals(listOf(playing, listOf(CollectionAction.Pin)), playlistMenuActions())
        // An empty playlist has nothing to play.
        assertEquals(listOf(listOf(CollectionAction.Pin)), playlistMenuActions(empty = true))
    }
}
