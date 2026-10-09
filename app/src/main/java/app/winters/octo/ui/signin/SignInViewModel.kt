package app.winters.octo.ui.signin

import app.winters.octo.ui.family.joinWithInvite
import app.winters.octo.ui.family.inviteProblem
import app.winters.octo.subsonic.parseFamilyLink
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.FamilyLink
import app.winters.octo.connection.fingerprint
import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.data.resolveHeaders
import app.winters.octo.connection.cleanHeaders
import app.winters.octo.connection.ServerClients
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import app.winters.octo.ui.family.joinProblem
import app.winters.octo.ui.family.joinFamily
import app.winters.octo.ui.family.JoinOutcome
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.subsonic.FamilyPair
import app.winters.octo.subsonic.FamilyJoinLink
import app.winters.octo.connection.DeviceIds
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.connection.LEGACY_UNSAFE_MESSAGE
import app.winters.octo.connection.LegacyRetry
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
    // Joining a family with a 6 digit code instead of a password, and the
    // code typed.
    var joining by mutableStateOf(false)
    var code by mutableStateOf("")

    // What pairing answered, kept until signed in: a code works once, so a
    // certificate asked about after pairing does not pair again.
    private var paired: FamilyPair? = null

    // An invite opened: the new member's name and the password they choose,
    // twice. Joining runs the invite's steps, then pairs this phone.
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

    // Whether a secret is saved for each way of signing in, when editing.
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
    }

    // Takes a family link (the https form a QR code carries, or
    // octo://join, scanned, opened or pasted): fills in the address and the
    // code or the invite, and switches to joining. False for any other text.
    fun takeJoinLink(text: String): Boolean {
        val link = parseFamilyLink(text) ?: return false
        startJoin(link)
        return true
    }

    fun startJoin(link: FamilyLink? = null) {
        joining = true
        scanning = false
        error = null
        if (link == null) return
        typeAddress(link.server)
        // A link's home address becomes this account's home address, so the
        // app uses the home network at home and the outside address away.
        link.home?.let { home = it }
        paired = null
        when (link) {
            is FamilyJoinLink -> {
                invite = null
                username = link.username
                code = link.code
            }
            is FamilyInviteLink -> invite = link
        }
    }

    fun stopJoin() {
        joining = false
        invite = null
        error = null
    }

    fun typeCode(text: String) {
        if (takeJoinLink(text)) return
        code = text.filter(Char::isDigit).take(6)
        paired = null
        error = null
    }

    // What is missing before joining, or null when it can go.
    val joinProblem: String?
        get() = if (invite != null) {
            if (url == null) "Type the server's address" else inviteProblem(inviteName, invitePassword, inviteAgain)
        } else {
            joinProblem(url, username, code)
        }

    // Pairs with the family code, then signs in with the secret it answers,
    // as a password nobody types. The server then is the one in use.
    fun join() {
        val url = url
        if (busy || joinProblem != null || url == null) return
        busy = true
        error = null
        viewModelScope.launch {
            // Set up as signing in to the server would be: the certificates
            // trusted for it, its headers and its client certificate.
            val homeUrl = home.trim().takeIf(String::isNotEmpty)?.let(::normalizeServerUrl)
            val (http, security) = clients.forJoin(url, ConnectionSettings(home = homeUrl, headers = cleanHeaders(resolveHeaders(headers.toList(), emptyList())), pins = pins.toMap(), clientCertAlias = clientCert))
            val invited = invite
            val pair = paired ?: when (val joined = withContext(Dispatchers.IO) {
                val name = devices.current().name
                if (invited != null) joinWithInvite(url, invited.token, inviteName, invitePassword, name, FamilyPlatform.Android, http, home = homeUrl)
                else joinFamily(url, username.trim(), code, name, FamilyPlatform.Android, http, home = homeUrl)
            }) {
                is JoinOutcome.Paired -> joined.pair.also { paired = it }
                // A certificate of the server's own is asked about as signing
                // in asks; trusting it joins again.
                is JoinOutcome.Untrusted -> {
                    val leaf = security.takeRejected(joined.host)
                    if (leaf != null) question = SignInError.Untrusted(joined.host, leaf.fingerprint()) else error = "This server's certificate is not trusted."
                    busy = false
                    return@launch
                }
                is JoinOutcome.Failed -> {
                    error = joined.message
                    busy = false
                    return@launch
                }
            }
            val request = SignInRequest(
                address = url.toString(),
                username = pair.username.ifBlank { username.trim() },
                secret = pair.secret,
                authMode = AuthMode.Token,
                home = home,
                headers = headers.toList(),
                pins = pins.toMap(),
                clientCertAlias = clientCert,
            )
            val (failed, words) = switcher.signIn(request)
            when (failed) {
                null -> {
                    notice = words
                    code = ""
                    invitePassword = ""
                    inviteAgain = ""
                    invite = null
                    paired = null
                    signedIn = true
                }
                is SignInError.Untrusted -> question = failed
                else -> error = failed.userMessage()
            }
            busy = false
        }
    }

    // Switches between https and http by hand.
    fun toggleScheme() {
        scheme = if (scheme == Scheme.Https) Scheme.Http else Scheme.Https
        schemePicked = true
        error = null
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
        if (form == ServerForm.SignIn) {
            // Signed out, so no password or key is kept to fall back on.
            savedPassword = false
            savedKey = false
        }
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
        headers.clear()
        headers.addAll(draft.headers)
        clientCert = draft.clientCertAlias
        pins.putAll(draft.pins)
        takesApiKeys = draft.takesApiKeys
        savedKey = useApiKey
        savedPassword = !useApiKey
        advancedOpen = draft.authMode != AuthMode.Token || draft.home.isNotEmpty() ||
            draft.headers.isNotEmpty() || draft.clientCertAlias != null
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

    private val mode: AuthMode
        get() = when {
            useApiKey -> AuthMode.ApiKey
            legacyPassword -> AuthMode.LegacyPassword
            else -> AuthMode.Token
        }

    // Whether the form has what the chosen way of signing in needs.
    val ready: Boolean
        get() = url != null && when (mode) {
            AuthMode.ApiKey -> apiKey.isNotBlank() || (editing && savedKey)
            else -> username.isNotBlank() && (password.isNotEmpty() || (editing && savedPassword))
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
        )
        viewModelScope.launch {
            val id = serverId
            val failed = when {
                // A new name alone needs nothing from the server.
                form == ServerForm.Edit && id != null && !changesConnection(request) -> {
                    switcher.onItsOwn { sessions.rename(id, label) }
                    notice = "Saved ${sessions.servers.value.find(id)?.name.orEmpty()}."
                    null
                }
                editing && id != null -> switcher.onItsOwn { sessions.edit(id, request) }.also { if (it == null) notice = savedWords(id) }
                form == ServerForm.Add -> switcher.onItsOwn { sessions.add(request) }.also { failed ->
                    if (failed == null) notice = "Added ${addedName(request)}. Switch to it whenever you like."
                }
                else -> switcher.signIn(request).let { (failed, words) ->
                    notice = words
                    failed
                }
            }
            when (failed) {
                null -> {
                    // Once in, the secrets are kept only in the sealed vault.
                    password = ""
                    apiKey = ""
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
                            return@launch
                        }
                        LegacyRetry.Unsafe -> error = LEGACY_UNSAFE_MESSAGE
                        LegacyRetry.None -> error = failed.userMessage()
                    }
                }
            }
            busy = false
        }
    }

    // Whether the form changes more than the name: the address, the sign-in,
    // a secret typed in, or anything under Advanced.
    private fun changesConnection(request: SignInRequest): Boolean {
        val was = started ?: return true
        return request.address.trim().removeSuffix("/") != was.address ||
            request.username.trim() != was.username ||
            request.secret.isNotEmpty() ||
            request.authMode != was.authMode ||
            request.home.trim().removeSuffix("/") != was.home ||
            request.headers.any { !it.saved || it.value.isNotEmpty() } ||
            request.headers.map { it.name.trim().lowercase() }.filter(String::isNotEmpty) != was.headers.map { it.name.trim().lowercase() } ||
            request.pins != was.pins ||
            request.clientCertAlias != was.clientCertAlias
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
        if (joining) join() else submit()
    }

    fun distrust() {
        question = null
        error = "Not connected. The server's certificate wasn't trusted."
    }
}
