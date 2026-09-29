package app.winters.octo.desktop.pages

import app.winters.octo.design.LocalReduceMotion
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.winters.octo.connection.formatFingerprint
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.server.CertificateQuestion
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.TestOutcome
import app.winters.octo.desktop.server.shownAddress
import app.winters.octo.desktop.ui.LocalKeyColour
import app.winters.octo.desktop.ui.keyRim
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.GlassField
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconAction
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.OctoType
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.Separator
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

// Signing in to a server, as on the phone: its address (with https or
// http in front), a username and password, a test of the connection, and
// the settings most servers never need under Advanced. A certificate the
// system does not trust is asked about before anything is sent.
@Composable
fun SignInPage(app: AppState, backdrop: HazeState) {
    val form = app.signInForm
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    // Which of test or sign-in ran last, to run again once a certificate is trusted.
    var again by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun test() {
        if (!form.ready) return
        again = ::test
        form.busy = true
        form.result = null
        scope.launch {
            when (val tested = app.accounts.test(form.request())) {
                is TestOutcome.Reached -> {
                    form.worked(tested.mode)
                    form.result = true to tested.facts.summary()
                }
                is TestOutcome.Failed -> form.result = false to tested.message
                is TestOutcome.Untrusted -> form.question = tested.question
            }
            form.busy = false
        }
    }

    fun signIn() {
        if (!form.ready) return
        again = ::signIn
        form.busy = true
        form.result = null
        scope.launch {
            when (val done = app.accounts.signIn(form.request())) {
                is SignInOutcome.Done -> {
                    form.password = ""
                    form.apiKey = ""
                    app.signedIn(done.connection, done.note)
                }
                is SignInOutcome.Failed -> form.result = false to done.message
                is SignInOutcome.Untrusted -> form.question = done.question
            }
            form.busy = false
        }
    }

    // Focus starts in the address, or in the password when the server and
    // user are already filled in.
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    // The certificate question floats in the middle of the window. Closing
    // it any way but trusting counts as no.
    LaunchedEffect(form.question) {
        val asked = form.question ?: return@LaunchedEffect
        app.popups.showCentred(width = 420.dp) { close ->
            DisposableEffect(Unit) { onDispose { if (form.question === asked) form.distrust() } }
            TrustQuestion(
                asked,
                onTrust = {
                    form.question = null
                    close()
                    app.accounts.security.trust(asked.host, asked.fingerprint)
                    again?.invoke()
                },
                onCancel = close,
            )
        }
    }

    // While the certificate is asked about, the card steps back behind it.
    val asking by animateFloatAsState(if (form.question != null) 0.35f else 1f, label = "asking")
    BoxWithConstraints(Modifier.fillMaxSize().alpha(asking)) {
        // On a short window the card packs tighter, so Sign in shows without scrolling.
        val short = maxHeight < ShortWindow
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(vertical = if (short) 12.dp else 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SignInCard(backdrop, short) {
                Txt("Octo", OctoType.display)
                Txt("Sign in to your music server. Any Subsonic, Navidrome or Octo server works.", OctoType.bodySmall, OctoColors.TextSecondary, maxLines = 3)

                Label("Server address")
                GlassField(
                    form.address,
                    form::typeAddress,
                    Modifier.fillMaxWidth(),
                    placeholder = "music.example.com or 192.168.1.20:4533",
                    focusRequester = if (form.startsAtPassword) null else first,
                    onSubmit = ::signIn,
                    leading = { SchemeToggle(form.scheme.prefix, form::toggleScheme) },
                )
                form.url?.let { Txt("Connects to ${shownAddress(it)}", OctoType.caption, OctoColors.TextMuted) }
                if (form.insecure) Txt("This address isn't encrypted and isn't on your home network, so others could read what is sent.", OctoType.caption, OctoColors.Error, maxLines = 3)

                Label(if (form.useApiKey) "Username (optional)" else "Username")
                GlassField(form.username, { form.username = it; form.result = null }, Modifier.fillMaxWidth(), onSubmit = ::signIn)
                if (!form.useApiKey) {
                    Label("Password")
                    SecretField(form.password, { form.password = it; form.result = null }, "Password", ::signIn, if (form.startsAtPassword) first else null)
                }
                SwitchRow(
                    if (form.useApiKey) "Remember API key" else "Remember password",
                    "Off, Octo asks again each time it opens.",
                    form.rememberPassword,
                ) { form.rememberPassword = it }

                AdvancedToggle(form.advancedOpen) { form.advancedOpen = !form.advancedOpen }
                if (form.advancedOpen) Advanced(form, ::signIn)

                form.result?.let { (ok, text) ->
                    Txt(text, OctoType.bodySmall, if (ok) OctoColors.TextSecondary else OctoColors.Error, maxLines = 4)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    GlazeCapsule(null, "Test connection", ::test, enabled = form.ready)
                    AccentButton("Sign in", ::signIn, Modifier.widthIn(min = 150.dp), enabled = form.ready, loading = form.busy, size = ButtonSize.Medium)
                }
            }
        }
    }
}

private val CardShape = RoundedCornerShape(22.dp)

// Below this height the card packs tighter.
private val ShortWindow = 720.dp

// The one card of the sign-in: frosted glass over Octo's colours, with a
// rim that takes a hint of the key colour.
@Composable
private fun SignInCard(backdrop: HazeState, short: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val key = LocalKeyColour.current
    FloatingGlaze(
        backdrop = backdrop,
        modifier = Modifier.width(480.dp),
        shape = CardShape,
        film = CardFilm,
        frost = 20f,
        saturation = 1.8f,
    ) {
        Box(Modifier.matchParentSize().innerShadow(CardShape, Shadow(radius = 0.dp, spread = 1.dp, color = keyRim(key))))
        Column(Modifier.fillMaxWidth().padding(if (short) 20.dp else 32.dp), verticalArrangement = Arrangement.spacedBy(if (short) 6.dp else 12.dp), content = content)
    }
}

// The card's film: a dark grey that lets the colours through.
private val CardFilm = Color(0xFF1A1A1E).copy(alpha = 0.55f)

// The settings most servers never need: other ways of signing in, a home
// address and extra headers.
@Composable
private fun ColumnScope.Advanced(form: SignInForm, submit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SwitchRow(
            "Legacy sign-in",
            "Only for older servers that reject the normal sign-in.",
            checked = form.legacyPassword && !form.useApiKey,
            enabled = !form.useApiKey,
        ) { form.legacyPassword = it; form.result = null }

        // Before signing in nobody knows whether the server takes keys, so
        // one may be tried; once known, the switch shows only where it works.
        if (form.takesApiKeys != false || form.useApiKey) {
            SwitchRow(
                "Sign in with an API key",
                if (form.takesApiKeys == true) "Your server gives out API keys." else "For servers that give out API keys.",
                checked = form.useApiKey,
            ) { form.useApiKey = it; form.result = null }
            if (form.useApiKey) SecretField(form.apiKey, { form.apiKey = it; form.result = null }, "API key", submit)
        }

        Section("Home network address", "Optional. At home, Octo uses it when it answers, and the main address otherwise.") {
            GlassField(form.home, { form.home = it; form.result = null }, Modifier.fillMaxWidth(), placeholder = "Home network address", onSubmit = submit)
        }

        Section("Custom headers", "Sent with every request to this server, such as an access token for a proxy in front of it.") {
            form.headers.forEachIndexed { index, header ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassField(header.name, { form.setHeaderName(index, it) }, Modifier.weight(0.42f), placeholder = "Header name", onSubmit = submit)
                    GlassField(
                        header.value,
                        { form.setHeaderValue(index, it) },
                        Modifier.weight(0.58f),
                        placeholder = if (header.saved) "Value (unchanged)" else "Value",
                        password = true,
                        onSubmit = submit,
                    )
                    OctoTooltip("Remove header") {
                        IconAction(OctoIcons.Close, "Remove header", { form.removeHeader(index) }, size = 32.dp, iconSize = 16.dp)
                    }
                }
            }
            GlazeCapsule(null, "Add header", form::addHeader, height = 32.dp)
        }
    }
}

// A password or key: dots until the eye inside the field shows it.
@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, placeholder: String, submit: () -> Unit, focus: FocusRequester? = null) {
    var reveal by remember { mutableStateOf(false) }
    GlassField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        placeholder = placeholder,
        password = !reveal,
        focusRequester = focus,
        onSubmit = submit,
        trailing = {
            val words = if (reveal) "Hide" else "Show"
            OctoTooltip(words) {
                IconAction(
                    if (reveal) OctoIcons.Conceal else OctoIcons.Reveal,
                    words,
                    { reveal = !reveal },
                    // Tab goes from field to field, past the eye.
                    Modifier.focusProperties { canFocus = false },
                    size = 32.dp,
                    iconSize = 18.dp,
                    tint = OctoColors.TextSecondary,
                )
            }
        },
    )
}

// The scheme at the start of the address: a small darker pill set into
// the field that switches between https:// and http:// when clicked.
@Composable
private fun SchemeToggle(prefix: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val other = if (prefix == "https://") "http://" else "https://"
    OctoTooltip("Switch to $other") {
        Box(
            Modifier
                .focusProperties { canFocus = false }
                .background(Color.Black.copy(alpha = 0.35f), shape)
                .hoverLift(shape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "Switch to $other" }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Txt(prefix, OctoType.bodySmall, OctoColors.TextSecondary)
        }
    }
}

@Composable
private fun AdvancedToggle(open: Boolean, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 90f else 0f, if (LocalReduceMotion.current) snap() else spring(), label = "advanced chevron")
    Column(Modifier.padding(top = 4.dp)) {
        Separator()
        Row(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .hoverLift(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt("Advanced", OctoType.label, OctoColors.TextSecondary, Modifier.weight(1f))
            Glyph(OctoIcons.Chevron, Modifier.rotate(turn), size = 18.dp, tint = OctoColors.TextMuted)
        }
    }
}

@Composable
private fun Section(title: String, detail: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Txt(title, OctoType.bodySmall)
            Txt(detail, OctoType.caption, OctoColors.TextMuted, maxLines = 2)
        }
        content()
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Txt(label, OctoType.bodySmall, if (enabled) OctoColors.TextPrimary else OctoColors.TextMuted)
            Txt(detail, OctoType.caption, OctoColors.TextMuted, maxLines = 2)
        }
        OctoSwitch(checked, onChange, enabled = enabled)
    }
}

// Asks before trusting a certificate the system does not, in the phone's
// words. It shows the host and the certificate's fingerprint, so the
// listener can compare it with the server's own. Nothing is trusted unless
// they say so.
@Composable
private fun TrustQuestion(question: CertificateQuestion, onTrust: () -> Unit, onCancel: () -> Unit) {
    MenuTitle("Trust this certificate?")
    PopupPadding {
        Txt(
            "This computer doesn't trust the certificate the server showed. If it's your own server's, " +
                "check the fingerprint matches before you trust it. Octo will trust this one " +
                "certificate, and only for this host.",
            OctoType.bodySmall,
            OctoColors.TextSecondary,
            maxLines = 6,
        )
        Column {
            Txt("Host", OctoType.caption, OctoColors.TextMuted)
            Txt(question.host, OctoType.bodySmall)
        }
        Column {
            Txt("SHA-256 fingerprint", OctoType.caption, OctoColors.TextMuted)
            // Two lines of sixteen pairs, so no pair is split.
            val pairs = formatFingerprint(question.fingerprint).split(':')
            Txt(pairs.chunked(16).joinToString("\n") { it.joinToString(":") }, OctoType.caption.copy(fontFamily = FontFamily.Monospace), maxLines = 3)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            GlazeCapsule(null, "Cancel", onCancel)
            GlazeCapsule(null, "Trust this certificate", onTrust, lit = true)
        }
    }
}

@Composable
private fun Label(text: String) {
    Txt(text, OctoType.caption, OctoColors.TextMuted, Modifier.padding(top = 4.dp))
}
