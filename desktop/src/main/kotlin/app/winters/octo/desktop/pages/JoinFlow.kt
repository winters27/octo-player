package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.ResolvedHeaders
import app.winters.octo.connection.ReceivedAction
import app.winters.octo.connection.SignInWords
import app.winters.octo.connection.signInOpen
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.ProgressRing
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.Space
import app.winters.octo.design.TextAction
import app.winters.octo.design.scrollFades
import app.winters.octo.design.scrollbar
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.NoticeAction
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.CertificateLoad
import app.winters.octo.desktop.server.CertificateQuestion
import app.winters.octo.desktop.server.CertificateTry
import app.winters.octo.desktop.server.COMMIT_SECONDS
import app.winters.octo.desktop.server.CheckClient
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.server.familyPlatform
import app.winters.octo.desktop.system.openInBrowser
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.FamilyLink
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.subsonic.parseFamilyLink
import app.winters.octo.ui.family.HANDOVER_SIGNED_IN
import app.winters.octo.ui.family.HandOverResult
import app.winters.octo.ui.family.JoinOutcome
import app.winters.octo.ui.family.MIN_PASSWORD
import app.winters.octo.ui.family.joinWithInvite
import app.winters.octo.ui.family.passwordStrength
import app.winters.octo.ui.family.receiveHandOver
import app.winters.octo.ui.family.shownServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Signs up from the invite the form holds: the new member's name and the
// password they chose go to the family page, then this app signs in with
// that username and password like any typed sign-in. The family page is
// asked through a client of its own, with the form's addresses, headers and
// sign-in page fields. A server with a certificate of its own is asked
// about first, as signing in asks. Answers the sign-in once done; otherwise
// the form says why (opening the sign-in page fields when one answered),
// or holds the certificate question.
suspend fun signUpFromForm(accounts: Accounts, form: SignInForm): SignInOutcome.Done? {
    val url = form.url ?: return null
    val invite = form.invite ?: return null
    form.sending()
    val tried = form.attemptAddress
    // A sign-up this invite already made is used again (the invite is spent),
    // at the main address it was made at.
    val held = form.signedUp ?: run {
        val headers = when (val resolved = accounts.joinHeaders(form.request())) {
            is ResolvedHeaders.Problem -> {
                form.result = false to resolved.text
                return null
            }
            is ResolvedHeaders.Ready -> resolved.headers.associate { it.name to it.value }
        }
        val addresses = listOfNotNull(url, form.homeUrl)
        val sent = accounts.joinCertificate(form, url)
        val joined = accounts.checkClient(addresses, headers, sent.certificate, COMMIT_SECONDS).use { join ->
            val outcome = joinWithInvite(url, invite.token, form.inviteName, form.invitePassword, join.http, home = form.homeUrl)
            joinAnswer(outcome, join, addresses, sent, form)
        }
        when (joined) {
            is JoinOutcome.Failed -> {
                form.result = false to (joined.gateway?.let { GatewayWords.failure(it, joined.loginSent) } ?: joined.message)
                form.failed(tried, joined.gateway)
                return null
            }
            is JoinOutcome.Untrusted -> return null
            is JoinOutcome.SignedUp -> HeldSignUp(joined.username, joined.password, url.toString()).also { form.signedUp = it }
        }
    }
    when (val done = accounts.signIn(form.signUpRequest(held.username, held.password, held.main))) {
        is SignInOutcome.Done -> {
            form.invitePassword = ""
            form.inviteAgain = ""
            form.invite = null
            form.signedUp = null
            return done
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
    return null
}

// Takes the sign-in another device of one's own hands over: redeems the
// token from its link, waits for that device to allow it, opens what it
// sends with the key from the link, and signs in with it like a typed one,
// with the form's headers and sign-in page fields. Every call goes through
// a client of its own with those. The form shows each step in plain words;
// a sign-in page that answers stops it and opens its fields, and Try again
// uses them.
suspend fun receiveFromForm(accounts: Accounts, platform: FamilyPlatform, form: SignInForm): SignInOutcome.Done? {
    val link = form.handOver ?: return null
    form.sending()
    var tried = form.attemptAddress
    // A sealed sign-in this code already brought is used again (the code is
    // spent).
    val signIn = form.received ?: run {
        val headers = when (val resolved = accounts.joinHeaders(form.request())) {
            is ResolvedHeaders.Problem -> {
                form.waiting = false
                form.handOverStep = null
                form.result = false to resolved.text
                return null
            }
            is ResolvedHeaders.Ready -> resolved.headers.associate { it.name to it.value }
        }
        val base = link.base.toHttpUrlOrNull()
        val addresses = listOfNotNull(base, form.homeUrl)
        val sent = accounts.joinCertificate(form, base)
        val got = accounts.checkClient(addresses, headers, sent.certificate, COMMIT_SECONDS).use { join ->
            val received = receiveHandOver(link, join.http, accounts.security.deviceName(), platform, step = { form.handOverStep = it })
            handOverAnswer(received, join, addresses, sent, form)
        }
        // The wait for the other device is over, however it ended: what
        // follows commits.
        form.waiting = false
        if (got !is HandOverResult.Received) {
            val refused = got as HandOverResult.Refused
            form.handOverStep = null
            form.result = false to (refused.gateway?.let { GatewayWords.failure(it, refused.loginSent) } ?: refused.message)
            form.failed(tried, refused.gateway)
            return null
        }
        got.signIn.also { form.received = it }
    }
    // The sealed sign-in is checked against what the form holds before
    // anything is sent to it.
    val action = form.receivedAction(signIn)
    if (action is ReceivedAction.Refuse) {
        form.refuseReceived(action.why)
        return null
    }
    if (action is ReceivedAction.ForgetAndAdopt) {
        form.adopt(action, link.username)
        // What this try meets is told against the address now in the box.
        tried = form.attemptAddress
    }
    // The form's own server: its addresses as the form holds them.
    val useForm = (action as? ReceivedAction.Keep)?.useForm == true
    val request = SignInRequest(
        address = if (useForm) form.url?.toString() ?: signIn.server else signIn.server,
        username = signIn.username,
        secret = signIn.secret,
        mode = runCatching { AuthMode.valueOf(signIn.mode) }.getOrDefault(AuthMode.Token),
        home = if (useForm) form.home else signIn.home.orEmpty(),
        headers = form.headers.toList(),
        rememberPassword = true,
        gateway = form.gatewayDraft(),
        certificate = form.certificateDraft,
    )
    when (val done = accounts.signIn(request)) {
        is SignInOutcome.Done -> {
            form.handOverStep = HANDOVER_SIGNED_IN
            form.handOver = null
            form.received = null
            return done
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
    form.handOverStep = null
    return null
}

// The certificate a join or hand-over sends from the form, by its own
// address, and how loading it went.
private suspend fun Accounts.joinCertificate(form: SignInForm, at: HttpUrl?): CertificateTry {
    val choice = certificateFor(form.certificateDraft, null, at, form.homeUrl)
    return CertificateTry(choice, loadChoice(choice))
}

// What a join's first answer leaves to do, with anything to say already on
// the form: a certificate the system does not trust is asked about first,
// then what the server said about the client certificate, then today's
// words for an untrusted one. Untrusted stops the join there.
private fun joinAnswer(outcome: JoinOutcome, join: CheckClient, addresses: List<HttpUrl>, sent: CertificateTry, form: SignInForm): JoinOutcome {
    form.tried(sent)
    if (outcome is JoinOutcome.SignedUp) return outcome
    addresses.firstNotNullOfOrNull { at -> join.security.peekRejected(at.host)?.let { at.host to it } }?.let { (host, leaf) ->
        form.question = CertificateQuestion(host, leaf)
        return JoinOutcome.Untrusted(host)
    }
    join.security.certificateWords(addresses)?.let { words ->
        form.result = false to ((sent.load as? CertificateLoad.Unreadable)?.words ?: words)
        form.askForCertificate()
        return JoinOutcome.Untrusted(addresses.first().host)
    }
    if (outcome is JoinOutcome.Untrusted) form.result = false to "This server's certificate is not trusted."
    return outcome
}

// What a hand-over's answer says: a certificate the system does not trust
// keeps today's words; otherwise what the server said about the client
// certificate, when it asked for one.
private fun handOverAnswer(received: HandOverResult, join: CheckClient, addresses: List<HttpUrl>, sent: CertificateTry, form: SignInForm): HandOverResult {
    form.tried(sent)
    if (received is HandOverResult.Received) return received
    if (addresses.any { join.security.peekRejected(it.host) != null }) return received
    val words = join.security.certificateWords(addresses) ?: return received
    form.askForCertificate()
    return HandOverResult.Refused((sent.load as? CertificateLoad.Unreadable)?.words ?: words)
}

// Opens a link clicked under a QR code: an octo:// link in Octo itself,
// any other in the browser (an Octo server's family page, which hands it
// on to Octo).
fun AppState.openJoinLink(url: String) {
    val link = if (url.startsWith("octo:", ignoreCase = true)) parseFamilyLink(url) else null
    if (link != null) openFamilyLink(link) else openInBrowser(url, os)
}

// Opens a family link, from a launch, the clipboard or a pasted link:
// signed out, the sign-in page fills in; signed in, a sheet does the same,
// and the link's server becomes the one in use once signed in. A link never
// replaces a sign-in that is open (running, a question waiting, a sign-up
// or sealed sign-in held, or a switch on its way): signed out, the page's
// link note says so; signed in, the open sheet's link note and the notice
// say so, the notice with Use it, which opens the link again from here.
fun AppState.openFamilyLink(link: FamilyLink) {
    if (connection == null) {
        val form = signInForm
        if (switching != null || signInOpen(form.busy, form.question != null, form.received != null, form.signedUp != null)) {
            form.noteLink(SignInWords.LINK_WHILE_SIGN_IN_OPEN)
            return
        }
        form.take(link)
        return
    }
    val open = popups.request?.takeIf { it.open() }
    if (open != null || switching != null) {
        open?.noteLink(SignInWords.LINK_WHILE_SIGN_IN_OPEN)
        notice = SignInWords.LINK_WHILE_SIGN_IN_OPEN
        noticeDetail = null
        noticeAction = NoticeAction(SignInWords.LINK_WHILE_SIGN_IN_OPEN, "Use it") { openFamilyLink(link) }
        return
    }
    // Nothing of the server in use is filled in but its certificate, which a
    // join to that server's own origin sends.
    val form = SignInForm(certificateOf = connection?.server?.id).also { it.take(link) }
    popups.showCentred(
        width = SettingsSize.Sheet,
        maxHeight = SettingsSize.SheetMax,
        scrim = true,
        scrollsItself = true,
        busy = { signInOpen(form.committing, form.question != null, form.received != null, form.signedUp != null) },
        open = { signInOpen(form.busy, form.question != null, form.received != null, form.signedUp != null) },
        noteLink = form::noteLink,
        link = true,
    ) { close -> CompositionLocalProvider(LocalInSheet provides true) { LinkSheet(this@openFamilyLink, form, close) } }
}

// The sheet a link opens while signed in: the link's card and Advanced
// scroll; the result line and the buttons stay at its foot. A certificate
// the system does not trust is asked about in its place. Nothing goes while
// a switch is on its way.
@Composable
private fun ColumnScope.LinkSheet(app: AppState, form: SignInForm, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val places = remember { FormPlaces() }
    val calloutFocus = remember { CalloutFocus() }
    var content by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var asking by remember { mutableStateOf<CertificateQuestion?>(null) }
    fun finish(done: SignInOutcome.Done?) {
        asking = form.question
        if (done != null) {
            close()
            app.arrive(done.connection, Page.Home, done.note)
        }
    }
    fun go() {
        if (form.busy || app.switching != null) return
        form.sending()
        form.busy = true
        form.waiting = form.handOver != null && form.received == null
        form.result = null
        val tried = form.attemptAddress
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val done = try {
                when {
                    form.invite != null -> signUpFromForm(app.accounts, form)
                    form.handOver != null -> receiveFromForm(app.accounts, familyPlatform(app.os), form)
                    else -> when (val signedIn = app.accounts.signIn(form.request())) {
                        is SignInOutcome.Done -> signedIn
                        is SignInOutcome.Failed -> null.also {
                            form.result = false to signedIn.message
                            form.failed(tried, signedIn.gateway)
                            form.tried(signedIn.tried)
                            if (signedIn.certificate) form.askForCertificate()
                        }
                        is SignInOutcome.Untrusted -> null.also { form.question = signedIn.question }
                        is SignInOutcome.Saved -> null
                    }
                }
            } finally {
                if (form.running === coroutineContext.job) {
                    form.busy = false
                    form.waiting = false
                    form.running = null
                }
            }
            finish(done)
        }
        form.running = job
        job.start()
    }
    // A hand-over starts at once: there is nothing to type.
    LaunchedEffect(form.handOver) { if (form.handOver != null && form.handOverStep == null) go() }
    val host = content?.let { CalloutHost(scroll, PopupTopInset, it, settled = { true }, imeBottom = { 0 }, focus = calloutFocus, fade = SheetFade) }
    CalloutEffect(form.calloutPending, host, { places.gateway }) { form.calloutShown() }
    CalloutEffect(form.keyPending, host, { places.key }) { form.keyShown() }
    CalloutEffect(form.certificatePending, host, { places.special }) { form.certificatePending = false }
    val question = asking
    if (question != null) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            TrustQuestion(
                question,
                onTrust = {
                    app.accounts.security.trust(question.host, question.pin)
                    form.question = null
                    asking = null
                    go()
                },
                onCancel = {
                    form.distrust()
                    asking = null
                },
            )
            LinkNote(form)
        }
        return
    }
    FormSheetTitle(
        when {
            form.invite != null -> "Join a family"
            form.handOver != null -> "Signing in from your other device"
            else -> "Sign in"
        },
    )
    CompositionLocalProvider(LocalCalloutHost provides host) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .scrollbar(scroll)
                .scrollFades(scroll, SheetFade, eased = true)
                .verticalScroll(scroll)
                .onGloballyPositioned { content = it }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LinkCard(form, ::go, places)
            Advanced(app, form, ::go, places, replacingId = null, url = form.handOver?.base?.toHttpUrlOrNull() ?: form.url, home = form.homeUrl)
        }
    }
    PopupPadding { LinkFooter(form, ::go, onLeave = { if (form.invite != null) form.leaveLink() else if (!form.committing) close() }, held = app.switching != null, onClose = { if (!form.committing) close() }) }
}

// The part of the sign-in a family link fills, as the page's main card:
// signing up from an invite (a name and a password, twice, the strength
// under the first), a sign-in coming from another device (what is
// happening, with a spinner at the end while it goes, or that it is not
// signed in yet once a try failed), or the server and
// username filled in, waiting for the password or key. On the page the
// address row comes first, with its notes. The invite's sentence and the
// hand-over's "choose Allow" are notes under the card.
@Composable
internal fun LinkCard(
    form: SignInForm,
    go: () -> Unit,
    places: FormPlaces,
    address: (@Composable () -> Unit)? = null,
    addressNotes: @Composable ColumnScope.() -> Unit = {},
) {
    val invite = form.invite
    val handOver = form.handOver
    FormGroup(
        null,
        notes = {
            addressNotes()
            when {
                invite != null -> FormNote("You're invited to ${shownServer(invite.server)}. Choose your name and a password. Your login then works in Octo and any other music app.")
                handOver != null -> FormNote("On your other device, choose Allow when it asks about this computer.")
            }
        },
    ) {
        address?.invoke()
        when {
            invite != null -> {
                FormFieldRow(
                    SignInWords.YOUR_NAME,
                    value = form.inviteName,
                    onChange = {
                        form.inviteName = it
                        form.result = null
                    },
                    placeholder = "As the family sees you",
                    onSubmit = go,
                )
                FormFieldRow(
                    SignInWords.PASSWORD,
                    description = passwordStrength(form.invitePassword),
                    value = form.invitePassword,
                    onChange = {
                        form.invitePassword = it
                        form.result = null
                    },
                    placeholder = "At least $MIN_PASSWORD characters",
                    secret = true,
                    onSubmit = go,
                )
                FormFieldRow(
                    SignInWords.PASSWORD_AGAIN,
                    value = form.inviteAgain,
                    onChange = {
                        form.inviteAgain = it
                        form.result = null
                    },
                    placeholder = "The same password",
                    secret = true,
                    onSubmit = go,
                )
            }
            handOver != null -> {
                val host = shownServer(handOver.server)
                FormRow(form.handOverStep ?: if (form.result?.first == false) "Not signed in to $host yet" else "Ready to sign in to $host") {
                    if (form.busy) ProgressRing(null)
                }
            }
            else -> {
                FormFieldRow(
                    SignInWords.USERNAME,
                    optional = form.useApiKey,
                    value = form.username,
                    onChange = {
                        form.username = it
                        form.result = null
                    },
                    placeholder = if (form.useApiKey) "" else SignInWords.REQUIRED,
                    onSubmit = go,
                )
                SecretRow(form, go, places, null)
            }
        }
    }
}

// The foot of a link's card: the result line and a refused link's words,
// then the buttons on the right: Cancel where there is a way out, and the
// page's filled button that goes on. With an invite held, Cancel closes the
// sheet (`onClose`; the page has none) and is off while a sign-up made here
// is held, which only the password sign-in leaves; the way to that sign-in
// sits under the buttons on their right edge, as Paste a family link does.
// With anything else Cancel leaves the link (`onLeave`). Nothing leaves
// while the request commits, and nothing goes while `held` (a switch on its
// way). The page draws it in its card; the sheet keeps it under its
// scrolling part.
@Composable
internal fun ColumnScope.LinkFooter(form: SignInForm, go: () -> Unit, onLeave: () -> Unit, held: Boolean = false, onClose: (() -> Unit)? = null) {
    val invite = form.invite
    val handOver = form.handOver
    ResultLine(form)
    LinkNote(form)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        val out = if (invite != null) onClose else onLeave
        out?.let { GlazeCapsule(null, "Cancel", it, enabled = !form.committing && (invite == null || form.signedUp == null), height = ControlHeight.L) }
        when {
            invite != null -> AccentButton("Sign up", go, Modifier.widthIn(min = 150.dp), enabled = form.inviteReady && !held, loading = form.busy, size = ButtonSize.Medium)
            handOver != null -> if (!form.busy) AccentButton("Try again", go, Modifier.widthIn(min = 150.dp), enabled = !held, size = ButtonSize.Medium)
            else -> AccentButton("Sign in", go, Modifier.widthIn(min = 150.dp), enabled = form.ready && !held, loading = form.busy, size = ButtonSize.Medium)
        }
    }
    if (invite != null) {
        // Its words, not its hover pill, end on the buttons' right edge.
        TextAction("Sign in with a password instead", onLeave, Modifier.align(Alignment.End).offset(x = Space.L), enabled = !form.committing)
    }
}
