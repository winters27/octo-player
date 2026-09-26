package app.winters.octo.ui.menu

import app.winters.octo.discovery.DownloadState
import app.winters.octo.ui.menu.SongAction.AddToPlaylist
import app.winters.octo.ui.menu.SongAction.AddToQueue
import app.winters.octo.ui.menu.SongAction.Download
import app.winters.octo.ui.menu.SongAction.GoToAlbum
import app.winters.octo.ui.menu.SongAction.GoToArtist
import app.winters.octo.ui.menu.SongAction.Like
import app.winters.octo.ui.menu.SongAction.PlayNext
import app.winters.octo.ui.menu.SongAction.StartRadio
import org.junit.Assert.assertEquals
import org.junit.Test

class SongActionsTest {
    @Test
    fun aLibrarySongOffersEverything() {
        assertEquals(
            listOf(PlayNext, AddToQueue, StartRadio, AddToPlaylist, Like, GoToAlbum, GoToArtist),
            songActions(find = false, radio = true),
        )
    }

    @Test
    fun radioNeedsAServer() {
        assertEquals(
            listOf(PlayNext, AddToQueue, AddToPlaylist, Like, GoToAlbum, GoToArtist),
            songActions(find = false, radio = false),
        )
    }

    @Test
    fun aFindOffersADownloadInsteadOfLibraryChoices() {
        assertEquals(listOf(PlayNext, AddToQueue, StartRadio, Download), songActions(find = true, radio = true))
        assertEquals(listOf(PlayNext, AddToQueue, Download), songActions(find = true, radio = false))
    }

    @Test
    fun theDownloadRowSaysWhereTheDownloadIs() {
        assertEquals("Download", downloadLabel(DownloadState.None))
        assertEquals("Downloading", downloadLabel(DownloadState.Requested))
        assertEquals("In your library", downloadLabel(DownloadState.Done))
    }
}
