package app.winters.octo.desktop.pages

import app.winters.octo.subsonic.normalizeServerUrl
import app.winters.octo.ui.family.HandOverSignIn
import app.winters.octo.ui.family.inviteProblem
import app.winters.octo.subsonic.parseFamilyLink
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.FamilyLink
import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilySignInPrefill
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import app.winters.octo.connection.AdvancedLayout
import app.winters.octo.connection.GatewayDraft
import app.winters.octo.connection.GatewayFormState
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.HeaderList
import app.winters.octo.connection.LinkAction
import app.winters.octo.connection.LinkAnchor
import app.winters.octo.connection.LinkForm
import app.winters.octo.connection.LinkFormState
import app.winters.octo.connection.LinkRefusal
import app.winters.octo.connection.LinkTake
import app.winters.octo.connection.ReceivedAction
import app.winters.octo.connection.SignInWords
import app.winters.octo.connection.addressChanged
import app.winters.octo.connection.linkFacts
import app.winters.octo.connection.receivedFacts
import app.winters.octo.connection.advancedLayout
import app.winters.octo.connection.authModeOf
import app.winters.octo.connection.flagsOf
import app.winters.octo.connection.gatewayAllowed
import app.winters.octo.connection.gatewayEdited
import app.winters.octo.connection.kindPicked
import app.winters.octo.connection.methodPicked
import app.winters.octo.connection.reactToFailure
import app.winters.octo.data.HeaderDraft
import app.winters.octo.connection.calloutShown as calloutShownRule
import app.winters.octo.connection.calm as calmRule
import app.winters.octo.connection.keepsKey as keepsKeyRule
import app.winters.octo.connection.keepsPassword as keepsPasswordRule
import app.winters.octo.connection.keyShown as keyShownRule
import app.winters.octo.connection.pointTo as pointToRule
import app.winters.octo.connection.sending as sendingRule
import app.winters.octo.desktop.server.API_KEY_EXTENSION
import app.winters.octo.desktop.server.CertificateChoice
import app.winters.octo.desktop.server.CertificateDraft
import app.winters.octo.desktop.server.CertificateLoad
import app.winters.octo.desktop.server.CertificateQuestion
import app.winters.octo.desktop.server.CertificateTry
import app.winters.octo.desktop.server.PickedCertificate
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
import kotlinx.coroutines.Job
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// What the sign-in page holds while someone fills it in, as the phone's
// sign-in keeps it: the address in two parts (the scheme, and the rest as
// typed), the sign-in, and the advanced settings, the sign-in page in front
// of the server among them. It starts from the server signed in to last, if
// any. With `keepsSecret` (editing a kept server) the password or key may be
// left empty to keep the saved one. The client certificate starts as that of
// `certificateOf`: the server the form started from, unless another is named.
@Stable
class SignInForm(last: SavedServer? = null, val keepsSecret: Boolean = false, certificateOf: String? = last?.id) {
    // Where family links may take this form (see LinkFormState): the saved
    // server it was filled from, what was typed and taken since, and the
    // words of the last link refused here.
    private var link by mutableStateOf(LinkFormState(LinkForm.Fresh))

    // The server this form was built from (or whose certificate it starts
    // with), until a link to another server starts it over; a late fill for
    // any other server changes nothing.
    var filledFrom: String? = certificateOf
        private set

    // How that server signs in, for which saved secret may stand in.
    private var lastMode: AuthMode? = last?.authMode

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
    private var homeText by mutableStateOf(last?.home?.removeSuffix("/").orEmpty())
    var home: String
        get() = homeText
        set(value) {
            if (value == homeText) return
            homeText = value
            edited()
        }

    // The custom header rows, each with an id kept beside it, so they draw
    // keyed; every write goes through one of HeaderList's ops.
    var headerList by mutableStateOf(HeaderList.empty)
        private set
    val headers: List<HeaderDraft> get() = headerList.rows
    val headerIds: List<Long> get() = headerList.ids

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
    // Every write calms the sign-in page group; a failure that met one calls
    // it out again just after its words are written (failed).
    private var said by mutableStateOf<Pair<Boolean, String>?>(null)
    var result: Pair<Boolean, String>?
        get() = said
        set(value) {
            said = value
            follow(calmRule(gatewayState))
        }

    // The sign-in page group calls the last failure out (`callout`) and
    // still has to be brought into view (`calloutPending`); the API key
    // row, just asked for, still has to be brought into view (`keyPending`).
    var callout by mutableStateOf(false)
        private set
    var calloutPending by mutableStateOf(false)
        private set
    var keyPending by mutableStateOf(false)
        private set

    // A certificate the system does not trust, waiting for an answer.
    var question by mutableStateOf<CertificateQuestion?>(null)

    // The client certificate: what the form started with, what a sign-in
    // sends now (see CertificateDraft), and what reading the one the row
    // shows found, and for which.
    private var startCertificate: CertificateDraft = certificateOf?.let { CertificateDraft.Keep(it) } ?: CertificateDraft.None
    var certificateDraft by mutableStateOf(startCertificate)
        private set
    var certificateLoad by mutableStateOf<CertificateTry?>(null)

    // Brings the Special setups group into view once Advanced shows it.
    var certificatePending by mutableStateOf(false)

    // A file chosen on the row while it opens, and why one wasn't taken.
    var certificateOpening by mutableStateOf(false)
    var certificateProblem by mutableStateOf<String?>(null)

    // The latest file opening, so an older one's answer is let go.
    internal var certificateTurn: Any? = null

    // The row's Choose or Change, which has the keyboard again once the
    // password asked for a chosen file is given or not.
    internal val certificateButton = FocusRequester()

    // Takes a file opened on the row.
    fun useCertificate(picked: PickedCertificate) {
        certificateDraft = CertificateDraft.Use(picked.path, picked.password)
        certificateLoad = CertificateTry(CertificateChoice.Picked(picked.path, picked.password), CertificateLoad.Found(picked.loaded))
        certificateProblem = null
        result = null
    }

    // Takes off the certificate the row shows: a kept server's comes off
    // that server when the sign-in keeps it, and a file just chosen goes
    // back to the form's first certificate taken off, or to none.
    fun removeCertificate() {
        certificateDraft = when (val now = certificateDraft) {
            is CertificateDraft.Keep -> CertificateDraft.Remove(now.serverId)
            is CertificateDraft.Use -> (startCertificate as? CertificateDraft.Keep)?.let { CertificateDraft.Remove(it.serverId) } ?: CertificateDraft.None
            else -> now
        }
        certificateLoad = null
        certificateProblem = null
        result = null
    }

    // Opens Advanced on the certificate: a try that found it unreadable, a
    // server asking for one or turning it down, or a form opened for it.
    fun askForCertificate() {
        advancedOpen = true
        certificatePending = true
    }

    // What a finished try found for the certificate it sent, which the row
    // then shows.
    fun tried(attempt: CertificateTry?) {
        if (attempt?.load != null) certificateLoad = attempt
    }

    // What reading the certificate the row shows found, landing after the
    // read. A read of a kept server's certificate begun before a link to
    // another server started the form over changes nothing.
    fun knowCertificate(choice: CertificateChoice, load: CertificateLoad?) {
        if (choice is CertificateChoice.Saved && filledFrom != choice.serverId) return
        if (certificateLoad?.choice != choice) certificateLoad = CertificateTry(choice, load)
    }

    // A hand-over's sealed sign-in, once redeemed, and a finished sign-up
    // with the main address it was made at: each works only once, so Try
    // again and Trust reuse them. Leaving the link, a sealed sign-in refused
    // here, or signing in ends them; a link never does (see take).
    var received by mutableStateOf<HandOverSignIn?>(null)
    var signedUp by mutableStateOf<HeldSignUp?>(null)

    // While a sign-up is held, its address stays the one it was made at.
    val addressLocked: Boolean get() = signedUp != null

    // A hand-over's redeem and its polls, before the other device allows it:
    // leaving then is harmless and cancels it. Every other request commits,
    // and nothing leaves it until it ends.
    var waiting by mutableStateOf(false)
    val committing: Boolean get() = busy && !waiting

    // The request running now: a way out while it waits cancels it, and a
    // cancelled request's late end never touches a newer one's flags.
    var running: Job? = null

    // The words of the last link refused here, under the result line.
    val linkNote: String? get() = link.note

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

    // The kept server this form signs in to again, when it was filled in
    // for one (AppState.fillSignIn): signing in then brings that server up
    // to date rather than keeping another.
    var keptId: String? = null

    // The kept server a sign-in brings up to date: the one the form was
    // filled in for, while its host is the one typed and, unless an API key
    // says whose it is, its username too. Anything else is signed in to as a
    // server of its own, never in place of the kept one; an API key of
    // another account is kept beside it (Accounts.signInAgain, sameAccount).
    val signsInAgainTo: String?
        get() {
            val kept = lastServer?.takeIf { it.id == keptId } ?: return null
            if (url?.host != keptHost) return null
            return kept.id.takeIf { useApiKey || username.trim() == kept.username }
        }

    // The host of the server the form started from.
    private val keptHost: String? = last?.address?.toHttpUrlOrNull()?.host

    init {
        if (last != null) {
            typeAddress(last.address.removeSuffix("/"))
            headerList = headerList.replaced(last.headerNames.map { HeaderDraft(it, "", saved = true) })
            link = link.filled(LinkAnchor(last.address, last.home, last.username))
        }
    }

    // Where focus starts: the secret (the password, or the API key) when the
    // server and who signs in are known.
    val startsAtPassword: Boolean get() = lastServer != null && (useApiKey || username.isNotBlank())

    // Takes what was typed or pasted into the address field. A full address
    // sets the scheme and leaves the rest; otherwise the scheme follows the
    // address until one is picked: http at home, https anywhere else.
    fun typeAddress(text: String) {
        if (addressLocked) return
        val before = attemptAddress
        val was = scheme to address
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
        if (scheme to address != was) edited()
    }

    // Switches between https and http by hand.
    fun toggleScheme() {
        if (addressLocked) return
        val before = attemptAddress
        scheme = if (scheme == Scheme.Https) Scheme.Http else Scheme.Https
        schemePicked = true
        result = null
        movedFrom(before)
        edited()
    }

    // The address, its scheme or the home changed: the form is no longer the
    // saved server's, and a link is compared with what was typed.
    private fun edited() {
        link = link.edited(url?.toString(), homeUrl?.toString(), address.isNotBlank())
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

    val mode: AuthMode get() = authModeOf(legacyPassword, useApiKey)

    // How the kept server signs in, for which saved secret may stand in.
    private val savedMode: AuthMode? get() = lastMode

    // Whether the password, or the key, may stay empty and keep the saved
    // one: only a saved password for a password Method, and a saved key for
    // API key.
    val keepsPassword: Boolean get() = keepsPasswordRule(keepsSecret, savedMode, mode)
    val keepsKey: Boolean get() = keepsKeyRule(keepsSecret, savedMode, mode)

    // Whether the form has what the chosen way of signing in needs.
    val ready: Boolean
        get() = !busy && url != null && when (mode) {
            AuthMode.ApiKey -> apiKey.isNotBlank() || keepsKey
            else -> username.isNotBlank() && (password.isNotEmpty() || keepsPassword)
        }

    // Advanced's groups and rows, and the Methods its Method row offers.
    val layout: AdvancedLayout
        get() = advancedLayout(
            joining = invite != null || handOver != null,
            takesApiKeys = takesApiKeys,
            apiKeyOn = useApiKey,
            kind = gatewayKind,
            certificateRow = true,
        )

    // Picks how to sign in. API key with no key kept asks for the key row to
    // be brought into view.
    fun pickMethod(picked: AuthMode) {
        val old = mode
        val (legacy, key) = flagsOf(picked)
        legacyPassword = legacy
        useApiKey = key
        follow(methodPicked(gatewayState, old, picked, keepsKeyRule(keepsSecret, savedMode, picked)))
        result = null
    }

    // Whether anything but the name differs from the kept server, so the
    // details need a sign-in test before they are saved. The sign-in page is
    // part of it: its kind, and anything typed in its fields.
    fun changesConnection(server: SavedServer): Boolean =
        url?.toString() != server.address || username.trim() != server.username || mode != server.authMode ||
            home.trim().removeSuffix("/") != server.home?.removeSuffix("/").orEmpty() || password.isNotEmpty() || apiKey.isNotEmpty() ||
            rememberPassword != server.rememberSignIn || headers.any { !it.saved || it.value.isNotBlank() } ||
            (headers.map { it.name } + gatewayRows).sortedBy { it.lowercase() } != server.headerNames.sortedBy { it.lowercase() } ||
            gatewayKind != savedGateway || gatewayFirst.isNotEmpty() || gatewaySecond.isNotEmpty() ||
            certificateDraft is CertificateDraft.Use || certificateDraft is CertificateDraft.Remove

    // A blank header row at the end; its id, for the row to take focus.
    fun addHeader(): Long {
        val (next, id) = headerList.added()
        headerList = next
        return id
    }

    fun removeHeader(id: Long) {
        headerList = headerList.remove(id)
        result = null
    }

    // A renamed saved header no longer keeps its saved value.
    fun setHeaderName(id: Long, name: String) {
        headerList = headerList.renamed(id, name)
        result = null
    }

    fun setHeaderValue(id: Long, value: String) {
        headerList = headerList.valued(id, value)
        result = null
    }

    // Picks the kind of sign-in the sign-in page takes. Another kind starts
    // with empty fields.
    fun pickGateway(kind: GatewayKind) {
        if (kind != gatewayKind) {
            gatewayFirst = ""
            gatewaySecond = ""
        }
        follow(kindPicked(gatewayState, kind))
        result = null
    }

    fun typeGatewayFirst(text: String) {
        gatewayFirst = text
        follow(gatewayEdited(gatewayState))
        result = null
    }

    fun typeGatewaySecond(text: String) {
        gatewaySecond = text
        follow(gatewayEdited(gatewayState))
        result = null
    }

    // Whether the fields show the saved sign-in page, kept while they stay
    // empty.
    val keepsSavedGateway: Boolean get() = savedGateway != GatewayKind.None && gatewayKind == savedGateway

    // How to get through the sign-in page met, or the one picked.
    val gatewayHint: String?
        get() = detected?.let(GatewayWords::hint) ?: GatewayWords.tip(gatewayKind)

    // Sign-in page fields over plain http to an address outside the home
    // network: refused when tried, said beforehand.
    val gatewayNeedsHttps: Boolean
        get() = gatewayKind != GatewayKind.None && url?.let { !gatewayAllowed(it.isHttps, isPrivateHost(it)) } == true

    // The sign-in page fields as a try sends them.
    fun gatewayDraft(): GatewayDraft = GatewayDraft(gatewayKind, gatewayFirst, gatewaySecond, savedGateway)

    // The kind of sign-in page the kept server's saved headers make up, read
    // off the window's thread: its rows show as that kind, saved, rather
    // than as headers, and Advanced opens on it. Nothing changes once
    // another address is typed, nor when the person touched one of those
    // rows (typed in it, removed it, or added a row of that name), since
    // the answer may land while they type; every other row keeps its id.
    fun knowSavedGateway(server: SavedServer, saved: SavedGateway) {
        if (filledFrom != server.id || saved.kind == GatewayKind.None || url?.toString() != server.address) return
        fun named(row: HeaderDraft) = saved.names.any { it.equals(row.name.trim(), ignoreCase = true) }
        val untouched = saved.names.all { name -> headers.any { it.saved && it.value.isEmpty() && it.name.trim().equals(name, ignoreCase = true) } } &&
            headers.none { !it.saved && named(it) }
        if (!untouched) return
        savedGateway = saved.kind
        gatewayRows = headers.filter { it.saved && named(it) }.map { it.name }
        headerList = headerList.kept { !(it.saved && named(it)) }
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

    private val gatewayState: GatewayFormState
        get() = GatewayFormState(advancedOpen, gatewayKind, detected, detectedFor, callout, calloutPending, keyPending)

    private fun follow(state: GatewayFormState) {
        advancedOpen = state.advancedOpen
        gatewayKind = state.kind
        detected = state.detected
        detectedFor = state.detectedFor
        callout = state.callout
        calloutPending = state.calloutPending
        keyPending = state.keyPending
    }

    // An attempt starts: nothing from the last one is called out or waits to
    // be brought into view.
    fun sending() = follow(sendingRule(gatewayState))

    // The group was brought into view.
    fun calloutShown() = follow(calloutShownRule(gatewayState))

    // The pointer under the buttons: the group is brought into view again.
    fun pointTo() = follow(pointToRule(gatewayState))

    // The key row was brought into view.
    fun keyShown() = follow(keyShownRule(gatewayState))

    // The sign-in page group shows the failure as its error note, ringed,
    // with the pointer in the result line's place.
    val showsCallout: Boolean get() = callout && advancedOpen && result?.first == false

    // Another address forgets the sign-in page met at the last one.
    private fun movedFrom(before: String) {
        if (attemptAddress != before) follow(addressChanged(gatewayState, attemptAddress))
    }

    // Takes a family link, pasted or opened: an invite to sign up with, a
    // sign-in from another device of one's own, or a server and username
    // to fill in. False for any other text, which a field takes as typed; a
    // link refused (see take) is still not typed in.
    fun takeJoinLink(text: String, switching: Boolean = false): Boolean {
        val link = parseFamilyLink(text) ?: return false
        take(link, switching)
        return true
    }

    // Takes a family link. One that comes while a sign-in runs here, a
    // certificate question waits or a switch is on its way is refused, its
    // words in the link note and nothing else changed. One for the server
    // this form holds keeps what it holds; one for another server starts the
    // form over first (forgetServerDetails), then writes its server and home.
    // While a finished sign-up or a received sign-in is held, the sign-in is
    // open: only the same invite or hand-over again, for the same server, is
    // taken, keeping what it already made so its token is never spent twice;
    // any other link is refused with the open sign-in's words, and the held
    // step, the address and the home stay as they are.
    fun take(link: FamilyLink, switching: Boolean = false) {
        val action = this.link.take(linkFacts(link), busy = busy || question != null || switching, home = home)
        val holds = signedUp != null || received != null
        if (action is LinkAction.Take && holds && (action.kind == LinkTake.Forget || !sameToken(link))) {
            this.link = this.link.noted(SignInWords.LINK_WHILE_SIGN_IN_OPEN)
            return
        }
        if (action is LinkAction.Take) {
            if (action.kind == LinkTake.Forget) forgetServerDetails(namedUsername(link))
            action.address?.let(::typeAddress)
            action.home?.let { home = it }
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
        this.link = this.link.took(action)
    }

    // Whether a link carries the invite or hand-over this form holds now.
    private fun sameToken(link: FamilyLink): Boolean = when (link) {
        is FamilyInviteLink -> invite?.token == link.token
        is FamilyHandOverLink -> handOver?.token == link.token
        is FamilySignInPrefill -> false
    }

    // The username a link names, if any.
    private fun namedUsername(link: FamilyLink): String? = when (link) {
        is FamilySignInPrefill -> link.username
        is FamilyHandOverLink -> link.username
        is FamilyInviteLink -> null
    }

    // Starts the form over for another server: nothing of the server it held
    // goes with it (its header rows, sign-in page, saved kind, saved secret,
    // Method, client certificate, a file still opening on the row or asking
    // for its password, which then lands nowhere), nor anything typed for it,
    // nor its username unless the link names one (a sign-in link then writes
    // it); the caller then writes the new server and home. The name given to
    // the server stays: it is never sent anywhere.
    private fun forgetServerDetails(linkUsername: String?) {
        keptId = null
        filledFrom = null
        lastMode = null
        headerList = headerList.replaced(emptyList())
        gatewayRows = emptyList()
        savedGateway = GatewayKind.None
        gatewayKind = GatewayKind.None
        gatewayFirst = ""
        gatewaySecond = ""
        legacyPassword = false
        useApiKey = false
        apiKey = ""
        password = ""
        inviteName = ""
        invitePassword = ""
        inviteAgain = ""
        if (linkUsername.isNullOrBlank()) username = ""
        rememberPassword = true
        keyShown()
        startCertificate = CertificateDraft.None
        certificateDraft = CertificateDraft.None
        certificateLoad = null
        certificateTurn = null
        certificateOpening = false
        certificateProblem = null
    }

    // Back to the plain sign-in. Never while a request commits; a hand-over
    // still waiting for the other device is cancelled. A finished sign-up's
    // username goes into the username field, since the server chose it.
    fun leaveLink() {
        if (committing) return
        if (busy) stopRunning()
        signedUp?.let { username = it.username }
        invite = null
        handOver = null
        handOverStep = null
        received = null
        signedUp = null
        result = null
        link = link.left()
    }

    // Cancels the request running now, and leaves the form as if it had ended.
    private fun stopRunning() {
        running?.cancel()
        running = null
        busy = false
        waiting = false
        handOverStep = null
    }

    // What to do with a hand-over's sealed sign-in (see LinkFormState).
    fun receivedAction(signIn: HandOverSignIn): ReceivedAction = link.received(receivedFacts(signIn))

    // A sealed sign-in for another server than the one this form held: the
    // form starts over and takes that server and home, so a retry keeps them.
    fun adopt(action: ReceivedAction.ForgetAndAdopt, linkUsername: String?) {
        forgetServerDetails(linkUsername)
        typeAddress(action.address)
        home = action.home
        link = link.adopted(action)
    }

    // A sealed sign-in refused here: the hand-over is left (its token is
    // spent) and the words go in the link note.
    fun refuseReceived(why: LinkRefusal) {
        invite = null
        handOver = null
        handOverStep = null
        received = null
        signedUp = null
        link = link.left().noted(why.words)
    }

    // Words for the link note from outside the form: a link that came while
    // a sign-in was open, or the note of the form this one replaced.
    fun noteLink(words: String) {
        link = link.noted(words)
    }

    // What is missing before signing up from an invite, or null when it can go.
    val inviteProblem: String?
        get() = if (url == null) "Type the server's address" else inviteProblem(inviteName, invitePassword, inviteAgain)

    val inviteReady: Boolean get() = !busy && invite != null && inviteProblem == null

    // The home address typed or from a link, when it is one.
    val homeUrl: HttpUrl? get() = home.trim().takeIf(String::isNotEmpty)?.let(::normalizeServerUrl)

    // Signing in after signing up, with the username the server gave and
    // the password just chosen, remembered like any typed one, at the main
    // address the sign-up was made at.
    fun signUpRequest(username: String, password: String, main: String = url?.toString() ?: (scheme.prefix + address.trim())): SignInRequest = SignInRequest(
        address = main,
        username = username,
        secret = password,
        mode = AuthMode.Token,
        home = home,
        headers = headers.toList(),
        rememberPassword = rememberPassword,
        gateway = gatewayDraft(),
        certificate = certificateDraft,
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
        certificate = certificateDraft,
    )

    // A way of signing in that worked other than the one asked for (the
    // password itself, for a server that cannot check tokens) shows in the
    // switch, and is the one saved.
    fun worked(used: AuthMode) {
        if (used == AuthMode.LegacyPassword && mode == AuthMode.Token) legacyPassword = true
    }

    fun distrust() {
        question = null
        result = false to SignInWords.TRUST_REFUSED
    }
}

// A finished sign-up's login, and the main address it was made at (never
// the home address it may have been made through).
class HeldSignUp(val username: String, val password: String, val main: String) {
    override fun toString() = "HeldSignUp(username=$username, main=$main)"
}
