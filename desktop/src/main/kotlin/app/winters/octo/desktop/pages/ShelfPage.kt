package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.home.AlbumShelf
import app.winters.octo.desktop.home.albumsOn
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.PageLoadingLine
import app.winters.octo.desktop.ui.LocalBottomRoom
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.ShelfCardWidth
import app.winters.octo.desktop.ui.pagePadding
import app.winters.octo.desktop.ui.rememberGridState
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.scrollbar
import androidx.compose.ui.Modifier
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.Space
import app.winters.octo.subsonic.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Every album on one of Home's shelves, as a grid that fills the width,
// worked out from the library away from the window's thread.
@Composable
fun ShelfPage(app: AppState, visit: Visit, shelf: AlbumShelf) {
    val grid = rememberGridState(app.navigator, visit)
    WithLibrary(app) { index ->
        val albums by produceState<List<Album>?>(null, index, shelf) {
            value = withContext(Dispatchers.Default) { albumsOn(shelf, index, System.currentTimeMillis()) }
        }
        val shown = albums ?: return@WithLibrary PageLoadingLine()
        LazyVerticalGrid(GridCells.Adaptive(ShelfCardWidth), Modifier.scrollbar(grid, LocalBottomRoom.current), state = grid, contentPadding = pagePadding(LocalBottomRoom.current)) {
            header { PageTitle(shelf.title, detail = if (shown.isEmpty()) null else "${if (shown.size == 1) "1 album" else "${shown.size} albums"}, ${shelf.detail}") }
            if (shown.isEmpty()) {
                header {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
                        NothingHere(shelf.emptyTitle, shelf.emptyDetail)
                        GlazeCapsule(OctoIcons.Album, "Browse albums", { app.navigator.go(Page.Albums) })
                    }
                }
            }
            items(shown, key = { it.id }) { AlbumCard(app, it) }
        }
    }
}
