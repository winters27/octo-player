package app.winters.octo.desktop.pages

import app.winters.octo.subsonic.normalizeServerUrl
import app.winters.octo.ui.family.inviteProblem
import app.winters.octo.subsonic.parseFamilyLink
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.FamilyLink
import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilySignInPrefill
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.connection.GatewayDraft
import app.winters.octo.connection.GatewayFormState
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.addressChanged
import app.winters.octo.connection.gatewayAllowed
import app.winters.octo.connection.reactToFailure
import app.winters.octo.data.HeaderDraft
import app.winters.octo.desktop.server.API_KEY_EXTENSION
import app.winters.octo.desktop.server.CertificateQuestion
import app.winters.octo.desktop.server.SavedGateway
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.Scheme
import app.winters.octo.subsonic.automaticScheme
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.isPrivateHost
import app.winters.octo.subsonic.serverUrl
import app.winters.octo.subsonic.splitScheme
import okhttp3.HttpUrl

// What the sign-in page holds while someone fills it in, as the phone's
// sign-in keeps it: the address in two parts (the scheme, and the rest as
// typed), the sign-in, and the advanced settings, the sign-in page in front
// of the server among them. It starts from the server signed in to last, if
// any. With `keepsSecret` (editing a kept server) the password or key may be
// left empty to keep the saved one.
@Stable
class SignInForm(last: SavedServer? = null, val keepsSecret: Boolean = false) {
    // The name the listener gives the server; empty shows its host.
    var label by mutableStateOf(last?.label.orEmpty())

    // The address without its scheme, as it shows in the field.
    var address by mutableStateOf("")
        private set
    var scheme by mutableStateOf(Scheme.Https)
        private set

    // Picked by hand (or typed, or remembered): no longer follows the address.
    private var schemePicked = false

    var username by mutableStateOf(last?.username.orEmpty())
    var password by mutableStateOf("")
    var rememberPassword by mutableStateOf(last?.rememberSignIn ?: true)

    // The advanced settings, folded away unless something in them is set.
    var advancedOpen by mutableStateOf(
        last != null && (last.authMode != AuthMode.Token || last.home != null || last.headerNames.isNotEmpty()),
    )
    var legacyPassword by mutableStateOf(last?.authMode == AuthMode.LegacyPassword)
    var useApiKey by mutableStateOf(last?.authMode == AuthMode.ApiKey)
    var apiKey by mutableStateOf("")
    var home by mutableStateOf(last?.home?.removeSuffix("/").orEmpty())
    val headers = mutableStateListOf<HeaderDraft>()

    // The sign-in page in front of the server: the kind of sign-in it takes,
    // its two fields (a username and password, or a Client ID and Secret),
    // and the kind the kept server's saved headers make up, kept while the
    // fields stay empty. The saved values never reach the form.
    var gatewayKind by mutableStateOf(GatewayKind.None)
        private set
    var gatewayFirst by mutableStateOf("")
        private set
    var gatewaySecond by mutableStateOf("")
        private set
    var savedGateway by mutableStateOf(GatewayKind.None)
        private set

    // The saved headers that make up the saved kind: shown as the kind,
    // not as rows.
    private var gatewayRows: List<String> = emptyList()

    // The sign-in page a failed try met, and the address it was met at.
    var detected by mutableStateOf<Gateway?>(null)
        private set
    private var detectedFor: String? = null

    var busy by mutableStateOf(false)

    // The last test or sign-in's answer: whether it worked, and what to say.
    var result by mutableStateOf<Pair<Boolean, String>?>(null)

    // A certificate the system does not trust, waiting for an answer.
    var question by mutableStateOf<CertificateQuestion?>(null)

    // A sign-in from another device of one's own, while it comes, and what
    // is happening, in plain words.
    var handOver by mutableStateOf<FamilyHandOverLink?>(null)
    var handOverStep by mutableStateOf<String?>(null)

    // An invite opened (a link or its QR code): the new member's name and
    // the password they choose, twice.
    var invite by mutableStateOf<FamilyInviteLink?>(null)
    var inviteName by mutableStateOf("")
    var invitePassword by mutableStateOf("")
    var inviteAgain by mutableStateOf("")

    // The server's extensions from last time, when it is the one typed.
    private val lastServer = last

    // The kept server the form started from, if any.
    val keptServer: SavedServer? get() = lastServer

    init {
        if (last != null) {
            typeAddress(last.address.removeSuffix("/"))
            headers.addAll(last.headerNames.map { HeaderDraft(it, "", saved = true) })
        }
    }

    // Where focus starts: the password when the server and user are known.
    val startsAtPassword: Boolean get() = lastServer != null && username.isNotBlank() && !useApiKey

    // Takes what was typed or pasted into the address field. A full address
    // sets the scheme and leaves the rest; otherwise the scheme follows the
    // address until one is picked: http at home, https anywhere else.
    fun typeAddress(text: String) {
        val before = attemptAddress
        val (typed, rest) = splitScheme(text)
        if (typed != null) {
            scheme = typed
            schemePicked = true
            address = rest
        } else {
            address = text
            if (!schemePicked) scheme = automaticScheme(text)
        }
        result = null
        movedFrom(before)
    }

    // Switches between https and http by hand.
    fun toggleScheme() {
        val before = attemptAddress
        scheme = if (scheme == Scheme.Https) Scheme.Http else Scheme.Https
        schemePicked = true
        result = null
        movedFrom(before)
    }

    val url: HttpUrl? get() = serverUrl(scheme, address)

    // The address a try goes to, as its result is tagged with.
    val attemptAddress: String get() = url?.toString() ?: (scheme.prefix + address.trim())

    // Plain http to an address outside the home network.
    val insecure: Boolean get() = url?.let { !it.isHttps && !isPrivateHost(it) } == true

    // Whether the server takes API keys: null until known, which is before
    // signing in to it, when a key may still be tried.
    val takesApiKeys: Boolean?
        get() {
            val last = lastServer ?: return null
            if (url?.toString() != last.address) return null
            return last.extensions.any { it.startsWith("$API_KEY_EXTENSION:") }
        }

    val mode: AuthMode
        get() = when {
            useApiKey -> AuthMode.ApiKey
            legacyPassword -> AuthMode.LegacyPassword
            else -> AuthMode.Token
        }

    // Whether the form has what the chosen way of signing in needs.
    val ready: Boolean
        get() = !busy && url != null && when (mode) {
            AuthMode.ApiKey -> apiKey.isNotBlank() || keepsSecret
            else -> username.isNotBlank() && (password.isNotEmpty() || keepsSecret)
        }

    // Whether anything but the name differs from the kept server, so the
    // details need a sign-in test before they are saved. The sign-in page is
    // part of it: its kind, and anything typed in its fields.
    fun changesConnection(server: SavedServer): Boolean =
        url?.toString() != server.address || username.trim() != server.username || mode != server.authMode ||
            home.trim().removeSuffix("/") != server.home?.removeSuffix("/").orEmpty() || password.isNotEmpty() || apiKey.isNotEmpty() ||
            rememberPassword != server.rememberSignIn || headers.any { !it.saved } ||
            (headers.map { it.name } + gatewayRows).sortedBy { it.lowercase() } != server.headerNames.sortedBy { it.lowercase() } ||
            gatewayKind != savedGateway || gatewayFirst.isNotEmpty() || gatewaySecond.isNotEmpty()

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
    // with empty fields.
    fun pickGateway(kind: GatewayKind) {
        if (kind != gatewayKind) {
            gatewayFirst = ""
            gatewaySecond = ""
        }
        gatewayKind = kind
        result = null
    }

    fun typeGatewayFirst(text: String) {
        gatewayFirst = text
        result = null
    }

    fun typeGatewaySecond(text: String) {
        gatewaySecond = text
        result = null
    }

    // Whether the fields show the saved sign-in page, kept while they stay
    // empty.
    val keepsSavedGateway: Boolean get() = savedGateway != GatewayKind.None && gatewayKind == savedGateway

    // How to get through the sign-in page met, or the one picked.
    val gatewayHint: String?
        get() = detected?.let(GatewayWords::hint)
            ?: if (gatewayKind == GatewayKind.CloudflareToken) GatewayWords.hint(Gateway.CloudflareAccess) else null

    // Sign-in page fields over plain http to an address outside the home
    // network: refused when tried, said beforehand.
    val gatewayNeedsHttps: Boolean
        get() = gatewayKind != GatewayKind.None && url?.let { !gatewayAllowed(it.isHttps, isPrivateHost(it)) } == true

    // The sign-in page fields as a try sends them.
    fun gatewayDraft(): GatewayDraft = GatewayDraft(gatewayKind, gatewayFirst, gatewaySecond, savedGateway)

    // The kind of sign-in page the kept server's saved headers make up, read
    // off the window's thread: its rows show as that kind, saved, rather
    // than as headers, and Advanced opens on it. Nothing changes once
    // another address is typed.
    fun knowSavedGateway(server: SavedServer, saved: SavedGateway) {
        if (saved.kind == GatewayKind.None || url?.toString() != server.address) return
        savedGateway = saved.kind
        val moved = headers.filter { row -> row.saved && saved.names.any { it.equals(row.name.trim(), ignoreCase = true) } }
        gatewayRows = moved.map { it.name }
        headers.removeAll(moved)
        if (gatewayKind == GatewayKind.None) gatewayKind = saved.kind
        advancedOpen = true
    }

    // A try at `attempted` failed, meeting `gateway` if any: Advanced opens
    // on the sign-in page fields with the kind it takes picked, and the most
    // telling page met at this address is kept. A result for an address no
    // longer typed changes nothing.
    fun failed(attempted: String, gateway: Gateway?) {
        follow(reactToFailure(gatewayState, attempted, attemptAddress, gateway))
    }

    private val gatewayState: GatewayFormState get() = GatewayFormState(advancedOpen, gatewayKind, detected, detectedFor)

    private fun follow(state: GatewayFormState) {
        advancedOpen = state.advancedOpen
        gatewayKind = state.kind
        detected = state.detected
        detectedFor = state.detectedFor
    }

    // Another address forgets the sign-in page met at the last one.
    private fun movedFrom(before: String) {
        if (attemptAddress != before) follow(addressChanged(gatewayState, attemptAddress))
    }

    // Takes a family link, pasted or opened: an invite to sign up with, a
    // sign-in from another device of one's own, or a server and username
    // to fill in. False for any other text, which a field takes as typed.
    fun takeJoinLink(text: String): Boolean {
        val link = parseFamilyLink(text) ?: return false
        take(link)
        return true
    }

    // A link's home address becomes this account's home address, so the
    // app uses the home network at home and the outside address away. A
    // link to another server than the one filled in leaves that one's
    // headers and sign-in page behind.
    fun take(link: FamilyLink) {
        val before = url?.toString()
        typeAddress(link.server)
        if (url?.toString() != before) forgetServerDetails()
        link.home?.let { home = it }
        invite = null
        handOver = null
        handOverStep = null
        when (link) {
            is FamilyInviteLink -> invite = link
            is FamilyHandOverLink -> handOver = link
            is FamilySignInPrefill -> {
                if (link.username.isNotBlank()) username = link.username
                password = ""
                useApiKey = false
            }
        }
        result = null
    }

    private fun forgetServerDetails() {
        headers.clear()
        gatewayRows = emptyList()
        savedGateway = GatewayKind.None
        gatewayKind = GatewayKind.None
        gatewayFirst = ""
        gatewaySecond = ""
    }

    // Back to the plain sign-in.
    fun leaveLink() {
        invite = null
        handOver = null
        handOverStep = null
        result = null
    }

    // What is missing before signing up from an invite, or null when it can go.
    val inviteProblem: String?
        get() = if (url == null) "Type the server's address" else inviteProblem(inviteName, invitePassword, inviteAgain)

    val inviteReady: Boolean get() = !busy && invite != null && inviteProblem == null

    // The home address typed or from a link, when it is one.
    val homeUrl: HttpUrl? get() = home.trim().takeIf(String::isNotEmpty)?.let(::normalizeServerUrl)

    // Signing in after signing up, with the username the server gave and
    // the password just chosen, remembered like any typed one.
    fun signUpRequest(username: String, password: String): SignInRequest = SignInRequest(
        address = url?.toString() ?: (scheme.prefix + address.trim()),
        username = username,
        secret = password,
        mode = AuthMode.Token,
        home = home,
        headers = headers.toList(),
        rememberPassword = rememberPassword,
        gateway = gatewayDraft(),
    )

    fun request(): SignInRequest = SignInRequest(
        address = url?.toString() ?: (scheme.prefix + address.trim()),
        username = username,
        secret = if (mode == AuthMode.ApiKey) apiKey else password,
        mode = mode,
        home = home,
        headers = headers.toList(),
        rememberPassword = rememberPassword,
        gateway = gatewayDraft(),
    )

    // A way of signing in that worked other than the one asked for (the
    // password itself, for a server that cannot check tokens) shows in the
    // switch, and is the one saved.
    fun worked(used: AuthMode) {
        if (used == AuthMode.LegacyPassword && mode == AuthMode.Token) legacyPassword = true
    }

    fun distrust() {
        question = null
        result = false to "Not connected. The server's certificate wasn't trusted."
    }
}
