package app.winters.octo.desktop.ui

import app.winters.octo.ui.upgrade.findHigherQualityLabel
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
                listOf("Add to playlist", "Add to favorites", "Rate"),
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
                listOf("Add to playlist", "Remove from favorites", "Rate"),
            ),
            labels(3, starred = true),
        )
    }

    @Test
    fun songsInTheListenersOwnPlaylistCanMoveOrComeOutOfIt() {
        val place = SongPlace.Playlist("p1", listOf(4, 7))
        assertEquals(listOf(listOf("Move"), listOf("Remove from this playlist")), labels(2, place, owns = true).takeLast(2))
        // Someone else's playlist, or one the server keeps, cannot be changed.
        assertEquals(listOf("Add to playlist", "Add to favorites", "Rate"), labels(2, place, owns = false).last())
    }

    @Test
    fun theLastPlaylistComesFirstAmongTheWaysToKeepSongs() {
        assertEquals(listOf("Add to last playlist: Late night", "Add to playlist", "Add to favorites", "Rate"), labels(1, last = "Late night")[1])
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
        assertEquals(listOf("Add to playlist", "Add to favorites", "Rate"), labels(2, SongPlace.Queue(listOf(12L, 13L))).first())
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
    fun aFamilyMemberWhoAsksSavesAndRequests() {
        val outside = songMenuActions(1, SongPlace.Library, outside = true, canAdd = true, canRequest = true)
        assertEquals(
            listOf("Save", "Request a copy", "Add to playlist"),
            outside[1].map { songActionLabel(it, false, addLabel = "Save") },
        )
        // Once saved, the same row takes it off Saved.
        assertEquals("Remove from Saved", songActionLabel(SongAction.AddToLibrary, true, addLabel = "Save"))
        // A request is for one song at a time, and never for a library song.
        assertEquals(false, songMenuActions(2, SongPlace.Library, outside = true, canAdd = true, canRequest = true).flatten().contains(SongAction.RequestCopy))
        assertEquals(false, songMenuActions(1, SongPlace.Library, canRequest = true).flatten().contains(SongAction.RequestCopy))
        // Without the family word, the add reads as always.
        assertEquals("Add to your library", songActionLabel(SongAction.AddToLibrary, true))
    }

    @Test
    fun aManagedMemberCanTakeSongsOutOfTheirOwnLibrary() {
        val last = songMenuActions(2, SongPlace.Library, canRemoveFromMine = true, canDelete = true).last()
        assertEquals(listOf(SongAction.RemoveFromMyLibrary, SongAction.DeleteFromDisk), last)
        assertEquals("Remove from my library", songActionLabel(SongAction.RemoveFromMyLibrary, false))
        assertEquals(false, songMenuActions(1, SongPlace.Library, outside = true, canRemoveFromMine = true).flatten().contains(SongAction.RemoveFromMyLibrary))
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
    fun findInFlacComesLastAmongTheWaysToKeepALibrarySong() {
        assertEquals(
            listOf("Add to playlist", "Add to favorites", "Rate", "Find higher quality"),
            songMenuActions(1, SongPlace.Library, canUpgrade = true)[1].map { songActionLabel(it, false) },
        )
        // Several picked: still offered, for those a FLAC could replace.
        assertEquals(SongAction.FindFlac, songMenuActions(3, SongPlace.Library, canUpgrade = true)[1].last())
        // In a playlist or the queue too: the song is the same library song.
        assertEquals(SongAction.FindFlac, songMenuActions(1, SongPlace.Queue(listOf(1L)), canUpgrade = true)[1].last())
    }

    @Test
    fun findSongsSitsBesideTheDetails_ForOneSongOnAServerThatKeepsLogs() {
        assertEquals(listOf("Song details", "Find songs"), songMenuActions(1, SongPlace.Library, canFind = true)[3].map { songActionLabel(it, false) })
        // A song found online can be looked for too, to pick its copy before it downloads.
        assertEquals(true, songMenuActions(1, SongPlace.Library, outside = true, canFind = true).flatten().contains(SongAction.FindSongs))
        assertEquals(false, songMenuActions(2, SongPlace.Library, canFind = true).flatten().contains(SongAction.FindSongs))
        assertEquals(false, songMenuActions(1, SongPlace.Library).flatten().contains(SongAction.FindSongs))
    }

    @Test
    fun findInFlacNeedsAServerThatCanAndNeverForSongsFoundOnline() {
        // Not offered when the server cannot, or no picked song loses detail.
        assertEquals(false, songMenuActions(1, SongPlace.Library).flatten().contains(SongAction.FindFlac))
        // A song found online has no file of its own to replace.
        assertEquals(false, songMenuActions(1, SongPlace.Library, outside = true, canAdd = true, canUpgrade = true).flatten().contains(SongAction.FindFlac))
    }

    @Test
    fun anAlbumOffersFindFlacWithHowManySongs() {
        val playing = listOf(CollectionAction.Play, CollectionAction.Shuffle, CollectionAction.PlayNext, CollectionAction.AddToQueue)
        assertEquals(
            listOf(
                playing + CollectionAction.StartRadio,
                listOf(CollectionAction.AddToPlaylist, CollectionAction.FindFlac, CollectionAction.Favourite),
                listOf(CollectionAction.GoToArtist),
            ),
            albumMenuActions(lossy = 9),
        )
        // None to replace, or an album found online: no row.
        assertEquals(albumMenuActions(), albumMenuActions(lossy = 0))
        assertEquals(albumMenuActions(outside = true), albumMenuActions(outside = true, lossy = 4))
        assertEquals("Find higher quality for 9 songs", findHigherQualityLabel(9))
        assertEquals("Find higher quality for 1 song", findHigherQualityLabel(1))
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

    @Test
    fun deleteFromDiskIsLastOfAll_OnlyWhereTheServerAllowsIt_AndNeverForASongFoundOnline() {
        val allowed = songMenuActions(1, SongPlace.Library, canDelete = true)
        assertEquals(listOf("Delete from disk"), allowed.last().map { songActionLabel(it, false) })
        assertEquals(false, songMenuActions(1, SongPlace.Library).flatten().contains(SongAction.DeleteFromDisk))
        assertEquals(false, songMenuActions(1, SongPlace.Library, outside = true, canDelete = true).flatten().contains(SongAction.DeleteFromDisk))
        // In a playlist it comes after taking the songs out of the playlist.
        val inPlaylist = songMenuActions(2, SongPlace.Playlist("p", listOf(0, 1)), ownsPlaylist = true, canDelete = true).last()
        assertEquals(listOf(SongAction.RemoveFromPlaylist, SongAction.DeleteFromDisk), inPlaylist)
    }
}
