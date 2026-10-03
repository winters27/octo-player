package app.winters.octo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.BuildConfig
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.settings.rows.ActionRow
import app.winters.octo.ui.settings.rows.ChoiceRow
import app.winters.octo.ui.settings.rows.InfoRow
import app.winters.octo.ui.settings.rows.NoteRow
import app.winters.octo.ui.settings.rows.SettingsGroup
import app.winters.octo.ui.settings.rows.SettingsPageFrame
import app.winters.octo.ui.settings.rows.SwitchRow
import app.winters.octo.update.AppUpdates
import app.winters.octo.update.InstallWhen
import app.winters.octo.update.UpdatePrefs
import app.winters.octo.update.UpdateSettings
import app.winters.octo.update.UpdateState
import app.winters.octo.whatsnew.WhatsNewPanel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val updates: AppUpdates,
    private val settings: UpdateSettings,
) : ViewModel() {
    val state: StateFlow<UpdateState> = updates.state
    val prefs: StateFlow<UpdatePrefs> = settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdatePrefs())

    fun setCheckAutomatically(on: Boolean) {
        viewModelScope.launch { settings.setCheckAutomatically(on) }
    }

    fun setInstall(choice: InstallWhen) {
        viewModelScope.launch { settings.setInstall(choice) }
    }

    fun setEarlyVersions(on: Boolean) {
        viewModelScope.launch { settings.setEarlyVersions(on) }
    }

    fun checkNow() = updates.checkNow()

    fun mayInstall() = updates.mayInstall()

    fun allowInstallsIntent() = updates.allowInstallsIntent()

    fun install() = updates.install(asked = true)
}

// The app's version, a ready update, how Octo looks for new versions, what
// is new, and who made it.
@Composable
fun AboutPage(onBack: () -> Unit, highlight: String?) {
    var reading by rememberSaveable { mutableStateOf(false) }
    val vm = hiltViewModel<UpdatesViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sheet = LocalChoiceSheet.current
    // Coming back from the system's settings, where installs may now be allowed.
    var mayInstall by rememberSaveable { mutableStateOf(vm.mayInstall()) }
    LifecycleResumeEffect(Unit) {
        mayInstall = vm.mayInstall()
        onPauseOrDispose { }
    }

    SettingsPageFrame("About", onBack, highlight, icon = OctoIcons.Info) {
        SettingsGroup {
            InfoRow(SettingsIndex.Version, BuildConfig.VERSION_NAME)
            state.ready?.let { ready ->
                if (mayInstall) {
                    ActionRow(
                        null,
                        onClick = vm::install,
                        title = "Update ready: ${ready.version}",
                        helper = "Your music and settings stay.",
                        trailing = "Install",
                    )
                } else {
                    // The first time, Android asks for Octo to be allowed to install.
                    ActionRow(
                        null,
                        onClick = { context.startActivity(vm.allowInstallsIntent()) },
                        title = "Update ready: ${ready.version}",
                        helper = "Allow Octo to install updates first.",
                        trailing = "Allow",
                    )
                }
                if (ready.notes.isNotBlank()) NoteRow(ready.notes.trim(), color = OctoColors.TextSecondary)
            }
            ActionRow(SettingsIndex.WhatsNew, onClick = { reading = true })
        }
        SettingsGroup(title = "Updates", icon = OctoIcons.Sync) {
            val off = state.off
            if (off != null) {
                NoteRow(off)
                return@SettingsGroup
            }
            SwitchRow(
                SettingsIndex.CheckUpdates,
                checked = prefs.checkAutomatically,
                onChange = vm::setCheckAutomatically,
                helper = "Every few hours, downloading on Wi-Fi.",
            )
            ChoiceRow(SettingsIndex.InstallUpdates, value = installName(prefs.install), onClick = {
                val options = InstallWhen.entries
                sheet.show(
                    ChoiceRequest(
                        SettingsIndex.InstallUpdates.title,
                        options.map { Choice(installName(it), installHelp(it)) },
                        options.indexOf(prefs.install),
                    ) { vm.setInstall(options[it]) },
                )
            })
            SwitchRow(
                SettingsIndex.EarlyVersions,
                checked = prefs.earlyVersions,
                onChange = vm::setEarlyVersions,
                helper = "Before they're finished. Can have rough edges.",
            )
            ActionRow(
                SettingsIndex.CheckNow,
                onClick = vm::checkNow,
                helper = state.line,
                busy = state.checking,
                chevron = false,
            )
        }
        Credit()
    }
    WhatsNewPanel(visible = reading, onDismiss = { reading = false })
}

internal fun installName(choice: InstallWhen): String = when (choice) {
    InstallWhen.Ask -> "Ask me"
    InstallWhen.OnQuit -> "When I leave Octo"
}

private fun installHelp(choice: InstallWhen): String = when (choice) {
    InstallWhen.Ask -> "A ready update waits here until you tap Install."
    InstallWhen.OnQuit -> "A ready update goes in when you leave Octo with nothing playing. Android may still ask the first time."
}

// One quiet line.
@Composable
private fun Credit() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Made by Winters with love" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Made by Winters with",
            style = OctoType.caption.copy(fontStyle = FontStyle.Italic),
            color = OctoColors.TextMuted,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Icon(
            painterResource(OctoIcons.Liked),
            contentDescription = null,
            tint = OctoColors.TextMuted,
            modifier = Modifier.padding(start = 4.dp).size(12.dp),
        )
    }
}
