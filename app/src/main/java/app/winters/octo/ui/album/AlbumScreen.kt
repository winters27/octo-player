package app.winters.octo.ui.album

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontWeight
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
import app.winters.octo.design.elevation3
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.SongLead
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.asLength
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.nav.ArtistRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel(assistedFactory = AlbumViewModel.Factory::class)
class AlbumViewModel @AssistedInject constructor(
    @Assisted private val id: String,
    dao: CatalogDao,
    private val playback: PlaybackConnection,
) : ViewModel() {
    val album: StateFlow<AlbumEntity?> =
        dao.album(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val tracks: StateFlow<List<TrackEntity>> =
        dao.albumTracks(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Plays the album from one of its songs.
    fun play(index: Int) = playback.playTracks(tracks.value.map { it.id }, index)

    fun shuffle() = playback.playAlbum(id, shuffle = true)

    @AssistedFactory
    interface Factory {
        fun create(id: String): AlbumViewModel
    }
}

private val CoverShape = RoundedCornerShape(10.dp)

@Composable
fun AlbumScreen(
    id: String,
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
    vm: AlbumViewModel = hiltViewModel<AlbumViewModel, AlbumViewModel.Factory> { it.create(id) },
) {
    val album by vm.album.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(extraTop = DetailTopGap)) {
            album?.let { a ->
                item(key = "header") {
                    AlbumHeader(
                        artwork = a.artwork,
                        title = a.title,
                        artist = a.artist,
                        details = listOfNotNull(
                            a.year?.toString(),
                            songs(a.songCount),
                            (a.durationMs / 1000).toInt().asLength(),
                        ).joinToString(" • "),
                        onArtist = { onOpen(ArtistRoute(a.artistId)) },
                        onPlay = { vm.play(0) },
                        onShuffle = vm::shuffle,
                        more = { AlbumShareButton(id, a.title) },
                    )
                }
            }
            val discs = tracks.groupBy { it.discNo ?: 1 }
            discs.forEach { (disc, onDisc) ->
                if (discs.size > 1) {
                    item(key = "disc:$disc") {
                        Text(
                            "Disc $disc",
                            style = OctoType.caption.copy(fontWeight = FontWeight.Bold),
                            color = OctoColors.TextMuted,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                }
                items(onDisc, key = { it.id }) { track ->
                    // Only say who is singing when it is not the album's artist.
                    val subtitle = track.artist.takeIf { it != album?.artist }
                    SongRow(track, SongLead.Number(track.trackNo), subtitle) { vm.play(tracks.indexOf(track)) }
                }
            }
        }
        BackButton(onBack)
    }
}

// The top of an album page: cover, title, artist, details, and the buttons
// that play it. The artist opens only when there is somewhere to go; `more`
// sits under the buttons, for an extra action.
@Composable
fun AlbumHeader(
    artwork: String?,
    title: String,
    artist: String,
    details: String,
    onArtist: (() -> Unit)?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    more: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(artwork, 240.dp, Modifier.elevation3(CoverShape), shape = CoverShape)
        Spacer(Modifier.height(20.dp))
        Text(title, style = OctoType.title, color = OctoColors.TextPrimary, textAlign = TextAlign.Center)
        Text(
            artist,
            style = OctoType.body,
            color = if (onArtist != null) OctoColors.Accent else OctoColors.TextSecondary,
            modifier = Modifier
                .padding(top = 4.dp)
                .then(if (onArtist != null) Modifier.clickable(onClick = onArtist) else Modifier),
        )
        Text(
            details,
            style = OctoType.caption,
            color = OctoColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentButton("Play", onClick = onPlay)
            GlazeButton("Shuffle", onClick = onShuffle)
        }
        more?.invoke()
    }
}
