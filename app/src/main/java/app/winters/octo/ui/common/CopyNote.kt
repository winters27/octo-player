package app.winters.octo.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.server.COPY_FAILED_TITLE
import app.winters.octo.server.COPY_LIKES_ON_ITS_WAY
import app.winters.octo.server.COPY_ON_ITS_WAY
import app.winters.octo.server.CopyOutcome
import app.winters.octo.server.CopyRunState
import app.winters.octo.server.CopyWait
import app.winters.octo.server.ServerSync
import app.winters.octo.server.copyDescription
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// What an empty list says while the server's music is still to come: that
// it is on its way (with the copy running, if one is), or that its first
// copy stopped.
sealed interface CopyNoteState {
    data class Coming(val run: CopyRunState?) : CopyNoteState

    data object Stopped : CopyNoteState
}

// Null when the music is not still to come, or that is not read yet.
fun copyNote(wait: CopyWait?, run: CopyRunState?, outcome: CopyOutcome?, account: String?): CopyNoteState? {
    if (wait?.awaiting != true || account == null) return null
    val mine = run?.takeIf { it.account == account && it.whole }
    if (mine == null && outcome is CopyOutcome.Failed && outcome.account == account) return CopyNoteState.Stopped
    return CopyNoteState.Coming(mine)
}

@HiltViewModel
class CopyNoteViewModel @Inject constructor(sync: ServerSync, sessions: SessionRepository) : ViewModel() {
    // From its first frame, so the list's own note never shows for a moment before it.
    val note: StateFlow<CopyNoteState?> =
        combine(sync.copyWait, sync.copyRun, sync.copyOutcome, sessions.state, ::noteOf)
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), noteOf(sync.copyWait.value, sync.copyRun.value, sync.copyOutcome.value, sessions.state.value))

    private fun noteOf(wait: CopyWait?, run: CopyRunState?, outcome: CopyOutcome?, state: SessionState): CopyNoteState? =
        copyNote(wait, run, outcome, (state as? SessionState.SignedIn)?.session?.id)
}

// An empty list's note: while the server's music is still to come, a small
// ring and that it is on its way (`likes` for the lists of likes), with how
// far the copy is; `otherwise` the list's own note. The ring sits where the
// list's first artwork will, and the words where its first title will.
@Composable
fun CopyNote(likes: Boolean = false, vm: CopyNoteViewModel = hiltViewModel(), otherwise: @Composable () -> Unit) {
    val note by vm.note.collectAsStateWithLifecycle()
    val shown = note ?: return otherwise()
    val run = (shown as? CopyNoteState.Coming)?.run
    Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CopyRing(run, 44.dp, stopped = shown == CopyNoteState.Stopped)
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when {
                    shown == CopyNoteState.Stopped -> COPY_FAILED_TITLE
                    likes -> COPY_LIKES_ON_ITS_WAY
                    else -> COPY_ON_ITS_WAY
                },
                style = OctoType.bodySmall,
                color = OctoColors.TextPrimary,
            )
            run?.let { Text(copyDescription(it), style = OctoType.caption.copy(fontFeatureSettings = "tnum"), color = OctoColors.TextMuted) }
        }
    }
}
