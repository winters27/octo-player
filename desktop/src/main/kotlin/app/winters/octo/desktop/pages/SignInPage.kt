package app.winters.octo.desktop.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.winters.octo.connection.AdvancedGroup
import app.winters.octo.connection.AdvancedRow
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.SignInWords
import app.winters.octo.connection.formatFingerprint
import app.winters.octo.connection.signInOpen
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.LocalFocusVisibility
import app.winters.octo.design.LocalOctoSurfaces
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.OctoType
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.Space
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.design.scrollbar
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.family.clipboardText
import app.winters.octo.desktop.server.CertificateQuestion
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SwitchOutcome
import app.winters.octo.desktop.server.TestOutcome
import app.winters.octo.desktop.server.familyPlatform
import app.winters.octo.desktop.settings.name
import app.winters.octo.desktop.ui.LocalKeyColour
import app.winters.octo.desktop.ui.keyRim
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.shownAddress
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Signing in to a server, as on the phone: its address (with https or
// http in front), a username and password or an API key, a test of the
// connection, and the settings most servers never need under Advanced, in
// three groups. A certificate the system does not trust is asked about
// before anything is sent. A failure that met a sign-in page in front of
// the server opens its group, calls the failure out there and brings it into
// view.
@Composable
fun SignInPage(app: AppState, backdrop: HazeState) {
    val form = app.signInForm
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val places = remember(form) { FormPlaces() }
    val calloutFocus = remember { CalloutFocus() }
    var content by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Which of test or sign-in ran last, to run again once a certificate is trusted.
    var again by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Each try is tagged with the address it went to, so a sign-in page it
    // met counts only while that address is still the one typed.
    // Runs a request as the form's own: a later one, or a way out that
    // cancels it, is never undone by its end.
    fun runRequest(work: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                work()
            } finally {
                if (form.running === coroutineContext.job) {
                    form.busy = false
                    form.waiting = false
                    form.running = null
                }
            }
        }
        form.running = job
        job.start()
    }

    fun test() {
        if (!form.ready || app.switching != null) return
        again = ::test
        form.sending()
        form.busy = true
        form.waiting = false
        form.result = null
        val tried = form.attemptAddress
        runRequest {
            when (val tested = app.accounts.test(form.request(), keptId = form.signsInAgainTo)) {
                is TestOutcome.Reached -> {
                    form.tried(tested.tried)
                    form.worked(tested.mode)
                    form.result = true to tested.facts.summary()
                }
                is TestOutcome.Failed -> {
                    form.result = false to tested.message
                    form.failed(tried, tested.gateway)
                    form.tried(tested.tried)
                    if (tested.certificate) form.askForCertificate()
                }
                is TestOutcome.Untrusted -> form.question = tested.question
            }
        }
    }

    fun signIn() {
        if (!form.ready || app.switching != null) return
        again = ::signIn
        form.sending()
        form.busy = true
        form.waiting = false
        form.result = null
        val tried = form.attemptAddress
        runRequest {
            val kept = form.signsInAgainTo
            when (val done = if (kept != null) app.accounts.signInAgain(kept, form.request(), sameAccount = true) else app.accounts.signIn(form.request())) {
                is SignInOutcome.Done -> {
                    form.password = ""
                    form.apiKey = ""
                    app.signedIn(done.connection, done.note)
                }
                is SignInOutcome.Saved -> Unit
                is SignInOutcome.Failed -> {
                    form.result = false to done.message
                    form.failed(tried, done.gateway)
                    form.tried(done.tried)
                    if (done.certificate) form.askForCertificate()
                }
                is SignInOutcome.Untrusted -> form.question = done.question
            }
        }
    }

    // Signs up from an invite, or takes a sign-in from another device of
    // one's own, then signs in. Only a hand-over's redeem waits.
    fun go() {
        if (form.busy || app.switching != null) return
        if (form.invite != null && !form.inviteReady) return
        again = ::go
        form.sending()
        form.busy = true
        form.waiting = form.handOver != null && form.received == null
        form.result = null
        runRequest {
            val done = if (form.invite != null) signUpFromForm(app.accounts, form) else receiveFromForm(app.accounts, familyPlatform(app.os), form)
            done?.let { app.signedIn(it.connection, it.note) }
        }
    }

    // A sign-in from another device starts at once: there is nothing to type.
    LaunchedEffect(form.handOver) { if (form.handOver != null && form.handOverStep == null && form.result == null) go() }

    // The kept server's sign-in page shows as its kind, saved, without its
    // values.
    LaunchedEffect(form) { form.keptServer?.let { form.knowSavedGateway(it, app.accounts.savedGateway(it)) } }

    // Focus starts in the address, or in the secret when the server and who
    // signs in are already filled in.
    // The fields are drawn in the page's own pass, after this runs: the
    // first holds its requester from the next frame.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

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
            LinkNote(form)
        }
    }

    // The sign-in page group and the key row are brought into view from
    // here, outside Advanced, so closing it never ends a wait.
    val host = content?.let { CalloutHost(scroll, PageTopInset, it, settled = { true }, imeBottom = { 0 }, focus = calloutFocus) }
    CalloutEffect(form.calloutPending, host, { places.gateway }) { form.calloutShown() }
    CalloutEffect(form.keyPending, host, { places.key }) { form.keyShown() }
    CalloutEffect(form.certificatePending, host, { places.special }) { form.certificatePending = false }

    // While the certificate is asked about, the card steps back behind it.
    val asking by animateFloatAsState(if (form.question != null) 0.35f else 1f, label = "asking")
    CompositionLocalProvider(LocalCalloutHost provides host) {
        BoxWithConstraints(Modifier.fillMaxSize().alpha(asking)) {
            // On a short window the card packs tighter, so Sign in shows without scrolling.
            val short = maxHeight < ShortWindow
            val pad = if (short) 12.dp else 32.dp
            val density = LocalDensity.current
            // The card and what is under it, measured with Advanced closed:
            // while Advanced is open their top stays where it was centered,
            // so nothing above Advanced moves as it opens.
            var closedHeight by remember(form) { mutableIntStateOf(-1) }
            val pinned = form.advancedOpen && closedHeight >= 0
            val pinTop = if (pinned) with(density) { ((maxHeight - pad * 2 - closedHeight.toDp()) / 2).coerceAtLeast(0.dp) } else 0.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .scrollbar(scroll)
                    .verticalScroll(scroll)
                    .heightIn(min = maxHeight)
                    .onGloballyPositioned { content = it }
                    .padding(vertical = pad),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (pinned) Arrangement.Top else Arrangement.Center,
            ) {
                if (pinned) Spacer(Modifier.height(pinTop))
                Column(
                    Modifier.onSizeChanged { if (!form.advancedOpen) closedHeight = it.height },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SignInCard(backdrop, short) {
                        Txt("Octo", OctoType.display)
                        Txt(
                            when {
                                form.invite != null -> "Sign up for your family's Octo server. Your login then works in Octo and any other music app."
                                form.handOver != null -> "Signing in with the login from your other device."
                                else -> "Sign in to your music server. Any Subsonic, Navidrome or Octo server works."
                            },
                            OctoType.bodySmall,
                            OctoColors.TextSecondary,
                            maxLines = 3,
                        )

                        val address: @Composable () -> Unit = {
                            FormFieldRow(
                                SignInWords.SERVER_ADDRESS,
                                value = form.address,
                                onChange = { if (!form.takeJoinLink(it, app.switching != null)) form.typeAddress(it) },
                                placeholder = SignInWords.REQUIRED,
                                leading = { SchemeToggle(form.scheme.prefix, form::toggleScheme, enabled = !form.addressLocked) },
                                onSubmit = { if (form.invite != null || form.handOver != null) go() else signIn() },
                                focus = if (form.startsAtPassword) null else first,
                                readOnly = form.addressLocked,
                            )
                        }

                        if (form.invite != null || form.handOver != null) {
                            LinkCard(form, ::go, places, address = address, addressNotes = { ConnectionNotes(form) })
                            LinkFooter(form, ::go, onLeave = form::leaveLink, held = app.switching != null)
                            Advanced(app, form, ::go, places, replacingId = null, url = form.handOver?.base?.toHttpUrlOrNull() ?: form.url, home = form.homeUrl)
                            return@SignInCard
                        }

                        FormGroup(null, notes = { ConnectionNotes(form) }) {
                            address()
                            FormFieldRow(
                                SignInWords.USERNAME,
                                optional = form.useApiKey,
                                value = form.username,
                                onChange = {
                                    form.username = it
                                    form.result = null
                                },
                                placeholder = if (form.useApiKey) "" else SignInWords.REQUIRED,
                                onSubmit = ::signIn,
                            )
                            SecretRow(form, ::signIn, places, if (form.startsAtPassword) first else null)
                            FormSwitchRow(
                                if (form.useApiKey) "Remember API key" else "Remember password",
                                rememberCaption(form.rememberPassword),
                                form.rememberPassword,
                            ) { form.rememberPassword = it }
                        }

                        ResultLine(form)
                        LinkNote(form)
                        val held = app.switching != null
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            GlazeCapsule(null, "Test connection", ::test, enabled = form.ready && !held)
                            AccentButton("Sign in", ::signIn, Modifier.widthIn(min = 150.dp), enabled = form.ready && !held, loading = form.busy, size = ButtonSize.Medium)
                        }
                        // An invite, or a sign-in link from another device, copied anywhere.
                        TextAction("Paste a family link", {
                            val text = clipboardText()
                            if (text == null || !form.takeJoinLink(text, app.switching != null)) form.result = false to "The clipboard holds no family link. Copy the link from your invite or your other device first."
                        }, Modifier.align(Alignment.CenterHorizontally), enabled = !held, icon = OctoIcons.Family)

                        Advanced(app, form, ::signIn, places, replacingId = form.signsInAgainTo, url = form.url, home = form.homeUrl)
                    }
                    OtherServers(app)
                }
            }
        }
    }
}

private val CardShape = RoundedCornerShape(22.dp)

// The other servers kept here, under the card, to open one of them instead.
// One whose password is kept opens at once; otherwise the card fills in
// with it and asks for the password.
@Composable
private fun OtherServers(app: AppState) {
    val settings by app.settings.state.collectAsState()
    val shown = app.signInForm.url?.toString()
    val others = settings.servers.filter { it.address != shown || it.username != app.signInForm.username.trim() }
    if (others.isEmpty()) return
    // A sign-in open on the page (running, a question waiting, or a sign-up
    // or sealed sign-in held) is never replaced by another server's.
    val form = app.signInForm
    val open = signInOpen(form.busy, form.question != null, form.received != null, form.signedUp != null)
    Column(Modifier.padding(top = 16.dp).width(480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(if (app.switching != null) "Opening ${app.switching?.name}" else "Or open another of your servers", OctoType.caption, OctoColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), modifier = Modifier.padding(top = 4.dp)) {
            others.take(4).forEach { server ->
                TextAction(server.name, {
                    if (server.signedOut) {
                        app.fillSignIn(server)
                    } else {
                        app.switchTo(server.id) { outcome ->
                            when (outcome) {
                                is SwitchOutcome.NeedsPassword -> app.fillSignIn(outcome.server, outcome.note)
                                is SwitchOutcome.NeedsCertificate -> app.fillSignIn(outcome.server, outcome.words, certificate = true)
                                else -> Unit
                            }
                        }
                    }
                }, enabled = app.switching == null && !open)
            }
        }
    }
}

// Below this height the card packs tighter.
private val ShortWindow = 720.dp

// The one card of the sign-in: frosted glass over Octo's colors, with a
// rim that takes a hint of the key color.
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
        // Secondary words a touch toward the main words: on the groups' glaze
        // over this film the theme's own read 6.6:1, under the 7:1 they need.
        val surfaces = LocalOctoSurfaces.current
        CompositionLocalProvider(LocalOctoSurfaces provides surfaces.copy(secondary = lerp(surfaces.secondary, surfaces.text, 0.12f))) {
            Column(Modifier.fillMaxWidth().padding(if (short) 20.dp else 32.dp), verticalArrangement = Arrangement.spacedBy(if (short) 6.dp else 12.dp), content = content)
        }
    }
}

// The line under Remember password, saying what the switch does as it is set.
internal fun rememberCaption(on: Boolean): String =
    if (on) "On, Octo keeps it on this computer and signs in by itself." else "Off, Octo asks again each time it opens."

// The card's film: a dark gray that lets the colors through, as dark as
// every word on the card needs over the brightest of them (main and
// secondary words 7:1, muted words 4.5:1).
private val CardFilm = Color(0xFF1A1A1E).copy(alpha = 0.79f)

// The password, or with API key the key in its place. The key row says
// where it is, for the scroll that brings it into view once it is asked
// for. A kept secret's empty field reads Unchanged.
@Composable
internal fun SecretRow(form: SignInForm, submit: () -> Unit, places: FormPlaces, focus: FocusRequester?) {
    if (form.useApiKey) {
        Box(Modifier.fillMaxWidth().onGloballyPositioned { places.key = it }) {
            FormFieldRow(
                SignInWords.API_KEY,
                value = form.apiKey,
                onChange = {
                    form.apiKey = it
                    form.result = null
                },
                placeholder = if (form.keepsKey) SignInWords.UNCHANGED else SignInWords.REQUIRED,
                secret = true,
                onSubmit = submit,
                focus = focus,
            )
        }
    } else {
        FormFieldRow(
            SignInWords.PASSWORD,
            value = form.password,
            onChange = {
                form.password = it
                form.result = null
            },
            placeholder = if (form.keepsPassword) SignInWords.UNCHANGED else SignInWords.REQUIRED,
            secret = true,
            onSubmit = submit,
            focus = focus,
        )
    }
}

// How the connection travels, under the main card: across the internet
// unencrypted it is an error first (for an API key, the words that say the
// key itself travels as it is), then where it connects, behind a lock or an
// open lock.
@Composable
internal fun ColumnScope.ConnectionNotes(form: SignInForm) {
    val url = form.url ?: return
    if (form.insecure) {
        FormNote(
            if (form.useApiKey) SignInWords.API_KEY_IN_THE_OPEN else "This address isn't encrypted and isn't on your home network, so others could read what is sent.",
            NoteKind.Error,
        )
    }
    FormNote("Connects to ${shownAddress(url)}", if (url.isHttps) NoteKind.Lock else NoteKind.OpenLock)
}

// The line over the buttons: what the last try said, or, while the sign-in
// page group calls a failure out, the pointer that brings it into view.
@Composable
internal fun ResultLine(form: SignInForm) {
    if (form.showsCallout) {
        FormNote(GatewayWords.POINTER, NoteKind.Error, onClick = form::pointTo)
        return
    }
    form.result?.let { (ok, text) -> FormNote(text, if (ok) NoteKind.Info else NoteKind.Error) }
}

// The settings most servers never need, under their head: getting to the
// server (the sign-in page in front of it, its two fields once a kind is
// picked, and the home address), signing in (the Method, left out while
// joining, where the link decides), and special setups (custom headers and
// the client certificate, whose row resolves `replacingId`, `url` and `home`
// as the request does). Groups come in the shared order and fade in and out.
@Composable
internal fun ColumnScope.Advanced(
    app: AppState,
    form: SignInForm,
    submit: () -> Unit,
    places: FormPlaces,
    replacingId: String?,
    url: HttpUrl?,
    home: HttpUrl?,
) {
    AdvancedHead(form.advancedOpen) { form.advancedOpen = !form.advancedOpen }
    AnimatedVisibility(form.advancedOpen, enter = fadeIn(advancedFade()), exit = fadeOut(advancedFade())) {
        val layout = form.layout
        Column(Modifier.fillMaxWidth().padding(top = Space.M), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
            layout.groups.forEach { group ->
                key(group.group) {
                    when (group.group) {
                        AdvancedGroup.GettingThere -> GettingThere(form, submit, group.rows, places)
                        AdvancedGroup.SigningIn -> SigningIn(form, layout.methods)
                        AdvancedGroup.SpecialSetups -> SpecialSetups(app, form, submit, group.rows, places, replacingId, url, home)
                    }
                }
            }
        }
    }
}

// The sign-in page in front of the server (Authentik, Authelia, a proxy's
// password, Cloudflare Access) and the home address. A failure that met the
// page shows as this group's error note, with the card ringed.
@Composable
private fun GettingThere(form: SignInForm, submit: () -> Unit, rows: List<AdvancedRow>, places: FormPlaces) {
    val joining = form.invite != null || form.handOver != null
    val saved = if (form.keepsSavedGateway) SignInWords.UNCHANGED else ""
    val labels = GatewayWords.fieldLabels(form.gatewayKind, form.detected)
    val error = form.result?.second?.takeIf { form.showsCallout }
    FormGroup(
        AdvancedGroup.GettingThere.title,
        outlined = form.showsCallout,
        place = { places.gateway = it },
        notes = {
            error?.let { FormNote(GatewayWords.inGroup(it), NoteKind.Error) }
            if (form.gatewayNeedsHttps) FormNote(GatewayWords.NEEDS_HTTPS, NoteKind.Error)
            form.gatewayHint?.let { FormNote(it) }
            if (joining && form.gatewayKind != GatewayKind.None) FormNote(GatewayWords.JOIN_HINT)
            FormNote(SignInWords.HOME_NOTE)
        },
    ) {
        rows.forEach { row ->
            key(row) {
                when (row) {
                    AdvancedRow.PageKind -> ChoiceFormRow(
                        GatewayWords.LABEL_SECTION,
                        GatewayKind.entries,
                        form.gatewayKind,
                        GatewayWords::kindLabel,
                        GatewayWords::kindHelper,
                        form::pickGateway,
                        description = if (form.gatewayKind == GatewayKind.None) GatewayWords.LABEL_CAPTION else null,
                    )
                    AdvancedRow.PageFirst -> FormFieldRow(
                        labels.first,
                        value = form.gatewayFirst,
                        onChange = form::typeGatewayFirst,
                        placeholder = saved.ifEmpty { SignInWords.REQUIRED },
                        onSubmit = submit,
                    )
                    AdvancedRow.PageSecond -> FormFieldRow(
                        labels.second,
                        value = form.gatewaySecond,
                        onChange = form::typeGatewaySecond,
                        placeholder = saved.ifEmpty { SignInWords.REQUIRED },
                        secret = true,
                        onSubmit = submit,
                    )
                    AdvancedRow.Home -> FormFieldRow(
                        SignInWords.HOME_ADDRESS,
                        optional = true,
                        value = form.home,
                        onChange = {
                            form.home = it
                            form.result = null
                        },
                        placeholder = SignInWords.HOME_PLACEHOLDER,
                        onSubmit = submit,
                    )
                    else -> Unit
                }
            }
        }
    }
}

// The Method: Normal, Legacy password or API key, each saying what it is for.
@Composable
private fun SigningIn(form: SignInForm, methods: List<AuthMode>) {
    FormGroup(AdvancedGroup.SigningIn.title) {
        ChoiceFormRow(
            SignInWords.METHOD,
            methods,
            form.mode,
            SignInWords::methodLabel,
            { SignInWords.methodHelper(it, form.takesApiKeys) },
            form::pickMethod,
        )
    }
}

// Custom headers, one row each, drawn keyed so a row's focus and Show stay
// with it. Add header puts the keyboard in the new row once it is on
// screen; a Remove from the keyboard gives the keyboard to Add header, and
// one with the mouse leaves nothing focused.
@Composable
private fun SpecialSetups(
    app: AppState,
    form: SignInForm,
    submit: () -> Unit,
    rows: List<AdvancedRow>,
    places: FormPlaces,
    replacingId: String?,
    url: HttpUrl?,
    home: HttpUrl?,
) {
    val visibility = LocalFocusVisibility.current
    var focusNewHeader by remember { mutableStateOf(false) }
    val newHeaderFocus = remember { FocusRequester() }
    var focusAddHeader by remember { mutableStateOf(false) }
    val addHeaderFocus = remember { FocusRequester() }
    FormGroup(AdvancedGroup.SpecialSetups.title, place = { places.special = it }) {
        rows.forEach { row ->
            when (row) {
                AdvancedRow.Headers -> {
                    FormRow(SignInWords.CUSTOM_HEADERS, SignInWords.HEADERS_DESCRIPTION)
                    form.headers.forEachIndexed { index, header ->
                        key(form, form.headerIds[index]) {
                            val id = form.headerIds[index]
                            HeaderRow(
                                header,
                                onName = { form.setHeaderName(id, it) },
                                onValue = { form.setHeaderValue(id, it) },
                                onRemove = { form.removeHeader(id); if (visibility.keyboard) focusAddHeader = true },
                                submit = submit,
                                nameFocus = if (index == form.headers.lastIndex) newHeaderFocus else null,
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = RowInset, vertical = Space.M)) {
                        GlazeCapsule(null, SignInWords.ADD_HEADER, { form.addHeader(); focusNewHeader = true }, Modifier.focusRequester(addHeaderFocus), height = ControlHeight.M)
                    }
                }
                AdvancedRow.Certificate -> CertificateRow(app, form, replacingId, url, home)
                else -> Unit
            }
        }
    }
    LaunchedEffect(focusNewHeader, form.headers.size) {
        if (focusNewHeader) {
            // The rows are drawn in the card's own pass, after this runs: the
            // new row holds the requester from the next frame.
            withFrameNanos { }
            runCatching { newHeaderFocus.requestFocus() }
            focusNewHeader = false
        }
    }
    LaunchedEffect(focusAddHeader, form.headers.size) {
        if (focusAddHeader) {
            runCatching { addHeaderFocus.requestFocus() }
            focusAddHeader = false
        }
    }
}

// The words of the last link refused here, under the result line.
@Composable
internal fun LinkNote(form: SignInForm) {
    form.linkNote?.let { FormNote(it) }
}

// The scheme at the start of the address: a small darker pill set into
// the field that switches between https:// and http:// when clicked. On a
// light theme a faint shade of the words' own ink. Not `enabled`, it only
// shows the scheme.
@Composable
internal fun SchemeToggle(prefix: String, onClick: () -> Unit, enabled: Boolean = true) {
    val shape = RoundedCornerShape(8.dp)
    val other = if (prefix == "https://") "http://" else "https://"
    val fill = if (LocalOctoSurfaces.current.light) OctoColors.TextPrimary.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.35f)
    OctoTooltip("Switch to $other") {
        Box(
            Modifier
                .focusProperties { canFocus = false }
                .background(fill, shape)
                .hoverLift(shape, clickable = enabled)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "Switch to $other" }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Txt(prefix, OctoType.bodySmall, OctoColors.TextSecondary)
        }
    }
}

// Asks before trusting a certificate the system does not, in the phone's
// words. It shows the host and the certificate's fingerprint, so the
// listener can compare it with the server's own. Nothing is trusted unless
// they say so.
@Composable
internal fun TrustQuestion(question: CertificateQuestion, onTrust: () -> Unit, onCancel: () -> Unit) {
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
