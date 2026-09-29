package app.winters.octo.ui.livelists

import app.winters.octo.covers.LIVE_LIST_COVER_LINE
import app.winters.octo.ui.common.PlaylistArtwork
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.mosaicCovers
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.LiveListSongs
import app.winters.octo.livelists.LiveListStore
import app.winters.octo.livelists.liveListSummary
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.Pickable
import app.winters.octo.ui.common.SelectableSongs
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.playlist.EmptyNote
import app.winters.octo.ui.playlist.ListHeader
import app.winters.octo.ui.playlist.LocalPlaylistSheets
import app.winters.octo.ui.playlist.PlaylistCover
import app.winters.octo.ui.playlist.PlaylistSheet
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

// A live list as its screen shows it: the list (null once deleted) and the
// songs it picks now.
data class LiveListPage(val list: LiveList?, val songs: List<TrackEntity>) {
    val covers = mosaicCovers(songs.map { it.albumId to it.artwork })
    val durationMs = songs.sumOf { it.durationMs }
}

@HiltViewModel(assistedFactory = LiveListViewModel.Factory::class)
class LiveListViewModel @AssistedInject constructor(
    @Assisted private val id: String,
    store: LiveListStore,
    songs: LiveListSongs,
    private val playback: PlaybackConnection,
) : ViewModel() {
    // Null until first read. The songs follow the library, likes and plays.
    val page: StateFlow<LiveListPage?> =
        combine(store.lists, songs.library) { lists, library ->
            val list = lists.firstOrNull { it.id == id }
            LiveListPage(list, list?.let { library.pick(it.query) }.orEmpty())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun ids() = page.value?.songs.orEmpty().map { it.id }

    fun play(index: Int) = playback.playTracks(ids(), index, source = page.value?.list?.name)

    fun shuffle() = playback.playTracks(ids(), shuffle = true, source = page.value?.list?.name)

    @AssistedFactory
    interface Factory {
        fun create(id: String): LiveListViewModel
    }
}

// One live list: its picture, name and size, one quiet line saying what
// it picks, Play and Shuffle, and its songs. More opens its options, where
// its rules are edited.
@Composable
fun LiveListScreen(
    id: String,
    onBack: () -> Unit,
    vm: LiveListViewModel = hiltViewModel<LiveListViewModel, LiveListViewModel.Factory> { it.create(id) },
) {
    val page by vm.page.collectAsStateWithLifecycle()
    val sheets = LocalPlaylistSheets.current
    // Leaves the screen once the list is deleted.
    val gone = page?.let { it.list == null } ?: false
    LaunchedEffect(gone) { if (gone) onBack() }

    Box(Modifier.fillMaxSize()) {
        val current = page
        val list = current?.list
        if (current != null && list != null) {
            val songs = current.songs
            val pickable = remember(songs) { songs.map { Pickable(it.id, it) } }
            SelectableSongs(pickable) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(extraTop = DetailTopGap)) {
                    item(key = "header") {
                        ListHeader(
                            title = list.name,
                            songCount = songs.size,
                            durationMs = current.durationMs,
                            onPlay = { vm.play(0) },
                            onShuffle = vm::shuffle,
                            onMore = { sheets.show(PlaylistSheet.LiveOptions(list.id, list.name)) },
                        ) { modifier, shape ->
                            PlaylistArtwork(list.id, list.name, current.covers, 240.dp, modifier, shape, line = LIVE_LIST_COVER_LINE) {
                                if (current.covers.isEmpty()) LiveMarkTile(240.dp, modifier, shape) else PlaylistCover(current.covers, 240.dp, modifier, shape)
                            }
                        }
                    }
                    item(key = "rules") {
                        Text(
                            liveListSummary(list.query, null),
                            style = OctoType.caption,
                            color = OctoColors.TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(bottom = 12.dp),
                        )
                    }
                    if (songs.isEmpty()) {
                        item(key = "empty") { EmptyNote(NO_MATCHES_NOTE) }
                    }
                    itemsIndexed(songs, key = { _, track -> track.id }) { index, track ->
                        SongRow(track) { vm.play(index) }
                    }
                }
            }
        }
        BackButton(onBack)
    }
}

const val NO_MATCHES_NOTE = "No songs match right now. As songs are added, played and liked, the ones that match show up here."
