package app.winters.octo.connection

import app.winters.octo.data.HeaderDraft
import app.winters.octo.data.resolveHeaders
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.gateway.GatewayNames
import app.winters.octo.subsonic.gateway.basicCredentials
import app.winters.octo.subsonic.gateway.savedGatewayNames

// The sign-in page fields as the form holds them: the kind picked, the two
// fields as typed (a username and password, or a Client ID and Client
// Secret), and the kind the saved headers make up. Left empty under the
// saved kind, the fields keep the saved values.
data class GatewayDraft(
    val kind: GatewayKind = GatewayKind.None,
    val first: String = "",
    val second: String = "",
    val saved: GatewayKind = GatewayKind.None,
) {
    override fun toString() = "GatewayDraft(kind=$kind, first=${mask(first)}, second=${mask(second)}, saved=$saved)"
}

// The headers to keep and send, or what keeps the form from going.
sealed interface ResolvedHeaders {
    data class Ready(val headers: List<ServerHeader>) : ResolvedHeaders

    data class Problem(val text: String) : ResolvedHeaders
}

// Every header the server gets: the rows as typed (a saved row left empty
// keeps its value) and the sign-in page fields made into theirs. `saved`
// is the saved headers with their values; `mainHttps` and `mainPrivate`
// describe the main address. The rows that make up the saved kind are
// shown as the fields, so they are not among `rows`.
fun resolveConnectionHeaders(
    rows: List<HeaderDraft>,
    gateway: GatewayDraft,
    saved: List<ServerHeader>,
    mainHttps: Boolean,
    mainPrivate: Boolean,
): ResolvedHeaders {
    val fields = when (val made = fieldHeaders(gateway, saved)) {
        is ResolvedHeaders.Problem -> return made
        is ResolvedHeaders.Ready -> made.headers
    }
    val reserved = reservedNames(gateway.kind)
    if (rows.any { row -> reserved.any { it.equals(row.name.trim(), ignoreCase = true) } }) {
        return ResolvedHeaders.Problem(GatewayWords.PROBLEM_RESERVED_ROW)
    }
    val headers = cleanHeaders(resolveHeaders(rows, saved)) + fields
    if (headers.any { GatewayNames.isGatewayName(it.name) } && !gatewayAllowed(mainHttps, mainPrivate)) {
        return ResolvedHeaders.Problem(GatewayWords.NEEDS_HTTPS)
    }
    return ResolvedHeaders.Ready(headers)
}

// A sign-in page's password may go over https, or over plain http inside
// the home network; never over plain http across the internet.
fun gatewayAllowed(mainHttps: Boolean, mainPrivate: Boolean): Boolean = mainHttps || mainPrivate

// The names the fields set for a kind, which no row may use beside them.
private fun reservedNames(kind: GatewayKind): List<String> = when (kind) {
    GatewayKind.None -> emptyList()
    GatewayKind.Password -> listOf(GatewayNames.AUTHORIZATION)
    GatewayKind.CloudflareToken -> listOf(GatewayNames.CF_ID, GatewayNames.CF_SECRET)
}

// The headers the fields make. The username is trimmed; a password, Client
// ID or Client Secret is used as typed.
private fun fieldHeaders(draft: GatewayDraft, saved: List<ServerHeader>): ResolvedHeaders {
    if (draft.kind == GatewayKind.None) return ResolvedHeaders.Ready(emptyList())
    val password = draft.kind == GatewayKind.Password
    val first = if (password) draft.first.trim() else draft.first
    val second = draft.second
    if (first.isEmpty() && second.isEmpty() && draft.kind == draft.saved) {
        val names = savedGatewayNames(saved.map { it.name to it.value }, draft.kind)
        val kept = saved.filter { it.name in names }
        if (kept.isNotEmpty()) return ResolvedHeaders.Ready(kept)
    }
    if (first.isEmpty() || second.isEmpty()) {
        return ResolvedHeaders.Problem(if (password) GatewayWords.PROBLEM_BOTH_PASSWORD else GatewayWords.PROBLEM_BOTH_TOKEN)
    }
    if (password) {
        if (':' in first) return ResolvedHeaders.Problem(GatewayWords.PROBLEM_COLON)
        if ((first + second).any(Char::isISOControl)) return ResolvedHeaders.Problem(GatewayWords.PROBLEM_CONTROL)
        return ResolvedHeaders.Ready(listOf(ServerHeader(GatewayNames.AUTHORIZATION, basicCredentials(first, second))))
    }
    if (!(first + second).all { it in ' '..'~' }) return ResolvedHeaders.Problem(GatewayWords.PROBLEM_CONTROL)
    return ResolvedHeaders.Ready(listOf(ServerHeader(GatewayNames.CF_ID, first), ServerHeader(GatewayNames.CF_SECRET, second)))
}

// The form's sign-in page state across attempts: whether Advanced is
// open, the kind picked, and the gateway seen at the address typed
// (`detectedFor`). `callout`: the last failure met a sign-in page at this
// address, so its group shows that failure as its error note, outlined,
// with a pointer under Sign in. `calloutPending`: the group still has to be
// brought into view. `keyPending`: the API key row, just asked for, still
// has to be brought into view.
data class GatewayFormState(
    val advancedOpen: Boolean,
    val kind: GatewayKind,
    val detected: Gateway?,
    val detectedFor: String?,
    val callout: Boolean = false,
    val calloutPending: Boolean = false,
    val keyPending: Boolean = false,
)

// A failed attempt at `attemptAddress`, while the address field reads
// `currentAddress`. A gateway opens Advanced and, when no kind is picked
// yet, picks the one that gets through it. The most specific gateway seen
// at this address is kept, and the group calls the failure out, again for
// a repeat. A result for an address no longer typed is dropped.
fun reactToFailure(state: GatewayFormState, attemptAddress: String, currentAddress: String, gateway: Gateway?): GatewayFormState {
    val address = currentAddress.trim()
    if (gateway == null || attemptAddress.trim() != address) return state
    val kept = if (state.detectedFor == address) moreSpecific(state.detected, gateway) ?: gateway else gateway
    return state.copy(
        advancedOpen = true,
        kind = if (state.kind == GatewayKind.None) kindFor(kept) else state.kind,
        detected = kept,
        detectedFor = address,
        callout = true,
        calloutPending = true,
    )
}

// Another address forgets the gateway seen at the last one, and its
// callout.
fun addressChanged(state: GatewayFormState, address: String): GatewayFormState =
    if (state.detectedFor == address.trim()) state
    else state.copy(detected = null, detectedFor = null, callout = false, calloutPending = false)

// Authentik, Authelia and Cloudflare Access outrank a password prompt and
// an unknown sign-in page; otherwise the later one counts.
fun moreSpecific(a: Gateway?, b: Gateway?): Gateway? = when {
    b == null -> a
    a == null -> b
    b in NAMED -> b
    a in NAMED -> a
    else -> b
}

private val NAMED = setOf(Gateway.Authentik, Gateway.Authelia, Gateway.CloudflareAccess)

private fun kindFor(gateway: Gateway): GatewayKind =
    if (gateway == Gateway.CloudflareAccess) GatewayKind.CloudflareToken else GatewayKind.Password
