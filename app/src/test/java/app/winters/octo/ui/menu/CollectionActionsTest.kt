package app.winters.octo.ui.menu

import app.winters.octo.ui.menu.CollectionAction.AddToPlaylist
import app.winters.octo.ui.menu.CollectionAction.AddToQueue
import app.winters.octo.ui.menu.CollectionAction.Delete
import app.winters.octo.ui.menu.CollectionAction.Download
import app.winters.octo.ui.menu.CollectionAction.GoToArtist
import app.winters.octo.ui.menu.CollectionAction.Play
import app.winters.octo.ui.menu.CollectionAction.PlayNext
import app.winters.octo.ui.menu.CollectionAction.Rename
import app.winters.octo.ui.menu.CollectionAction.Shuffle
import app.winters.octo.ui.menu.CollectionAction.StartRadio
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionActionsTest {
    @Test
    fun anAlbumOffersADownloadOnlyWithSongsLeftToDownload() {
        assertEquals(listOf(Play, Shuffle, PlayNext, AddToQueue, AddToPlaylist, Download, GoToArtist), albumActions(canDownload = true))
        assertEquals(listOf(Play, Shuffle, PlayNext, AddToQueue, AddToPlaylist, GoToArtist), albumActions(canDownload = false))
    }

    @Test
    fun anArtistRadioNeedsAServer() {
        assertEquals(listOf(Play, Shuffle, PlayNext, AddToQueue, StartRadio), artistActions(radio = true))
        assertEquals(listOf(Play, Shuffle, PlayNext, AddToQueue), artistActions(radio = false))
    }

    @Test
    fun anEmptyPlaylistCanOnlyBeRenamedOrDeleted() {
        assertEquals(listOf(Play, Shuffle, PlayNext, AddToQueue, Rename, Delete), playlistActions(empty = false))
        assertEquals(listOf(Rename, Delete), playlistActions(empty = true))
    }
}
