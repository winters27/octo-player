package app.winters.octo.server

import app.winters.octo.connection.GatewayWords
import app.winters.octo.livelists.accountKey
import app.winters.octo.subsonic.gateway.Gateway
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// The servers both apps keep, several at once with one in use: what each is
// called, the quiet words in its row, the notice after a switch, and how an
// older app's one server becomes the list.

// How a kept server answered a quick look: whether it is there, and how
// long it took.
data class ServerCheck(val reach: Reach, val ms: Long? = null)

sealed interface Reach {
    data object Answers : Reach

    // It is there, but did not take the saved password.
    data object WrongPassword : Reach

    data object Unreachable : Reach

    // Signed out, so not asked.
    data object Unknown : Reach

    // A sign-in page in front of the server answered in its place.
    // `loginSent`: the saved sign-in for it went along and it said no.
    data class SignInPage(val gateway: Gateway, val loginSent: Boolean) : Reach

    // Kept with a sign-in page's password for a plain http address outside
    // the home network, where the app never sends it.
    data object NeedsHttps : Reach
}

// What a server is called: the name the listener gave it, else its host.
fun serverName(label: String, address: String): String =
    label.trim().ifEmpty { address.toHttpUrlOrNull()?.host ?: address.removeSuffix("/") }

// Who is signed in where, for a server's row: "winters at music.example.com".
fun accountLine(username: String, address: String): String {
    val where = address.substringAfter("://").removeSuffix("/")
    return if (username.isBlank()) where else "$username at $where"
}

// A server's quiet status in its row: what it is, and how it answered.
// The one in use shows only its kind here, the rest being said below the
// list, unless its sign-in page needs https or turns it away, which
// nothing else says.
fun statusLine(
    serverType: String?,
    serverVersion: String?,
    signedOut: Boolean,
    check: ServerCheck?,
    switching: Boolean = false,
    inUse: Boolean = false,
): String {
    val kind = serverKind(serverType, serverVersion)
    return when {
        switching -> "Switching to it now"
        signedOut -> "Signed out. Sign in again to use it."
        check?.reach == Reach.NeedsHttps -> "$kind · ${GatewayWords.ROW_NEEDS_HTTPS}"
        check != null && check.reach is Reach.SignInPage -> "$kind · ${GatewayWords.row(check.reach.gateway, check.reach.loginSent)}"
        inUse || check == null || check.reach == Reach.Unknown -> kind
        check.reach == Reach.Answers -> listOfNotNull(kind, check.ms?.let(::answerWords)).joinToString(" · ")
        check.reach == Reach.WrongPassword -> "$kind · Didn't take the saved password"
        else -> "$kind · Out of reach right now"
    }
}

// Whether a row's status line is a warning.
fun ServerCheck?.isWarning(): Boolean = when (this?.reach) {
    Reach.Unreachable, Reach.WrongPassword, Reach.NeedsHttps, is Reach.SignInPage -> true
    else -> false
}

// The short words for a warning on its own, as the account card shows it:
// the sign-in page by name when it is the trouble. Null when all is well.
fun ServerCheck?.troubleWords(): String? = when (val reach = this?.reach) {
    Reach.Unreachable -> "Can't reach"
    Reach.WrongPassword -> "Didn't take the saved password"
    Reach.NeedsHttps -> GatewayWords.ROW_NEEDS_HTTPS
    is Reach.SignInPage -> GatewayWords.row(reach.gateway, reach.loginSent)
    else -> null
}

// Whether a row's status line shows as a warning. The one in use warns
// only that its sign-in page needs https or turns it away; its other
// troubles are said below the list.
fun ServerCheck?.warnsInRow(inUse: Boolean): Boolean =
    isWarning() && (!inUse || this?.reach == Reach.NeedsHttps || this?.reach is Reach.SignInPage)

// The notice once another server is in use, and plainly when music was
// stopped for it. Null when nothing was in use before.
fun switchNotice(to: String, from: String?, stoppedMusic: Boolean): String? = when {
    from == null -> null
    stoppedMusic -> "Now on $to. The music from $from stopped, and its queue is kept for when you come back."
    else -> "Now on $to."
}

fun switchFailedWords(name: String, why: String): String = "Couldn't switch to $name. $why"

fun passwordRefusedWords(name: String): String = "$name didn't take the saved password. It may have been changed."

// An id for a newly kept server: the account's key, as older versions named
// its folder of plays and queue, unless another kept server has it already.
fun freshServerId(username: String, address: String, taken: Set<String>): String {
    val first = accountKey(username, address)
    return generateSequence(1) { it + 1 }.map { n -> if (n == 1) first else "$first-$n" }.first { it !in taken }
}

// The kept servers in one shape, with the one in use. `legacy` is the one
// server an older version of the app keeps, and it has the last word, since
// that version writes only it: a store from before the list becomes a list
// of one, a server an older version signed in to since is added (or brought
// up to date) and made the one in use, and one it signed out of leaves none
// in use. `id` gives a server's id (the account's key for one kept before
// ids), `same` tells whether two are the same account, and `adopt` makes
// the legacy server into the kept one, with the id and name of the one it
// matched, if any.
fun <T> settleServers(
    listed: List<T>,
    legacy: T?,
    id: (T) -> String,
    same: (T, T) -> Boolean,
    adopt: (legacy: T, match: T?) -> T,
): Pair<List<T>, T?> {
    val list = listed.distinctBy(id)
    legacy ?: return list to null
    val match = list.firstOrNull { id(it) == id(legacy) || same(it, legacy) }
    val kept = adopt(legacy, match)
    return (if (match == null) list + kept else list.map { if (id(it) == id(match)) kept else it }) to kept
}
