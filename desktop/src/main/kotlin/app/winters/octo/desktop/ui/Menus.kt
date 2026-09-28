package app.winters.octo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.artistSongs
import app.winters.octo.desktop.library.isOutsideSong
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.playlistSongs
import app.winters.octo.desktop.ratingOf
import app.winters.octo.desktop.removeFromPlaylist
import app.winters.octo.desktop.removeFromQueue
import app.winters.octo.desktop.setRating
import app.winters.octo.desktop.startAlbumRadio
import app.winters.octo.desktop.startArtistRadio
import app.winters.octo.desktop.startRadio
import app.winters.octo.design.GlassField
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.Space
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.IntOffset
import app.winters.octo.desktop.AddQuestion
import app.winters.octo.desktop.addSongsToPlaylist
import app.winters.octo.desktop.addToPlaylistChecked
import app.winters.octo.desktop.checkAdd
import app.winters.octo.desktop.deletePlaylist
import app.winters.octo.desktop.duplicatePlaylist
import app.winters.octo.desktop.exportPlaylist
import app.winters.octo.desktop.lastPlaylists
import app.winters.octo.desktop.moveInPlaylist
import app.winters.octo.desktop.newPlaylistWith
import app.winters.octo.desktop.playlistView
import app.winters.octo.desktop.playlists.PlaylistMove
import app.winters.octo.desktop.playlists.choosePlaylistFile
import app.winters.octo.desktop.playlists.movesAnything
import app.winters.octo.desktop.renamePlaylist
import app.winters.octo.desktop.setPlaylistPublic
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.system.JumpKind
import app.winters.octo.desktop.system.JumpTarget
import app.winters.octo.design.OctoType
import app.winters.octo.design.Txt
import app.winters.octo.playlists.playlistFileName
import app.winters.octo.ui.playlist.ADD_NEW_ONES
import app.winters.octo.ui.playlist.addAgainChoice
import kotlinx.coroutines.launch

private enum class MenuPage { Main, Playlists, Rate, Move }

// The menu for songs: one, or everything picked in a table. Its rows and
// groups come from songMenuActions; `place` says where it was opened, for
// taking the songs out of a playlist or the queue. Songs found online
// (`outside`) are not in the library, so they have no favourite heart (on
// Octo a star would fetch them), no rating and no album or artist to open.
// `extra` is a first group of the place's own rows (the queue's moves).
@Composable
fun ColumnScope.SongMenu(
    app: AppState,
    songs: List<Song>,
    close: () -> Unit,
    outside: Boolean = false,
    place: SongPlace = SongPlace.Library,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var page by remember { mutableStateOf(MenuPage.Main) }
    // The playlist the chooser adds to at once: the last one used.
    var start by remember { mutableStateOf<Playlist?>(null) }
    if (songs.isEmpty()) return
    val one = songs.singleOrNull()
    val inPlaylist = place as? SongPlace.Playlist
    when (page) {
        MenuPage.Main -> {
            MenuTitle(one?.title ?: "${songs.size} songs")
            val owns = inPlaylist?.let { p -> app.playlists.firstOrNull { it.id == p.id }?.let(app::canEdit) } == true
            val starred = songs.all(app::isStarred)
            val rating = sharedRating(songs.map(app::ratingOf))
            if (extra != null) {
                extra()
                MenuSeparator()
            }
            val last = app.lastPlaylists().firstOrNull()
            val fetches = app.fetches
            songMenuActions(songs.size, place, outside, owns, lastPlaylist = last != null, inFolder = !one?.parent.isNullOrEmpty(), canAdd = fetches != null).forEachIndexed { index, group ->
                if (index > 0) MenuSeparator()
                group.forEach { action ->
                    val label = songActionLabel(action, starred, last?.name)
                    when (action) {
                        SongAction.Play -> MenuRow(label, { app.play(songs); close() }, OctoIcons.Play)
                        SongAction.PlayNext -> MenuRow(label, { app.playNext(songs); close() }, OctoIcons.PlayNext)
                        SongAction.AddToQueue -> MenuRow(label, { app.addToQueue(songs); close() }, OctoIcons.AddToQueue)
                        SongAction.StartRadio -> MenuRow(label, { one?.let(app::startRadio); close() }, OctoIcons.Radio)
                        // Asks for the picked songs the library does not have.
                        SongAction.AddToLibrary -> MenuRow(label, {
                            val index = app.library?.index
                            songs.filter { isOutsideSong(it, index, canFetch = true) }.forEach { fetches?.request(it.id) }
                            close()
                        }, OctoIcons.AddToLibrary)
                        SongAction.AddToLastPlaylist -> MenuRow(label, {
                            start = last
                            page = MenuPage.Playlists
                        }, OctoIcons.AddToPlaylist)
                        SongAction.AddToPlaylist -> MenuRow(label, { page = MenuPage.Playlists }, OctoIcons.AddToPlaylist, more = true)
                        SongAction.Favourite -> MenuRow(label, { app.setStarred(songs, !starred); close() }, if (starred) OctoIcons.Liked else OctoIcons.Like)
                        SongAction.Rate -> MenuRow(
                            label,
                            { page = MenuPage.Rate },
                            if ((rating ?: 0) > 0) OctoIcons.StarFilled else OctoIcons.Star,
                            more = true,
                            detail = rating?.let(::starsLabel),
                        )
                        SongAction.GoToAlbum -> MenuRow(label, { one?.albumId?.let { app.navigator.go(Page.Album(it)) }; close() }, OctoIcons.Album, enabled = !one?.albumId.isNullOrEmpty())
                        SongAction.GoToArtist -> MenuRow(label, { one?.artistId?.let { app.navigator.go(Page.Artist(it, one.artist.orEmpty())) }; close() }, OctoIcons.Artist, enabled = !one?.artistId.isNullOrEmpty())
                        SongAction.ShowInFolder -> MenuRow(label, { one?.parent?.let { app.navigator.go(Page.Folder(it, "", focus = one.id)) }; close() }, OctoIcons.Folder)
                        SongAction.Details -> MenuRow(label, { app.showInfo(one); close() }, OctoIcons.Info)
                        SongAction.Move -> MenuRow(label, { page = MenuPage.Move }, OctoIcons.Sort, more = true)
                        SongAction.RemoveFromPlaylist -> MenuRow(label, {
                            (place as? SongPlace.Playlist)?.let { app.removeFromPlaylist(it.id, it.positions) }
                            close()
                        }, OctoIcons.RemoveFromPlaylist, destructive = true)
                        SongAction.RemoveFromQueue -> MenuRow(label, {
                            (place as? SongPlace.Queue)?.let { app.removeFromQueue(it.keys) }
                            close()
                        }, OctoIcons.Close, destructive = true)
                    }
                }
            }
        }
        MenuPage.Playlists -> PlaylistChooser(app, { songs }, close, start = start) {
            start = null
            page = MenuPage.Main
        }
        MenuPage.Rate -> RateMenu(sharedRating(songs.map(app::ratingOf)), { app.setRating(songs, it); close() }) { page = MenuPage.Main }
        MenuPage.Move -> if (inPlaylist != null) MoveMenu(app, inPlaylist, close) { page = MenuPage.Main }
    }
}

// Moving picked songs within the listener's own playlist: to the top, up
// one, down one, or to the bottom. A move that would change nothing is dim.
@Composable
private fun ColumnScope.MoveMenu(app: AppState, place: SongPlace.Playlist, close: () -> Unit, back: () -> Unit) {
    val count = app.playlistView(place.id)?.entry?.size ?: app.playlists.firstOrNull { it.id == place.id }?.songCount ?: 0
    MenuRow("Back", back, OctoIcons.Back)
    MenuSeparator()
    PlaylistMove.entries.forEach { move ->
        val icon = when (move) {
            PlaylistMove.Top, PlaylistMove.Up -> OctoIcons.Ascending
            PlaylistMove.Down, PlaylistMove.Bottom -> OctoIcons.Descending
        }
        MenuRow(move.label, { app.moveInPlaylist(place.id, place.positions, move); close() }, icon, enabled = movesAnything(move, count, place.positions))
    }
}

// Five rows of stars, most first, then none; the rating the songs share is
// ticked. The Octo server can act on a rating when its admin has set that
// up, just as it does for the phone's.
@Composable
private fun ColumnScope.RateMenu(current: Int?, rate: (Int) -> Unit, back: () -> Unit) {
    MenuRow("Back", back, OctoIcons.Back)
    MenuSeparator()
    for (stars in 5 downTo 1) {
        MenuRow(starsLabel(stars)!!, { rate(stars) }, leading = { Stars(stars) }, checked = current == stars)
    }
    MenuSeparator()
    MenuRow("No rating", { rate(0) }, leading = { Stars(0) }, checked = current == 0)
}

// A rating as five stars, `lit` of them filled.
@Composable
private fun Stars(lit: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.Xxs), verticalAlignment = Alignment.CenterVertically) {
        for (star in 1..5) {
            val on = star <= lit
            Glyph(if (on) OctoIcons.StarFilled else OctoIcons.Star, size = IconSize.Inline, tint = if (on) OctoColors.TextPrimary else OctoColors.TextMuted)
        }
    }
}

// The rows a collection's menu shares: play, shuffle, queue it, add it to
// a playlist. The songs are read only once a row is picked.
@Composable
private fun CollectionRow(
    app: AppState,
    action: CollectionAction,
    songs: suspend () -> List<Song>,
    close: () -> Unit,
    choose: () -> Unit,
) {
    fun withSongs(use: (List<Song>) -> Unit) {
        close()
        app.scope.launch {
            val read = try {
                songs()
            } catch (e: SubsonicException) {
                emptyList()
            }
            if (read.isNotEmpty()) use(read)
        }
    }
    when (action) {
        CollectionAction.Play -> MenuRow("Play", { withSongs { app.play(it) } }, OctoIcons.Play)
        CollectionAction.Shuffle -> MenuRow("Shuffle", { withSongs { app.play(it, shuffle = true) } }, OctoIcons.Shuffle)
        CollectionAction.PlayNext -> MenuRow("Play next", { withSongs { app.playNext(it) } }, OctoIcons.PlayNext)
        CollectionAction.AddToQueue -> MenuRow("Add to queue", { withSongs { app.addToQueue(it) } }, OctoIcons.AddToQueue)
        CollectionAction.AddToPlaylist -> MenuRow("Add to playlist", choose, OctoIcons.AddToPlaylist, more = true)
        else -> Unit
    }
}

// The menu for an album card: play it, start a radio from it, keep it.
@Composable
fun ColumnScope.AlbumMenu(app: AppState, album: Album, close: () -> Unit, outside: Boolean = false) {
    var choosing by remember { mutableStateOf(false) }
    val songs: suspend () -> List<Song> = { app.albumSongs(album.id) }
    if (choosing) {
        PlaylistChooser(app, songs, close) { choosing = false }
        return
    }
    MenuTitle(album.name)
    val jumpList = app.jumpList
    albumMenuActions(outside, jumpList = jumpList != null).forEachIndexed { index, group ->
        if (index > 0) MenuSeparator()
        group.forEach { action ->
            when (action) {
                CollectionAction.StartRadio -> MenuRow("Start radio", { app.startAlbumRadio(album.id); close() }, OctoIcons.Radio)
                CollectionAction.Favourite -> {
                    val starred = app.isAlbumStarred(album.id, album.starred)
                    MenuRow(if (starred) "Remove from favourites" else "Add to favourites", { app.setAlbumStarred(album.id, !starred); close() }, if (starred) OctoIcons.Liked else OctoIcons.Like)
                }
                CollectionAction.GoToArtist -> MenuRow("Go to artist", { album.artistId?.let { app.navigator.go(Page.Artist(it, album.artist)) }; close() }, OctoIcons.Artist, enabled = !album.artistId.isNullOrEmpty())
                CollectionAction.JumpList -> if (jumpList != null) {
                    val target = JumpTarget(JumpKind.Album, album.id, album.name, album.displayArtist ?: album.artist.ifBlank { null })
                    val pinned = jumpList.isPinned(target)
                    MenuRow(if (pinned) "Unpin from jump list" else "Pin to jump list", { jumpList.setPinned(target, !pinned); close() }, OctoIcons.Pin)
                }
                else -> CollectionRow(app, action, songs, close) { choosing = true }
            }
        }
    }
}

// The menu for an artist, on a card or their page: play everything they
// have, album by album, start a radio of songs like theirs, or keep them.
// `starred` is the heart the server sent with the artist.
@Composable
fun ColumnScope.ArtistMenu(app: AppState, id: String, name: String, starred: String?, close: () -> Unit, outside: Boolean = false) {
    MenuTitle(name)
    artistMenuActions(outside).forEachIndexed { index, group ->
        if (index > 0) MenuSeparator()
        group.forEach { action ->
            when (action) {
                CollectionAction.StartRadio -> MenuRow("Start radio", { app.startArtistRadio(id, name); close() }, OctoIcons.Radio)
                CollectionAction.Favourite -> {
                    val on = app.isArtistStarred(id, starred)
                    MenuRow(if (on) "Remove from favourites" else "Add to favourites", { app.setArtistStarred(id, !on); close() }, if (on) OctoIcons.Liked else OctoIcons.Like)
                }
                else -> CollectionRow(app, action, { app.artistSongs(id) }, close) {}
            }
        }
    }
}

// The menu for a playlist, in the sidebar, on its page or in a list: play
// it, pin it, rename it in place, copy it, write it to a file, and for the
// listener's own, make it public or private or delete it (asked first, in
// the menu itself).
@Composable
fun ColumnScope.PlaylistMenu(app: AppState, playlist: Playlist, close: () -> Unit) {
    var deleting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var name by remember(playlist.name) { mutableStateOf(playlist.name) }
    if (deleting) {
        ConfirmDelete(app, playlist, close) { deleting = false }
        return
    }
    MenuTitle(playlist.name)
    playlistMenuActions(empty = playlist.songCount == 0, owns = app.canEdit(playlist)).forEachIndexed { index, group ->
        if (index > 0) MenuSeparator()
        group.forEach { action ->
            when (action) {
                CollectionAction.Pin -> {
                    val pinned = app.isPinned(playlist.id)
                    MenuRow(if (pinned) "Unpin" else "Pin to the top", { app.setPinned(playlist.id, !pinned); close() }, OctoIcons.Pin)
                }
                CollectionAction.Rename -> if (renaming) {
                    val save = {
                        if (name.isNotBlank()) {
                            if (name.trim() != playlist.name) app.renamePlaylist(playlist.id, name)
                            close()
                        }
                    }
                    NameField(name, { name = it }, "Playlist name", "Save", save) { renaming = false }
                } else {
                    MenuRow("Rename", { renaming = true }, OctoIcons.Rename)
                }
                CollectionAction.Duplicate -> MenuRow("Duplicate", { app.duplicatePlaylist(playlist); close() }, OctoIcons.AddToPlaylist)
                CollectionAction.Export -> MenuRow("Export as M3U", {
                    close()
                    // After the menu has gone, since the file window holds the window's thread.
                    app.scope.launch { choosePlaylistFile(save = true, windows = app.os == DesktopOs.Windows, suggested = playlistFileName(playlist.name))?.let { app.exportPlaylist(playlist, it) } }
                }, OctoIcons.Download)
                CollectionAction.Public -> MenuRow(
                    if (playlist.public) "Make private" else "Make public",
                    { app.setPlaylistPublic(playlist.id, !playlist.public); close() },
                    OctoIcons.Share,
                )
                CollectionAction.Delete -> MenuRow("Delete", { deleting = true }, OctoIcons.Delete, destructive = true, more = true)
                else -> CollectionRow(app, action, { app.playlistSongs(playlist.id) }, close) {}
            }
        }
    }
}

// Asks before a playlist is deleted, in the menu that offered it. Its songs
// stay in the library.
@Composable
private fun ColumnScope.ConfirmDelete(app: AppState, playlist: Playlist, close: () -> Unit, cancel: () -> Unit) {
    Question("Delete \"${playlist.name}\"?", "It can't be undone. The songs stay in your library.")
    MenuRow("Delete", { app.deletePlaylist(playlist); close() }, OctoIcons.Delete, destructive = true)
    MenuRow("Cancel", cancel, OctoIcons.Close)
}

// A question a menu asks in place of its rows, with a quieter line under it.
@Composable
internal fun Question(words: String, detail: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.Xl, vertical = Space.M), verticalArrangement = Arrangement.spacedBy(Space.Xs)) {
        Txt(words, OctoType.bodySmall, OctoColors.TextPrimary, maxLines = 3)
        if (detail != null) Txt(detail, OctoType.caption, OctoColors.TextMuted, maxLines = 3)
    }
}

// A name typed in place of a menu row, like a new playlist's or a new
// name for one: Enter or the button saves, Escape goes back to the row.
@Composable
internal fun NameField(value: String, onChange: (String) -> Unit, placeholder: String, action: String, save: () -> Unit, cancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.L, vertical = Space.S), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
        GlassField(value, onChange, Modifier.weight(1f), placeholder = placeholder, focusRequester = focus, onSubmit = save, onEscape = cancel)
        GlazeCapsule(null, action, save, lit = true, enabled = value.isNotBlank())
    }
}

// Opens an artist's menu where the pointer is, for a card's right click:
// `MediaCard(..., onMenu = artistMenu(app, artist))`.
@Composable
fun artistMenu(app: AppState, artist: Artist, outside: Boolean = false): () -> Unit {
    val pointer = LocalPointer.current
    return { app.popups.showAt(pointer.point) { close -> ArtistMenu(app, artist.id, artist.name, artist.starred, close, outside) } }
}

// Opens a playlist's menu where the pointer is, for a row's right click:
// `Modifier.onRightClick(playlistMenu(app, playlist))`.
@Composable
fun playlistMenu(app: AppState, playlist: Playlist): () -> Unit {
    val pointer = LocalPointer.current
    return { app.popups.showAt(pointer.point) { close -> PlaylistMenu(app, playlist, close) } }
}

// Picks a playlist to add songs to, or makes a new one with them: the
// playlists added to lately first, then the rest of the listener's own.
// The songs are gathered only once a playlist is chosen. When some are on
// it already, the chooser turns into the question of what to add. With
// `start`, it adds to that playlist at once, as "Add to last playlist" does.
@Composable
fun ColumnScope.PlaylistChooser(app: AppState, songs: suspend () -> List<Song>, close: () -> Unit, start: Playlist? = null, back: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<AddQuestion?>(null) }
    // The playlist being checked, while the server is asked what it holds.
    var adding by remember { mutableStateOf(start) }
    suspend fun gathered(): List<Song> = try {
        songs()
    } catch (e: SubsonicException) {
        emptyList()
    }
    fun pick(playlist: Playlist) {
        adding = playlist
        app.scope.launch {
            val question = app.checkAdd(playlist, gathered())
            if (question == null) close() else asking = question
            adding = null
        }
    }
    LaunchedEffect(start) { start?.let(::pick) }
    asking?.let { question ->
        AddAgainMenu(app, question, close) { asking = null }
        return
    }
    MenuRow("Back", back, OctoIcons.Back)
    MenuSeparator()
    adding?.let {
        MenuRow("Adding to ${it.name}", {}, OctoIcons.AddToPlaylist, enabled = false)
        return
    }
    if (naming) {
        val create = {
            if (name.isNotBlank()) {
                close()
                app.scope.launch { app.newPlaylistWith(name, gathered()) }
            }
        }
        NameField(name, { name = it }, "Playlist name", "Create", create) { naming = false }
    } else {
        MenuRow("New playlist", { naming = true }, OctoIcons.AddToLibrary)
    }
    val recent = app.lastPlaylists()
    val rest = app.playlists.filter(app::canEdit) - recent.toSet()
    listOf(recent, rest).filter { it.isNotEmpty() }.forEach { group ->
        MenuSeparator()
        group.forEach { playlist -> MenuRow(playlist.name, { pick(playlist) }, OctoIcons.Playlists, detail = "${playlist.songCount}") }
    }
}

// Asks what to add when some of the songs are on the playlist already: only
// the new ones (when there are some), all of them anyway, or nothing. It is
// the chooser's own page, and the menu a drop on a playlist opens.
@Composable
fun ColumnScope.AddAgainMenu(app: AppState, question: AddQuestion, close: () -> Unit, back: (() -> Unit)? = null) {
    if (back != null) {
        MenuRow("Back", back, OctoIcons.Back)
        MenuSeparator()
    }
    Question(question.words)
    if (question.plan.offersNewOnly) MenuRow(ADD_NEW_ONES, { app.addSongsToPlaylist(question.playlist, question.fresh); close() }, OctoIcons.AddToPlaylist)
    MenuRow(addAgainChoice(question.plan), { app.addSongsToPlaylist(question.playlist, question.all); close() }, OctoIcons.AddToPlaylist)
    MenuRow("Cancel", close, OctoIcons.Close)
}

// Adds songs dropped on a playlist: straight in when none are there yet,
// else the question opens where they were dropped. Only the listener's own
// playlists take songs; a drop on another's says so in the notice line.
fun addDroppedSongs(app: AppState, playlist: Playlist, songs: List<Song>, at: IntOffset) =
    app.addToPlaylistChecked(playlist, songs) { question -> app.popups.showAt(at) { close -> AddAgainMenu(app, question, close) } }
