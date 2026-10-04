package app.winters.octo.ui.common

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.GlowIcon
import app.winters.octo.design.OctoIcons
import app.winters.octo.discovery.DownloadPhase
import app.winters.octo.discovery.Downloads
import app.winters.octo.player.PlayerSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: Downloads,
    private val feedback: Feedback,
    player: PlayerSettings,
    private val serverDownloads: app.winters.octo.data.ServerDownloads,
) : ViewModel() {
    val phases: StateFlow<Map<String, DownloadPhase>> = downloads.phases

    // Whether the server keeps a log of its downloads, so a song on its way
    // can be followed in the server downloads sheet.
    val canFollow: StateFlow<Boolean> = serverDownloads.supported

    fun follow(trackId: String) = serverDownloads.followTrack(trackId)

    // Octo's own Reduce motion, beside the phone's.
    val reduceMotion: StateFlow<Boolean> = player.prefs
        .map { it.reduceMotion }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun request(track: TrackEntity) {
        viewModelScope.launch { downloads.request(track) }
    }

    fun showing(trackId: String): () -> Unit = downloads.showing(trackId)

    // Why a download failed, asked for with a long press.
    fun explain(reason: String) = feedback.show("Could not download: $reason")
}

// What TalkBack says the button is called, before what it is doing.
const val AddToLibraryText = "Add to your library"

// What TalkBack says the add button is doing.
fun downloadStateText(phase: DownloadPhase): String = when (phase) {
    DownloadPhase.None -> "Not in your library"
    DownloadPhase.Queued -> "Queued"
    is DownloadPhase.Downloading -> phase.progress?.let { "Downloading, ${(it * 100).roundToInt()} percent" } ?: "Downloading"
    DownloadPhase.Adding -> "Adding to your library"
    DownloadPhase.Done -> "In your library"
    is DownloadPhase.Failed -> "Could not download"
}

// Where a heart would be for a song not in the library: a plus that has the
// server download the song into the library. The download arrow means
// something else (saving a library song to the phone), so it is not used
// here. A ring around the plus shows the song on its way and turns into a
// check once it is in the library. A failed one shows an alert: a tap tries
// again, a long press says why.
@Composable
fun AddToLibraryButton(
    track: TrackEntity,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    vm: DownloadsViewModel = hiltViewModel(),
) {
    val phases by vm.phases.collectAsStateWithLifecycle()
    val phase = phases[track.id] ?: DownloadPhase.None
    val appCalm by vm.reduceMotion.collectAsStateWithLifecycle()
    val calm = appCalm || rememberSystemReduceMotion()
    // Progress is asked for more often while this button is on screen.
    LifecycleStartEffect(track.id) {
        val release = vm.showing(track.id)
        onStopOrDispose { release() }
    }
    val failed = phase as? DownloadPhase.Failed
    val canFollow by vm.canFollow.collectAsStateWithLifecycle()
    // On its way, a tap follows it in the server downloads sheet.
    val follows = canFollow && phase != DownloadPhase.None && phase != DownloadPhase.Done && failed == null
    Box(
        modifier
            .size(size)
            .combinedClickable(
                interactionSource = null,
                indication = null,
                enabled = phase == DownloadPhase.None || failed != null || follows,
                role = Role.Button,
                onClickLabel = if (failed != null) "Try again" else if (follows) "Follow it" else null,
                onLongClickLabel = if (failed != null) "Why" else null,
                onLongClick = failed?.let { { vm.explain(it.reason) } },
            ) { if (follows) vm.follow(track.id) else vm.request(track) }
            .semantics {
                contentDescription = AddToLibraryText
                stateDescription = downloadStateText(phase)
            },
        contentAlignment = Alignment.Center,
    ) {
        // Keyed by song, so a button that moves on to another song (as the
        // player's does) starts its ring afresh.
        key(track.id) {
            when (phase) {
                DownloadPhase.None -> GlowIcon(
                    painterResource(OctoIcons.AddToLibrary),
                    tint = Color.White.copy(alpha = 0.6f),
                    lit = false,
                    modifier = Modifier.size(iconSize),
                )
                is DownloadPhase.Failed -> DownloadAlert(Modifier.size(iconSize))
                else -> DownloadRing(phase, calm, Modifier.size(iconSize))
            }
        }
    }
}
