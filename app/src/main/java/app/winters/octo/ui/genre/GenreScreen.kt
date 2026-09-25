package app.winters.octo.ui.genre

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.ui.common.AlbumCard
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.albums
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.nav.AlbumRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel(assistedFactory = GenreViewModel.Factory::class)
class GenreViewModel @AssistedInject constructor(
    @Assisted name: String,
    dao: CatalogDao,
    private val playback: PlaybackConnection,
) : ViewModel() {
    val albums: StateFlow<List<AlbumEntity>> =
        dao.genreAlbums(name).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tracks: StateFlow<List<TrackEntity>> =
        dao.genreTracks(name).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Plays the genre's songs from one of them.
    fun play(index: Int) = playback.playTracks(tracks.value.map { it.id }, index)

    fun shuffle() = playback.playTracks(tracks.value.map { it.id }, shuffle = true)

    @AssistedFactory
    interface Factory {
        fun create(name: String): GenreViewModel
    }
}

// Album cards are at least this wide, as in the Library's grid.
private val CardWidth = 150.dp
private val CardGap = 14.dp

// One genre: play it all, its albums as a grid, then its songs.
@Composable
fun GenreScreen(
    name: String,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
    vm: GenreViewModel = hiltViewModel<GenreViewModel, GenreViewModel.Factory> { it.create(name) },
) {
    val albumList by vm.albums.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The grid sits in the song list, so its rows are laid out here: as
        // many cards as fit, all the same width.
        val columns = ((maxWidth - 40.dp + CardGap) / (CardWidth + CardGap)).toInt().coerceAtLeast(1)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(extraTop = DetailTopGap)) {
            item(key = "header") {
                Header(name, albumList.size, tracks.size, onPlay = { vm.play(0) }, onShuffle = vm::shuffle)
            }
            items(albumList.chunked(columns), key = { "albums:${it.first().id}" }) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(CardGap),
                ) {
                    row.forEach { album ->
                        AlbumCard(album, onClick = { onOpen(AlbumRoute(album.id)) }, modifier = Modifier.weight(1f), width = null)
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (tracks.isNotEmpty()) {
                item(key = "songs") { SectionTitle("Songs") }
            }
            items(tracks, key = { it.id }) { track ->
                SongRow(track) { vm.play(tracks.indexOf(track)) }
            }
        }
        BackButton(onBack)
    }
}

@Composable
private fun Header(name: String, albumCount: Int, songCount: Int, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, style = OctoType.title, color = OctoColors.TextPrimary, textAlign = TextAlign.Center)
        Text(
            "${albums(albumCount)} • ${songs(songCount)}",
            style = OctoType.caption,
            color = OctoColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentButton("Play", onClick = onPlay)
            GlazeButton("Shuffle", onClick = onShuffle)
        }
    }
}
