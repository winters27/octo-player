package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.TestOutcome
import app.winters.octo.design.GlassField
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.design.Spinner
import app.winters.octo.design.Txt
import app.winters.octo.design.glassPanel
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.isPrivateHost
import app.winters.octo.subsonic.normalizeServerUrl
import kotlinx.coroutines.launch

// Signing in to a server: its address, a username and password, a test of
// the connection, and an option for servers that cannot take a token.
@Composable
fun SignInPage(app: AppState) {
    val last = remember { app.accounts.last }
    var address by remember { mutableStateOf(last?.address.orEmpty()) }
    var username by remember { mutableStateOf(last?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var legacy by remember { mutableStateOf(last?.authMode == AuthMode.LegacyPassword) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()
    val mode = if (legacy) AuthMode.LegacyPassword else AuthMode.Token
    val ready = address.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && !busy
    val url = normalizeServerUrl(address)
    val insecure = url != null && !url.isHttps && !isPrivateHost(url)

    fun test() {
        busy = true
        result = null
        scope.launch {
            result = when (val tested = app.accounts.test(address, username, password, mode)) {
                is TestOutcome.Reached -> true to tested.facts.summary()
                is TestOutcome.Failed -> false to tested.message
            }
            busy = false
        }
    }

    fun signIn() {
        if (!ready) return
        busy = true
        result = null
        scope.launch {
            when (val done = app.accounts.signIn(address, username, password, mode)) {
                is SignInOutcome.Done -> {
                    password = ""
                    app.signedIn(done.connection, done.note)
                }
                is SignInOutcome.Failed -> result = false to done.message
            }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(440.dp).glassPanel(RoundedCornerShape(22.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Txt("Octo", OctoType.display)
            Txt("Sign in to your music server. Any Subsonic, Navidrome or Octo server works.", OctoType.bodySmall, OctoColors.TextSecondary, maxLines = 3)
            Label("Server address")
            GlassField(address, { address = it; result = null }, Modifier.fillMaxWidth(), placeholder = "music.example.com or 192.168.1.20:4533", onSubmit = ::signIn)
            if (insecure) Txt("This address isn't encrypted and isn't on your home network, so others could read what is sent.", OctoType.caption, OctoColors.Error, maxLines = 3)
            Label("Username")
            GlassField(username, { username = it; result = null }, Modifier.fillMaxWidth(), onSubmit = ::signIn)
            Label("Password")
            GlassField(password, { password = it; result = null }, Modifier.fillMaxWidth(), password = true, onSubmit = ::signIn)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("Send the password itself", OctoType.bodySmall)
                    Txt("For older servers, or accounts that can't sign in with a token.", OctoType.caption, OctoColors.TextMuted, maxLines = 2)
                }
                OctoSwitch(legacy, { legacy = it })
            }
            result?.let { (ok, text) ->
                Txt(text, OctoType.bodySmall, if (ok) OctoColors.TextSecondary else OctoColors.Error, maxLines = 4)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (busy) Spinner(size = 18.dp)
                GlazeCapsule(null, "Test connection", ::test, enabled = ready)
                GlazeCapsule(null, "Sign in", ::signIn, lit = true, enabled = ready)
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Txt(text, OctoType.caption, OctoColors.TextMuted, Modifier.padding(top = 4.dp))
}
