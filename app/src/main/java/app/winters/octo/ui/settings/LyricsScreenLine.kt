package app.winters.octo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.lyrics.LyricsTiming
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LyricsScreenViewModel @Inject constructor(private val timing: LyricsTiming) : ViewModel() {
    val keepScreenOn: StateFlow<Boolean> = timing.keepScreenOn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setKeepScreenOn(on: Boolean) {
        viewModelScope.launch { timing.setKeepScreenOn(on) }
    }
}

// Whether the screen stays on while lyrics show, under Lyrics in the
// Player card.
@Composable
internal fun LyricsScreenLine(vm: LyricsScreenViewModel = hiltViewModel()) {
    val on by vm.keepScreenOn.collectAsStateWithLifecycle()
    SwitchLine(
        label = "Keep the screen on",
        detail = "While lyrics show in the player, the screen does not turn off.",
        checked = on,
        onChange = vm::setKeepScreenOn,
    )
}
