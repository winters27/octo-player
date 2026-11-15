package app.winters.octo.desktop.pages

import androidx.compose.animation.AnimatedVisibility
import app.winters.octo.design.PopupHost
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
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
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.OctoType
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.Space
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.design.scrollFades
import app.winters.octo.design.scrollbar
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.family.clipboardText
import app.winters.octo.desktop.server.CertificateQuestion
import app.winters.octo.desktop.server.ServerSecurity
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
import kotlin.math.roundToInt

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
                    app.accounts.security.trust(asked.host, asked.pin)
                    again?.invoke()
                },
                onCancel = close,
            )
            LinkNote(form)
        }
    }

    // The sign-in page group and the key row are brought into view from
    // here, outside Advanced, so closing it never ends a wait.
    val host = content?.let { CalloutHost(scroll, PageTopInset, it, settled = { true }, imeBottom = { 0 }, focus = calloutFocus, fade = PageFade) }
    CalloutEffect(form.calloutPending, host, { places.gateway }) { form.calloutShown() }
    CalloutEffect(form.keyPending, host, { places.key }) { form.keyShown() }
    CalloutEffect(form.certificatePending, host, { places.special }) { form.certificatePending = false }

    // While the certificate is asked about, the card steps back behind it.
    val asking by animateFloatAsState(if (form.question != null) 0.35f else 1f, label = "asking")
    // While Advanced is open, the place its opening glide lifts the card to
    // is the scroll's floor: the room's middle when the open card fits, else
    // its top with the words at their own top. A wheel or drag turned back
    // stops there, so nothing but closing Advanced lowers the open card from
    // where it sits balanced, and the card's bar counts from there. The pin
    // while Advanced is open, else 0.
    val openPin = remember(form) { mutableIntStateOf(0) }
    val floor = remember(scroll, openPin) { { minOf(openPin.intValue, scroll.maxValue) } }
    val hold = remember(scroll, floor) { HoldPast(scroll, floor) }
    CompositionLocalProvider(LocalCalloutHost provides host) {
        // A wheel turned anywhere on the page scrolls the card, as it does
        // over the card itself, and both are held at the floor.
        val reverse = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, Orientation.Vertical, reverseScrolling = false)
        BoxWithConstraints(Modifier.fillMaxSize().alpha(asking).nestedScroll(hold).scrollable(scroll, Orientation.Vertical, reverseDirection = reverse)) {
            // On a short window the card packs tighter, so Sign in shows without scrolling.
            val short = maxHeight < ShortWindow
            val pad = if (short) 12.dp else 32.dp
            val density = LocalDensity.current
            // The card and what is under it, measured with Advanced closed:
            // while Advanced's groups are drawn (open, or fading out) the
            // card rests where it was centered, its pin, and the scroll
            // lifts it from there.
            var closedHeight by remember(form) { mutableIntStateOf(-1) }
            var advancedShown by remember(form) { mutableStateOf(false) }
            val pinned = (form.advancedOpen || advancedShown) && closedHeight >= 0
            val pinTop = if (pinned) with(density) { ((maxHeight - pad * 2 - closedHeight.toDp()) / 2).coerceAtLeast(0.dp) } else 0.dp
            val pinPx = with(density) { pinTop.roundToPx() }
            SideEffect { openPin.intValue = if (form.advancedOpen) pinPx else 0 }
            // Advanced opening glides the card up from its pin as the groups
            // fade in, until the open card sits balanced: in the middle of
            // the room when it fits there (the scroll's end, see SignInCard),
            // else at the room's top with its words scrolling inside. Opened
            // from its head, the same glide goes on, if it must, until the
            // first group's title and the top of its card show over the soft
            // foot. A callout, or a key or certificate row asked for, brings
            // its own part up instead. Closing under a lifted card takes the
            // card back down to its pin, and its words back to their top, as
            // the groups fade out.
            val fade = advancedFade()
            val still = LocalReduceMotion.current
            val peek = with(density) { (AdvancedPeek + PageFade).roundToPx() }
            LaunchedEffect(form.advancedOpen) {
                if (form.advancedOpen) {
                    val head = places.openedFromHead
                    places.openedFromHead = false
                    if (form.calloutPending || form.keyPending || form.certificatePending) return@LaunchedEffect
                    // The open card is laid out by the next frame.
                    withFrameNanos { }
                    val lift = if (pinTop > 0.dp) minOf(with(density) { pinTop.roundToPx() }, scroll.maxValue) else 0
                    val reveal = if (head) revealTarget(scroll, content, places.advanced, peek) else 0
                    val target = maxOf(lift, reveal).coerceAtMost(scroll.maxValue)
                    if (target <= scroll.value) return@LaunchedEffect
                    if (still) scroll.scrollTo(target) else scroll.glideTo(target, fade)
                    return@LaunchedEffect
                }
                if (pinTop == 0.dp || scroll.value == 0) return@LaunchedEffect
                if (still) scroll.scrollTo(0) else scroll.glideTo(0, fade)
            }
            // The card takes the room between the page's ends, over the
            // other servers, resting `pinTop` under its top. A scroll toward
            // the end first lifts the whole card, to the top or, when it fits,
            // to the middle, then moves its words inside it (see SignInCard).
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(vertical = pad),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (pinned) Arrangement.Top else Arrangement.Center,
            ) {
                Column(
                    Modifier.weight(1f, fill = false).onSizeChanged { if (!pinned && !form.advancedOpen && !advancedShown) closedHeight = it.height },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SignInCard(backdrop, short, scroll, pinTop, floor, onContent = { content = it }, modifier = Modifier.weight(1f, fill = false)) {
                        Txt("Octo", OctoType.display)
                        Txt(
                            when {
                                form.invite != null -> "Sign up for your family's Octo server."
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
                            Advanced(app, form, ::go, places, replacingId = null, url = form.handOver?.base?.toHttpUrlOrNull() ?: form.url, home = form.homeUrl, revealsItself = false, onShown = { advancedShown = it })
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
                        // An invite, or a sign-in link from another device, copied
                        // anywhere. Under the buttons, on their right edge: its words,
                        // not its hover pill, end there.
                        TextAction("Paste a family link", {
                            val text = clipboardText()
                            if (text == null || !form.takeJoinLink(text, app.switching != null)) form.result = false to "The clipboard holds no family link. Copy the link from your invite or your other device first."
                        }, Modifier.align(Alignment.End).offset(x = Space.L), enabled = !held, icon = OctoIcons.Family)

                        Advanced(app, form, ::signIn, places, replacingId = form.signsInAgainTo, url = form.url, home = form.homeUrl, revealsItself = false, onShown = { advancedShown = it })
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
// rim that takes a hint of the key color. Its words scroll on `scroll`
// through the room `modifier` gives it, starting `pin` under the room's
// top. That one scroll does both moves: its first `pin` lifts the glass
// with its words up to the room's top, and past that the glass holds there
// while the words move inside it, fading at its ends. Scrolled back, the
// words reach their top before the glass comes down. With a pin, words that
// fit the room have half the room they leave added after them, so the
// scroll ends with the card in the room's middle. The glass reaches from
// its words' top to their end, or to the room's foot, and never leaves the
// room, so its frost is drawn wherever it is. Its bar counts from `rest`,
// the least a scroll back reaches. `onContent` hears where the scroll's
// child is, for the callout.
@Composable
private fun SignInCard(
    backdrop: HazeState,
    short: Boolean,
    scroll: ScrollState,
    pin: Dp,
    rest: () -> Int,
    onContent: (LayoutCoordinates) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val key = LocalKeyColour.current
    val inset = if (short) 20.dp else 32.dp
    val pinPx = with(LocalDensity.current) { pin.roundToPx() }
    val insetPx = with(LocalDensity.current) { inset.roundToPx() }
    // How far under the room's top the glass starts: the pin, less what of
    // it the scroll has used. The words' scroll fills the whole room and sits
    // that far up inside the glass, so where the glass is never moves the
    // words; only the scroll does.
    val glassTop = remember(scroll, pinPx) { derivedStateOf { (pinPx - scroll.value).coerceAtLeast(0) } }
    val measures = remember { CardMeasures() }
    FloatingGlaze(
        backdrop = backdrop,
        modifier = modifier
            .width(480.dp)
            .layout { measurable, constraints ->
                val glass = measurable.measure(constraints)
                val top = glassTop.value
                layout(glass.width, glass.height + top) { glass.place(0, top) }
            }
            // The bar runs between the corner arcs. Its resting thumb says
            // there is more while words run past the card even once it is
            // lifted to the top: not for the lift alone, nor for the card's
            // own empty padding at its foot.
            .scrollbar(scroll, bottom = CardBarEnds, resting = scroll.maxValue > pinPx + insetPx, top = CardBarEnds, start = rest),
        shape = CardShape,
        film = CardFilm,
        frost = 20f,
        saturation = 1.8f,
    ) {
        Box(Modifier.matchParentSize().innerShadow(CardShape, Shadow(radius = 0.dp, spread = 1.dp, color = keyRim(key))).testTag(SIGN_IN_CARD_TAG))
        // Secondary words a touch toward the main words: on the groups' glaze
        // over this film the theme's own read 6.6:1, under the 7:1 they need.
        val surfaces = LocalOctoSurfaces.current
        CompositionLocalProvider(LocalOctoSurfaces provides surfaces.copy(secondary = lerp(surfaces.secondary, surfaces.text, 0.12f))) {
            Layout(
                content = {
                    Column(
                        Modifier.fillMaxWidth().padding(inset),
                        verticalArrangement = Arrangement.spacedBy(if (short) 6.dp else 12.dp),
                        content = content,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        measures.room = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
                        val room = measurable.measure(constraints)
                        val top = glassTop.value
                        val bottom = minOf(room.height, measures.wordsEnd - scroll.value)
                        layout(room.width, (bottom - top).coerceAtLeast(0)) { room.place(0, -top) }
                    }
                    .scrollFades(scroll, PageFade, eased = true)
                    .verticalScroll(scroll)
                    .onGloballyPositioned(onContent),
            ) { measurables, constraints ->
                // The scroll's content: the pin, the words, and with a pin,
                // half of the room the words leave.
                val words = measurables.single().measure(constraints)
                val tail = if (pinPx > 0) ((measures.room - words.height) / 2).coerceAtLeast(0) else 0
                measures.wordsEnd = pinPx + words.height
                layout(words.width, pinPx + words.height + tail) { words.place(0, pinPx) }
            }
        }
    }
}

// What the card's layout hands from step to step within one measure, all
// on the scroll's one node: the room the card may take, read before its
// words are measured, and where the words end in the scroll, read after.
private class CardMeasures {
    var room = 0
    var wordsEnd = 0
}

// Keeps a wheel or drag toward the start from taking `scroll` back past
// `floor`: what would go under it is given up, so nothing moves. A scroll
// the page sets itself (a glide, a callout, the bar's drag) is not held.
private class HoldPast(private val scroll: ScrollState, private val floor: () -> Int) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val stop = floor()
        // A positive y is toward the start: a scroll passes the gesture on
        // as it comes, and turns it round only for its own state.
        if (available.y <= 0f || stop <= 0) return Offset.Zero
        val room = (scroll.value - stop).coerceAtLeast(0).toFloat()
        return Offset(0f, (available.y - room).coerceAtLeast(0f))
    }
}

// The card's glass, for finding it in tests.
internal const val SIGN_IN_CARD_TAG = "sign-in-card"

// How far the card's scrollbar keeps from its top and foot: past the
// corner arcs, less the bar's own 3 inset.
private val CardBarEnds = 19.dp

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
// as the request does). Groups come in the shared order and fade in and out;
// `onShown` hears true once they are drawn and false once their fade out
// ends. Opened from its head, Advanced glides its scroll on until its first
// group's title and the top of its card show over the soft foot, unless its
// host does that in a glide of its own (`revealsItself` false: the page,
// which lifts its card in the same motion).
@Composable
internal fun ColumnScope.Advanced(
    app: AppState,
    form: SignInForm,
    submit: () -> Unit,
    places: FormPlaces,
    replacingId: String?,
    url: HttpUrl?,
    home: HttpUrl?,
    revealsItself: Boolean = true,
    onShown: (Boolean) -> Unit = {},
) {
    AdvancedHead(form.advancedOpen) {
        form.advancedOpen = !form.advancedOpen
        places.openedFromHead = form.advancedOpen
    }
    if (revealsItself) {
        val host by rememberUpdatedState(LocalCalloutHost.current)
        val still = LocalReduceMotion.current
        val fade = advancedFade()
        val peek = with(LocalDensity.current) { (AdvancedPeek + (host?.fade ?: 0.dp)).roundToPx() }
        LaunchedEffect(form.advancedOpen) {
            if (!form.advancedOpen || !places.openedFromHead) return@LaunchedEffect
            places.openedFromHead = false
            // The open groups are laid out by the next frame.
            withFrameNanos { }
            val at = host ?: return@LaunchedEffect
            val target = revealTarget(at.scroll, at.content, places.advanced, peek).coerceAtMost(at.scroll.maxValue)
            if (target <= at.scroll.value) return@LaunchedEffect
            if (still) at.scroll.scrollTo(target) else at.scroll.glideTo(target, fade)
        }
    }
    val shown by rememberUpdatedState(onShown)
    AnimatedVisibility(form.advancedOpen, enter = fadeIn(advancedFade()), exit = fadeOut(advancedFade())) {
        DisposableEffect(Unit) {
            shown(true)
            onDispose { shown(false) }
        }
        val layout = form.layout
        Column(
            Modifier.fillMaxWidth().padding(top = Space.M).onGloballyPositioned { places.advanced = it },
            verticalArrangement = Arrangement.spacedBy(GroupGap),
        ) {
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

// How much of Advanced's first group shows once it is opened from its head:
// its title and the top of its card.
private val AdvancedPeek = 64.dp

// Where `scroll` goes so the top `peek` of Advanced's groups (at `groups`,
// in the scroll's child `content`) ends over the bottom of the view: never
// back from where the scroll is.
private fun revealTarget(scroll: ScrollState, content: LayoutCoordinates?, groups: LayoutCoordinates?, peek: Int): Int {
    if (content == null || groups == null || !content.isAttached || !groups.isAttached) return scroll.value
    val top = content.localPositionOf(groups, Offset.Zero).y
    return maxOf(scroll.value, (top + peek - scroll.viewportSize).roundToInt())
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
internal fun TrustQuestion(question: CertificateQuestion, onTrust: () -> Unit, onCancel: () -> Unit, changed: Boolean = false) {
    PopupPadding {
        // The question as a sheet's title, then what it means close under it.
        Column(Modifier.fillMaxWidth().padding(top = Space.S), verticalArrangement = Arrangement.spacedBy(Space.M)) {
            Txt(
                if (changed) SignInWords.TRUST_CHANGED_TITLE else SignInWords.TRUST_TITLE,
                SheetTitleWords,
                OctoColors.TextPrimary,
                Modifier.semantics { heading() },
                maxLines = 2,
            )
            Txt(
                if (changed) SignInWords.TRUST_CHANGED_DETAIL else SignInWords.trustDetail("computer"),
                OctoType.bodySmall,
                OctoColors.TextSecondary,
                maxLines = 8,
            )
        }
        Column {
            Txt(SignInWords.TRUST_HOST, OctoType.caption, OctoColors.TextMuted)
            Txt(question.host, OctoType.bodySmall)
        }
        Column {
            Txt(SignInWords.TRUST_FINGERPRINT, OctoType.caption, OctoColors.TextMuted)
            // Two lines of sixteen pairs, so no pair is split.
            val pairs = formatFingerprint(question.fingerprint).split(':')
            Txt(pairs.chunked(16).joinToString("\n") { it.joinToString(":") }, OctoType.caption.copy(fontFamily = FontFamily.Monospace), maxLines = 3)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            GlazeCapsule(null, "Cancel", onCancel, height = ControlHeight.L)
            AccentButton(SignInWords.TRUST_CONFIRM, onTrust, Modifier.widthIn(min = SheetMainButton), size = ButtonSize.Medium)
        }
    }
}

// A host of the server in use showing another certificate than the one
// the listener trusted there, asked about again in the middle of the
// window. Trusting it keeps its pin, so the next request (and the audio
// engine's next connection) goes through. Closing it any way but trusting
// counts as no, and that certificate is not asked about again.
fun askAboutChangedCertificate(popups: PopupHost, security: ServerSecurity, question: CertificateQuestion) {
    popups.showCentred(width = 420.dp, scrim = true) { close ->
        val trusted = remember { booleanArrayOf(false) }
        DisposableEffect(Unit) { onDispose { if (!trusted[0]) security.answered(question, trusted = false) } }
        TrustQuestion(
            question,
            onTrust = {
                trusted[0] = true
                security.trust(question.host, question.pin)
                security.answered(question, trusted = true)
                close()
            },
            onCancel = close,
            changed = true,
        )
    }
}
