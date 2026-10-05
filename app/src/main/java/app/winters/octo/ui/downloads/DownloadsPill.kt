package app.winters.octo.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.data.ServerDownloads
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.discovery.DownloadPhase
import app.winters.octo.discovery.Downloads
import app.winters.octo.player.PlayerSettings
import app.winters.octo.ui.common.DownloadRing
import app.winters.octo.ui.common.rememberSystemReduceMotion
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DownloadsPillViewModel @Inject constructor(
    downloads: Downloads,
    private val serverDownloads: ServerDownloads,
    player: PlayerSettings,
) : ViewModel() {
    // How many songs asked for are still on their way into the library.
    val coming: StateFlow<Int> = downloads.phases
        .map { phases -> phases.values.count(::onItsWay) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Only a server that keeps a downloads list has a sheet to open.
    val canOpen: StateFlow<Boolean> = serverDownloads.supported

    // Octo's own Reduce motion, beside the phone's.
    val reduceMotion: StateFlow<Boolean> = player.prefs
        .map { it.reduceMotion }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun open() {
        if (!serverDownloads.supported.value) return
        serverDownloads.showList()
        serverDownloads.open()
    }
}

// A song asked for that has not arrived yet, nor failed.
internal fun onItsWay(phase: DownloadPhase): Boolean =
    phase == DownloadPhase.Queued || phase is DownloadPhase.Downloading || phase == DownloadPhase.Adding

// The pill's words: how many songs are on their way.
internal fun comingText(count: Int): String = if (count == 1) "1 downloading" else "$count downloading"

// While songs are on their way into the library, a small glazed pill with
// the turning download ring and how many. A tap opens the server downloads
// sheet from anywhere; nothing opens by itself, and with nothing on its way
// the pill is gone.
@Composable
fun DownloadsPill(haze: HazeState, modifier: Modifier = Modifier, vm: DownloadsPillViewModel = hiltViewModel()) {
    val coming by vm.coming.collectAsStateWithLifecycle()
    val canOpen by vm.canOpen.collectAsStateWithLifecycle()
    val appCalm by vm.reduceMotion.collectAsStateWithLifecycle()
    val calm = appCalm || rememberSystemReduceMotion()
    if (coming <= 0 || !canOpen) return
    val words = comingText(coming)
    FloatingGlaze(
        haze,
        modifier
            .height(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Show server downloads", onClick = vm::open)
            .semantics(mergeDescendants = true) { contentDescription = "$words. Show server downloads" },
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 14.dp).align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DownloadRing(DownloadPhase.Queued, calm, Modifier.size(20.dp))
            Text(words, style = OctoType.caption, color = OctoColors.TextPrimary, maxLines = 1)
        }
    }
}
