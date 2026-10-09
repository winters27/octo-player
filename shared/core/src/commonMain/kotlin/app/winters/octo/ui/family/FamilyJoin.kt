package app.winters.octo.ui.family

import app.winters.octo.subsonic.FamilyWeb
import app.winters.octo.subsonic.SubsonicException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import javax.net.ssl.SSLException

// What signing up from an invite came to: the member's username and the
// password they chose, to sign in with like any typed one, and the address
// that answered; a certificate the device does not trust yet, to ask about
// and try again; or why it did not work, and whether the server could not
// be reached at all.
sealed interface JoinOutcome {
    class SignedUp(val username: String, val password: String, val at: HttpUrl? = null) : JoinOutcome {
        override fun toString() = "SignedUp(username=$username, at=$at)"
    }
    class Untrusted(val host: String) : JoinOutcome
    class Failed(val message: String, val unreachable: Boolean = false) : JoinOutcome
}

// What an invite still needs before it is accepted, or null when it can go.
fun inviteProblem(displayName: String, password: String, again: String): String? = when {
    displayName.isBlank() -> "Type your name"
    password.length < MIN_PASSWORD -> "Choose a password of at least $MIN_PASSWORD characters"
    password.length > MAX_PASSWORD -> "That password is too long"
    password != again -> "The two passwords are not the same"
    else -> null
}

const val MIN_PASSWORD = 8
const val MAX_PASSWORD = 1024

// How strong a password looks, in plain words, while it is typed: its
// length and the kinds of character in it. Null before anything is typed.
fun passwordStrength(password: String): String? {
    if (password.isEmpty()) return null
    if (password.length < MIN_PASSWORD) return "Too short: at least $MIN_PASSWORD characters"
    val kinds = listOf(password.any(Char::isLowerCase), password.any(Char::isUpperCase), password.any(Char::isDigit), password.any { !it.isLetterOrDigit() }).count { it }
    return when {
        password.length >= 16 || (password.length >= 12 && kinds >= 3) -> "Strong"
        password.length >= 12 || kinds >= 3 -> "Good. Longer is stronger."
        else -> "Weak: make it longer, or mix in numbers and symbols"
    }
}

// Accepts an invite on this device: the new member's name and the
// password they choose go to the family page, which makes the account. The
// caller then signs in with that username and password, like any typed
// sign-in. `home`, from the link, is the server's home network address:
// tried when `address` can't be reached at all (no connection, or its
// certificate failed), never after the server turned the invite down.
suspend fun joinWithInvite(
    address: HttpUrl,
    token: String,
    displayName: String,
    password: String,
    http: OkHttpClient,
    home: HttpUrl? = null,
): JoinOutcome = orAtHome(address, home) { at ->
    joining(at, "That invite did not work. Ask for a new one.") {
        val username = FamilyWeb(at, http).join(token, password, displayName)
        JoinOutcome.SignedUp(username, password, at)
    }
}

// Signs up at the address that works from anywhere; when that can't be
// reached at all and the link named a home address, signs up there instead.
// A certificate question from home is asked; otherwise the first answer
// stands.
private suspend fun orAtHome(address: HttpUrl, home: HttpUrl?, join: suspend (HttpUrl) -> JoinOutcome): JoinOutcome {
    val first = join(address)
    val unreached = (first is JoinOutcome.Failed && first.unreachable) || first is JoinOutcome.Untrusted
    if (home == null || home == address || !unreached) return first
    val second = join(home)
    return when {
        second is JoinOutcome.SignedUp -> second
        second is JoinOutcome.Untrusted && first !is JoinOutcome.Untrusted -> second
        else -> first
    }
}

private suspend fun joining(address: HttpUrl, refused: String, join: suspend () -> JoinOutcome.SignedUp): JoinOutcome = try {
    val made = join()
    if (made.username.isBlank()) JoinOutcome.Failed("The server did not answer with a username. Ask for a new invite.") else made
} catch (e: SubsonicException.WrongCredentials) {
    JoinOutcome.Failed(e.message ?: refused)
} catch (e: SubsonicException.Unreachable) {
    if (e.cause is SSLException) JoinOutcome.Untrusted(address.host) else JoinOutcome.Failed("Octo can't reach that address. Check it and try again.", unreachable = true)
} catch (e: SubsonicException.NotSubsonic) {
    JoinOutcome.Failed(if (e.status == 404) "That server has no family sign-up. Check the address, or update Octo on the server." else e.message?.takeIf { e.status != null } ?: "That address did not answer like an Octo server.")
} catch (e: SubsonicException) {
    JoinOutcome.Failed(e.message ?: refused)
}
