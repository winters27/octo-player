package app.winters.octo.ui.menu

import app.winters.octo.catalog.PinKind
import app.winters.octo.favourites.PinKey
import app.winters.octo.ui.menu.CollectionAction.AddToFavourites
import app.winters.octo.ui.menu.CollectionAction.AddToPlaylist
import app.winters.octo.ui.menu.CollectionAction.AddToQueue
import app.winters.octo.ui.menu.CollectionAction.Delete
import app.winters.octo.ui.menu.CollectionAction.Download
import app.winters.octo.ui.menu.CollectionAction.GoToArtist
import app.winters.octo.ui.menu.CollectionAction.MoveToFront
import app.winters.octo.ui.menu.CollectionAction.PinToHome
import app.winters.octo.ui.menu.CollectionAction.Play
import app.winters.octo.ui.menu.CollectionAction.PlayNext
import app.winters.octo.ui.menu.CollectionAction.RemoveFromFavourites
import app.winters.octo.ui.menu.CollectionAction.Rename
import app.winters.octo.ui.menu.CollectionAction.Shuffle
import app.winters.octo.ui.menu.CollectionAction.StartRadio
import app.winters.octo.ui.menu.CollectionAction.Unpin
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionActionsTest {
    @Test
    fun anAlbumOffersADownloadOnlyWithSongsLeftToDownload() {
        assertEquals(
            listOf(Play, Shuffle, PlayNext, AddToQueue, AddToPlaylist, Download, AddToFavourites, PinToHome, GoToArtist),
            albumActions(canDownload = true),
        )
        assertEquals(
            listOf(Play, Shuffle, PlayNext, AddToQueue, AddToPlaylist, AddToFavourites, PinToHome, GoToArtist),
            albumActions(canDownload = false),
        )
    }

    @Test
    fun anArtistRadioNeedsAServer() {
        assertEquals(listOf(Play, Shuffle, PlayNext, AddToQueue, StartRadio, AddToFavourites, PinToHome), artistActions(radio = true))
        assertEquals(listOf(Play, Shuffle, PlayNext, AddToQueue, AddToFavourites, PinToHome), artistActions(radio = false))
    }

    @Test
    fun anEmptyPlaylistCanOnlyBePinnedRenamedOrDeleted() {
        assertEquals(listOf(Play, Shuffle, PlayNext, AddToQueue, PinToHome, Rename, Delete), playlistActions(empty = false))
        assertEquals(listOf(PinToHome, Rename, Delete), playlistActions(empty = true))
    }

    @Test
    fun aFavouriteOffersToBeRemoved() {
        assertEquals(RemoveFromFavourites, albumActions(canDownload = false, favourite = true)[5])
        assertEquals(RemoveFromFavourites, artistActions(radio = false, favourite = true)[4])
    }

    @Test
    fun aPinOffersUnpinAndMoveToFrontUnlessItIsFirst() {
        assertEquals(
            listOf(Play, Shuffle, PlayNext, AddToQueue, AddToFavourites, Unpin, MoveToFront),
            artistActions(radio = false, pin = PinSpot.Pinned),
        )
        assertEquals(listOf(Unpin, Rename, Delete), playlistActions(empty = true, pin = PinSpot.First))
        assertEquals(listOf(Unpin, MoveToFront, Rename, Delete), playlistActions(empty = true, pin = PinSpot.Pinned))
    }

    @Test
    fun aPinsSpotComesFromTheRowHomeShows() {
        val a = PinKey(PinKind.Album, "a")
        val b = PinKey(PinKind.Artist, "b")
        val shown = listOf(a, b)
        assertEquals(PinSpot.First, pinSpot(shown, a))
        assertEquals(PinSpot.Pinned, pinSpot(shown, b))
        assertEquals(PinSpot.None, pinSpot(shown, PinKey(PinKind.Playlist, "a")))
    }
}
