package app.winters.octo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.BuildConfig
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.design.AccentButton
import app.winters.octo.design.LineSlider
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.design.glassPanel
import app.winters.octo.device.Access
import app.winters.octo.device.DEVICE
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.device.MusicFolder
import app.winters.octo.player.CrossfadeSecondsRange
import app.winters.octo.player.LiveBackgroundSupported
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.PlayerSettings
import app.winters.octo.sound.SoundEngine
import app.winters.octo.sound.SoundSettings
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.nav.EditConnectionRoute
import app.winters.octo.ui.nav.OctoAdminRoute
import app.winters.octo.ui.nav.SignInRoute
import app.winters.octo.ui.nav.SoundRoute
import app.winters.octo.ui.sound.soundSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val library: DeviceLibrary,
    dao: CatalogDao,
    private val player: PlayerSettings,
    soundEngine: SoundEngine,
) : ViewModel() {
    // For the line that opens the Sound page.
    val sound: StateFlow<SoundSettings> = soundEngine.current

    val playerPrefs: StateFlow<PlayerPrefs> =
        player.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())

    fun setLiveBackground(on: Boolean) {
        viewModelScope.launch { player.setLiveBackground(on) }
    }

    fun setCrossfade(on: Boolean) {
        viewModelScope.launch { player.setCrossfade(on) }
    }

    fun setCrossfadeSeconds(seconds: Int) {
        viewModelScope.launch { player.setCrossfadeSeconds(seconds) }
    }

    fun setLyricsOnline(on: Boolean) {
        viewModelScope.launch { player.setLyricsOnline(on) }
    }

    val songCount: StateFlow<Int> =
        dao.trackCount(DEVICE).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun rescan() {
        viewModelScope.launch { library.rescan() }
    }

    fun setFolderIncluded(folder: String, included: Boolean) {
        viewModelScope.launch { library.setFolderIncluded(folder, included) }
    }
}

private val CardShape = RoundedCornerShape(20.dp)

@Composable
fun SettingsScreen(onOpen: (NavKey) -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val access by vm.library.access.collectAsStateWithLifecycle()
    val scanning by vm.library.scanning.collectAsStateWithLifecycle()
    val count by vm.songCount.collectAsStateWithLifecycle()
    val folders by vm.library.folders.collectAsStateWithLifecycle()
    val player by vm.playerPrefs.collectAsStateWithLifecycle()
    val sound by vm.sound.collectAsStateWithLifecycle()
    val padding = screenPadding()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
    ) {
        ScreenTitle("Settings")

        Card("Music on this phone") {
            Line("Access", if (access == Access.Granted) "Allowed" else "Not allowed")
            Line("Songs", "%,d".format(count))
            if (scanning) Line("Status", "Looking for music…")
            AccentButton(
                text = "Rescan",
                onClick = vm::rescan,
                enabled = access == Access.Granted,
                loading = scanning,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (folders.isNotEmpty()) {
            Card("Music folders", Modifier.padding(top = 16.dp)) {
                Text(
                    "Switch a folder off to leave its music out of your library.",
                    style = OctoType.caption,
                    color = OctoColors.TextMuted,
                )
                folders.forEach { folder ->
                    FolderLine(folder) { on -> vm.setFolderIncluded(folder.name, on) }
                }
            }
        }

        ServerCard(
            onConnect = { onOpen(SignInRoute) },
            onOpenAdmin = { onOpen(OctoAdminRoute) },
            onEditConnection = { onOpen(EditConnectionRoute) },
            modifier = Modifier.padding(top = 16.dp),
            onOpen = onOpen,
        )

        PlaylistsCard(Modifier.padding(top = 16.dp))

        Card("Player", Modifier.padding(top = 16.dp)) {
            ChoiceLine("Sound", soundSummary(sound)) { onOpen(SoundRoute) }
            SwitchLine(
                label = "Live background",
                detail = if (LiveBackgroundSupported) {
                    "Colours from the artwork, moving slowly while music plays."
                } else {
                    "Needs Android 13 or newer. The blurred artwork is used instead."
                },
                checked = player.liveBackground && LiveBackgroundSupported,
                enabled = LiveBackgroundSupported,
                onChange = vm::setLiveBackground,
            )
            SwitchLine(
                label = "Crossfade",
                detail = "Each song fades into the next. Albums played in order stay gapless.",
                checked = player.crossfade,
                onChange = vm::setCrossfade,
            )
            if (player.crossfade) CrossfadeLength(player.crossfadeSeconds, vm::setCrossfadeSeconds)
            PlaybackLines()
            Text("Lyrics", style = OctoType.label, color = OctoColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            SwitchLine(
                label = "Find lyrics online",
                detail = "When your server and the song's files have none, look them up on LRCLIB. " +
                    "Only the title, artist, album and length are sent.",
                checked = player.lyricsOnline,
                onChange = vm::setLyricsOnline,
            )
        }

        StreamingCard(Modifier.padding(top = 16.dp))

        OfflineCard(Modifier.padding(top = 16.dp))
        BackupCard(Modifier.padding(top = 16.dp))

        Card("About", Modifier.padding(top = 16.dp)) {
            Line("Octo", BuildConfig.VERSION_NAME)
        }
    }
}

@Composable
internal fun Card(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .glassPanel(CardShape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = OctoType.section, color = OctoColors.TextPrimary)
        content()
    }
}

@Composable
internal fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = OctoType.bodySmall, color = OctoColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
    }
}

@Composable
internal fun SwitchLine(
    label: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = OctoType.bodySmall, color = if (enabled) OctoColors.TextPrimary else OctoColors.TextMuted)
            Text(detail, style = OctoType.caption, color = OctoColors.TextMuted)
        }
        OctoSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

// How long the blend is, from 1 to 12 seconds, saved as it changes.
@Composable
private fun CrossfadeLength(seconds: Int, onChange: (Int) -> Unit) {
    val range = CrossfadeSecondsRange
    val span = (range.last - range.first).toFloat()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LineSlider(
            fraction = { (seconds - range.first) / span },
            onSeek = { fraction ->
                val picked = range.first + Math.round(fraction * span)
                if (picked != seconds) onChange(picked)
            },
            live = true,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Crossfade length" },
        )
        Text(
            "$seconds s",
            style = OctoType.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = OctoColors.TextPrimary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun FolderLine(folder: MusicFolder, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                folder.name.ifEmpty { "Phone storage" },
                style = OctoType.bodySmall,
                color = if (folder.included) OctoColors.TextPrimary else OctoColors.TextMuted,
            )
            Text("%,d songs".format(folder.songs), style = OctoType.caption, color = OctoColors.TextMuted)
        }
        OctoSwitch(checked = folder.included, onCheckedChange = onChange)
    }
}

