package app.winters.octo.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlassInput
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.listening.LISTENBRAINZ_TOKEN_PAGE
import app.winters.octo.listening.ListenBrainzPrefs
import app.winters.octo.listening.ListenBrainzSync
import app.winters.octo.listening.SendPlays
import app.winters.octo.listening.TokenCheck
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
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ScrobblingViewModel @Inject constructor(private val sync: ListenBrainzSync) : ViewModel() {
    val prefs: StateFlow<ListenBrainzPrefs> =
        sync.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListenBrainzPrefs())
    val queued: StateFlow<Int> = sync.queued

    // The token as typed. Only held here while connecting, never saved as is.
    var token by mutableStateOf("")
    var checking by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    fun connect() {
        if (checking || token.isBlank()) return
        checking = true
        problem = null
        viewModelScope.launch {
            val check = runCatching { sync.connect(token) }.getOrDefault(TokenCheck.Unreachable)
            problem = when (check) {
                is TokenCheck.Valid -> null
                TokenCheck.Invalid -> "ListenBrainz did not accept that token. Copy it again from your settings page."
                TokenCheck.Unreachable -> "Couldn't reach ListenBrainz. Check the connection and try again."
            }
            if (check is TokenCheck.Valid) token = ""
            checking = false
        }
    }

    fun disconnect() {
        token = ""
        problem = null
        sync.disconnect()
    }

    fun setEnabled(on: Boolean) = sync.setEnabled(on)
    fun setSendPlays(mode: SendPlays) = sync.setSendPlays(mode)
    fun setNowPlaying(on: Boolean) = sync.setNowPlaying(on)
}

private val SendPlays.label: String
    get() = when (this) {
        SendPlays.All -> "All songs"
        SendPlays.PhoneOnly -> "Only songs on this phone"
    }

private val SendPlays.detail: String
    get() = when (this) {
        SendPlays.All -> "Every song you play here"
        SendPlays.PhoneOnly -> "Songs your server also has are left out"
    }

// Sending plays to ListenBrainz straight from the phone, with or without a server.
@Composable
fun ScrobblingPage(onBack: () -> Unit, highlight: String?, vm: ScrobblingViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val queued by vm.queued.collectAsStateWithLifecycle()
    val sheet = LocalChoiceSheet.current
    val modes = SendPlays.entries

    SettingsPageFrame("Scrobbling", onBack, highlight, icon = OctoIcons.Scrobbling) {
        SettingsGroup(title = "ListenBrainz", icon = OctoIcons.ListenBrainz, brand = true) {
            SwitchRow(
                SettingsIndex.ListenBrainz,
                checked = prefs.enabled,
                onChange = vm::setEnabled,
                helper = "The songs you play, on your profile.",
            )
            if (prefs.enabled && prefs.connected) {
                if (prefs.needsAttention) {
                    NoteRow(
                        "The saved token stopped working. Paste a new one; plays wait here until then.",
                        color = OctoColors.Error,
                    )
                } else {
                    InfoRow(null, prefs.user.orEmpty(), title = "Connected as")
                }
                if (queued > 0) InfoRow(null, "%,d".format(queued), title = "Waiting to send")
            }
        }
        if (!prefs.enabled) return@SettingsPageFrame

        if (!prefs.connected || prefs.needsAttention) {
            SettingsGroup(title = "Connect", icon = OctoIcons.Key) { TokenEntry(vm) }
        }

        if (prefs.connected) {
            SettingsGroup(
                footer = "If your server already sends its plays, choose Only songs on this phone so none count twice.",
            ) {
                ChoiceRow(SettingsIndex.SendPlays, value = prefs.sendPlays.label, onClick = {
                    sheet.show(
                        ChoiceRequest(SettingsIndex.SendPlays.title, modes.map { Choice(it.label, it.detail) }, modes.indexOf(prefs.sendPlays)) {
                            vm.setSendPlays(modes[it])
                        },
                    )
                })
                SwitchRow(
                    SettingsIndex.NowPlaying,
                    checked = prefs.nowPlaying,
                    onChange = vm::setNowPlaying,
                    helper = "Shown on your profile while it plays.",
                )
            }
            SettingsGroup {
                ActionRow(SettingsIndex.ScrobblingDisconnect, onClick = vm::disconnect, destructive = true, chevron = false)
            }
        }
    }
}

// Pasting the user token, with the page it comes from.
@Composable
private fun TokenEntry(vm: ScrobblingViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        NoteRow(
            "Paste the user token from your ListenBrainz settings. It stays encrypted on this phone.",
        )
        ActionRow(
            null,
            title = "Open ListenBrainz settings",
            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, LISTENBRAINZ_TOKEN_PAGE.toUri())) } },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassInput(
                value = vm.token,
                onValueChange = { vm.token = it.trim() },
                placeholder = "User token",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.connect() }),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
            AccentButton("Connect", onClick = vm::connect, enabled = vm.token.isNotBlank(), loading = vm.checking)
        }
        vm.problem?.let {
            Text(it, style = OctoType.caption, color = OctoColors.Error, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
    }
}
