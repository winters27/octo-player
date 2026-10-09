package app.winters.octo.ui.signin

import app.winters.octo.ui.family.QrScanner
import app.winters.octo.ui.family.MIN_PASSWORD
import app.winters.octo.design.ButtonSize
import app.winters.octo.ui.family.JOIN_WITH_A_FAMILY_CODE
import app.winters.octo.subsonic.FamilyLink
import android.security.KeyChain
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.winters.octo.R
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
import app.winters.octo.subsonic.Scheme
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.LocalFeedback
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.LocalReduceMotion
import app.winters.octo.ui.common.PopupQuestion
import app.winters.octo.ui.common.rememberLast
import app.winters.octo.ui.nav.ServerForm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    // Opened from a pairing link: joining a family, filled in.
    join: FamilyLink? = null,
    vm: SignInViewModel = hiltViewModel(),
) {
    val feedback = LocalFeedback.current
    LaunchedEffect(join) { if (join != null) vm.startJoin(join) }
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
        // The camera, over everything, until it reads a family link.
        if (vm.scanning) QrScanner(onFound = { vm.startJoin(it) }, onClose = { vm.scanning = false })
    }
    TrustSheet(vm.question, onTrust = vm::trust, onCancel = vm::distrust)
}

// The sign-in page: Octo's octopus in a soft glow over its name, then one
// glass card with the address, username and password, each led by its
// icon. The octopus comes in as the desktop's opening brings it, rising and
// growing a little as it fades in; the words and the card follow a beat
// apart. With the phone's animations off everything only fades in.
@Composable
private fun SignInForm(vm: SignInViewModel) {
    var reveal by remember { mutableStateOf(false) }
    val still = LocalReduceMotion.current
    val mark = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    val card = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val spec = if (still) CalmFade else Rise
        launch { mark.animateTo(1f, spec) }
        launch {
            if (!still) delay(WORDS_AFTER_MS)
            words.animateTo(1f, spec)
        }
        launch {
            if (!still) delay(CARD_AFTER_MS)
            card.animateTo(1f, spec)
        }
    }
    val rise = with(LocalDensity.current) { 14.dp.toPx() }

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
            OctoMark(mark, still)
            Column(
                Modifier.entrance(words, rise, still),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // From the list of servers it says what the form is for.
                val heading = vm.heading
                if (heading == null) {
                    Text("Octo", style = OctoType.display, color = OctoColors.TextPrimary)
                    Text(
                        when {
                            vm.invite != null -> "Join your family"
                            vm.joining -> "Join your family's server with its QR code or 6 digit code"
                            vm.editing -> "Change how Octo connects"
                            else -> "Sign in to your music server"
                        },
                        style = OctoType.bodySmall,
                        color = OctoColors.TextSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Text(heading, style = OctoType.title, color = OctoColors.TextPrimary, textAlign = TextAlign.Center)
                    vm.subheading?.let {
                        Text(
                            it,
                            style = OctoType.bodySmall,
                            color = OctoColors.TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))

            Column(Modifier.fillMaxWidth().entrance(card, rise, still), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glassPanel(CardShape)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlassInput(
                        value = vm.address,
                        onValueChange = { if (!vm.takeJoinLink(it)) vm.typeAddress(it) },
                        placeholder = "music.example.com",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        leading = { SchemeButton(vm.scheme, onClick = vm::toggleScheme) },
                    )
                    if (vm.form == ServerForm.Add || vm.form == ServerForm.Edit) {
                        GlassInput(
                            value = vm.label,
                            onValueChange = { vm.label = it },
                            placeholder = "Name (optional, like Home or Work)",
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                            leading = { FieldIcon(OctoIcons.Rename) },
                        )
                    }
                    if (vm.joining) {
                        JoinFields(vm)
                        return@Column
                    }
                    GlassInput(
                        value = vm.username,
                        onValueChange = { vm.username = it },
                        placeholder = if (vm.useApiKey) "Username (optional)" else "Username",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        contentType = ContentType.Username,
                        leading = { FieldIcon(OctoIcons.Artist) },
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
                            leading = { FieldIcon(OctoIcons.Key) },
                            trailing = { RevealToggle(reveal) { reveal = !reveal } },
                        )
                    }
                }

                ConnectionNote(vm)

                if (vm.joining) {
                    JoinButtons(vm)
                    return@Column
                }
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

                if (vm.form == null && !vm.editing) {
                    GlazeButton(
                        JOIN_WITH_A_FAMILY_CODE,
                        { vm.startJoin() },
                        size = ButtonSize.Small,
                        icon = painterResource(OctoIcons.Listeners),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                AdvancedToggle(vm.advancedOpen) { vm.advancedOpen = !vm.advancedOpen }
                AnimatedVisibility(vm.advancedOpen) { Advanced(vm) }
            }
        }
    }
}

// The rest of the card while joining a family. With a pair code: the
// username and the code. With an invite: the new member's name and the
// password they choose. A family link pasted into any field fills them in;
// the camera reads a family QR code.
@Composable
private fun JoinFields(vm: SignInViewModel) {
    val invite = vm.invite
    if (invite != null) {
        Text(
            "You're invited to ${invite.server.substringAfter("://")}. Choose your name and a password for your account; this phone then joins.",
            style = OctoType.bodySmall,
            color = OctoColors.TextSecondary,
        )
        GlassInput(
            value = vm.inviteName,
            onValueChange = { vm.inviteName = it },
            placeholder = "Your name",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            leading = { FieldIcon(OctoIcons.Artist) },
        )
        GlassInput(
            value = vm.invitePassword,
            onValueChange = { vm.invitePassword = it },
            placeholder = "Password, at least $MIN_PASSWORD characters",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            visualTransformation = PasswordVisualTransformation(),
            contentType = ContentType.NewPassword,
            leading = { FieldIcon(OctoIcons.Key) },
        )
        GlassInput(
            value = vm.inviteAgain,
            onValueChange = { vm.inviteAgain = it },
            placeholder = "The same password again",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.join() }),
            visualTransformation = PasswordVisualTransformation(),
            contentType = ContentType.NewPassword,
            leading = { FieldIcon(OctoIcons.Key) },
        )
        return
    }
    GlassInput(
        value = vm.username,
        onValueChange = { if (!vm.takeJoinLink(it)) vm.username = it },
        placeholder = "Username",
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        contentType = ContentType.Username,
        leading = { FieldIcon(OctoIcons.Artist) },
    )
    GlassInput(
        value = vm.code,
        onValueChange = vm::typeCode,
        placeholder = "Family code (6 digits)",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.join() }),
        leading = { FieldIcon(OctoIcons.Key) },
    )
}

// Join, what went wrong, and the way back to signing in with a password.
@Composable
private fun JoinButtons(vm: SignInViewModel) {
    AccentButton(
        text = "Join",
        onClick = vm::join,
        loading = vm.busy,
        enabled = vm.joinProblem == null,
        modifier = Modifier.padding(top = 20.dp).fillMaxWidth(),
    )
    vm.error?.let {
        Text(it, style = OctoType.bodySmall, color = OctoColors.Error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
    }
    GlazeButton(
        "Scan the QR code",
        { vm.scanning = true },
        size = ButtonSize.Small,
        icon = painterResource(OctoIcons.Camera),
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        "The person who runs the server gives you a QR code, a link, or a 6 digit code.",
        style = OctoType.caption,
        color = OctoColors.TextMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
    )
    GlazeButton("Sign in with a password", vm::stopJoin, size = ButtonSize.Small, modifier = Modifier.padding(top = 12.dp))
}

// How the octopus and the words come in: a soft spring, or with calm
// motion a quick fade. The words and the card start this long after it.
private val Rise = spring<Float>(dampingRatio = 0.8f, stiffness = 160f)
private val CalmFade = tween<Float>(durationMillis = 180, easing = LinearOutSlowInEasing)
private const val WORDS_AFTER_MS = 110L
private const val CARD_AFTER_MS = 200L

// Fades something in as `shown` goes from 0 to 1, rising into place by
// `rise` pixels unless motion is calm.
private fun Modifier.entrance(shown: Animatable<Float, *>, rise: Float, still: Boolean): Modifier = graphicsLayer {
    val p = shown.value
    alpha = (p / 0.6f).coerceIn(0f, 1f)
    translationY = if (still) 0f else (1f - p) * rise
}

// Octo's octopus in a soft glow of the accent. The picture keeps a wide
// margin round the octopus, so it is drawn larger than the room it takes
// and the glow sits in that margin.
@Composable
private fun OctoMark(shown: Animatable<Float, *>, still: Boolean) {
    val rise = with(LocalDensity.current) { 12.dp.toPx() }
    Box(Modifier.size(MarkRoom), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .requiredSize(MarkDrawn)
                .graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) }
                .drawBehind {
                    val radius = size.minDimension * 0.42f
                    drawCircle(
                        Brush.radialGradient(
                            0f to OctoColors.Accent.copy(alpha = 0.30f),
                            0.55f to OctoColors.Accent.copy(alpha = 0.10f),
                            1f to Color.Transparent,
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                    )
                },
        )
        Image(
            painterResource(R.drawable.splash_octopus),
            contentDescription = "Octo",
            modifier = Modifier.requiredSize(MarkDrawn).graphicsLayer {
                val p = shown.value
                alpha = (p / 0.6f).coerceIn(0f, 1f)
                val grow = if (still) 1f else GROW_FROM + (1f - GROW_FROM) * p
                scaleX = grow
                scaleY = grow
                translationY = if (still) 0f else (1f - p) * rise
            },
        )
    }
}

// The octopus fills about half its picture: drawn at this size it is about
// 110dp across, in a space of this height on the page.
private val MarkDrawn = 220.dp
private val MarkRoom = 150.dp
private const val GROW_FROM = 0.88f

// The start of the address: a lock and "https://", or an open lock and
// "http://", in the field's quieter words, so the address reads whole. A
// tap switches between them; it is picked by itself as the address is
// typed (http at home, https anywhere else).
@Composable
private fun SchemeButton(scheme: Scheme, onClick: () -> Unit) {
    val secure = scheme == Scheme.Https
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = if (secure) "Use http" else "Use https", onClick = onClick)
            .semantics { contentDescription = if (secure) "Encrypted, https" else "Not encrypted, http" }
            .padding(start = 2.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(if (secure) OctoIcons.Lock else OctoIcons.LockOpen),
            contentDescription = null,
            tint = if (secure) OctoColors.Accent else OctoColors.TextMuted,
            modifier = Modifier.padding(end = 8.dp).size(18.dp),
        )
        Text(scheme.prefix, style = OctoType.body, color = OctoColors.TextMuted, maxLines = 1)
    }
}

// The icon at the start of a field, naming it.
@Composable
private fun FieldIcon(@DrawableRes icon: Int) {
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = OctoColors.TextMuted,
        modifier = Modifier.padding(start = 2.dp, end = 10.dp).size(18.dp),
    )
}

// One quiet line under the card on how the connection travels: encrypted,
// plain on the home network, or plain across the internet, which is said
// plainly (in red when the password itself would travel as it is).
@Composable
private fun ConnectionNote(vm: SignInViewModel) {
    val url = vm.url ?: return
    val danger = vm.insecure && vm.legacyPassword && !vm.useApiKey
    val (icon, text) = when {
        url.isHttps -> OctoIcons.Lock to "Encrypted connection"
        !vm.insecure -> OctoIcons.LockOpen to "Not encrypted. Fine on your home network."
        danger -> OctoIcons.LockOpen to "Not encrypted, and a legacy password travels as it is. Anyone on the way can read it."
        else -> OctoIcons.LockOpen to "Not encrypted. Your password goes as a one-time token, but the rest can be read on the way."
    }
    val color = when {
        danger -> OctoColors.Error
        vm.insecure -> OctoColors.TextSecondary
        else -> OctoColors.TextMuted
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp, end = 6.dp).size(14.dp))
        Text(text, style = OctoType.caption, color = color, textAlign = TextAlign.Center)
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
                val url = vm.url
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
