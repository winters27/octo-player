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
import kotlinx.coroutines.launch

private enum class MenuPage { Main, Playlists, Rate }

// The menu for songs: one, or everything picked in a table. Its rows and
// groups come from songMenuActions; `place` says where it was opened, for
// taking the songs out of a playlist or the queue. Songs found online
// (`outside`) are not in the library, so they have no favourite heart (on
// Octo a star would fetch them), no rating and no album or artist to open.
@Composable
fun ColumnScope.SongMenu(app: AppState, songs: List<Song>, close: () -> Unit, outside: Boolean = false, place: SongPlace = SongPlace.Library) {
    var page by remember { mutableStateOf(MenuPage.Main) }
    if (songs.isEmpty()) return
    val one = songs.singleOrNull()
    when (page) {
        MenuPage.Main -> {
            MenuTitle(one?.title ?: "${songs.size} songs")
            val owns = (place as? SongPlace.Playlist)?.let { p -> app.playlists.firstOrNull { it.id == p.id }?.let(app::canEdit) } == true
            val starred = songs.all(app::isStarred)
            val rating = sharedRating(songs.map(app::ratingOf))
            songMenuActions(songs.size, place, outside, owns).forEachIndexed { index, group ->
                if (index > 0) MenuSeparator()
                group.forEach { action ->
                    val label = songActionLabel(action, starred)
                    when (action) {
                        SongAction.Play -> MenuRow(label, { app.play(songs); close() }, OctoIcons.Play)
                        SongAction.PlayNext -> MenuRow(label, { app.playNext(songs); close() }, OctoIcons.PlayNext)
                        SongAction.AddToQueue -> MenuRow(label, { app.addToQueue(songs); close() }, OctoIcons.AddToQueue)
                        SongAction.StartRadio -> MenuRow(label, { one?.let(app::startRadio); close() }, OctoIcons.Radio)
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
                        SongAction.Details -> MenuRow(label, { app.showInfo(one); close() }, OctoIcons.Info)
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
        MenuPage.Playlists -> PlaylistChooser(app, { songs }, close) { page = MenuPage.Main }
        MenuPage.Rate -> RateMenu(sharedRating(songs.map(app::ratingOf)), { app.setRating(songs, it); close() }) { page = MenuPage.Main }
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
    albumMenuActions(outside).forEachIndexed { index, group ->
        if (index > 0) MenuSeparator()
        group.forEach { action ->
            when (action) {
                CollectionAction.StartRadio -> MenuRow("Start radio", { app.startAlbumRadio(album.id); close() }, OctoIcons.Radio)
                CollectionAction.Favourite -> {
                    val starred = app.isAlbumStarred(album.id, album.starred)
                    MenuRow(if (starred) "Remove from favourites" else "Add to favourites", { app.setAlbumStarred(album.id, !starred); close() }, if (starred) OctoIcons.Liked else OctoIcons.Like)
                }
                CollectionAction.GoToArtist -> MenuRow("Go to artist", { album.artistId?.let { app.navigator.go(Page.Artist(it, album.artist)) }; close() }, OctoIcons.Artist, enabled = !album.artistId.isNullOrEmpty())
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

// The menu for a playlist, in the sidebar or a list: play it, or pin it to
// the top of the sidebar.
@Composable
fun ColumnScope.PlaylistMenu(app: AppState, playlist: Playlist, close: () -> Unit) {
    MenuTitle(playlist.name)
    playlistMenuActions(empty = playlist.songCount == 0).forEachIndexed { index, group ->
        if (index > 0) MenuSeparator()
        group.forEach { action ->
            when (action) {
                CollectionAction.Pin -> {
                    val pinned = app.isPinned(playlist.id)
                    MenuRow(if (pinned) "Unpin" else "Pin to the top", { app.setPinned(playlist.id, !pinned); close() }, OctoIcons.Pin)
                }
                else -> CollectionRow(app, action, { app.playlistSongs(playlist.id) }, close) {}
            }
        }
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

// Picks a playlist to add songs to, or makes a new one with them. The
// songs are gathered only once a playlist is chosen.
@Composable
fun ColumnScope.PlaylistChooser(app: AppState, songs: suspend () -> List<Song>, close: () -> Unit, back: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    MenuRow("Back", back, OctoIcons.Back)
    MenuSeparator()
    if (naming) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.L, vertical = Space.S), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            val create = {
                if (name.isNotBlank()) {
                    close()
                    app.scope.launch { app.createPlaylist(name, runCatching { songs() }.getOrDefault(emptyList())) }
                }
            }
            GlassField(name, { name = it }, Modifier.weight(1f), placeholder = "Playlist name", onSubmit = create)
            GlazeCapsule(null, "Create", create, lit = true, enabled = name.isNotBlank())
        }
    } else {
        MenuRow("New playlist", { naming = true }, OctoIcons.AddToLibrary)
    }
    val own = app.playlists.filter(app::canEdit)
    if (own.isNotEmpty()) MenuSeparator()
    own.forEach { playlist ->
        MenuRow(playlist.name, {
            close()
            app.scope.launch { app.addToPlaylist(playlist.id, runCatching { songs() }.getOrDefault(emptyList())) }
        }, OctoIcons.Playlists, detail = "${playlist.songCount}")
    }
}
