package app.winters.octo.ui.signin

import android.security.KeyChain
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.winters.octo.connection.formatFingerprint
import app.winters.octo.data.SignInError
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlassInput
import app.winters.octo.design.GlassPopup
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.design.glassPanel
import app.winters.octo.subsonic.normalizeServerUrl
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.LocalFeedback
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.PopupQuestion
import app.winters.octo.ui.common.rememberLast
import app.winters.octo.ui.nav.ServerForm

private val CardShape = RoundedCornerShape(20.dp)

// Opened from Settings. Signing in goes back there, and the server's
// music starts copying on its own. When editing, it starts from the saved
// connection and signs in again with the changes.
//
// From the list of servers it is the sheet for adding a server (the one in
// use stays), editing a kept one, or signing in to one again.
@Composable
fun SignInScreen(
    onBack: () -> Unit,
    editing: Boolean = false,
    form: ServerForm? = null,
    serverId: String? = null,
    note: String? = null,
    vm: SignInViewModel = hiltViewModel(),
) {
    val feedback = LocalFeedback.current
    LaunchedEffect(editing) { if (editing) vm.startEditing() }
    LaunchedEffect(form) { if (form != null) vm.start(form, serverId, note) }
    LaunchedEffect(vm.signedIn) {
        if (vm.signedIn) {
            vm.notice?.let(feedback::done)
            onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(OctoColors.Background)) {
        SignInForm(vm)
        BackButton(onBack)
    }
    TrustSheet(vm.question, onTrust = vm::trust, onCancel = vm::distrust)
}

@Composable
private fun SignInForm(vm: SignInViewModel) {
    var reveal by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                // Clear of the back button above and the floating bar below.
                .padding(start = 24.dp, end = 24.dp, top = DetailTopGap, bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val heading = vm.heading
            if (heading == null) {
                Text("Octo", style = OctoType.display, color = OctoColors.TextPrimary)
                Text(
                    if (vm.editing) "Change how Octo connects" else "Sign in to your music server",
                    style = OctoType.bodySmall,
                    color = OctoColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Text(heading, style = OctoType.title, color = OctoColors.TextPrimary, textAlign = TextAlign.Center)
                vm.subheading?.let {
                    Text(it, style = OctoType.bodySmall, color = OctoColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Spacer(Modifier.height(32.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .glassPanel(CardShape)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassInput(
                    value = vm.address,
                    onValueChange = { vm.address = it },
                    placeholder = "Server address",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                if (vm.form == ServerForm.Add || vm.form == ServerForm.Edit) {
                    GlassInput(
                        value = vm.label,
                        onValueChange = { vm.label = it },
                        placeholder = "Name (optional, like Home or Work)",
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    )
                }
                GlassInput(
                    value = vm.username,
                    onValueChange = { vm.username = it },
                    placeholder = if (vm.useApiKey) "Username (optional)" else "Username",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    contentType = ContentType.Username,
                )
                if (!vm.useApiKey) {
                    GlassInput(
                        value = vm.password,
                        onValueChange = { vm.password = it },
                        placeholder = if (vm.editing) "Password (unchanged)" else "Password",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { vm.submit() }),
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        contentType = ContentType.Password,
                        trailing = { RevealToggle(reveal) { reveal = !reveal } },
                    )
                }
            }

            if (vm.insecure) {
                Text(
                    if (vm.legacyPassword && !vm.useApiKey) {
                        "This address isn't encrypted, and a legacy password travels as it is. Anyone on the way can read it."
                    } else {
                        "This address isn't encrypted. Your password goes as a one-time token, " +
                            "but the rest can be read on the way."
                    },
                    style = OctoType.caption,
                    color = if (vm.legacyPassword && !vm.useApiKey) OctoColors.Error else OctoColors.TextMuted,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            AdvancedToggle(vm.advancedOpen) { vm.advancedOpen = !vm.advancedOpen }
            AnimatedVisibility(vm.advancedOpen) { Advanced(vm) }

            AccentButton(
                text = when (vm.form) {
                    ServerForm.Add -> "Add"
                    ServerForm.Edit -> "Save"
                    ServerForm.SignIn -> "Sign in"
                    null -> if (vm.editing) "Save and reconnect" else "Sign in"
                },
                onClick = vm::submit,
                loading = vm.busy,
                enabled = vm.ready,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth(),
            )

            vm.error?.let {
                Text(
                    it,
                    style = OctoType.bodySmall,
                    color = OctoColors.Error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun RevealToggle(revealed: Boolean, onClick: () -> Unit) {
    Text(
        if (revealed) "Hide" else "Show",
        style = OctoType.label,
        color = OctoColors.Accent,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun AdvancedToggle(open: Boolean, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "advanced chevron")
    Row(
        Modifier
            .padding(top = 20.dp)
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Advanced", style = OctoType.label, color = OctoColors.TextSecondary, modifier = Modifier.weight(1f))
        Icon(
            painterResource(OctoIcons.Chevron),
            contentDescription = null,
            tint = OctoColors.TextMuted,
            modifier = Modifier.size(20.dp).rotate(turn),
        )
    }
}

// The settings most servers never need: other ways of signing in, a home
// address, extra headers and a client certificate.
@Composable
private fun Advanced(vm: SignInViewModel) {
    var revealKey by remember { mutableStateOf(false) }
    Column(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .glassPanel(CardShape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SwitchRow(
            "Legacy sign-in",
            "Only for older servers that reject the normal sign-in.",
            checked = vm.legacyPassword && !vm.useApiKey,
            enabled = !vm.useApiKey,
        ) { vm.legacyPassword = it }

        // Before signing in nobody knows whether the server takes keys, so
        // one may be tried; once known, the switch shows only where it works.
        if (vm.takesApiKeys != false || vm.useApiKey) {
            SwitchRow(
                "Sign in with an API key",
                if (vm.takesApiKeys == true) "Your server gives out API keys." else "For servers that give out API keys.",
                checked = vm.useApiKey,
            ) { vm.useApiKey = it }
            if (vm.useApiKey) {
                GlassInput(
                    value = vm.apiKey,
                    onValueChange = { vm.apiKey = it },
                    placeholder = if (vm.editing) "API key (unchanged)" else "API key",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = { RevealToggle(revealKey) { revealKey = !revealKey } },
                )
            }
        }

        Section("Home network address", "Optional. On Wi-Fi, Octo uses it when it answers, and the main address otherwise.") {
            GlassInput(
                value = vm.home,
                onValueChange = { vm.home = it },
                placeholder = "Home network address",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
        }

        Section("Custom headers", "Sent with every request to this server, such as an access token for a proxy in front of it.") {
            vm.headers.forEachIndexed { index, header ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassInput(
                        value = header.name,
                        onValueChange = { vm.setHeaderName(index, it) },
                        placeholder = "Header name",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    )
                    GlassInput(
                        value = header.value,
                        onValueChange = { vm.setHeaderValue(index, it) },
                        placeholder = if (header.saved) "Value (unchanged)" else "Value",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        visualTransformation = PasswordVisualTransformation(),
                        trailing = {
                            Text(
                                "Remove",
                                style = OctoType.label,
                                color = OctoColors.Accent,
                                modifier = Modifier
                                    .clickable(role = Role.Button) { vm.removeHeader(index) }
                                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                            )
                        },
                    )
                }
            }
            GlazeButton("Add header", onClick = vm::addHeader)
        }

        ClientCertificate(vm)
    }
}

// Picks a certificate installed on the phone, through the phone's own
// picker. Octo only gets to use the one picked.
@Composable
private fun ClientCertificate(vm: SignInViewModel) {
    val activity = LocalActivity.current
    Section("Client certificate", "For servers that ask the phone to prove itself with a certificate.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                vm.clientCert ?: "None",
                style = OctoType.bodySmall,
                color = if (vm.clientCert == null) OctoColors.TextMuted else OctoColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (vm.clientCert != null) {
                Text(
                    "Remove",
                    style = OctoType.label,
                    color = OctoColors.Accent,
                    modifier = Modifier
                        .clickable(role = Role.Button) { vm.clientCert = null }
                        .padding(8.dp),
                )
            }
        }
        GlazeButton(
            if (vm.clientCert == null) "Choose certificate" else "Choose another",
            enabled = activity != null,
            onClick = {
                val host = activity ?: return@GlazeButton
                val url = normalizeServerUrl(vm.address)
                KeyChain.choosePrivateKeyAlias(
                    host,
                    { alias -> if (alias != null) host.runOnUiThread { vm.clientCert = alias } },
                    null,
                    null,
                    url?.host,
                    url?.port ?: -1,
                    vm.clientCert,
                )
            },
        )
    }
}

@Composable
private fun Section(title: String, detail: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(title, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
            Text(detail, style = OctoType.caption, color = OctoColors.TextMuted)
        }
        content()
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = OctoType.bodySmall, color = if (enabled) OctoColors.TextPrimary else OctoColors.TextMuted)
            Text(detail, style = OctoType.caption, color = OctoColors.TextMuted)
        }
        OctoSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

// Asks before trusting a certificate the phone does not. It shows the
// host and the certificate's fingerprint, so the user can compare it with
// the server's own. Nothing is trusted unless they say so. It floats in
// the middle of the screen, above the bar.
@Composable
private fun TrustSheet(question: SignInError.Untrusted?, onTrust: () -> Unit, onCancel: () -> Unit) {
    GlassPopup(
        visible = question != null,
        anchor = null,
        onDismiss = onCancel,
        backdrop = LocalHaze.current,
        title = "Trust this certificate",
        maxWidth = 380.dp,
    ) {
        val shown = rememberLast(question) ?: return@GlassPopup
        PopupQuestion(
            "Trust this certificate?",
            "This phone doesn't trust the certificate the server showed. If it's your own server's, " +
                "check the fingerprint matches before you trust it. Octo will trust this one " +
                "certificate, and only for this host.",
            "Trust this certificate",
            onConfirm = onTrust,
            onCancel = onCancel,
            modifier = Modifier.verticalScroll(rememberScrollState()),
            width = 380.dp,
        ) {
            Text("Host", style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(top = 16.dp))
            Text(shown.host, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
            Text("SHA-256 fingerprint", style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(top = 12.dp))
            Text(
                formatFingerprint(shown.fingerprint),
                style = OctoType.caption.copy(fontFamily = FontFamily.Monospace),
                color = OctoColors.TextPrimary,
            )
        }
    }
}
