package app.winters.octo.ui.menu

import app.winters.octo.covers.playlistGlyph
import app.winters.octo.ui.common.PlaylistArtwork
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.PinKind
import app.winters.octo.design.GlassPopup
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.PopupPager
import app.winters.octo.favourites.PinKey
import app.winters.octo.listening.FavouriteKind
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.GlassMenuAction
import app.winters.octo.ui.common.GlassMenuGroups
import app.winters.octo.ui.common.GlassMenuHeader
import app.winters.octo.ui.common.GlassMenuNote
import app.winters.octo.ui.common.GlassMenuPage
import app.winters.octo.ui.common.GlassMenuSeparator
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.PopupNameForm
import app.winters.octo.ui.common.albums
import app.winters.octo.ui.common.rememberOpenedBeside
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.nav.ArtistRoute
import app.winters.octo.ui.playlist.ConfirmDelete
import app.winters.octo.ui.playlist.PlaylistCover
import app.winters.octo.data.AlbumUpgrade
import app.winters.octo.ui.playlist.AddSource
import app.winters.octo.ui.playlist.PlaylistPickerPage
import app.winters.octo.ui.playlist.PlaylistSheetsViewModel
import app.winters.octo.playlists.PlaylistFolderWords
import app.winters.octo.ui.playlist.FolderMoveTarget
import app.winters.octo.ui.playlist.MoveToFolderPage
import app.winters.octo.ui.playlist.deletes
import app.winters.octo.ui.playlist.fileable
import app.winters.octo.ui.playlist.placeKey
import app.winters.octo.ui.playlist.renames
import app.winters.octo.ui.common.PopupQuestion
import app.winters.octo.ui.upgrade.KEEPS_ORIGINAL
import app.winters.octo.ui.upgrade.findHigherQualityAsk
import app.winters.octo.ui.upgrade.findHigherQualityLabel
import dev.chrisbanes.haze.HazeState

// The menu for an album, an artist or a playlist, a glass card like the
// song menu's, beside the card or row held. Adding to a playlist, renaming
// and deleting open as its next pages. `onOpen` goes to a page.
@Composable
fun CollectionMenuHost(
    state: CollectionMenuState,
    onOpen: (NavKey) -> Unit,
    backdrop: HazeState = LocalHaze.current,
    vm: CollectionMenuViewModel = hiltViewModel(),
) {
    val open = state.target != null
    GlassPopup(
        visible = open,
        anchor = rememberOpenedBeside(open),
        onDismiss = state::close,
        backdrop = backdrop,
        title = "Menu",
        onBack = state.pages::back,
    ) {
        val target = state.last ?: return@GlassPopup
        val back: () -> Unit = { state.pages.back() }
        PopupPager(state.pages) { page, _ ->
            when (page) {
                CollectionPage.Actions -> when (target) {
                    is CollectionTarget.Album -> AlbumMenu(target, state, vm, onOpen)
                    is CollectionTarget.Artist -> ArtistMenu(target, state, vm)
                    is CollectionTarget.Playlist -> PlaylistMenu(target, state, vm)
                }
                // The songs are named by what they are, and walked a page at
                // a time once a playlist is picked.
                CollectionPage.AddToPlaylist -> PlaylistPickerPage(AddSource.Spec(vm.addSpecOf(target)), onBack = back, onDone = state::close)
                CollectionPage.MoveToFolder -> PlaylistMoveToFolder(target, vm, onBack = back, onDone = state::close)
                CollectionPage.Rename -> PlaylistRename(target, onBack = back, onDone = state::close)
                CollectionPage.Delete -> PlaylistDelete(target, vm, onBack = back, onDone = state::close)
                CollectionPage.FindFlac -> AlbumFindFlac(target, vm, onBack = back, onDone = state::close)
            }
        }
    }
}

@Composable
private fun AlbumMenu(target: CollectionTarget.Album, state: CollectionMenuState, vm: CollectionMenuViewModel, onOpen: (NavKey) -> Unit) {
    val album by remember(target) { vm.album(target.id) }.collectAsStateWithLifecycle(null)
    val canDownload by remember(target) { vm.canDownload(target.id) }.collectAsStateWithLifecycle(false)
    val changes by remember { vm.trackChanges }.collectAsStateWithLifecycle(Unit)
    val favourites by vm.favouriteAlbums.collectAsStateWithLifecycle()
    val pin = PinKey(PinKind.Album, target.id)
    // From the row as it stands, so the menu opens on the right choice.
    val spot by remember(target) { vm.spotOf(pin) }.collectAsStateWithLifecycle(pinSpot(vm.pinned.value, pin))
    val radio by vm.radio.collectAsStateWithLifecycle()
    val canUpgrade by vm.canUpgrade.collectAsStateWithLifecycle()
    // Read again when the album's songs change, as after a FLAC lands.
    val lossy by produceState(0, target, canUpgrade, changes) {
        value = if (canUpgrade) vm.upgradableInAlbum(target.id).count else 0
    }
    val shown = album ?: return
    val actions = albumActions(canDownload = canDownload, favourite = target.id in favourites, pin = spot, radio = radio, lossy = lossy)
    CollectionActionsPage(
        shown.title,
        shown.artist,
        { Artwork(shown.artwork, 40.dp, shape = RoundedCornerShape(6.dp)) },
        target,
        actions,
        state,
        vm,
        lossy = lossy,
    ) { action ->
        when (action) {
            CollectionAction.Download -> vm.downloadAlbum(target.id)
            CollectionAction.GoToArtist -> onOpen(ArtistRoute(shown.artistId))
            CollectionAction.StartRadio -> vm.startAlbumRadio(target.id)
            else -> favouriteOrPin(action, vm, FavouriteKind.Album, pin)
        }
    }
}

@Composable
private fun ArtistMenu(target: CollectionTarget.Artist, state: CollectionMenuState, vm: CollectionMenuViewModel) {
    val artist by remember(target) { vm.artist(target.id) }.collectAsStateWithLifecycle(null)
    val radio by vm.radio.collectAsStateWithLifecycle()
    val favourites by vm.favouriteArtists.collectAsStateWithLifecycle()
    val pin = PinKey(PinKind.Artist, target.id)
    val spot by remember(target) { vm.spotOf(pin) }.collectAsStateWithLifecycle(pinSpot(vm.pinned.value, pin))
    val shown = artist ?: return
    val actions = artistActions(radio, favourite = target.id in favourites, pin = spot)
    CollectionActionsPage(shown.name, albums(shown.albumCount), { Artwork(shown.artwork, 40.dp, shape = CircleShape) }, target, actions, state, vm) { action ->
        if (action == CollectionAction.StartRadio) vm.startRadio(target.id) else favouriteOrPin(action, vm, FavouriteKind.Artist, pin)
    }
}

@Composable
private fun PlaylistMenu(target: CollectionTarget.Playlist, state: CollectionMenuState, vm: CollectionMenuViewModel) {
    val playlist by remember(target) { vm.playlist(target.id) }.collectAsStateWithLifecycle(null)
    val pinned by vm.pinned.collectAsStateWithLifecycle()
    val shown = playlist ?: return
    val pin = PinKey(PinKind.Playlist, target.id)
    val foldersOn by vm.foldersOffered.collectAsStateWithLifecycle()
    // An imported playlist's copy is never renamed or deleted here; one that
    // can be filed moves to a folder while folders are offered.
    val actions = playlistActions(
        empty = shown.songCount == 0,
        pin = pinSpot(pinned, pin),
        renames = shown.renames,
        deletes = shown.deletes,
        moves = foldersOn && shown.fileable,
    )
    CollectionActionsPage(
        shown.name,
        songs(shown.songCount),
        {
            PlaylistArtwork(shown.id, shown.name, shown.covers, 40.dp, shape = RoundedCornerShape(6.dp), glyph = playlistGlyph(shown.octoNotice, null)) {
                PlaylistCover(shown.covers, 40.dp, shape = RoundedCornerShape(6.dp))
            }
        },
        target,
        actions,
        state,
        vm,
    ) { action -> if (action == CollectionAction.Duplicate) vm.duplicatePlaylist(target.id) else favouriteOrPin(action, vm, null, pin) }
}

// The favourite and pin choices, the same for every kind. `kind` is null
// for a playlist, which cannot be a favourite.
private fun favouriteOrPin(action: CollectionAction, vm: CollectionMenuViewModel, kind: FavouriteKind?, pin: PinKey) {
    when (action) {
        CollectionAction.AddToFavourites -> kind?.let { vm.setFavourite(it, pin.id, favourite = true) }
        CollectionAction.RemoveFromFavourites -> kind?.let { vm.setFavourite(it, pin.id, favourite = false) }
        CollectionAction.PinToHome -> vm.pin(pin)
        CollectionAction.Unpin -> vm.unpin(pin)
        CollectionAction.MoveToFront -> vm.moveToFront(pin)
        else -> Unit
    }
}

// The collection at the top, then its actions in their groups. Playing and
// queueing work the same for every kind; the next pages open in place;
// `other` does the rest. Every other choice closes the menu.
@Composable
private fun CollectionActionsPage(
    title: String,
    subtitle: String,
    picture: @Composable () -> Unit,
    target: CollectionTarget,
    actions: List<CollectionAction>,
    state: CollectionMenuState,
    vm: CollectionMenuViewModel,
    // How many songs Find higher quality would ask for, for its words.
    lossy: Int = 0,
    other: (CollectionAction) -> Unit,
) {
    GlassMenuPage(
        header = {
            GlassMenuHeader(title, subtitle, picture)
            GlassMenuSeparator()
        },
    ) {
        GlassMenuGroups(collectionMenuGroups(actions)) { action ->
            val (icon, label) = when (action) {
                CollectionAction.Play -> OctoIcons.Play to "Play"
                CollectionAction.Shuffle -> OctoIcons.Shuffle to "Shuffle"
                CollectionAction.PlayNext -> OctoIcons.PlayNext to "Play next"
                CollectionAction.AddToQueue -> OctoIcons.AddToQueue to "Add to queue"
                CollectionAction.AddToPlaylist -> OctoIcons.AddToPlaylist to "Add to playlist"
                CollectionAction.Download -> OctoIcons.Download to "Download"
                CollectionAction.FindFlac -> OctoIcons.Lossless to findHigherQualityLabel(lossy)
                CollectionAction.StartRadio -> OctoIcons.Radio to "Start radio"
                CollectionAction.AddToFavourites -> OctoIcons.Like to "Add to favorites"
                CollectionAction.RemoveFromFavourites -> OctoIcons.Liked to "Remove from favorites"
                CollectionAction.PinToHome -> OctoIcons.Pin to "Pin to Home"
                CollectionAction.Unpin -> OctoIcons.Pin to "Unpin"
                CollectionAction.MoveToFront -> OctoIcons.Pin to "Move to front"
                CollectionAction.GoToArtist -> OctoIcons.Artist to "Go to artist"
                CollectionAction.Rename -> OctoIcons.Rename to "Rename"
                CollectionAction.MoveToFolder -> OctoIcons.PlaylistFolder to PlaylistFolderWords.MOVE_TO_FOLDER
                CollectionAction.Duplicate -> OctoIcons.AddToPlaylist to "Duplicate"
                CollectionAction.Delete -> OctoIcons.Delete to "Delete"
            }
            val page = when (action) {
                CollectionAction.AddToPlaylist -> CollectionPage.AddToPlaylist
                CollectionAction.Rename -> CollectionPage.Rename
                CollectionAction.Delete -> CollectionPage.Delete
                CollectionAction.FindFlac -> CollectionPage.FindFlac
                CollectionAction.MoveToFolder -> CollectionPage.MoveToFolder
                else -> null
            }
            GlassMenuAction(icon, label, opensPage = page != null, destructive = action == CollectionAction.Delete, onClick = {
                if (page != null) {
                    state.pages.open(page)
                    return@GlassMenuAction
                }
                state.close()
                when (action) {
                    CollectionAction.Play -> vm.play(target, shuffle = false)
                    CollectionAction.Shuffle -> vm.play(target, shuffle = true)
                    CollectionAction.PlayNext -> vm.playNext(target)
                    CollectionAction.AddToQueue -> vm.addToQueue(target)
                    else -> other(action)
                }
            })
        }
    }
}

// Renaming a playlist, a page of its menu.
@Composable
private fun PlaylistRename(target: CollectionTarget, onBack: () -> Unit, onDone: () -> Unit, sheets: PlaylistSheetsViewModel = hiltViewModel()) {
    val playlist = target as? CollectionTarget.Playlist ?: return
    val playlists by sheets.playlists.collectAsStateWithLifecycle()
    val name = playlists.firstOrNull { it.id == playlist.id }?.name ?: return
    PopupNameForm("Rename playlist", "Playlist name", "Save", onBack = onBack, initial = name, onDone = { typed ->
        sheets.rename(playlist.id, typed)
        onDone()
    })
}

// Asking before an album's songs go to the server to be looked for, a page
// of its menu, since it may be many downloads.
@Composable
private fun AlbumFindFlac(target: CollectionTarget, vm: CollectionMenuViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val album = target as? CollectionTarget.Album ?: return
    val asks by produceState<AlbumUpgrade?>(null, album) { value = vm.upgradableInAlbum(album.id) }
    val source by vm.upgradeSource.collectAsStateWithLifecycle()
    val shown = asks
    when {
        shown == null -> GlassMenuPage { GlassMenuNote("Gathering the songs") }
        shown.count == 0 -> GlassMenuPage { GlassMenuNote("Every song here is being looked for already") }
        else -> PopupQuestion(findHigherQualityAsk(shown.count, source), KEEPS_ORIGINAL, "Find higher quality", onConfirm = {
            vm.findFlac(shown)
            onDone()
        }, onCancel = onBack)
    }
}

// Moving a playlist to a folder, a page of its menu.
@Composable
private fun PlaylistMoveToFolder(
    target: CollectionTarget,
    vm: CollectionMenuViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    sheets: PlaylistSheetsViewModel = hiltViewModel(),
) {
    val playlist = target as? CollectionTarget.Playlist ?: return
    val shown by remember(playlist) { vm.playlist(playlist.id) }.collectAsStateWithLifecycle(null)
    val summary = shown ?: return
    MoveToFolderPage(FolderMoveTarget.Playlists(listOf(summary.placeKey), summary.name), onBack = onBack, onDone = onDone, vm = sheets)
}

// Asking before a playlist is deleted, a page of its menu.
@Composable
private fun PlaylistDelete(
    target: CollectionTarget,
    vm: CollectionMenuViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    sheets: PlaylistSheetsViewModel = hiltViewModel(),
) {
    val playlist = target as? CollectionTarget.Playlist ?: return
    val shown by remember(playlist) { vm.playlist(playlist.id) }.collectAsStateWithLifecycle(null)
    val summary = shown ?: return
    ConfirmDelete(summary.name, summary.onServer, family = summary.family, onCancel = onBack) {
        sheets.delete(summary.id)
        onDone()
    }
}
