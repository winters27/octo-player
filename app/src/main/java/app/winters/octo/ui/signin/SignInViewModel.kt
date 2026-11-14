package app.winters.octo.ui.signin

import app.winters.octo.ui.family.joinWithInvite
import app.winters.octo.ui.family.inviteProblem
import app.winters.octo.subsonic.parseFamilyLink
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.FamilyLink
import app.winters.octo.connection.fingerprint
import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.GatewayDraft
import app.winters.octo.connection.GatewayFormState
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.ResolvedHeaders
import app.winters.octo.connection.addressChanged
import app.winters.octo.connection.reactToFailure
import app.winters.octo.connection.resolveConnectionHeaders
import app.winters.octo.connection.ServerClients
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import javax.inject.Inject

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val switcher: ServerSwitch,
    private val clients: ServerClients,
    private val devices: DeviceIds,
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

    // Signed up from an invite, kept until signed in: an invite works once,
    // so a certificate asked about afterwards does not sign up again.
    private var signedUp: JoinOutcome.SignedUp? = null

    // A sign-in received from another device, kept until signed in with: a
    // hand-over works once, so a certificate asked about afterwards does not
    // redeem it again.
    private var received: HandOverSignIn? = null

    // An invite opened: the new member's name and the password they choose,
    // twice. Signing up runs the invite's steps, then signs this phone in.
    var invite by mutableStateOf<FamilyInviteLink?>(null)
        private set
    var inviteName by mutableStateOf("")
    var invitePassword by mutableStateOf("")
    var inviteAgain by mutableStateOf("")

    // The camera scanner, over the join form.
    var scanning by mutableStateOf(false)

    // The address in two parts, as the desktop keeps it: the scheme, shown
    // as a button at the start of the field, and the rest as typed.
    var address by mutableStateOf("")
        private set
    var scheme by mutableStateOf(Scheme.Https)
        private set

    // Picked by hand (or typed, or saved): no longer follows the address.
    private var schemePicked = false
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

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
    var home by mutableStateOf("")
    val headers = mutableStateListOf<HeaderDraft>()

    // The sign-in page in front of the server: the kind of sign-in it takes
    // from apps, what is typed for it, and the kind saved for this server,
    // whose values are kept while its fields are left empty.
    var gatewayKind by mutableStateOf(GatewayKind.None)
        private set
    var gatewayFirst by mutableStateOf("")
    var gatewaySecond by mutableStateOf("")
    var gatewaySaved by mutableStateOf(GatewayKind.None)
        private set

    // The sign-in page the last failed attempt met, and the address it met
    // it at.
    var gateway by mutableStateOf<Gateway?>(null)
        private set
    private var gatewayFor: String? = null

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

    // Whether a saved secret stands in for each way of signing in when its
    // field is left empty: when editing, or signing in again to a kept
    // server not signed out of.
    private var savedPassword = false
    private var savedKey = false

    // The server's address, or null while what is typed isn't one.
    val url: HttpUrl? by derivedStateOf { serverUrl(scheme, address) }

    // Plain http to an address outside the home network.
    val insecure by derivedStateOf { url?.let { !it.isHttps && !isPrivateHost(it) } == true }

    // Takes what was typed or pasted into the address field. A full address
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
    // link fills in the server and username for the password. False for any
    // other text.
    fun takeJoinLink(text: String): Boolean {
        val link = parseFamilyLink(text) ?: return false
        startJoin(link)
        return true
    }

    fun startJoin(link: FamilyLink? = null) {
        scanning = false
        error = null
        if (link == null) {
            joining = true
            return
        }
        typeAddress(link.server)
        // A link's home address becomes this account's home address, so the
        // app uses the home network at home and the outside address away.
        link.home?.let { home = it }
        signedUp = null
        received = null
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

    fun stopJoin() {
        joining = false
        invite = null
        handOver = null
        handOverStep = null
        received = null
        error = null
    }

    // What is missing before signing up, or null when it can go.
    val joinProblem: String?
        get() = when {
            invite == null -> "Scan or open your invite first"
            url == null -> "Type the server's address"
            else -> inviteProblem(inviteName, invitePassword, inviteAgain)
        }

    // Takes the sign-in another device of one's own hands over: redeems the
    // token from its link, waits for that device to allow it, opens what it
    // sends with the key from the link, and signs in with it like a typed one.
    // The form's sign-in page fields, headers and certificates go with it,
    // so a server behind a sign-in page or with a certificate of its own is
    // reached; a sign-in page that turns it away opens Advanced at its fields.
    fun receive() {
        val link = handOver ?: return
        if (busy) return
        val base = link.base.toHttpUrlOrNull() ?: return
        val connection = when (val form = formConnection(base, home, headers.toList(), gatewayDraft, pins.toMap(), clientCert)) {
            is FormConnection.Ready -> form.settings
            is FormConnection.Problem -> {
                error = form.text
                return
            }
        }
        val attempt = sentAddress
        busy = true
        error = null
        viewModelScope.launch {
            val signIn = received ?: run {
                val (http, security) = clients.forJoin(base, connection)
                // Its own client, let go once the hand-over has answered.
                val got = try {
                    withContext(Dispatchers.IO) {
                        receiveHandOver(link, http, devices.current().name, FamilyPlatform.Android, step = { handOverStep = it })
                    }
                } finally {
                    http.letGo()
                }
                if (got is HandOverResult.Received) return@run got.signIn.also { received = it }
                val refused = got as HandOverResult.Refused
                handOverStep = null
                // A certificate the phone does not trust is asked about, as
                // signing in asks; trusting it tries again.
                val leaf = security.takeRejected(base.host)
                if (leaf != null) {
                    question = SignInError.Untrusted(base.host, leaf.fingerprint())
                } else {
                    error = refused.gateway?.let { GatewayWords.failure(it, refused.loginSent) } ?: refused.message
                    met(attempt, refused.gateway)
                }
                busy = false
                return@launch
            }
            val request = SignInRequest(
                address = signIn.server,
                username = signIn.username,
                secret = signIn.secret,
                authMode = runCatching { AuthMode.valueOf(signIn.mode) }.getOrDefault(AuthMode.Token),
                home = signIn.home.orEmpty(),
                headers = headers.toList(),
                pins = pins.toMap(),
                clientCertAlias = clientCert,
                gateway = gatewayDraft,
            )
            val (failed, words) = switcher.signIn(request)
            when (failed) {
                null -> {
                    handOverStep = HANDOVER_SIGNED_IN
                    notice = words
                    handOver = null
                    received = null
                    signedIn = true
                }
                is SignInError.Untrusted -> question = failed
                else -> {
                    handOverStep = null
                    error = failed.userMessage()
                    met(attempt, gatewayOf(failed))
                }
            }
            busy = false
        }
    }

    // Signs up from the invite: the name and password chosen go to the
    // family page, then this phone signs in with that username and password
    // like any typed sign-in. The server then is the one in use.
    fun join() {
        val url = url
        if (busy || joinProblem != null || url == null) return
        // Set up as signing in to the server would be: the certificates
        // trusted for it, its headers, its sign-in page fields and its client
        // certificate.
        val connection = when (val form = formConnection(url, home, headers.toList(), gatewayDraft, pins.toMap(), clientCert)) {
            is FormConnection.Ready -> form.settings
            is FormConnection.Problem -> {
                error = form.text
                return
            }
        }
        val attempt = sentAddress
        busy = true
        error = null
        viewModelScope.launch {
            val invited = invite ?: return@launch
            // Its own client, let go once the family page has answered; its
            // security still holds a certificate it refused, to ask about.
            val (http, security) = clients.forJoin(url, connection)
            val joined = try {
                signedUp ?: withContext(Dispatchers.IO) {
                    joinWithInvite(url, invited.token, inviteName, invitePassword, http, home = connection.home)
                }
            } finally {
                http.letGo()
            }
            val account = when (joined) {
                is JoinOutcome.SignedUp -> joined.also { signedUp = it }
                // A certificate of the server's own is asked about as signing
                // in asks; trusting it signs up again.
                is JoinOutcome.Untrusted -> {
                    val leaf = security.takeRejected(joined.host)
                    if (leaf != null) question = SignInError.Untrusted(joined.host, leaf.fingerprint()) else error = "This server's certificate is not trusted."
                    busy = false
                    return@launch
                }
                // A sign-in page that turns the invite away opens Advanced at
                // its fields.
                is JoinOutcome.Failed -> {
                    error = joined.gateway?.let { GatewayWords.failure(it, joined.loginSent) } ?: joined.message
                    met(attempt, joined.gateway)
                    busy = false
                    return@launch
                }
            }
            val request = SignInRequest(
                address = url.toString(),
                username = account.username,
                secret = account.password,
                authMode = AuthMode.Token,
                home = home,
                headers = headers.toList(),
                pins = pins.toMap(),
                clientCertAlias = clientCert,
                gateway = gatewayDraft,
            )
            val (failed, words) = switcher.signIn(request)
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
            busy = false
        }
    }

    // Switches between https and http by hand.
    fun toggleScheme() {
        scheme = if (scheme == Scheme.Https) Scheme.Http else Scheme.Https
        schemePicked = true
        error = null
        useGatewayForm(addressChanged(gatewayForm, sentAddress))
    }

    // Starts from the saved connection of the server in use. Nothing secret
    // is filled in.
    fun startEditing() {
        if (editing) return
        serverId = sessions.servers.value.active ?: return
        editing = true
        fill(sessions.connectionDraft(serverId) ?: return)
    }

    // Opened from the list of servers: a new one to keep, a kept one to
    // edit, or a kept one to sign in to again (its password asked for).
    fun start(form: ServerForm, id: String?, note: String?) {
        if (this.form != null) return
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
        if (!keepsSavedSecret(form, kept?.signedOut != false)) {
            savedPassword = false
            savedKey = false
        }
    }

    // Whether the password or key may be left empty to keep the saved one.
    val keepsPassword: Boolean get() = savedPassword
    val keepsKey: Boolean get() = savedKey

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
        headers.clear()
        headers.addAll(draft.headers)
        clientCert = draft.clientCertAlias
        pins.putAll(draft.pins)
        takesApiKeys = draft.takesApiKeys
        savedKey = useApiKey
        savedPassword = !useApiKey
        // The saved sign-in page's kind; its values stay sealed and are kept
        // while its fields are left empty.
        gatewaySaved = draft.gateway
        gatewayKind = draft.gateway
        gatewayFirst = ""
        gatewaySecond = ""
        advancedOpen = draft.authMode != AuthMode.Token || draft.home.isNotEmpty() ||
            draft.headers.isNotEmpty() || draft.clientCertAlias != null || draft.gateway != GatewayKind.None
    }

    fun addHeader() {
        headers.add(HeaderDraft("", ""))
    }

    fun removeHeader(index: Int) {
        if (index in headers.indices) headers.removeAt(index)
    }

    // A renamed saved header no longer keeps its saved value.
    fun setHeaderName(index: Int, name: String) {
        val old = headers.getOrNull(index) ?: return
        headers[index] = old.copy(name = name, saved = old.saved && name.trim().equals(old.name.trim(), ignoreCase = true))
    }

    fun setHeaderValue(index: Int, value: String) {
        val old = headers.getOrNull(index) ?: return
        headers[index] = old.copy(value = value)
    }

    // Picks the kind of sign-in the sign-in page takes. Another kind starts
    // with empty fields, since they mean other things.
    fun pickGateway(kind: GatewayKind) {
        if (kind != gatewayKind) {
            gatewayFirst = ""
            gatewaySecond = ""
        }
        gatewayKind = kind
        error = null
    }

    private val gatewayDraft: GatewayDraft
        get() = GatewayDraft(gatewayKind, gatewayFirst, gatewaySecond, gatewaySaved)

    private val gatewayForm: GatewayFormState
        get() = GatewayFormState(advancedOpen, gatewayKind, gateway, gatewayFor)

    private fun useGatewayForm(state: GatewayFormState) {
        advancedOpen = state.advancedOpen
        gatewayKind = state.kind
        gateway = state.detected
        gatewayFor = state.detectedFor
    }

    // The address as a sign-in sends it, which what it met belongs to.
    private val sentAddress: String
        get() = url?.toString() ?: (scheme.prefix + address.trim())

    // An attempt at `attempt` that met a sign-in page opens Advanced at its
    // fields, unless the address has changed since.
    private fun met(attempt: String, found: Gateway?) = useGatewayForm(reactToFailure(gatewayForm, attempt, sentAddress, found))

    private val mode: AuthMode
        get() = when {
            useApiKey -> AuthMode.ApiKey
            legacyPassword -> AuthMode.LegacyPassword
            else -> AuthMode.Token
        }

    // Whether the form has what the chosen way of signing in needs.
    val ready: Boolean
        get() = url != null && when (mode) {
            AuthMode.ApiKey -> apiKey.isNotBlank() || savedKey
            else -> username.isNotBlank() && (password.isNotEmpty() || savedPassword)
        }

    fun submit() {
        if (busy || !ready) return
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
        error = null
        val request = SignInRequest(
            address = url?.toString() ?: (scheme.prefix + address.trim()),
            username = username,
            secret = if (mode == AuthMode.ApiKey) apiKey.trim() else password,
            authMode = mode,
            home = home,
            headers = headers.toList(),
            pins = pins.toMap(),
            clientCertAlias = clientCert,
            label = label.takeIf { form == ServerForm.Add || form == ServerForm.Edit },
            gateway = gatewayDraft,
        )
        viewModelScope.launch {
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
                else -> switcher.signIn(request, signsInAgainTo(form, serverId)).let { (failed, words) ->
                    notice = words
                    failed
                }
            }
            when (failed) {
                null -> {
                    // Once in, the secrets are kept only in the sealed vault.
                    password = ""
                    apiKey = ""
                    gatewayFirst = ""
                    gatewaySecond = ""
                    signedIn = true
                }
                is SignInError.Untrusted -> question = failed
                else -> {
                    // A sign-in page in front of the server opens Advanced
                    // at its fields.
                    met(request.address, gatewayOf(failed))
                    // A server that cannot check tokens gets the password
                    // itself where that is safe, and the switch shows it.
                    val retry = (failed as? SignInError.Failed)?.cause
                    val url = url
                    when (if (url == null) LegacyRetry.None else legacyRetry(retry, mode, url)) {
                        LegacyRetry.Retry -> {
                            legacyPassword = true
                            busy = false
                            submit()
                            return@launch
                        }
                        LegacyRetry.Unsafe -> error = SignInWords.LEGACY_UNSAFE
                        LegacyRetry.None -> error = failed.userMessage()
                    }
                }
            }
            busy = false
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
        pins[pinKey(asked.host)] = asked.fingerprint
        question = null
        when {
            handOver != null -> receive()
            joining -> join()
            else -> submit()
        }
    }

    fun distrust() {
        question = null
        error = "Not connected. The server's certificate wasn't trusted."
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
internal fun connectionChanged(request: SignInRequest, was: ConnectionDraft?): Boolean {
    was ?: return true
    return request.address.trim().removeSuffix("/") != was.address ||
        request.username.trim() != was.username ||
        request.secret.isNotEmpty() ||
        request.authMode != was.authMode ||
        request.home.trim().removeSuffix("/") != was.home ||
        request.headers.any { !it.saved || it.value.isNotEmpty() } ||
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
