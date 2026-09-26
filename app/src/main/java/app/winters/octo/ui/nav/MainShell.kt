package app.winters.octo.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateInt
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.winters.octo.design.OctoColors
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.player.PlayerArtCorner
import app.winters.octo.player.PlayerOverlay
import app.winters.octo.ui.admin.OctoAdminScreen
import app.winters.octo.ui.album.AlbumScreen
import app.winters.octo.ui.artist.ArtistScreen
import app.winters.octo.ui.common.ChoiceSheet
import app.winters.octo.ui.common.ChoiceSheetHost
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.genre.GenreScreen
import app.winters.octo.ui.genre.GenresScreen
import app.winters.octo.ui.home.HomeScreen
import app.winters.octo.ui.library.AlbumsScreen
import app.winters.octo.ui.library.ArtistsScreen
import app.winters.octo.ui.library.LibraryScreen
import app.winters.octo.ui.library.SongsScreen
import app.winters.octo.ui.menu.LocalSongMenu
import app.winters.octo.ui.menu.SongMenuHost
import app.winters.octo.ui.menu.SongMenuState
import app.winters.octo.ui.online.OnlineAlbumScreen
import app.winters.octo.ui.online.OnlineArtistScreen
import app.winters.octo.ui.playlist.LikedScreen
import app.winters.octo.ui.playlist.LocalPlaylistSheets
import app.winters.octo.ui.playlist.PlaylistScreen
import app.winters.octo.ui.playlist.PlaylistSheets
import app.winters.octo.ui.playlist.PlaylistSheetsHost
import app.winters.octo.ui.playlist.PlaylistsScreen
import app.winters.octo.ui.search.SearchScreen
import app.winters.octo.ui.settings.DisconnectPrompt
import app.winters.octo.ui.settings.DisconnectSheetHost
import app.winters.octo.ui.settings.LocalDisconnectPrompt
import app.winters.octo.ui.settings.SettingsScreen
import app.winters.octo.ui.signin.SignInScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

// The whole app: one back stack per tab, the screens, and the floating
// bar over them.
@Composable
fun MainShell(library: DeviceLibrary, playback: PlaybackConnection) {
    val now by playback.now.collectAsStateWithLifecycle()
    val haze = rememberHazeState()
    // Called once each, always in this order.
    val stacks = listOf(
        rememberNavBackStack(HomeRoute),
        rememberNavBackStack(SearchRoute),
        rememberNavBackStack(LibraryRoute),
        rememberNavBackStack(SettingsRoute),
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val stack = stacks[selected]
    val open: (NavKey) -> Unit = { stack.add(it) }
    val back: () -> Unit = { stack.removeLastOrNull() }
    val hasTrack = now.trackId != null
    // The bar's small player, and the full one over everything.
    var barPlayer by rememberSaveable { mutableStateOf(false) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    // When the queue empties, both fold away.
    LaunchedEffect(hasTrack) {
        if (!hasTrack) {
            barPlayer = false
            playerOpen = false
        }
    }
    val barActions = BarActions(
        onSelect = { index ->
            barPlayer = false
            if (index == selected) {
                // Tapping the current tab goes back to its top.
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
            } else {
                selected = index
            }
        },
        // With nothing loaded, the round button shuffles the library.
        onRoundButton = { if (hasTrack) barPlayer = true else playback.togglePlayPause() },
        onShowTabs = { barPlayer = false },
        onOpenPlayer = { playerOpen = true },
        onPrevious = playback::previous,
        onPlayPause = playback::togglePlayPause,
        onNext = playback::next,
    )

    // Access can be changed in system settings while the app is away.
    LifecycleResumeEffect(Unit) {
        library.refresh()
        onPauseOrDispose { }
    }

    val songMenu = remember { SongMenuState() }
    val playlistSheets = remember { PlaylistSheets() }
    val disconnectPrompt = remember { DisconnectPrompt() }
    val choiceSheet = remember { ChoiceSheet() }
    // Going to a page from the song menu also closes the player.
    val openFromMenu: (NavKey) -> Unit = { key ->
        playerOpen = false
        stack.add(key)
    }

    CompositionLocalProvider(
        LocalHaze provides haze,
        LocalSongMenu provides songMenu,
        LocalPlaylistSheets provides playlistSheets,
        LocalDisconnectPrompt provides disconnectPrompt,
        LocalChoiceSheet provides choiceSheet,
    ) {
        SharedTransitionLayout {
            Box(Modifier.fillMaxSize().background(OctoColors.Background)) {
                NavDisplay(
                    backStack = stack,
                    onBack = { stack.removeLastOrNull() },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    modifier = Modifier.fillMaxSize().hazeSource(haze),
                    entryProvider = entryProvider {
                        entry<HomeRoute> { HomeScreen(open) }
                        entry<SearchRoute> { SearchScreen(open) }
                        entry<LibraryRoute> { LibraryScreen(open) }
                        entry<SettingsRoute> { SettingsScreen(open) }
                        entry<AlbumRoute> { AlbumScreen(it.id, open, back) }
                        entry<ArtistRoute> { ArtistScreen(it.id, open, back) }
                        entry<OnlineAlbumRoute> { OnlineAlbumScreen(it.id, open, back) }
                        entry<OnlineArtistRoute> { OnlineArtistScreen(it.id, open, back) }
                        entry<AlbumsRoute> { AlbumsScreen(open, back) }
                        entry<ArtistsRoute> { ArtistsScreen(open, back) }
                        entry<SongsRoute> { SongsScreen(back) }
                        entry<GenresRoute> { GenresScreen(open, back) }
                        entry<GenreRoute> { GenreScreen(it.name, open, back) }
                        entry<PlaylistsRoute> { PlaylistsScreen(open, back) }
                        entry<LikedRoute> { LikedScreen(back) }
                        entry<PlaylistRoute> { PlaylistScreen(it.id, back) }
                        entry<SignInRoute> { SignInScreen(back) }
                        entry<EditConnectionRoute> { SignInScreen(back, editing = true) }
                        entry<OctoAdminRoute> { OctoAdminScreen(back) }
                    },
                )
                // Back from the top of another tab goes Home rather than out.
                BackHandler(enabled = selected != 0 && stack.size == 1) { selected = 0 }

                // The bar steps aside while the full player is open; the artwork
                // flies between the two.
                AnimatedVisibility(
                    visible = !playerOpen,
                    enter = fadeIn(tween(300)),
                    exit = fadeOut(tween(300)),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    // The mirror of the player's card: a soft square while it
                    // flies back, a circle once it lands.
                    val corner by transition.animateInt(label = "capsule art corners") {
                        if (it == EnterExitState.Visible) 50 else PlayerArtCorner
                    }
                    BottomBar(
                        haze = haze,
                        selected = selected,
                        now = now,
                        positionMs = playback::positionMs,
                        playerShown = barPlayer && hasTrack,
                        actions = barActions,
                        artModifier = Modifier.sharedElement(rememberSharedContentState(ArtKey), this),
                        artShape = RoundedCornerShape(percent = corner),
                    )
                }
                AnimatedVisibility(
                    visible = playerOpen && hasTrack,
                    enter = fadeIn(tween(400)),
                    exit = fadeOut(tween(300)),
                ) {
                    PlayerOverlay(
                        artModifier = Modifier.sharedElement(rememberSharedContentState(ArtKey), this),
                        onClose = { playerOpen = false },
                        onOpenArtist = { id ->
                            playerOpen = false
                            stack.add(ArtistRoute(id))
                        },
                    )
                }
                SongMenuHost(songMenu, onOpen = openFromMenu)
                PlaylistSheetsHost(playlistSheets)
                DisconnectSheetHost(disconnectPrompt)
                ChoiceSheetHost(choiceSheet)
            }
        }
    }
}

// One key for whichever song is on, so a song change mid-flight cannot
// break the hand-off.
private const val ArtKey = "now-playing-art"
