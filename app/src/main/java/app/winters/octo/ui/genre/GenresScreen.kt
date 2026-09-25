package app.winters.octo.ui.genre

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.GenreSummary
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.nav.GenreRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class GenresViewModel @Inject constructor(dao: CatalogDao) : ViewModel() {
    // Null until the first read, so a loading moment is not shown as empty.
    val genres: StateFlow<List<GenreSummary>?> =
        dao.genres().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

// Every genre in the library, with how many songs each has.
@Composable
fun GenresScreen(onOpen: (NavKey) -> Unit, onBack: () -> Unit, vm: GenresViewModel = hiltViewModel()) {
    val genres by vm.genres.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(extraTop = DetailTopGap)) {
            item(key = "title") { ScreenTitle("Genres") }
            genres?.let { list ->
                if (list.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "None of your songs has a genre yet",
                            style = OctoType.bodySmall,
                            color = OctoColors.TextMuted,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
                items(list, key = { "genre:${it.name}" }) { genre ->
                    GenreRow(genre) { onOpen(GenreRoute(genre.name)) }
                }
            }
        }
        BackButton(onBack)
    }
}

// A genre line: a cover from one of its albums, the name, the song count.
@Composable
private fun GenreRow(genre: GenreSummary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .height(64.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(genre.artwork, 48.dp, shape = RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f)) {
            Text(genre.name, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(songs(genre.songCount), style = OctoType.caption, color = OctoColors.TextMuted)
        }
    }
}
