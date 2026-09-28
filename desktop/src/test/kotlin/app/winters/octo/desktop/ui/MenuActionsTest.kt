package app.winters.octo.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// What each menu offers, group by group, in the words shown.
class MenuActionsTest {
    private fun labels(count: Int, place: SongPlace = SongPlace.Library, outside: Boolean = false, owns: Boolean = false, starred: Boolean = false, last: String? = null) =
        songMenuActions(count, place, outside, owns, lastPlaylist = last != null).map { group -> group.map { songActionLabel(it, starred, last) } }

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
    fun aSongInAKnownFolderCanBeShownThere() {
        assertEquals(listOf("Go to album", "Go to artist", "Show in folder"), songMenuActions(1, SongPlace.Library, inFolder = true)[2].map { songActionLabel(it, false) })
        // Not for several songs, nor one found online.
        assertEquals(false, songMenuActions(3, SongPlace.Library, inFolder = true).flatten().contains(SongAction.ShowInFolder))
        assertEquals(false, songMenuActions(1, SongPlace.Library, outside = true, inFolder = true).flatten().contains(SongAction.ShowInFolder))
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
    fun songsInTheListenersOwnPlaylistCanMoveOrComeOutOfIt() {
        val place = SongPlace.Playlist("p1", listOf(4, 7))
        assertEquals(listOf(listOf("Move"), listOf("Remove from this playlist")), labels(2, place, owns = true).takeLast(2))
        // Someone else's playlist, or one the server keeps, cannot be changed.
        assertEquals(listOf("Add to playlist", "Add to favourites", "Rate"), labels(2, place, owns = false).last())
    }

    @Test
    fun theLastPlaylistComesFirstAmongTheWaysToKeepSongs() {
        assertEquals(listOf("Add to last playlist: Late night", "Add to playlist", "Add to favourites", "Rate"), labels(1, last = "Late night")[1])
    }

    @Test
    fun songsInTheQueueCanComeOutOfIt() {
        val groups = labels(1, SongPlace.Queue(listOf(12L)))
        assertEquals(listOf("Remove from the queue"), groups.last())
        assertEquals(listOf("Song details"), groups[groups.size - 2])
    }

    @Test
    fun inTheQueuePlayingAndQueueingGiveWayToItsOwnRows() {
        // Play, Play next and Add to queue would play or queue a second copy.
        assertEquals(listOf("Start radio"), labels(1, SongPlace.Queue(listOf(12L))).first())
        assertEquals(listOf("Add to playlist", "Add to favourites", "Rate"), labels(2, SongPlace.Queue(listOf(12L, 13L))).first())
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
    fun songsFoundOnlineCanBeAddedWhereTheServerFetchesThem() {
        assertEquals(
            listOf("Add to your library", "Add to playlist"),
            songMenuActions(1, SongPlace.Library, outside = true, canAdd = true).map { group -> group.map { songActionLabel(it, false) } }[1],
        )
        // Not for library songs, nor on a server that cannot fetch.
        assertEquals(false, songMenuActions(1, SongPlace.Library, canAdd = true).flatten().contains(SongAction.AddToLibrary))
        assertEquals(false, songMenuActions(1, SongPlace.Library, outside = true).flatten().contains(SongAction.AddToLibrary))
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
        // Someone else's playlist can be played, pinned, copied and written to a file.
        assertEquals(
            listOf(playing, listOf(CollectionAction.Pin), listOf(CollectionAction.Duplicate, CollectionAction.Export)),
            playlistMenuActions(),
        )
        // The listener's own can also be renamed, shown to others, and deleted, last.
        assertEquals(
            listOf(
                playing,
                listOf(CollectionAction.Pin),
                listOf(CollectionAction.Rename, CollectionAction.Duplicate, CollectionAction.Export, CollectionAction.Public),
                listOf(CollectionAction.Delete),
            ),
            playlistMenuActions(owns = true),
        )
        // An empty playlist has nothing to play or write out.
        assertEquals(
            listOf(listOf(CollectionAction.Pin), listOf(CollectionAction.Rename, CollectionAction.Duplicate, CollectionAction.Public), listOf(CollectionAction.Delete)),
            playlistMenuActions(empty = true, owns = true),
        )
    }
}
