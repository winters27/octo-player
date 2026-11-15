package app.winters.octo.ui.signin

import android.security.KeyChain
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.AutoClearFocusBehavior
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.winters.octo.R
import app.winters.octo.connection.AdvancedGroup
import app.winters.octo.connection.AdvancedRow
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.SignInWords
import app.winters.octo.connection.advancedLayout
import app.winters.octo.connection.formatFingerprint
import app.winters.octo.data.SignInError
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.GlassPopup
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.FamilyLink
import app.winters.octo.subsonic.Scheme
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.CalloutEffect
import app.winters.octo.ui.common.CalloutFocus
import app.winters.octo.ui.common.CalloutHost
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.FormCertificateRow
import app.winters.octo.ui.common.FormChoiceRow
import app.winters.octo.ui.common.FormDivider
import app.winters.octo.ui.common.FormError
import app.winters.octo.ui.common.FormFieldRow
import app.winters.octo.ui.common.FormGroupCard
import app.winters.octo.ui.common.FormGroupTitle
import app.winters.octo.ui.common.FormNote
import app.winters.octo.ui.common.FormRowTitle
import app.winters.octo.ui.common.FormSectionHead
import app.winters.octo.ui.common.FormStatusRow
import app.winters.octo.ui.common.HeaderRow
import app.winters.octo.ui.common.LocalCalloutHost
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.common.LocalFeedback
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.LocalReduceMotion
import app.winters.octo.ui.common.PopupQuestion
import app.winters.octo.ui.common.glideTo
import app.winters.octo.ui.common.octoIcon
import app.winters.octo.ui.common.rememberLast
import app.winters.octo.ui.family.LocalQrScanner
import app.winters.octo.ui.family.passwordStrength
import app.winters.octo.ui.family.shownServer
import app.winters.octo.ui.nav.ServerForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import kotlin.math.roundToInt

// Soft edges on the page's scroll. What runs up under the back button melts
// away over the top gap instead of showing through behind it; what runs on
// past the bottom fades out over BottomFade, gently at both ends. Each shows
// only while there is more of the page that way, coming in over the first
// stretch scrolled so it never pops.
private fun Modifier.pageFades(scroll: ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val top = DetailTopGap.toPx()
        val above = (scroll.value / top).coerceIn(0f, 1f)
        if (above > 0f) {
            val hidden = Shown.copy(alpha = 1f - above)
            drawRect(
                Brush.verticalGradient(0f to hidden, 0.4f to hidden, 1f to Shown, endY = top),
                size = Size(size.width, top),
                blendMode = BlendMode.DstIn,
            )
        }
        val bottom = BottomFade.toPx()
        val below = ((scroll.maxValue - scroll.value) / bottom).coerceIn(0f, 1f)
        if (below > 0f) {
            drawRect(
                Brush.verticalGradient(*easedStops(1f, 1f - below), startY = size.height - bottom, endY = size.height),
                topLeft = Offset(0f, size.height - bottom),
                size = Size(size.width, bottom),
                blendMode = BlendMode.DstIn,
            )
        }
    }

// How tall the soft edge at the bottom of the page's scroll is.
private val BottomFade = 24.dp

// How much of Advanced's first card shows under its group's title, above the
// soft bottom edge, once Advanced is opened from its head.
private val AdvancedPeek = 24.dp

// Whether Advanced's head opened it: only that opening scrolls its first
// group into view. Not state: the effect that scrolls reads and clears it.
private class HeadOpening {
    var fromHead = false
}

// What a fade's mask draws where the page shows: only its alpha counts.
private val Shown = Color.Black

// The stops of a fade's mask from showing `from` to showing `to`, along a
// smooth step, so it starts and ends gently.
private fun easedStops(from: Float, to: Float): Array<Pair<Float, Color>> = Array(9) { i ->
    val t = i / 8f
    t to Shown.copy(alpha = from + (to - from) * t * t * (3 - 2 * t))
}

// Opened from Settings. Signing in goes back there, and the server's
// music starts copying on its own. When editing, it starts from the saved
// connection and signs in again with the changes.
//
// From the list of servers it is the sheet for adding a server (the one in
// use stays), editing a kept one, or signing in to one again.
//
// The camera opens over the page as a modal: focus stays in it, the page
// behind it is out of a screen reader's reach, Back closes it, and every way
// it closes gives focus a home out of touch mode (where Android cannot
// leave nothing focused): Cancel, Back and Escape return to the button that
// opened it, and a link it read goes to the address.
@Composable
fun SignInScreen(
    onBack: () -> Unit,
    // Called once it has signed in: the page closes itself (its own entry,
    // never whatever was pushed over it while it ran).
    onDone: () -> Unit,
    editing: Boolean = false,
    form: ServerForm? = null,
    serverId: String? = null,
    note: String? = null,
    // Opened from a family link: an invite, a sign-in from another device,
    // or a server and username to fill in. `joinOpened` tells this opening
    // from a page drawn again.
    join: FamilyLink? = null,
    joinOpened: Long = 0,
    vm: SignInViewModel = hiltViewModel(),
) {
    val feedback = LocalFeedback.current
    val focusManager = LocalFocusManager.current
    val inputModes = LocalInputModeManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scanCancel = remember { FocusRequester() }
    val scanAgain = remember { FocusRequester() }
    val addressFocus = remember { FocusRequester() }
    val closeScanner = { if (inputModes.inputMode != InputMode.Touch) scanAgain.requestFocus(); vm.scanning = false }
    val focusAddress = { if (inputModes.inputMode != InputMode.Touch) addressFocus.requestFocus() }
    val onFound: (FamilyLink) -> Unit = { if (vm.scanning) { focusAddress(); vm.startJoin(it) } }
    LaunchedEffect(join, joinOpened) { if (join != null) vm.open(join, joinOpened) { focusAddress() } }
    LaunchedEffect(editing) { if (editing) vm.startEditing() }
    LaunchedEffect(form) { if (form != null) vm.start(form, serverId, note) }
    LaunchedEffect(vm.signedIn) {
        if (vm.signedIn) {
            vm.notice?.let(feedback::done)
            onDone()
        }
    }
    // The camera opening: in touch mode nothing stays focused; otherwise its
    // Cancel takes focus and the on-screen keyboard closes.
    LaunchedEffect(vm.scanning) { if (vm.scanning) { if (inputModes.inputMode == InputMode.Touch) focusManager.clearFocus() else { scanCancel.requestFocus(); keyboard?.hide() } } }

    // Back while a step commits (a sign-up, a sign-in) does nothing until it
    // ends. With a finished sign-up held it leaves the link and stays, the
    // new username in its field, so the next Back leaves. Otherwise it leaves
    // the link (cancelling a hand-over that waits for the other device) and
    // the page.
    val back = {
        when {
            vm.committing -> Unit
            vm.holdsSignUp -> vm.stopJoin()
            else -> {
                vm.stopJoin()
                onBack()
            }
        }
    }
    Box(Modifier.fillMaxSize().background(OctoColors.Background)) {
        Box(Modifier.fillMaxSize().then(if (vm.scanning) Modifier.clearAndSetSemantics {} else Modifier)) {
            SignInForm(vm, scanAgain, addressFocus)
            BackButton(back, enabled = !vm.committing)
        }
        // The camera, over everything, until it reads a family link.
        if (vm.scanning) LocalQrScanner.current(onFound, closeScanner, scanCancel)
    }
    BackHandler(enabled = vm.busy || vm.holdsSignUp) { back() }
    // While the camera is open, Back and Escape close it (the handler
    // composed last wins).
    BackHandler(enabled = vm.scanning) { closeScanner() }
    TrustSheet(vm.question?.let { TrustAsk(it.host, it.fingerprint) }, onTrust = vm::trust, onCancel = vm::distrust)
}

// The sign-in page: Octo's octopus in a soft glow over its name, then the
// main card (the address and the sign-in, or a family link's own rows), its
// notes, the button with what the last attempt came to under it, and
// Advanced. The octopus comes in as the desktop's opening brings it, rising
// and growing a little as it fades in; the words and the card follow a beat
// apart. With the phone's animations off everything only fades in.
//
// A failure that met a sign-in page, and picking API key with no key kept,
// each bring their part into view (CalloutEffect, here at the page's level
// so closing Advanced never ends it).
@Composable
private fun SignInForm(vm: SignInViewModel, scanAgain: FocusRequester, addressFocus: FocusRequester) {
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
    val density = LocalDensity.current
    val rise = with(density) { 14.dp.toPx() }
    val scroll = rememberScrollState()
    val ime = WindowInsets.ime
    val calloutFocus = remember { CalloutFocus() }
    var content by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var gatewayGroup by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var gatewayTitle by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var keyRow by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Read in this part's own body, which a side effect's reads are not, so
    // the groups fade in or out on every change of it, a tap on the head
    // included.
    val advancedOpen = vm.advancedOpen
    val advanced = remember { MutableTransitionState(advancedOpen) }
    SideEffect { advanced.targetState = advancedOpen }
    val topInset = with(density) { (DetailTopGap + 12.dp).roundToPx() }
    val host = content?.let {
        CalloutHost(
            scroll = scroll,
            topInset = topInset,
            content = it,
            settled = { advanced.isIdle && advanced.currentState },
            imeBottom = { ime.getBottom(density) },
            focus = calloutFocus,
            fade = with(density) { BottomFade.roundToPx() },
        )
    }
    CalloutEffect(vm.calloutPending, host, { gatewayGroup }, vm::calloutShown)
    CalloutEffect(vm.keyPending, host?.copy(settled = { true }), { keyRow }, vm::keyShown)

    // Advanced opened from its head (a tap, or the keyboard's Enter) brings
    // its first group's title, with the top of that group's card, above the
    // soft bottom edge in one glide, or at once with calm motion, so groups
    // that open under a head low on the screen are seen. A page that already
    // shows that much stays where it is, and a callout on its way does its
    // own scroll instead.
    val opening = remember { HeadOpening() }
    val reveal = with(density) { (AdvancedPeek + BottomFade).roundToPx() }
    LaunchedEffect(advancedOpen) {
        if (!advancedOpen || !opening.fromHead) return@LaunchedEffect
        opening.fromHead = false
        if (vm.calloutPending || vm.keyPending) return@LaunchedEffect
        val title = snapshotFlow { gatewayTitle?.takeIf { it.isAttached } }.filterNotNull().first()
        val page = content?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val target = page.localPositionOf(title, Offset.Zero).y.roundToInt() + title.size.height + reveal - scroll.viewportSize
        if (target <= scroll.value) return@LaunchedEffect
        if (still) scroll.scrollTo(target) else scroll.glideTo(target)
    }

    CompositionLocalProvider(LocalCalloutHost provides host) {
        Box(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .pageFades(scroll)
                .verticalScroll(scroll),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .onGloballyPositioned { content = it }
                    // Clear of the back button above.
                    .padding(start = 24.dp, end = 24.dp, top = DetailTopGap, bottom = 32.dp),
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
                                vm.invite != null -> "Sign up for your family's server"
                                vm.handOver != null -> "Signing in with your other device's login"
                                vm.joining -> "Scan the QR code from your invite or your other device"
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
                    MainCard(vm, addressFocus) { keyRow = it }
                    MainNotes(vm)
                    if (vm.joining) JoinButtons(vm, scanAgain) else SignInButtons(vm)
                    // Advanced shows for a family link too: a server behind a
                    // sign-in page needs its fields before the link can go.
                    FormSectionHead(
                        SignInWords.ADVANCED,
                        SignInWords.ADVANCED_LINE,
                        vm.advancedOpen,
                        {
                            vm.advancedOpen = !vm.advancedOpen
                            opening.fromHead = vm.advancedOpen
                        },
                        Modifier.padding(top = 20.dp),
                    )
                    AnimatedVisibility(advanced, enter = fadeIn(), exit = fadeOut()) {
                        Advanced(vm, onGatewayTitle = { gatewayTitle = it }) { gatewayGroup = it }
                    }
                }
            }
        }
    }
}

// The main card: the address (with its scheme at the start), the server's
// name when adding or editing one, then the sign-in: a username with the
// password or the API key, or, for a family link, an invite's name and
// password or a hand-over's status. The keyboard's Next only ever goes to a
// field drawn after it; a field with none after it says Done.
@Composable
private fun MainCard(vm: SignInViewModel, addressFocus: FocusRequester, onKeyRow: (LayoutCoordinates) -> Unit) {
    val showsName = vm.form == ServerForm.Add || vm.form == ServerForm.Edit
    FormGroupCard {
        FormFieldRow(
            title = SignInWords.SERVER_ADDRESS,
            value = vm.address,
            onChange = { if (!vm.takeJoinLink(it)) vm.typeAddressFromField(it) },
            placeholder = "music.example.com",
            keyboardType = KeyboardType.Uri,
            imeAction = if (vm.joining && vm.invite == null && !showsName) ImeAction.Done else ImeAction.Next,
            leading = { SchemeButton(vm.scheme, enabled = !vm.holdsSignUp, onClick = vm::toggleScheme) },
            focus = addressFocus,
            readOnly = vm.holdsSignUp,
        )
        LinkNote(vm)
        if (showsName) {
            FormDivider()
            FormFieldRow(
                title = SignInWords.SERVER_NAME,
                optional = true,
                value = vm.label,
                onChange = { vm.label = it },
                placeholder = SignInWords.NAME_PLACEHOLDER,
                capitalization = KeyboardCapitalization.Words,
                autocorrect = true,
                imeAction = if (vm.joining && vm.invite == null) ImeAction.Done else ImeAction.Next,
            )
        }
        val handOver = vm.handOver
        when {
            handOver != null -> {
                FormDivider()
                val host = shownServer(handOver.server)
                FormStatusRow(vm.handOverStep ?: if (vm.error != null) "Not signed in to $host yet" else "Ready to sign in to $host", vm.busy)
            }
            vm.invite != null -> {
                FormDivider()
                FormFieldRow(
                    title = SignInWords.YOUR_NAME,
                    value = vm.inviteName,
                    onChange = { vm.inviteName = it },
                    placeholder = SignInWords.REQUIRED,
                    contentType = ContentType.PersonFullName,
                    capitalization = KeyboardCapitalization.Words,
                    autocorrect = true,
                    imeAction = ImeAction.Next,
                )
                FormDivider()
                FormFieldRow(
                    title = SignInWords.PASSWORD,
                    description = passwordStrength(vm.invitePassword),
                    value = vm.invitePassword,
                    onChange = { vm.invitePassword = it },
                    placeholder = SignInWords.REQUIRED,
                    secret = true,
                    contentType = ContentType.NewPassword,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                )
                FormDivider()
                FormFieldRow(
                    title = SignInWords.PASSWORD_AGAIN,
                    value = vm.inviteAgain,
                    onChange = { vm.inviteAgain = it },
                    placeholder = SignInWords.REQUIRED,
                    secret = true,
                    contentType = ContentType.NewPassword,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    onSubmit = vm::join,
                )
            }
            vm.joining -> Unit
            else -> {
                FormDivider()
                FormFieldRow(
                    title = SignInWords.USERNAME,
                    optional = vm.useApiKey,
                    value = vm.username,
                    onChange = { vm.username = it },
                    placeholder = if (vm.useApiKey) "" else SignInWords.REQUIRED,
                    contentType = ContentType.Username,
                    imeAction = ImeAction.Next,
                )
                FormDivider()
                if (vm.useApiKey) {
                    FormFieldRow(
                        title = SignInWords.API_KEY,
                        value = vm.apiKey,
                        onChange = { vm.apiKey = it },
                        placeholder = if (vm.keepsKey) SignInWords.UNCHANGED else SignInWords.REQUIRED,
                        secret = true,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        onSubmit = vm::submit,
                        modifier = Modifier.onGloballyPositioned(onKeyRow),
                    )
                } else {
                    FormFieldRow(
                        title = SignInWords.PASSWORD,
                        value = vm.password,
                        onChange = { vm.password = it },
                        placeholder = if (vm.keepsPassword) SignInWords.UNCHANGED else SignInWords.REQUIRED,
                        secret = true,
                        contentType = ContentType.Password,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        onSubmit = vm::submit,
                    )
                }
            }
        }
    }
}

// A family link refused here, said under the address it was pasted into,
// which stays above the keyboard while it has focus; it is brought into
// view as it appears.
@Composable
private fun LinkNote(vm: SignInViewModel) {
    val words = vm.linkNote ?: return
    val seen = remember { BringIntoViewRequester() }
    LaunchedEffect(words) { seen.bringIntoView() }
    FormNote(words, Modifier.bringIntoViewRequester(seen).padding(bottom = 12.dp))
}

// The notes under the main card: how the connection travels, then what a
// family link asks of the person.
@Composable
private fun MainNotes(vm: SignInViewModel) {
    ConnectionNote(vm)
    val invite = vm.invite
    when {
        vm.handOver != null -> FormNote("On your other device, tap Allow when it asks about this phone.")
        invite != null -> FormNote(
            "You're invited to ${shownServer(invite.server)}. Choose your name and a password. Your login then works in Octo and any other music app.",
        )
        vm.joining -> FormNote(
            "Your invite shows a QR code. So does Sign in on another device, in Octo on your other phone or computer. Scan it, or open its link on this phone.",
        )
    }
}

// Sign in (or Add, or Save), what the last attempt came to, and the
// scanner for a family QR code.
@Composable
private fun SignInButtons(vm: SignInViewModel) {
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
    ResultLine(vm)
    if (vm.form == null && !vm.editing) {
        // An invite, or a sign-in shown on another device of one's own, under
        // the button on its right edge; off while a sign-in runs.
        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.CenterEnd) {
            GlazeButton(
                "Scan a family QR code",
                vm::scanFamilyCode,
                enabled = !vm.busy,
                size = ButtonSize.Small,
                icon = octoIcon(OctoIcons.Camera),
            )
        }
    }
}

// Sign up (or, for a sign-in from another device, Try again), what the last
// attempt came to, the scanner, and the way back to signing in with a
// password.
@Composable
private fun JoinButtons(vm: SignInViewModel, scanAgain: FocusRequester) {
    when {
        vm.invite != null -> AccentButton(
            text = "Sign up",
            onClick = vm::join,
            loading = vm.busy,
            enabled = vm.joinProblem == null,
            modifier = Modifier.padding(top = 20.dp).fillMaxWidth(),
        )
        vm.handOver != null && !vm.busy -> AccentButton(
            text = "Try again",
            onClick = vm::receive,
            modifier = Modifier.padding(top = 20.dp).fillMaxWidth(),
        )
    }
    ResultLine(vm)
    // The scanner and the way back to a password sign-in, under the button
    // on its right edge, where Scan a family QR code sits on the plain page.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (vm.handOver == null) {
            GlazeButton(
                "Scan the QR code",
                { vm.scanning = true },
                size = ButtonSize.Small,
                icon = octoIcon(OctoIcons.Camera),
                modifier = Modifier.padding(top = 12.dp).focusRequester(scanAgain),
            )
        }
        GlazeButton("Sign in with a password", vm::stopJoin, enabled = !vm.committing, size = ButtonSize.Small, modifier = Modifier.padding(top = 12.dp))
    }
}

// What the last attempt came to, under its button: while the sign-in page
// group calls the failure out, a pointer that brings the group into view;
// otherwise the failure itself.
@Composable
private fun ResultLine(vm: SignInViewModel) {
    val error = vm.error ?: return
    if (vm.callout && vm.advancedOpen) FormError(GatewayWords.POINTER, onClick = vm::pointTo) else FormError(error)
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
    val glow = OctoColors.Accent
    Box(Modifier.size(MarkRoom), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .requiredSize(MarkDrawn)
                .graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) }
                .drawBehind {
                    val radius = size.minDimension * 0.42f
                    drawCircle(
                        Brush.radialGradient(
                            0f to glow.copy(alpha = 0.30f),
                            0.55f to glow.copy(alpha = 0.10f),
                            1f to Color.Transparent,
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                    )
                },
        )
        Image(
            octoIcon(R.drawable.splash_octopus),
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
// typed (http at home, https anywhere else). While the address is held (a
// finished sign-up signs in there) it takes no tap.
@Composable
private fun SchemeButton(scheme: Scheme, enabled: Boolean, onClick: () -> Unit) {
    val secure = scheme == Scheme.Https
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = if (secure) "Use http" else "Use https", onClick = onClick)
            .semantics { contentDescription = if (secure) "Encrypted, https" else "Not encrypted, http" }
            .padding(start = 2.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            octoIcon(if (secure) OctoIcons.Lock else OctoIcons.LockOpen),
            contentDescription = null,
            tint = if (secure) OctoColors.Accent else OctoColors.TextMuted,
            modifier = Modifier.padding(end = 8.dp).size(18.dp),
        )
        Text(scheme.prefix, style = OctoType.formEntry, color = OctoColors.TextMuted, maxLines = 1)
    }
}

// How the connection travels, as a note under the main card with its lock:
// encrypted, plain on the home network, or plain across the internet, said
// plainly. A password or an API key that would itself travel as it is across
// the internet is an error. A family link signs in the normal way.
@Composable
private fun ConnectionNote(vm: SignInViewModel) {
    val url = vm.url ?: return
    val mode = if (vm.joining) AuthMode.Token else vm.mode
    when {
        url.isHttps -> FormNote("Encrypted connection", icon = OctoIcons.Lock)
        !vm.insecure -> FormNote("Not encrypted. Fine on your home network.", icon = OctoIcons.LockOpen, iconTint = OctoColors.TextMuted)
        mode == AuthMode.LegacyPassword -> FormError(
            "Not encrypted, and a legacy password travels as it is. Anyone on the way can read it.",
        )
        mode == AuthMode.ApiKey -> FormError(SignInWords.API_KEY_IN_THE_OPEN)
        else -> FormNote(
            "Not encrypted. Your password goes as a one-time token, but the rest can be read on the way.",
            icon = OctoIcons.LockOpen,
            iconTint = OctoColors.TextSecondary,
        )
    }
}

// The settings most servers never need, in three groups in the order every
// app draws them: getting to the server (a sign-in page in front of it and
// the home address), signing in (the Method; left out for a family link,
// whose way of signing in is the link's), and special setups (custom headers
// and the client certificate). `onGatewayTitle` hears where the first
// group's title sits, down to the top of its card; `onGatewayGroup` where
// the whole group sits, from its title through its last note.
@Composable
internal fun Advanced(vm: SignInViewModel, onGatewayTitle: (LayoutCoordinates) -> Unit = {}, onGatewayGroup: (LayoutCoordinates) -> Unit = {}) {
    val layout = advancedLayout(vm.joining, vm.takesApiKeys, vm.useApiKey, vm.gatewayKind, certificateRow = true)
    Column(Modifier.fillMaxWidth()) {
        layout.groups.forEachIndexed { index, group ->
            key(group.group) {
                val gap = if (index == 0) 12.dp else 26.dp
                when (group.group) {
                    AdvancedGroup.GettingThere -> GatewayGroup(vm, group.rows, Modifier.padding(top = gap).onGloballyPositioned(onGatewayGroup), onGatewayTitle)
                    AdvancedGroup.SigningIn -> MethodGroup(vm, layout.methods, Modifier.padding(top = gap))
                    AdvancedGroup.SpecialSetups -> SetupsGroup(vm, group.rows, Modifier.padding(top = gap))
                }
            }
        }
    }
}

// The sign-in page in front of the server (Authentik, Authelia, a proxy's
// password or Cloudflare Access): which sign-in it takes from apps, its two
// fields once a kind is picked, named for the page met, and the home
// address. Under the card: a failure it calls out (the card outlined, the
// failure in the group's own words), then what to know about it. `onTitle`
// hears where its title sits.
@Composable
private fun GatewayGroup(vm: SignInViewModel, rows: List<AdvancedRow>, modifier: Modifier, onTitle: (LayoutCoordinates) -> Unit) {
    val sheet = LocalChoiceSheet.current
    val error = vm.error
    val calling = vm.callout && vm.advancedOpen && error != null
    val labels = GatewayWords.fieldLabels(vm.gatewayKind, vm.gateway)
    // Values saved for this kind stay while its fields are left empty.
    val saved = vm.gatewayKind == vm.gatewaySaved
    val kinds = GatewayKind.entries
    Column(modifier.fillMaxWidth()) {
        FormGroupTitle(SignInWords.GROUP_GETTING_THERE, Modifier.onGloballyPositioned(onTitle))
        FormGroupCard(outlined = calling) {
            rows.forEachIndexed { index, row ->
                key(row) {
                    if (index > 0) FormDivider()
                    when (row) {
                        AdvancedRow.PageKind -> FormChoiceRow(
                            title = GatewayWords.LABEL_SECTION,
                            value = GatewayWords.kindLabel(vm.gatewayKind),
                            description = GatewayWords.kindHelper(vm.gatewayKind) ?: GatewayWords.LABEL_CAPTION,
                            answers = kinds.map { GatewayWords.kindLabel(it) },
                            onClick = {
                                sheet.show(
                                    ChoiceRequest(
                                        GatewayWords.LABEL_SECTION,
                                        kinds.map { Choice(GatewayWords.kindLabel(it), GatewayWords.kindHelper(it) ?: GatewayWords.LABEL_CAPTION) },
                                        kinds.indexOf(vm.gatewayKind),
                                    ) { vm.pickGateway(kinds[it]) },
                                )
                            },
                        )
                        AdvancedRow.PageFirst -> FormFieldRow(
                            title = labels.first,
                            value = vm.gatewayFirst,
                            onChange = { vm.gatewayFirst = it },
                            placeholder = if (saved) SignInWords.UNCHANGED else SignInWords.REQUIRED,
                            keyboardType = if (vm.gatewayKind == GatewayKind.CloudflareToken) KeyboardType.Ascii else KeyboardType.Text,
                            imeAction = ImeAction.Next,
                        )
                        AdvancedRow.PageSecond -> FormFieldRow(
                            title = labels.second,
                            value = vm.gatewaySecond,
                            onChange = { vm.gatewaySecond = it },
                            placeholder = if (saved) SignInWords.UNCHANGED else SignInWords.REQUIRED,
                            secret = true,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                            onSubmit = gatewaySubmit(vm),
                        )
                        AdvancedRow.Home -> FormFieldRow(
                            title = SignInWords.HOME_ADDRESS,
                            optional = true,
                            value = vm.home,
                            onChange = { vm.home = it },
                            placeholder = SignInWords.HOME_PLACEHOLDER,
                            keyboardType = KeyboardType.Uri,
                            imeAction = if (vm.headers.isEmpty()) ImeAction.Done else ImeAction.Next,
                        )
                        else -> Unit
                    }
                }
            }
        }
        if (calling && error != null) FormError(GatewayWords.inGroup(error))
        // A sign-in page's password never crosses the internet readable.
        if (vm.gatewayKind != GatewayKind.None && vm.insecure) FormError(GatewayWords.NEEDS_HTTPS)
        // How to get through the one a sign-in met, or, with none met, what
        // the kind picked needs; for a family link, who to ask for an
        // account there.
        val met = vm.gateway
        if (met != null) FormNote(GatewayWords.hint(met)) else GatewayWords.tip(vm.gatewayKind)?.let { FormNote(it) }
        if (vm.joining) FormNote(GatewayWords.JOIN_HINT)
        FormNote(SignInWords.HOME_NOTE)
    }
}

// What Done in the sign-in page's second field does: signs up with an
// invite, receives a hand-over, signs in otherwise; while joining with no
// link to go by, nothing (the keyboard closes).
private fun gatewaySubmit(vm: SignInViewModel): (() -> Unit)? = when {
    vm.invite != null -> vm::join
    vm.handOver != null -> vm::receive
    vm.joining -> null
    else -> vm::submit
}

// How to sign in: the normal way, the legacy password, or an API key, each
// with its line in the choice.
@Composable
private fun MethodGroup(vm: SignInViewModel, methods: List<AuthMode>, modifier: Modifier) {
    val sheet = LocalChoiceSheet.current
    Column(modifier.fillMaxWidth()) {
        FormGroupTitle(SignInWords.GROUP_SIGNING_IN)
        FormGroupCard {
            FormChoiceRow(
                title = SignInWords.METHOD,
                value = SignInWords.methodLabel(vm.mode),
                description = SignInWords.methodHelper(vm.mode, vm.takesApiKeys),
                answers = methods.map { SignInWords.methodLabel(it) },
                onClick = {
                    sheet.show(
                        ChoiceRequest(
                            SignInWords.METHOD,
                            methods.map { Choice(SignInWords.methodLabel(it), SignInWords.methodHelper(it, vm.takesApiKeys)) },
                            methods.indexOf(vm.mode),
                        ) { vm.pickMethod(methods[it]) },
                    )
                },
            )
        }
    }
}

// Custom headers and the client certificate.
@Composable
private fun SetupsGroup(vm: SignInViewModel, rows: List<AdvancedRow>, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        FormGroupTitle(SignInWords.GROUP_SPECIAL_SETUPS)
        FormGroupCard {
            if (AdvancedRow.Headers in rows) CustomHeaders(vm)
            if (AdvancedRow.Certificate in rows) {
                FormDivider()
                ClientCertificate(vm)
            }
        }
    }
}

// The custom header rows, drawn by their ids, so a Remove takes its own row
// with its focus and its Show. Add header puts focus in the new row's name
// once that row is on screen. A tap on Remove clears focus; out of touch
// mode (where Android cannot leave nothing focused) a Remove of the row that
// holds focus hands it to Add header first, and one elsewhere leaves focus
// where it is. While the rows are drawn a mouse press moves no focus by
// itself: Compose would clear focus on a press outside the focused field,
// before the click runs, and Android would then hand it to the page's first
// control.
@Composable
private fun CustomHeaders(vm: SignInViewModel) {
    val focusManager = LocalFocusManager.current
    val inputModes = LocalInputModeManager.current
    val host = LocalView.current.parent as? AbstractComposeView
    DisposableEffect(host) {
        val before = host?.autoClearFocusBehavior
        host?.autoClearFocusBehavior = AutoClearFocusBehavior.None
        onDispose { if (host != null && before != null) host.autoClearFocusBehavior = before }
    }
    var focusNewHeader by remember { mutableStateOf(false) }
    val newHeaderFocus = remember { FocusRequester() }
    val addHeaderFocus = remember { FocusRequester() }
    var focusedRowId by remember { mutableStateOf<Long?>(null) }
    val headerIds = vm.headerIds
    Column(Modifier.fillMaxWidth()) {
        FormRowTitle(SignInWords.CUSTOM_HEADERS, SignInWords.HEADERS_DESCRIPTION)
        vm.headers.forEachIndexed { index, header ->
            key(headerIds[index]) {
                val id = headerIds[index]
                HeaderRow(
                    name = header.name,
                    value = header.value,
                    saved = header.saved,
                    onName = { vm.setHeaderName(id, it) },
                    onValue = { vm.setHeaderValue(id, it) },
                    onRemove = { if (inputModes.inputMode == InputMode.Touch) focusManager.clearFocus() else if (focusedRowId == id) addHeaderFocus.requestFocus(); vm.removeHeader(id) },
                    modifier = Modifier.onFocusChanged { if (it.hasFocus) focusedRowId = id else if (focusedRowId == id) focusedRowId = null },
                    nameFocus = if (index == vm.headers.lastIndex) newHeaderFocus else null,
                    valueImeAction = if (index == vm.headers.lastIndex) ImeAction.Done else ImeAction.Next,
                )
            }
        }
        GlazeButton(
            SignInWords.ADD_HEADER,
            onClick = {
                vm.addHeader()
                focusNewHeader = true
            },
            size = ButtonSize.Small,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 14.dp).focusRequester(addHeaderFocus),
        )
        LaunchedEffect(focusNewHeader, vm.headers.size) {
            if (focusNewHeader) {
                newHeaderFocus.requestFocus()
                focusNewHeader = false
            }
        }
    }
}

// Picks a certificate installed on the phone, through the phone's own
// picker. Octo only gets to use the one picked. Its expiry date is read
// from the phone's store off the main thread.
@Composable
private fun ClientCertificate(vm: SignInViewModel) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val alias = vm.clientCert
    val expires by produceState<String?>(null, alias) {
        value = alias?.let { name ->
            withContext(Dispatchers.IO) { runCatching { KeyChain.getCertificateChain(context, name)?.firstOrNull()?.notAfter }.getOrNull() }
        }?.let(::dateWords)
    }
    val until = expires
    FormCertificateRow(
        title = SignInWords.CERT_TITLE,
        line = when {
            alias == null -> SignInWords.CERT_NONE
            until != null -> SignInWords.certificateLine(alias, until)
            else -> alias
        },
        action = if (alias == null) SignInWords.CERT_CHOOSE else SignInWords.CERT_CHANGE,
        onAction = {
            val host = activity
            if (host != null) {
                val url = vm.url
                KeyChain.choosePrivateKeyAlias(
                    host,
                    { picked -> if (picked != null) host.runOnUiThread { vm.clientCert = picked } },
                    null,
                    null,
                    url?.host,
                    url?.port ?: -1,
                    vm.clientCert,
                )
            }
        },
        actionEnabled = activity != null,
        removeName = SignInWords.CERT_REMOVE,
        onRemove = if (alias != null) ({ vm.clientCert = null }) else null,
    )
}

// A date in the phone's own way of writing one.
private fun dateWords(date: Date): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date.toInstant().atZone(ZoneId.systemDefault()))

// A certificate to ask about: the host that showed it and its fingerprint.
internal data class TrustAsk(val host: String, val fingerprint: String)

// Asks before trusting a certificate the phone does not. It shows the
// host and the certificate's fingerprint, so the user can compare it with
// the server's own. Nothing is trusted unless they say so. It floats in
// the middle of the screen, above the bar. A tap outside its card leaves
// the question waiting; Back and its own buttons answer it. `changed` asks
// about a host the user trusted another certificate for, met while in use.
@Composable
internal fun TrustSheet(question: TrustAsk?, onTrust: () -> Unit, onCancel: () -> Unit, changed: Boolean = false) {
    val title = if (changed) SignInWords.TRUST_CHANGED_TITLE else SignInWords.TRUST_TITLE
    GlassPopup(
        visible = question != null,
        anchor = null,
        onDismiss = {},
        backdrop = LocalHaze.current,
        title = title,
        maxWidth = 380.dp,
        onBack = {
            onCancel()
            true
        },
    ) {
        val shown = rememberLast(question) ?: return@GlassPopup
        PopupQuestion(
            title,
            if (changed) SignInWords.TRUST_CHANGED_DETAIL else SignInWords.trustDetail("phone"),
            SignInWords.TRUST_CONFIRM,
            onConfirm = onTrust,
            onCancel = onCancel,
            modifier = Modifier.verticalScroll(rememberScrollState()),
            width = 380.dp,
        ) {
            Text(SignInWords.TRUST_HOST, style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(top = 16.dp))
            Text(shown.host, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
            Text(SignInWords.TRUST_FINGERPRINT, style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(top = 12.dp))
            Text(
                formatFingerprint(shown.fingerprint),
                style = OctoType.caption.copy(fontFamily = FontFamily.Monospace),
                color = OctoColors.TextPrimary,
            )
        }
    }
}
