package app.winters.octo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.lyrics.LyricsTiming
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.PlayerSettings
import app.winters.octo.ui.settings.rows.SettingsGroup
import app.winters.octo.ui.settings.rows.SettingsPageFrame
import app.winters.octo.ui.settings.rows.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LyricsSettingsViewModel @Inject constructor(
    private val player: PlayerSettings,
    private val timing: LyricsTiming,
) : ViewModel() {
    val prefs: StateFlow<PlayerPrefs> = player.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())
    val keepScreenOn: StateFlow<Boolean> = timing.keepScreenOn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setLyricsOnline(on: Boolean) {
        viewModelScope.launch { player.setLyricsOnline(on) }
    }

    fun setKeepScreenOn(on: Boolean) {
        viewModelScope.launch { timing.setKeepScreenOn(on) }
    }
}

// Where lyrics come from, and the screen while they show.
@Composable
fun LyricsPage(onBack: () -> Unit, highlight: String?, vm: LyricsSettingsViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()

    SettingsPageFrame("Lyrics", onBack, highlight) {
        SettingsGroup {
            SwitchRow(
                SettingsIndex.LyricsOnline,
                checked = prefs.lyricsOnline,
                onChange = vm::setLyricsOnline,
                helper = "When your server and the song's files have none, look them up on LRCLIB. " +
                    "Only the title, artist, album and length are sent.",
            )
            SwitchRow(
                SettingsIndex.LyricsScreenOn,
                checked = keepScreenOn,
                onChange = vm::setKeepScreenOn,
                helper = "While lyrics show in the player, the screen does not turn off.",
            )
        }
    }
}
