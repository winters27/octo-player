package app.winters.octo.connection

import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.FamilyLink
import app.winters.octo.subsonic.FamilySignInPrefill
import app.winters.octo.ui.family.HandOverSignIn
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Whether a family link may take a form to its server, and what of the form
// it keeps. Every app's form keeps one LinkFormState, feeds it the form's
// events and asks it before taking a link or a received sign-in; it
// compares only addresses that came from a link, a received sign-in, a
// saved server or the app's own built address, never the address box's
// text. OctoKit's LinkServer.swift is the same, and both run
// fixtures/gateway/signin-vectors.json.

// A form opened for a kept server (a sign-in again, an edit, an edit of the
// connection), or any other.
enum class LinkForm { Fresh, KeptServer }

// The saved server a form was filled from: its main and home addresses as
// saved, and its username.
data class LinkAnchor(val main: String, val home: String?, val username: String)

// What a family link names: its server, the address a hand-over is
// redeemed at (`base`), its home address, the username it names, and
// whether it is an invite (which always makes a new account).
data class LinkFacts(
    val server: String,
    val base: String? = null,
    val home: String? = null,
    val username: String? = null,
    val invite: Boolean = false,
)

fun linkFacts(link: FamilyLink): LinkFacts = when (link) {
    is FamilyInviteLink -> LinkFacts(link.server, home = link.home, invite = true)
    is FamilySignInPrefill -> LinkFacts(link.server, home = link.home, username = link.username.takeIf(String::isNotBlank))
    is FamilyHandOverLink -> LinkFacts(link.server, base = link.base, home = link.home, username = link.username)
}

// What a hand-over's sealed sign-in names.
data class ReceivedFacts(val server: String, val home: String?, val username: String)

fun receivedFacts(signIn: HandOverSignIn): ReceivedFacts =
    ReceivedFacts(signIn.server, signIn.home?.takeIf(String::isNotBlank), signIn.username)

enum class LinkTake { Fitting, Keep, Forget }

// Why a link, or a received sign-in, is refused, and the words the form's
// link note shows.
enum class LinkRefusal(val words: String) {
    Busy(SignInWords.LINK_WHILE_BUSY),
    AnotherAccount(SignInWords.LINK_FOR_ANOTHER_ACCOUNT),
    AnotherServer(SignInWords.LINK_FOR_ANOTHER_SERVER),
    CodeElsewhere(SignInWords.CODE_ELSEWHERE),
    AddressChanged(SignInWords.LINK_AFTER_ADDRESS_CHANGE),
}

sealed interface LinkAction {
    // The link is taken. For Keep and Forget the form writes `address` into
    // its address box and `home` into its home field (Forget after forgetting
    // the last server's details); Fitting writes neither. `addresses` is what
    // took() records as taken.
    data class Take(val kind: LinkTake, val address: String?, val home: String?, val addresses: List<String>) : LinkAction

    data class Refuse(val why: LinkRefusal) : LinkAction
}

sealed interface ReceivedAction {
    // Sign in with the received sign-in: at the form's own addresses when
    // `useForm` (a kept server's), else at the received ones.
    data class Keep(val useForm: Boolean) : ReceivedAction

    data class Refuse(val why: LinkRefusal) : ReceivedAction

    // Forget the form's details, write `address` and `home` into it, then
    // adopted().
    data class ForgetAndAdopt(val address: String, val home: String, val addresses: List<String>) : ReceivedAction
}

// What a form knows about where links may take it. `anchor`: the saved
// server it was filled from, until any edit of the address, scheme or home
// ends it (`anchorEnded`). `hadAddress`: the address box held text at some
// point. `taken`: the addresses of the links taken since the last edit.
// `typed`: the address and home typed since the last link. `note`: the words
// of the last refusal, the form's link note.
data class LinkFormState(
    val kind: LinkForm,
    val anchor: LinkAnchor? = null,
    val anchorEnded: Boolean = false,
    val hadAddress: Boolean = false,
    val taken: List<String> = emptyList(),
    val typed: List<String> = emptyList(),
    val note: String? = null,
) {
    // The fill from a saved server, after its own writes.
    fun filled(anchor: LinkAnchor): LinkFormState = copy(anchor = anchor, anchorEnded = false)

    // The address, its scheme or the home changed, by the person or by the
    // app's own writes: `address` is the app's built main address (null when
    // it does not build), `home` the home as the app reads it.
    fun edited(address: String?, home: String?, addressNonBlank: Boolean): LinkFormState {
        val main = address?.let(::norm)
        return copy(
            anchor = null,
            anchorEnded = anchorEnded || anchor != null,
            hadAddress = hadAddress || addressNonBlank,
            taken = emptyList(),
            typed = if (main == null) emptyList() else listOfNotNull(main, home?.let(::norm)).distinct(),
            note = null,
        )
    }

    // What to do with a link arriving while the form's home field reads
    // `home`. `busy` covers a certificate question waiting too.
    fun take(link: LinkFacts, busy: Boolean, home: String): LinkAction {
        if (busy) return LinkAction.Refuse(LinkRefusal.Busy)
        val addresses = listOfNotNull(link.server, link.home, link.base).mapNotNull(::norm).distinct()
        fun takeAs(kind: LinkTake) =
            LinkAction.Take(kind, link.server, linkHome(kind == LinkTake.Forget, home, link.home), addresses)
        val anchor = anchor
        if (anchor != null) {
            val own = anchorAddresses(anchor)
            val fits = fitsIn(link.server, own) && (link.base == null || fitsIn(link.base, own))
            val named = link.username?.trim()?.takeIf(String::isNotEmpty)
            if (fits && !link.invite && (named == null || named == anchor.username.trim())) {
                return LinkAction.Take(LinkTake.Fitting, null, null, addresses)
            }
            if (kind == LinkForm.Fresh) return takeAs(LinkTake.Forget)
            return LinkAction.Refuse(
                when {
                    fits -> LinkRefusal.AnotherAccount
                    link.base != null -> LinkRefusal.CodeElsewhere
                    else -> LinkRefusal.AnotherServer
                },
            )
        }
        if (kind == LinkForm.KeptServer) {
            return LinkAction.Refuse(if (anchorEnded) LinkRefusal.AddressChanged else LinkRefusal.AnotherServer)
        }
        val known = taken + typed
        val all = listOfNotNull(link.server, link.base, link.home).all { fitsIn(it, known) }
        return takeAs(if (all || !hadAddress) LinkTake.Keep else LinkTake.Forget)
    }

    // The state a taken or refused link leaves, after the app's own writes.
    fun took(action: LinkAction): LinkFormState = when (action) {
        is LinkAction.Refuse -> copy(note = action.why.words)
        is LinkAction.Take -> when (action.kind) {
            LinkTake.Fitting -> copy(note = null)
            LinkTake.Keep -> copy(taken = (taken + action.addresses).distinct(), note = null)
            LinkTake.Forget -> copy(anchor = null, anchorEnded = anchorEnded || anchor != null, taken = action.addresses, note = null)
        }
    }

    // What to do with a hand-over's sealed sign-in, before its request is
    // built (and again on every retry that reuses it).
    fun received(signIn: ReceivedFacts): ReceivedAction {
        val server = norm(signIn.server)
        val shownHome = signIn.home?.takeIf(String::isNotBlank)
        val home = shownHome?.let(::norm)
        val anchor = anchor
        if (anchor != null) {
            val own = anchorAddresses(anchor)
            val fits = (server != null && server in own) || (home != null && home in own)
            if (fits && signIn.username.trim() == anchor.username.trim()) return ReceivedAction.Keep(useForm = true)
            if (kind == LinkForm.KeptServer) {
                return ReceivedAction.Refuse(if (fits) LinkRefusal.AnotherAccount else LinkRefusal.AnotherServer)
            }
        } else {
            if (server != null && server in taken && (shownHome == null || (home != null && home in taken))) {
                return ReceivedAction.Keep(useForm = false)
            }
            if (kind == LinkForm.KeptServer) {
                return ReceivedAction.Refuse(if (anchorEnded) LinkRefusal.AddressChanged else LinkRefusal.AnotherServer)
            }
        }
        return ReceivedAction.ForgetAndAdopt(signIn.server, shownHome.orEmpty(), listOfNotNull(server, home).distinct())
    }

    // The received server adopted, after the app's forget and its writes, so
    // a retry keeps it.
    fun adopted(action: ReceivedAction): LinkFormState =
        if (action is ReceivedAction.ForgetAndAdopt) copy(taken = action.addresses) else this

    // The form left the link (its sign-up, hand-over or prefill).
    fun left(): LinkFormState = copy(note = null)

    // Words for the link note from outside the form: a refused received
    // sign-in, or a link that came in while a sign-in was open.
    fun noted(words: String): LinkFormState = copy(note = words)
}

// The home a taken link writes into the form's home field: the link's own
// on a forget (none when it names none), else the link's or the one there.
fun linkHome(forget: Boolean, home: String, linkHome: String?): String = if (forget) linkHome.orEmpty() else linkHome ?: home

// Whether a sign-in is open in a form: running, a certificate question
// waiting, or a received sign-in or a finished sign-up held, each of which
// works only once.
fun signInOpen(busy: Boolean, question: Boolean, holdsReceived: Boolean, holdsSignUp: Boolean): Boolean =
    busy || question || holdsReceived || holdsSignUp

// An address in one form for comparing: `scheme://host[:port]path`, scheme
// and host lowercased, the scheme's own port left out, one trailing slash
// dropped and an IPv6 host in brackets. Null for anything that is not an
// http or https address with a host, and, before parsing, for what URL
// parsers read differently: user info, a dot segment in the path, an empty
// port, a host ending in a dot or one that is not plain ASCII. Null never
// fits anything.
fun norm(address: String): String? {
    val text = address.trim()
    if (!plainEnough(text)) return null
    val url = text.toHttpUrlOrNull() ?: return null
    val host = if (':' in url.host) "[${url.host}]" else url.host
    val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
    return "${url.scheme}://$host$port${url.encodedPath.removeSuffix("/")}"
}

private val dotSegments = setOf(".", "..", "%2e", "%2e%2e", ".%2e", "%2e.")

// Whether `text` holds a scheme and none of what parsers read differently.
private fun plainEnough(text: String): Boolean {
    val start = text.indexOf("://")
    if (start < 0) return false
    val rest = text.substring(start + 3)
    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, end)
    if ('@' in authority) return false
    val host: String
    val port: String?
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0) return false
        host = authority.substring(1, close)
        val after = authority.substring(close + 1)
        if (after.isNotEmpty() && !after.startsWith(":")) return false
        port = if (after.isEmpty()) null else after.substring(1)
    } else {
        host = authority.substringBefore(':')
        port = if (':' in authority) authority.substringAfter(':') else null
    }
    if (port != null && port.isEmpty()) return false
    if (host.isEmpty() || host.endsWith('.') || host.any { it.code > 127 }) return false
    val path = rest.substring(end).substringBefore('?').substringBefore('#')
    return path.split('/').none { it.lowercase() in dotSegments }
}

private fun fitsIn(address: String, known: List<String>): Boolean = norm(address)?.let { it in known } == true

private fun anchorAddresses(anchor: LinkAnchor): List<String> =
    listOfNotNull(anchor.main, anchor.home).mapNotNull(::norm).distinct()
