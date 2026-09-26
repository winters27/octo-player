package app.winters.octo.ui.menu

import app.winters.octo.discovery.DownloadState
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.ui.menu.SongAction.AddToPlaylist
import app.winters.octo.ui.menu.SongAction.AddToQueue
import app.winters.octo.ui.menu.SongAction.Download
import app.winters.octo.ui.menu.SongAction.GoToAlbum
import app.winters.octo.ui.menu.SongAction.GoToArtist
import app.winters.octo.ui.menu.SongAction.KeepOffline
import app.winters.octo.ui.menu.SongAction.Like
import app.winters.octo.ui.menu.SongAction.PlayNext
import app.winters.octo.ui.menu.SongAction.Rate
import app.winters.octo.ui.menu.SongAction.Share
import app.winters.octo.ui.menu.SongAction.StartRadio
import org.junit.Assert.assertEquals
import org.junit.Test

class SongActionsTest {
    @Test
    fun aLibrarySongOffersEverything() {
        assertEquals(
            listOf(PlayNext, AddToQueue, StartRadio, AddToPlaylist, Like, Rate, GoToAlbum, GoToArtist),
            songActions(find = false, radio = true),
        )
    }

    @Test
    fun radioNeedsAServer() {
        assertEquals(
            listOf(PlayNext, AddToQueue, AddToPlaylist, Like, Rate, GoToAlbum, GoToArtist),
            songActions(find = false, radio = false),
        )
    }

    @Test
    fun shareFollowsAPlaylistWhenTheSongIsOnAServerThatShares() {
        assertEquals(
            listOf(PlayNext, AddToQueue, StartRadio, AddToPlaylist, Share, Like, Rate, GoToAlbum, GoToArtist),
            songActions(find = false, radio = true, share = true),
        )
        // A find is never shared: it is not in the server's library.
        assertEquals(listOf(PlayNext, AddToQueue, StartRadio, Download), songActions(find = true, radio = true, share = true))
    }

    @Test
    fun aFindOffersADownloadInsteadOfLibraryChoices() {
        assertEquals(listOf(PlayNext, AddToQueue, StartRadio, Download), songActions(find = true, radio = true))
        assertEquals(listOf(PlayNext, AddToQueue, Download), songActions(find = true, radio = false))
    }

    @Test
    fun tappingAStarSetsTheRatingAndTappingItAgainClearsIt() {
        assertEquals(4, nextRating(current = 0, tapped = 4))
        assertEquals(2, nextRating(current = 4, tapped = 2))
        assertEquals(0, nextRating(current = 4, tapped = 4))
    }

    @Test
    fun aSongOnlyOnTheServerCanBeDownloadedToThePhone() {
        assertEquals(
            listOf(PlayNext, AddToQueue, StartRadio, AddToPlaylist, KeepOffline, Like, Rate, GoToAlbum, GoToArtist),
            songActions(find = false, radio = true, offline = true),
        )
        // A find downloads to the server first, never straight to the phone.
        assertEquals(listOf(PlayNext, AddToQueue, Download), songActions(find = true, radio = false, offline = true))
    }

    @Test
    fun theKeepRowSaysWhereTheSongIs() {
        assertEquals("Download", keepOfflineLabel(null, byHand = false))
        assertEquals("Waiting to download", keepOfflineLabel(DownloadStatus.Queued, byHand = true))
        assertEquals("Downloading", keepOfflineLabel(DownloadStatus.Downloading, byHand = true))
        assertEquals("Remove download", keepOfflineLabel(DownloadStatus.Done, byHand = true))
        assertEquals("Kept downloaded", keepOfflineLabel(DownloadStatus.Done, byHand = false))
        assertEquals("Download failed, try again", keepOfflineLabel(DownloadStatus.Failed, byHand = false))
        // Only what a tap can do is offered: a rule's download goes with its rule.
        assertEquals(true, keepOfflineEnabled(null, byHand = false))
        assertEquals(false, keepOfflineEnabled(DownloadStatus.Done, byHand = false))
        assertEquals(true, keepOfflineEnabled(DownloadStatus.Done, byHand = true))
        assertEquals(false, keepOfflineEnabled(DownloadStatus.Downloading, byHand = true))
        assertEquals(true, keepOfflineEnabled(DownloadStatus.Failed, byHand = false))
    }

    @Test
    fun theDownloadRowSaysWhereTheDownloadIs() {
        assertEquals("Download", downloadLabel(DownloadState.None))
        assertEquals("Downloading", downloadLabel(DownloadState.Requested))
        assertEquals("In your library", downloadLabel(DownloadState.Done))
    }
}
