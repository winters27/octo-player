package app.winters.octo.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.winters.octo.data.Session
import androidx.compose.material3.Text
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.LocalSubsonic
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

// Everything after sign-in: one back stack per tab, the screens, and the
// floating bar over them.
@Composable
fun MainShell(session: Session) {
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

    CompositionLocalProvider(LocalSubsonic provides session.client, LocalHaze provides haze) {
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
                    entry<HomeRoute> { Placeholder("Home") }
                    entry<SearchRoute> { Placeholder("Search") }
                    entry<LibraryRoute> { Placeholder("Library") }
                    entry<SettingsRoute> { Placeholder("Settings") }
                    entry<AlbumRoute> { Placeholder("Album") }
                    entry<ArtistRoute> { Placeholder("Artist") }
                    entry<PlaylistRoute> { Placeholder("Playlist") }
                },
            )
            // Back from the top of another tab goes Home rather than out.
            BackHandler(enabled = selected != 0 && stack.size == 1) { selected = 0 }

            BottomBar(
                haze = haze,
                selected = selected,
                onSelect = { index ->
                    if (index == selected) {
                        // Tapping the current tab goes back to its top.
                        while (stack.size > 1) stack.removeAt(stack.lastIndex)
                    } else {
                        selected = index
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title, style = OctoType.display, color = OctoColors.TextMuted)
    }
}
