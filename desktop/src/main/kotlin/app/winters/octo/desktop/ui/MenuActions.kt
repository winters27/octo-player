package app.winters.octo.desktop.ui

import app.winters.octo.ui.downloads.FIND_SONGS
import app.winters.octo.ui.upgrade.FIND_HIGHER_QUALITY

// What each menu offers, and in which groups, worked out apart from how the
// rows are drawn so the choices can be checked without a window. Every menu
// runs in the same order: playing, keeping, going somewhere, looking into
// it, and last, what takes something away.

// Where a song menu was opened, for the removal it offers at the bottom.
sealed interface SongPlace {
    // Any list that is not a playlist or the queue: the library, search,
    // an album or an artist.
    data object Library : SongPlace

    // A playlist's page. `positions` are the picked rows' places in the
    // playlist's own order, counted from 0, as the server removes them.
    data class Playlist(val id: String, val positions: List<Int>) : SongPlace

    // The queue. `keys` are the picked entries' queue keys.
    data class Queue(val keys: List<Long>) : SongPlace
}

enum class SongAction {
    Play, PlayNext, AddToQueue, StartRadio,
    AddToLibrary, AddToLastPlaylist, AddToPlaylist, Favourite, Rate, FindFlac,
    GoToAlbum, GoToArtist, ShowInFolder,
    Details, FindSongs,
    Move,
    RemoveFromPlaylist, RemoveFromQueue,
}

// The song menu's rows in their groups. A radio and the details are for
// one song; so are Go to album and Go to artist. In the queue, playing
// and queueing give way to the queue's own rows (play now, move), since
// they would play or queue the songs a second time. Songs found online
// (`outside`) are not in the library, so they cannot be favourites, be
// rated or open an album or artist; where the server can fetch them
// (`canAdd`) they can be added to it. A playlist's songs can be moved or
// come out of it only when the listener's own playlist (`ownsPlaylist`).
// `lastPlaylist` is whether there is a playlist added to lately, offered
// first among the ways to keep the songs. Show in folder needs the server
// to have said which folder the song is in (`inFolder`). Find higher
// quality needs an Octo server that can look for a better copy and a
// picked library song of a kind that loses detail (`canUpgrade`); a song
// found online has no file to replace. Find songs, which runs one song's
// search again on the server's download sources to pick the copy, needs an
// Octo server that keeps a log of its downloads (`canFind`).
fun songMenuActions(
    count: Int,
    place: SongPlace,
    outside: Boolean = false,
    ownsPlaylist: Boolean = false,
    lastPlaylist: Boolean = false,
    inFolder: Boolean = false,
    canAdd: Boolean = false,
    canUpgrade: Boolean = false,
    canFind: Boolean = false,
): List<List<SongAction>> {
    val one = count == 1
    val editable = place is SongPlace.Playlist && ownsPlaylist && place.positions.isNotEmpty()
    val groups = listOf(
        if (place is SongPlace.Queue) {
            listOfNotNull(SongAction.StartRadio.takeIf { one })
        } else {
            listOfNotNull(SongAction.Play, SongAction.PlayNext, SongAction.AddToQueue, SongAction.StartRadio.takeIf { one })
        },
        listOfNotNull(
            SongAction.AddToLibrary.takeIf { outside && canAdd },
            SongAction.AddToLastPlaylist.takeIf { lastPlaylist },
            SongAction.AddToPlaylist,
            SongAction.Favourite.takeIf { !outside },
            SongAction.Rate.takeIf { !outside },
            SongAction.FindFlac.takeIf { canUpgrade && !outside },
        ),
        if (one && !outside) listOfNotNull(SongAction.GoToAlbum, SongAction.GoToArtist, SongAction.ShowInFolder.takeIf { inFolder }) else emptyList(),
        listOfNotNull(SongAction.Details.takeIf { one }, SongAction.FindSongs.takeIf { one && canFind }),
        listOfNotNull(SongAction.Move.takeIf { editable }),
        listOfNotNull(
            SongAction.RemoveFromPlaylist.takeIf { editable },
            SongAction.RemoveFromQueue.takeIf { place is SongPlace.Queue && place.keys.isNotEmpty() },
        ),
    )
    return groups.filter { it.isNotEmpty() }
}

// A song row's words. `starred` is whether every picked song is a
// favourite; `last` is the name of the playlist added to last.
fun songActionLabel(action: SongAction, starred: Boolean, last: String? = null): String = when (action) {
    SongAction.Play -> "Play"
    SongAction.PlayNext -> "Play next"
    SongAction.AddToQueue -> "Add to queue"
    SongAction.StartRadio -> "Start radio"
    SongAction.AddToLibrary -> "Add to your library"
    SongAction.AddToLastPlaylist -> "Add to last playlist: ${last.orEmpty()}"
    SongAction.AddToPlaylist -> "Add to playlist"
    SongAction.Favourite -> if (starred) "Remove from favorites" else "Add to favorites"
    SongAction.Rate -> "Rate"
    SongAction.FindFlac -> FIND_HIGHER_QUALITY
    SongAction.GoToAlbum -> "Go to album"
    SongAction.GoToArtist -> "Go to artist"
    SongAction.ShowInFolder -> "Show in folder"
    SongAction.Details -> "Song details"
    SongAction.FindSongs -> FIND_SONGS
    SongAction.Move -> "Move"
    SongAction.RemoveFromPlaylist -> "Remove from this playlist"
    SongAction.RemoveFromQueue -> "Remove from the queue"
}

// "3 stars", or nothing for a song not rated yet, as the phone says it.
fun starsLabel(rating: Int): String? = when {
    rating <= 0 -> null
    rating == 1 -> "1 star"
    else -> "$rating stars"
}

// The rating every picked song shares, or null when they differ.
fun sharedRating(ratings: List<Int>): Int? = ratings.distinct().singleOrNull()

enum class CollectionAction {
    Play, Shuffle, PlayNext, AddToQueue, StartRadio,
    AddToPlaylist, FindFlac, Favourite,
    Pin, JumpList,
    GoToArtist,
    Rename, Duplicate, Export, Public,
    Delete,
}

private val Playing = listOf(CollectionAction.Play, CollectionAction.Shuffle, CollectionAction.PlayNext, CollectionAction.AddToQueue)

// An album's menu. One found online has no favourite heart (on Octo a star
// would download it), no artist page to open and no place in the jump list.
// `jumpList` is whether there is a jump list to pin it to (Windows).
// `lossy` is how many of its songs a FLAC could replace, counted only when
// the server can look for one; 0 leaves the row out.
fun albumMenuActions(outside: Boolean = false, jumpList: Boolean = false, lossy: Int = 0): List<List<CollectionAction>> = listOf(
    Playing + CollectionAction.StartRadio,
    listOfNotNull(
        CollectionAction.AddToPlaylist,
        CollectionAction.FindFlac.takeIf { lossy > 0 && !outside },
        CollectionAction.Favourite.takeIf { !outside },
        CollectionAction.JumpList.takeIf { jumpList && !outside },
    ),
    listOfNotNull(CollectionAction.GoToArtist.takeIf { !outside }),
).filter { it.isNotEmpty() }

// An artist's menu: playing them, a radio, and their heart.
fun artistMenuActions(outside: Boolean = false): List<List<CollectionAction>> = listOf(
    Playing + CollectionAction.StartRadio,
    listOfNotNull(CollectionAction.Favourite.takeIf { !outside }),
).filter { it.isNotEmpty() }

// A playlist's menu. An empty one has nothing to play or write to a file.
// Only the listener's own (`owns`) can be renamed, made public or private,
// or deleted; anyone's can be copied into a new one of their own.
fun playlistMenuActions(empty: Boolean = false, owns: Boolean = false): List<List<CollectionAction>> = listOf(
    if (empty) emptyList() else Playing,
    listOf(CollectionAction.Pin),
    listOfNotNull(
        CollectionAction.Rename.takeIf { owns },
        CollectionAction.Duplicate,
        CollectionAction.Export.takeIf { !empty },
        CollectionAction.Public.takeIf { owns },
    ),
    listOfNotNull(CollectionAction.Delete.takeIf { owns }),
).filter { it.isNotEmpty() }
