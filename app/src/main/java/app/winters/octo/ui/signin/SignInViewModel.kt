package app.winters.octo.ui.signin

import app.winters.octo.ui.family.joinWithInvite
import app.winters.octo.ui.family.inviteProblem
import app.winters.octo.subsonic.parseFamilyLink
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.FamilyLink
import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.GatewayDraft
import app.winters.octo.connection.GatewayFormState
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.HeaderList
import app.winters.octo.connection.JoinClients
import app.winters.octo.connection.LinkAction
import app.winters.octo.connection.LinkAnchor
import app.winters.octo.connection.LinkForm
import app.winters.octo.connection.LinkFormState
import app.winters.octo.connection.LinkTake
import app.winters.octo.connection.ReceivedAction
import app.winters.octo.connection.ResolvedHeaders
import app.winters.octo.connection.addressChanged
import app.winters.octo.connection.authModeOf
import app.winters.octo.connection.calm
import app.winters.octo.connection.flagsOf
import app.winters.octo.connection.gatewayEdited
import app.winters.octo.connection.kindPicked
import app.winters.octo.connection.linkFacts
import app.winters.octo.connection.methodPicked
import app.winters.octo.connection.reactToFailure
import app.winters.octo.connection.receivedFacts
import app.winters.octo.connection.resolveConnectionHeaders
import app.winters.octo.connection.sending
import app.winters.octo.connection.signInOpen
import app.winters.octo.connection.letGo
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.ui.family.HandOverSignIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import app.winters.octo.ui.family.JoinOutcome
import app.winters.octo.ui.family.HandOverResult
import app.winters.octo.ui.family.HANDOVER_SIGNED_IN
import app.winters.octo.ui.family.receiveHandOver
import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilySignInPrefill
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.connection.DeviceIds
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.connection.LegacyRetry
import app.winters.octo.connection.SignInWords
import app.winters.octo.connection.isHeaderName
import app.winters.octo.connection.isHeaderValue
import app.winters.octo.connection.legacyRetry
import app.winters.octo.connection.pinKey
import app.winters.octo.data.ConnectionDraft
import app.winters.octo.data.HeaderDraft
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SignInError
import app.winters.octo.data.SignInRequest
import app.winters.octo.data.userMessage
import app.winters.octo.playback.ServerSwitch
import app.winters.octo.playback.SignInSwitch
import app.winters.octo.server.accountLine
import app.winters.octo.server.serverName
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.isPrivateHost
import app.winters.octo.subsonic.Scheme
import app.winters.octo.subsonic.automaticScheme
import app.winters.octo.subsonic.normalizeServerUrl
import app.winters.octo.subsonic.serverUrl
import app.winters.octo.subsonic.splitScheme
import app.winters.octo.ui.nav.ServerForm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import javax.inject.Inject

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val switcher: ServerSwitch,
    private val signIns: SignInSwitch,
    private val clients: JoinClients,
    private val devices: DeviceIds,
    private val running: SignInRunning,
) : ViewModel() {
    // A family link's card in place of the password: signing up from an
    // invite, or a sign-in coming from another device of one's own.
    var joining by mutableStateOf(false)

    // A sign-in from another device, while it comes, and what is happening,
    // in plain words.
    var handOver by mutableStateOf<FamilyHandOverLink?>(null)
        private set
    var handOverStep by mutableStateOf<String?>(null)
        private set

    // A sign-up made from an invite, kept until signed in: an invite works
    // once, so a retry or a certificate asked about afterwards does not sign
    // up again. It signs in at the address the form held when the sign-up
    // started (never the home address a sign-up fell back to), and the
    // invite's token tells the same invite opened again.
    private class HeldSignUp(val account: JoinOutcome.SignedUp, val main: HttpUrl, val token: String)

    private var signedUp by mutableStateOf<HeldSignUp?>(null)

    // A sign-in received from another device, kept until signed in with: a
    // hand-over works once, so a retry or a certificate asked about
    // afterwards does not redeem it again.
    private class HeldReceived(val signIn: HandOverSignIn, val token: String)

    private var received by mutableStateOf<HeldReceived?>(null)

    // An invite opened: the new member's name and the password they choose,
    // twice. Signing up runs the invite's steps, then signs this phone in.
    var invite by mutableStateOf<FamilyInviteLink?>(null)
        private set
    var inviteName by mutableStateOf("")
    var invitePassword by mutableStateOf("")
    var inviteAgain by mutableStateOf("")

    // The camera scanner, over the join form.
    var scanning by mutableStateOf(false)

    // The family link this page opened, and which opening of it: a page
    // drawn again (a rotation) is the same opening and starts nothing.
    private var joinedFrom: Pair<FamilyLink, Long>? = null

    // The address in two parts, as the desktop keeps it: the scheme, shown
    // as a button at the start of the field, and the rest as typed. Each
    // change of either, or of the home address, tells the form's link state
    // what the form now points at.
    private var addressText by mutableStateOf("")
    var address: String
        get() = addressText
        private set(value) {
            if (value == addressText) return
            addressText = value
            linkEdited()
        }
    private var schemeChoice by mutableStateOf(Scheme.Https)
    var scheme: Scheme
        get() = schemeChoice
        private set(value) {
            if (value == schemeChoice) return
            schemeChoice = value
            linkEdited()
        }

    // Picked by hand (or typed, or saved): no longer follows the address.
    private var schemePicked = false
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set

    // Of a request that runs, the part that only waits for the other device
    // to allow a hand-over: leaving then is harmless and cancels it. Every
    // other running request commits (a sign-up, a sign-in, a save) and is
    // never left halfway.
    var waiting by mutableStateOf(false)
        private set
    val committing: Boolean get() = busy && !waiting

    // The request running, while it is the form's current one.
    internal var linkJob: Job? = null
        private set

    // What the last attempt came to. Every write, a new failure included,
    // ends the sign-in page group's callout of the one before; a failure
    // that met a sign-in page calls it out again after its words are set.
    private var errorWords by mutableStateOf<String?>(null)
    var error: String?
        get() = errorWords
        private set(value) {
            errorWords = value
            useGatewayForm(calm(gatewayForm))
        }

    // True once signed in, so the screen can close. The library copy
    // starts on its own.
    var signedIn by mutableStateOf(false)
        private set

    // What to say once done, if anything: the switch notice, or that a
    // server was added or saved.
    var notice by mutableStateOf<String?>(null)
        private set

    // What the form is for when opened from the list of servers, and which
    // kept server; null for signing in from scratch.
    var form by mutableStateOf<ServerForm?>(null)
        private set
    private var serverId: String? = null

    // The name the listener gives the server, when adding or editing one.
    var label by mutableStateOf("")

    // What the screen says it is for, when opened from the list of servers.
    var heading by mutableStateOf<String?>(null)
        private set
    var subheading by mutableStateOf<String?>(null)
        private set

    // The connection as the form started, to tell a new name alone apart.
    private var started: ConnectionDraft? = null

    // Changing the saved connection rather than signing in fresh. Secrets
    // left empty keep their saved values.
    var editing by mutableStateOf(false)
        private set

    // The advanced settings, folded away unless something in them is set.
    var advancedOpen by mutableStateOf(false)
    var legacyPassword by mutableStateOf(false)
    var useApiKey by mutableStateOf(false)
    var apiKey by mutableStateOf("")
    private var homeText by mutableStateOf("")
    var home: String
        get() = homeText
        set(value) {
            if (value == homeText) return
            homeText = value
            linkEdited()
        }

    // The custom header rows, each with an id that stays with it, so the
    // rows draw keyed and an edit always lands on its own row. Every write
    // goes through HeaderList.
    private var list by mutableStateOf(HeaderList.empty)
    val headers: List<HeaderDraft> get() = list.rows
    val headerIds: List<Long> get() = list.ids

    // The sign-in page in front of the server: the kind of sign-in it takes
    // from apps, what is typed for it, and the kind saved for this server,
    // whose values are kept while its fields are left empty. Typing in its
    // fields makes the last failure the person's to fix: its words go, and
    // the group stops calling it out.
    var gatewayKind by mutableStateOf(GatewayKind.None)
        private set
    private var firstWords by mutableStateOf("")
    var gatewayFirst: String
        get() = firstWords
        set(value) {
            firstWords = value
            error = null
            useGatewayForm(gatewayEdited(gatewayForm))
        }
    private var secondWords by mutableStateOf("")
    var gatewaySecond: String
        get() = secondWords
        set(value) {
            secondWords = value
            error = null
            useGatewayForm(gatewayEdited(gatewayForm))
        }
    var gatewaySaved by mutableStateOf(GatewayKind.None)
        private set

    // The sign-in page the last failed attempt met, and the address it met
    // it at.
    var gateway by mutableStateOf<Gateway?>(null)
        private set
    private var gatewayFor: String? = null

    // The last failure met a sign-in page here: its group shows it, outlined,
    // with a pointer under Sign in. The group, or the API key's row, still
    // has to be brought into view.
    var callout by mutableStateOf(false)
        private set
    var calloutPending by mutableStateOf(false)
        private set
    var keyPending by mutableStateOf(false)
        private set

    // The client certificate's name in the phone's store, once picked.
    var clientCert by mutableStateOf<String?>(null)

    // Whether the server takes API keys: null until known, which is before
    // signing in, when a key may still be tried.
    var takesApiKeys by mutableStateOf<Boolean?>(null)
        private set

    // A certificate the phone does not trust, waiting for the user's answer.
    var question by mutableStateOf<SignInError.Untrusted?>(null)
        private set

    // Certificates the user trusted, by host.
    private val pins = HashMap<String, String>()

    // Whether a saved secret stands in for an empty field: a form that keeps
    // it (an edit, or a sign-in again to a kept server not signed out of),
    // and the way it was saved.
    private var keepsSecret = false
    private var savedMode: AuthMode? = null

    // Where family links may take this form, and the words of the last one
    // refused (shown under the address).
    private var links by mutableStateOf(LinkFormState(LinkForm.Fresh))
    val linkNote: String? get() = links.note

    // A finished sign-up held (the address then stays as it is), or a
    // received sign-in.
    val holdsSignUp: Boolean get() = signedUp != null
    internal val holdsReceived: Boolean get() = received != null

    // Marks this form in the app's set of open sign-ins while it is open,
    // whichever of the four facts changes it. It never ends while the form
    // lives.
    internal val marking: Job = viewModelScope.launch {
        snapshotFlow { signInOpen(busy, question != null, received != null, signedUp != null) }.collect { running.mark(this@SignInViewModel, it) }
    }

    // The server's address, or null while what is typed isn't one.
    val url: HttpUrl? by derivedStateOf { serverUrl(scheme, address) }

    // Plain http to an address outside the home network.
    val insecure by derivedStateOf { url?.let { !it.isHttps && !isPrivateHost(it) } == true }

    // The address, its scheme or the home changed: the form's link state
    // hears what the form now points at (the address as built, the home as
    // the app reads it).
    private fun linkEdited() {
        val homeRead = homeText.trim().takeIf(String::isNotEmpty)?.let(::normalizeServerUrl)?.toString()
        links = links.edited(serverUrl(schemeChoice, addressText)?.toString(), homeRead, addressText.isNotBlank())
    }

    // What the person typed or pasted into the address field. A held sign-up
    // signs in at the address it was made for, which then stays as it is.
    fun typeAddressFromField(text: String) {
        if (signedUp != null) return
        typeAddress(text)
    }

    // Writes the address, as typed or as the app puts it. A full address
    // sets the scheme and leaves the rest; otherwise the scheme follows the
    // address until one is picked: http at home, https anywhere else.
    fun typeAddress(text: String) {
        val (typed, rest) = splitScheme(text)
        if (typed != null) {
            scheme = typed
            schemePicked = true
            address = rest
        } else {
            address = text
            if (!schemePicked) scheme = automaticScheme(text)
        }
        error = null
        // A sign-in page met at another address is forgotten.
        useGatewayForm(addressChanged(gatewayForm, sentAddress))
    }

    // Takes a family link (the https form a QR code carries, or the app's
    // own octo:// form, scanned, opened or pasted). An invite shows the
    // sign-up card; a sign-in from another device starts at once; a sign-in
    // link fills in the server and username for the password. True for any
    // link, taken or refused, so it is never typed into the address; false
    // for any other text.
    fun takeJoinLink(text: String): Boolean {
        val link = parseFamilyLink(text) ?: return false
        startJoin(link)
        return true
    }

    // Opens a family link the page was opened for, once per opening: a page
    // drawn again (a rotation, a redraw) is the same opening and starts
    // nothing, while the same link opened again is a new page. `before` runs
    // first, only when the link is taken (the page puts focus on the
    // address).
    fun open(link: FamilyLink, opened: Long, before: () -> Unit = {}) {
        if (joinedFrom == link to opened) return
        joinedFrom = link to opened
        before()
        startJoin(link)
    }

    // Scan a family QR code, from the plain page: the join form with the
    // camera over it. Never while a sign-in runs or its certificate question
    // waits, which is never switched halfway.
    fun scanFamilyCode() {
        if (busy || question != null) return
        startJoin()
        scanning = true
    }

    // Takes a family link into the form; with none, opens the join form
    // (never while a request runs or its certificate question waits). A link
    // that comes while the form signs in, or while a certificate question
    // waits, is refused, as is one a form for a kept server cannot take, and,
    // while a sign-up or a received sign-in is held, any but the same invite
    // or hand-over again for the same server: its words go under the address
    // and nothing else changes, but the camera closes. A link to another
    // server forgets every detail of the last one before it fills anything
    // in.
    fun startJoin(link: FamilyLink? = null) {
        scanning = false
        if (link == null) {
            if (busy || question != null) return
            error = null
            joining = true
            return
        }
        val facts = linkFacts(link)
        val action = links.take(facts, busy || question != null, home)
        if (action !is LinkAction.Take) {
            links = links.took(action)
            return
        }
        // A held sign-up or received sign-in works once and keeps the
        // address it was made for: no link drops it or writes over it. The
        // same one again, for the same server, picks it up where it was.
        if ((signedUp != null || received != null) && !(action.kind != LinkTake.Forget && isHeld(link))) {
            links = links.noted(SignInWords.LINK_WHILE_SIGN_IN_OPEN)
            return
        }
        error = null
        if (action.kind == LinkTake.Forget) forget(facts.username)
        if (action.kind != LinkTake.Fitting) {
            typeAddress(action.address ?: link.server)
            home = action.home.orEmpty()
        }
        links = links.took(action)
        invite = null
        handOver = null
        handOverStep = null
        when (link) {
            is FamilyInviteLink -> {
                invite = link
                joining = true
            }
            is FamilyHandOverLink -> {
                handOver = link
                joining = true
                receive()
            }
            is FamilySignInPrefill -> {
                joining = false
                if (link.username.isNotBlank()) username = link.username
                password = ""
                useApiKey = false
            }
        }
    }

    // Whether `link` is the invite or hand-over whose step the form holds.
    private fun isHeld(link: FamilyLink): Boolean = when (link) {
        is FamilyInviteLink -> signedUp?.token == link.token
        is FamilyHandOverLink -> received?.token == link.token
        is FamilySignInPrefill -> false
    }

    // Leaves the family link. A step that commits (a sign-up, a sign-in) is
    // never left halfway, whatever calls this; one waiting on the other
    // device is cancelled, and nothing it would have done happens.
    fun stopJoin() {
        if (committing) return
        linkJob?.cancel()
        linkJob = null
        busy = false
        waiting = false
        handOverStep = null
        leaveLink()
    }

    // What leaving the link clears: the link, a sign-in received for it and
    // a sign-up made from it, whose new username stays in the username field
    // (the server chose it, and the person may not have seen it).
    private fun leaveLink() {
        signedUp?.let { username = it.account.username }
        signedUp = null
        received = null
        joining = false
        invite = null
        handOver = null
        error = null
        links = links.left()
    }

    // Words for the link note from outside the form: a link that came in
    // while this or another sign-in was open.
    fun noteLink(words: String) {
        links = links.noted(words)
    }

    // What is missing before signing up, or null when it can go.
    val joinProblem: String?
        get() = when {
            invite == null -> "Scan or open your invite first"
            url == null -> "Type the server's address"
            else -> inviteProblem(inviteName, invitePassword, inviteAgain)
        }

    // Forgets every detail of the last server that a join or a sign-in
    // reads, for a link or a received sign-in that goes to another: its
    // header rows, sign-in page, way of signing in and secrets, what was
    // typed for it, its client certificate and its trusted certificates (but
    // those named in `keepPins`). The username stays only when the link names
    // one (a prefill then fills it in). The name the person gave the server
    // is never sent anywhere, so it stays.
    private fun forget(linkUsername: String?, keepPins: Set<String> = emptySet()) {
        list = list.replaced(emptyList())
        firstWords = ""
        secondWords = ""
        gatewaySaved = GatewayKind.None
        useGatewayForm(gatewayForm.copy(kind = GatewayKind.None))
        legacyPassword = false
        useApiKey = false
        apiKey = ""
        password = ""
        inviteName = ""
        invitePassword = ""
        inviteAgain = ""
        if (linkUsername.isNullOrBlank()) username = ""
        takesApiKeys = null
        clientCert = null
        pins.keys.retainAll(keepPins)
    }

    // Runs the form's request as its current one. Its end clears the form's
    // busy state only while it still is the current one, so a request left
    // behind (cancelled, or replaced by a retry) never clears a newer one's.
    private fun launchLink(work: suspend CoroutineScope.() -> Unit) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                work()
            } finally {
                if (linkJob === coroutineContext.job) {
                    busy = false
                    waiting = false
                    linkJob = null
                }
            }
        }
        linkJob = job
        job.start()
    }

    // Takes the sign-in another device of one's own hands over: redeems the
    // token from its link, waits for that device to allow it, opens what it
    // sends with the key from the link, and signs in with it like a typed one.
    // The form's sign-in page fields, headers and certificates go with it,
    // so a server behind a sign-in page or with a certificate of its own is
    // reached; a sign-in page that turns it away opens Advanced at its fields.
    // What it receives is checked against the form first: a form for a kept
    // server signs in to that server again, or refuses another; any other
    // form forgets the last server's details for another and shows where the
    // sign-in goes.
    fun receive() {
        val link = handOver ?: return
        if (busy) return
        val base = link.base.toHttpUrlOrNull() ?: return
        useGatewayForm(sending(gatewayForm))
        val connection = when (val form = formConnection(base, home, headers, gatewayDraft, pins.toMap(), clientCert)) {
            is FormConnection.Ready -> form.settings
            is FormConnection.Problem -> {
                error = form.text
                return
            }
        }
        var attempt = sentAddress
        busy = true
        // Waiting on the other device only while a redeem will run; a sign-in
        // already received goes straight to signing in, which commits.
        waiting = received == null
        error = null
        launchLink {
            val signIn = received?.signIn ?: run {
                val (http, security) = clients.forJoin(base, connection)
                // Its own client, let go once the hand-over has answered.
                val got = try {
                    withContext(Dispatchers.IO) {
                        receiveHandOver(link, http, devices.current().name, FamilyPlatform.Android, step = { handOverStep = it })
                    }
                } finally {
                    http.letGo()
                }
                waiting = false
                if (got is HandOverResult.Received) return@run got.signIn.also { received = HeldReceived(it, link.token) }
                val refused = got as HandOverResult.Refused
                handOverStep = null
                // A certificate the phone does not trust is asked about, as
                // signing in asks; trusting it tries again.
                val leaf = security.takeRejected(base.host)
                if (leaf != null) {
                    question = SignInError.Untrusted(base.host, leaf)
                } else {
                    error = refused.gateway?.let { GatewayWords.failure(it, refused.loginSent) } ?: refused.message
                    met(attempt, refused.gateway)
                }
                return@launchLink
            }
            val check = links.received(receivedFacts(signIn))
            if (check is ReceivedAction.Refuse) {
                // The token is spent: the form leaves the hand-over, then says why.
                handOverStep = null
                leaveLink()
                links = links.noted(check.why.words)
                return@launchLink
            }
            if (check is ReceivedAction.ForgetAndAdopt) {
                // A certificate trusted for an address both the link and the
                // sign-in name stays trusted; nothing else of the last server
                // does. The form then shows where the sign-in goes, and what
                // it meets there is reported against that address. A link
                // note set meanwhile (a link refused while this ran) stays
                // through these writes of the app's own.
                val note = links.note
                val keep = check.addresses.filter { it in links.taken }.mapNotNull { it.toHttpUrlOrNull()?.host }.map(::pinKey).toSet()
                forget(link.username, keep)
                typeAddress(check.address)
                home = check.home
                links = links.adopted(check)
                attempt = sentAddress
                if (note != null) links = links.noted(note)
            }
            val own = check is ReceivedAction.Keep && check.useForm
            val request = SignInRequest(
                address = if (own) sentAddress else signIn.server,
                username = signIn.username,
                secret = signIn.secret,
                authMode = runCatching { AuthMode.valueOf(signIn.mode) }.getOrDefault(AuthMode.Token),
                home = if (own) home else signIn.home.orEmpty(),
                headers = headers,
                pins = pins.toMap(),
                clientCertAlias = clientCert,
                gateway = gatewayDraft,
            )
            // A form opened for a kept server brings that server up to date,
            // never keeps it twice.
            val (failed, words) = signIns.signIn(request, serverId.takeIf { links.kind == LinkForm.KeptServer })
            when (failed) {
                null -> {
                    handOverStep = HANDOVER_SIGNED_IN
                    notice = words
                    handOver = null
                    received = null
                    signedIn = true
                }
                // Every failure ends "Allowed, signing in": the row then says
                // it is ready to sign in, while the question waits.
                is SignInError.Untrusted -> {
                    handOverStep = null
                    question = failed
                }
                else -> {
                    handOverStep = null
                    error = failed.userMessage()
                    met(attempt, gatewayOf(failed))
                }
            }
        }
    }

    // Signs up from the invite: the name and password chosen go to the
    // family page, then this phone signs in with that username and password
    // like any typed sign-in. The server then is the one in use. A sign-up
    // already made signs in at the address it was made for.
    fun join() {
        val url = url
        if (busy || joinProblem != null || url == null) return
        useGatewayForm(sending(gatewayForm))
        val held = signedUp
        val main = held?.main ?: url
        // Set up as signing in to the server would be: the certificates
        // trusted for it, its headers, its sign-in page fields and its client
        // certificate.
        val connection = when (val form = formConnection(main, home, headers, gatewayDraft, pins.toMap(), clientCert)) {
            is FormConnection.Ready -> form.settings
            is FormConnection.Problem -> {
                error = form.text
                return
            }
        }
        val attempt = sentAddress
        busy = true
        waiting = false
        error = null
        launchLink {
            val invited = invite ?: return@launchLink
            val account = held?.account ?: run {
                // Its own client, let go once the family page has answered;
                // its security still holds a certificate it refused, to ask
                // about.
                val (http, security) = clients.forJoin(main, connection)
                val joined = try {
                    withContext(Dispatchers.IO) {
                        joinWithInvite(main, invited.token, inviteName, invitePassword, http, home = connection.home)
                    }
                } finally {
                    http.letGo()
                }
                when (joined) {
                    is JoinOutcome.SignedUp -> joined.also {
                        signedUp = HeldSignUp(it, main, invited.token)
                        // The address field shows where the sign-in goes; a
                        // link note set meanwhile stays through that write.
                        if (sentAddress != main.toString()) {
                            val note = links.note
                            typeAddress(main.toString())
                            if (note != null) links = links.noted(note)
                        }
                    }
                    // A certificate of the server's own is asked about as
                    // signing in asks; trusting it signs up again.
                    is JoinOutcome.Untrusted -> {
                        val leaf = security.takeRejected(joined.host)
                        if (leaf != null) question = SignInError.Untrusted(joined.host, leaf) else error = "This server's certificate is not trusted."
                        return@launchLink
                    }
                    // A sign-in page that turns the invite away opens Advanced
                    // at its fields.
                    is JoinOutcome.Failed -> {
                        error = joined.gateway?.let { GatewayWords.failure(it, joined.loginSent) } ?: joined.message
                        met(attempt, joined.gateway)
                        return@launchLink
                    }
                }
            }
            val request = SignInRequest(
                address = main.toString(),
                username = account.username,
                secret = account.password,
                authMode = AuthMode.Token,
                home = home,
                headers = headers,
                pins = pins.toMap(),
                clientCertAlias = clientCert,
                gateway = gatewayDraft,
            )
            val (failed, words) = signIns.signIn(request)
            when (failed) {
                null -> {
                    notice = words
                    invitePassword = ""
                    inviteAgain = ""
                    invite = null
                    signedUp = null
                    signedIn = true
                }
                is SignInError.Untrusted -> question = failed
                else -> {
                    error = failed.userMessage()
                    met(attempt, gatewayOf(failed))
                }
            }
        }
    }

    // Switches between https and http by hand. A held sign-up's address stays
    // as it is.
    fun toggleScheme() {
        if (signedUp != null) return
        scheme = if (scheme == Scheme.Https) Scheme.Http else Scheme.Https
        schemePicked = true
        error = null
        useGatewayForm(addressChanged(gatewayForm, sentAddress))
    }

    // Starts from the saved connection of the server in use. Nothing secret
    // is filled in. It is a form for a kept server, whose links must fit it.
    fun startEditing() {
        if (editing) return
        links = LinkFormState(LinkForm.KeptServer)
        serverId = sessions.servers.value.active ?: return
        editing = true
        fill(sessions.connectionDraft(serverId) ?: return)
    }

    // Opened from the list of servers: a new one to keep, a kept one to
    // edit, or a kept one to sign in to again (its password asked for). A
    // form opened for a kept server takes only links that fit it.
    fun start(form: ServerForm, id: String?, note: String?) {
        if (this.form != null) return
        links = LinkFormState(if (form == ServerForm.Add || id == null) LinkForm.Fresh else LinkForm.KeptServer)
        this.form = form
        serverId = id
        error = note
        val kept = id?.let { sessions.servers.value.find(it) }
        heading = when (form) {
            ServerForm.Add -> "Add a server"
            ServerForm.Edit -> "Edit ${kept?.name.orEmpty()}"
            ServerForm.SignIn -> "Sign in to ${kept?.name.orEmpty()}"
        }
        subheading = when (form) {
            ServerForm.Add -> "Keep another server here and switch to it in one tap."
            ServerForm.Edit -> "Octo signs in with the new details before it saves them."
            ServerForm.SignIn -> kept?.let { accountLine(it.username, it.serverUrl) }
        }
        if (form == ServerForm.Add || id == null) return
        val draft = sessions.connectionDraft(id) ?: return
        editing = form == ServerForm.Edit
        fill(draft)
        if (!keepsSavedSecret(form, kept?.signedOut != false)) keepsSecret = false
    }

    // How the form signs in: an API key, the legacy password, or the normal
    // way.
    val mode: AuthMode get() = authModeOf(legacyPassword, useApiKey)

    // Whether the password or key may be left empty to keep the saved one.
    val keepsPassword: Boolean get() = app.winters.octo.connection.keepsPassword(keepsSecret, savedMode, mode)
    val keepsKey: Boolean get() = app.winters.octo.connection.keepsKey(keepsSecret, savedMode, mode)

    // Picks how to sign in. Picking API key with no key kept for it asks for
    // the key's row to be brought into view.
    fun pickMethod(next: AuthMode) {
        val was = mode
        val (legacy, key) = flagsOf(next)
        legacyPassword = legacy
        useApiKey = key
        useGatewayForm(methodPicked(gatewayForm, was, next, app.winters.octo.connection.keepsKey(keepsSecret, savedMode, next)))
    }

    private fun fill(draft: ConnectionDraft) {
        started = draft
        label = draft.label
        // A saved address carries its scheme, which then stays as saved.
        val (saved, rest) = splitScheme(draft.address.removeSuffix("/"))
        if (saved != null) {
            scheme = saved
            schemePicked = true
        }
        address = rest
        username = draft.username
        legacyPassword = draft.authMode == AuthMode.LegacyPassword
        useApiKey = draft.authMode == AuthMode.ApiKey
        home = draft.home
        list = list.replaced(draft.headers)
        clientCert = draft.clientCertAlias
        pins.putAll(draft.pins)
        takesApiKeys = draft.takesApiKeys
        keepsSecret = true
        savedMode = draft.authMode
        // The saved sign-in page's kind; its values stay sealed and are kept
        // while its fields are left empty.
        gatewaySaved = draft.gateway
        gatewayKind = draft.gateway
        firstWords = ""
        secondWords = ""
        advancedOpen = draft.authMode != AuthMode.Token || draft.home.isNotEmpty() ||
            draft.headers.isNotEmpty() || draft.clientCertAlias != null || draft.gateway != GatewayKind.None
        // The server the form was filled from, which links must fit.
        links = links.filled(LinkAnchor(draft.address, draft.home.takeIf(String::isNotBlank), draft.username))
    }

    // A blank header row at the end, and its id.
    fun addHeader(): Long {
        val (next, id) = list.added()
        list = next
        return id
    }

    fun removeHeader(id: Long) {
        list = list.remove(id)
    }

    // A renamed saved header no longer keeps its saved value.
    fun setHeaderName(id: Long, name: String) {
        list = list.renamed(id, name)
    }

    fun setHeaderValue(id: Long, value: String) {
        list = list.valued(id, value)
    }

    // Picks the kind of sign-in the sign-in page takes. Another kind starts
    // with empty fields, since they mean other things.
    fun pickGateway(kind: GatewayKind) {
        if (kind != gatewayKind) {
            firstWords = ""
            secondWords = ""
        }
        error = null
        useGatewayForm(kindPicked(gatewayForm, kind))
    }

    // The sign-in page group was brought into view, or the API key's row.
    fun calloutShown() {
        useGatewayForm(app.winters.octo.connection.calloutShown(gatewayForm))
    }

    fun keyShown() {
        useGatewayForm(app.winters.octo.connection.keyShown(gatewayForm))
    }

    // The pointer under Sign in: the group calling out the failure comes
    // into view again.
    fun pointTo() {
        useGatewayForm(app.winters.octo.connection.pointTo(gatewayForm))
    }

    private val gatewayDraft: GatewayDraft
        get() = GatewayDraft(gatewayKind, gatewayFirst, gatewaySecond, gatewaySaved)

    private val gatewayForm: GatewayFormState
        get() = GatewayFormState(advancedOpen, gatewayKind, gateway, gatewayFor, callout, calloutPending, keyPending)

    private fun useGatewayForm(state: GatewayFormState) {
        advancedOpen = state.advancedOpen
        gatewayKind = state.kind
        gateway = state.detected
        gatewayFor = state.detectedFor
        callout = state.callout
        calloutPending = state.calloutPending
        keyPending = state.keyPending
    }

    // The address as a sign-in sends it, which what it met belongs to.
    private val sentAddress: String
        get() = url?.toString() ?: (scheme.prefix + address.trim())

    // An attempt at `attempt` that met a sign-in page opens Advanced at its
    // fields and calls the failure out there, unless the address has changed
    // since. It runs after the failure's words are set.
    private fun met(attempt: String, found: Gateway?) = useGatewayForm(reactToFailure(gatewayForm, attempt, sentAddress, found))

    // Whether the form has what the chosen way of signing in needs.
    val ready: Boolean
        get() = url != null && when (mode) {
            AuthMode.ApiKey -> apiKey.isNotBlank() || keepsKey
            else -> username.isNotBlank() && (password.isNotEmpty() || keepsPassword)
        }

    fun submit() {
        if (busy || !ready) return
        useGatewayForm(sending(gatewayForm))
        // A header row left completely empty is ignored; a half-filled or
        // malformed one is pointed out rather than dropped.
        val unusable = headers.any { row ->
            val name = row.name.trim()
            val value = row.value.trim()
            (name.isNotEmpty() || value.isNotEmpty()) &&
                (!isHeaderName(name) || !isHeaderValue(value) || (value.isEmpty() && !row.saved))
        }
        if (unusable) {
            error = "Each header needs a name with no spaces and a plain text value."
            return
        }
        busy = true
        waiting = false
        error = null
        val request = SignInRequest(
            address = url?.toString() ?: (scheme.prefix + address.trim()),
            username = username,
            secret = if (mode == AuthMode.ApiKey) apiKey.trim() else password,
            authMode = mode,
            home = home,
            headers = headers,
            pins = pins.toMap(),
            clientCertAlias = clientCert,
            label = label.takeIf { form == ServerForm.Add || form == ServerForm.Edit },
            gateway = gatewayDraft,
        )
        launchLink {
            val id = serverId
            val failed = when {
                // A new name alone needs nothing from the server.
                form == ServerForm.Edit && id != null && !connectionChanged(request, started) -> {
                    switcher.onItsOwn { sessions.rename(id, label) }
                    notice = "Saved ${sessions.servers.value.find(id)?.name.orEmpty()}."
                    null
                }
                editing && id != null -> switcher.onItsOwn { sessions.edit(id, request) }.also { if (it == null) notice = savedWords(id) }
                form == ServerForm.Add -> switcher.onItsOwn { sessions.add(request) }.also { failed ->
                    if (failed == null) notice = "Added ${addedName(request)}. Switch to it whenever you like."
                }
                else -> signIns.signIn(request, signsInAgainTo(form, serverId)).let { (failed, words) ->
                    notice = words
                    failed
                }
            }
            when (failed) {
                null -> {
                    // Once in, the secrets are kept only in the sealed vault.
                    password = ""
                    apiKey = ""
                    firstWords = ""
                    secondWords = ""
                    signedIn = true
                }
                is SignInError.Untrusted -> question = failed
                else -> {
                    // A server that cannot check tokens gets the password
                    // itself where that is safe, and the switch shows it.
                    val retry = (failed as? SignInError.Failed)?.cause
                    val url = url
                    when (if (url == null) LegacyRetry.None else legacyRetry(retry, mode, url)) {
                        LegacyRetry.Retry -> {
                            legacyPassword = true
                            busy = false
                            submit()
                            return@launchLink
                        }
                        LegacyRetry.Unsafe -> error = SignInWords.LEGACY_UNSAFE
                        LegacyRetry.None -> error = failed.userMessage()
                    }
                    // A sign-in page in front of the server opens Advanced
                    // at its fields.
                    met(request.address, gatewayOf(failed))
                }
            }
        }
    }

    private fun savedWords(id: String): String {
        // An edit that changed the username keeps the server under a new id.
        val name = sessions.servers.value.find(id)?.name ?: label.trim().ifEmpty { url?.host ?: address }
        return "Saved $name."
    }

    private fun addedName(request: SignInRequest): String =
        serverName(request.label.orEmpty(), normalizeServerUrl(request.address)?.toString() ?: request.address)

    // Trusts the certificate asked about, for its host only, and tries again.
    fun trust() {
        val asked = question ?: return
        useGatewayForm(sending(gatewayForm))
        pins[pinKey(asked.host)] = asked.pin
        question = null
        when {
            handOver != null -> receive()
            joining -> join()
            else -> submit()
        }
    }

    fun distrust() {
        question = null
        error = SignInWords.TRUST_REFUSED
    }

    // The page is gone for good: its request stops, and it leaves the app's
    // set of open sign-ins.
    override fun onCleared() {
        linkJob?.cancel()
        running.mark(this, false)
    }
}

// The connection a join or a hand-over checks with, from the form: its home
// address, headers (the sign-in page's built from its fields), trusted
// certificates and client certificate; or what is wrong with the form.
internal sealed interface FormConnection {
    class Ready(val settings: ConnectionSettings) : FormConnection
    class Problem(val text: String) : FormConnection
}

internal fun formConnection(
    main: HttpUrl,
    home: String,
    rows: List<HeaderDraft>,
    gateway: GatewayDraft,
    pins: Map<String, String>,
    clientCert: String?,
): FormConnection = when (val resolved = resolveConnectionHeaders(rows, gateway, emptyList(), main.isHttps, isPrivateHost(main))) {
    is ResolvedHeaders.Problem -> FormConnection.Problem(resolved.text)
    is ResolvedHeaders.Ready -> FormConnection.Ready(
        ConnectionSettings(
            home = home.trim().takeIf(String::isNotEmpty)?.let(::normalizeServerUrl),
            headers = resolved.headers,
            pins = pins,
            clientCertAlias = clientCert,
        ),
    )
}

// Whether the form changes more than the server's name: the address, the
// sign-in, a secret typed in, or anything under Advanced, the sign-in page
// in front of the server included. With nothing to compare with, it does.
// A saved header whose value holds only spaces is left as it was.
internal fun connectionChanged(request: SignInRequest, was: ConnectionDraft?): Boolean {
    was ?: return true
    return request.address.trim().removeSuffix("/") != was.address ||
        request.username.trim() != was.username ||
        request.secret.isNotEmpty() ||
        request.authMode != was.authMode ||
        request.home.trim().removeSuffix("/") != was.home ||
        request.headers.any { !it.saved || it.value.isNotBlank() } ||
        request.headers.map { it.name.trim().lowercase() }.filter(String::isNotEmpty) != was.headers.map { it.name.trim().lowercase() } ||
        request.pins != was.pins ||
        request.clientCertAlias != was.clientCertAlias ||
        request.gateway.kind != was.gateway ||
        request.gateway.first.isNotEmpty() ||
        request.gateway.second.isNotEmpty()
}

// Whether a form for a kept server can leave the password or key empty to
// keep the saved one: an edit, or a sign-in again to a server not signed
// out of (one whose address must move to https first). A server signed out
// of has none kept to fall back on.
internal fun keepsSavedSecret(form: ServerForm, signedOut: Boolean): Boolean = form != ServerForm.Add && !signedOut

// The kept server a form's sign-in brings up to date: the one a sign-in
// form was opened for; any other sign-in keeps a server of its own.
internal fun signsInAgainTo(form: ServerForm?, id: String?): String? = id.takeIf { form == ServerForm.SignIn }

// The sign-in page a failed sign-in met, if it met one.
internal fun gatewayOf(error: SignInError): Gateway? =
    ((error as? SignInError.Failed)?.cause as? SubsonicException.NotSubsonic)?.gateway
