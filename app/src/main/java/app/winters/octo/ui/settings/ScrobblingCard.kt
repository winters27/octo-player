package app.winters.octo.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.Role
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
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.listening.LISTENBRAINZ_TOKEN_PAGE
import app.winters.octo.listening.ListenBrainzPrefs
import app.winters.octo.listening.ListenBrainzSync
import app.winters.octo.listening.SendPlays
import app.winters.octo.listening.TokenCheck
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
internal fun ScrobblingCard(modifier: Modifier = Modifier, vm: ScrobblingViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val queued by vm.queued.collectAsStateWithLifecycle()
    val sheet = LocalChoiceSheet.current
    val modes = SendPlays.entries

    Card("Scrobbling", modifier) {
        SwitchLine(
            label = "ListenBrainz",
            detail = "Add the songs you play to your ListenBrainz profile.",
            checked = prefs.enabled,
            onChange = vm::setEnabled,
        )
        if (!prefs.enabled) return@Card

        if (prefs.connected) {
            if (prefs.needsAttention) {
                Text(
                    "ListenBrainz no longer accepts the saved token. Paste a new one to keep sending plays. " +
                        "Plays wait here until then.",
                    style = OctoType.caption,
                    color = OctoColors.Error,
                )
            } else {
                Text("Connected as ${prefs.user}", style = OctoType.caption, color = OctoColors.TextMuted)
            }
            if (queued > 0) Line("Waiting to send", "%,d".format(queued))
        }
        if (!prefs.connected || prefs.needsAttention) TokenEntry(vm)

        if (prefs.connected) {
            ChoiceLine("Send plays of", prefs.sendPlays.label) {
                sheet.show(
                    ChoiceRequest("Send plays of", modes.map { Choice(it.label, it.detail) }, modes.indexOf(prefs.sendPlays)) {
                        vm.setSendPlays(modes[it])
                    },
                )
            }
            Text(
                "Choose Only songs on this phone when your server already passes its plays on to ListenBrainz, " +
                    "so they are not counted twice.",
                style = OctoType.caption,
                color = OctoColors.TextMuted,
            )
            SwitchLine(
                label = "Show what I'm playing now",
                detail = "Your profile shows the song while it plays.",
                checked = prefs.nowPlaying,
                onChange = vm::setNowPlaying,
            )
            GlazeButton("Disconnect", onClick = vm::disconnect, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// Pasting the user token, with the page it comes from.
@Composable
private fun TokenEntry(vm: ScrobblingViewModel) {
    val context = LocalContext.current
    Text(
        "Paste your user token from your ListenBrainz settings. It is kept encrypted on this phone " +
            "and only ever sent to ListenBrainz.",
        style = OctoType.caption,
        color = OctoColors.TextMuted,
    )
    Text(
        "Open ListenBrainz settings",
        style = OctoType.bodySmall,
        color = OctoColors.Accent,
        modifier = Modifier.clickable(role = Role.Button) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, LISTENBRAINZ_TOKEN_PAGE.toUri())) }
        },
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
    vm.problem?.let { Text(it, style = OctoType.caption, color = OctoColors.Error) }
}
