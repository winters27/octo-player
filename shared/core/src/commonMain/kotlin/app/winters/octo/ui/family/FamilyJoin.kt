package app.winters.octo.ui.family

import app.winters.octo.subsonic.FamilyDeviceKind
import app.winters.octo.subsonic.FamilyPair
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.subsonic.FamilyWeb
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.isFamilyCode
import app.winters.octo.subsonic.pairWithFamilyCode
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import javax.net.ssl.SSLException

// What is missing from a family code join, in plain words, or null when it
// can be sent.
fun joinProblem(address: HttpUrl?, username: String, code: String): String? = when {
    address == null -> "Type the server's address"
    username.isBlank() -> "Type your username"
    !isFamilyCode(code.filter { !it.isWhitespace() }) -> "The family code is 6 digits"
    else -> null
}

// What joining came to: the account and its secret, to sign in with like
// any password, and the address that answered; a certificate the device
// does not trust yet, to ask about and try again; or why it did not work,
// and whether the server could not be reached at all.
sealed interface JoinOutcome {
    class Paired(val pair: FamilyPair, val at: HttpUrl? = null) : JoinOutcome
    class Untrusted(val host: String) : JoinOutcome
    class Failed(val message: String, val unreachable: Boolean = false) : JoinOutcome
}

// Pairs this device with a family code. The secret it answers is this
// device's password from then on; the caller signs in with it. `http`
// is set up to trust what the listener trusted, so a server with a
// certificate of its own is asked about first, as signing in does.
// `home`, from the link, is the server's home network address: tried when
// `address` can't be reached at all (no connection, or its certificate
// failed), never after the server turned the code down.
suspend fun joinFamily(
    address: HttpUrl,
    username: String,
    code: String,
    deviceName: String,
    platform: FamilyPlatform,
    http: OkHttpClient,
    home: HttpUrl? = null,
): JoinOutcome = orAtHome(address, home) { at ->
    joining(at, "That code did not work. Ask for a new one.") {
        pairWithFamilyCode(at, http, username, code.filter { !it.isWhitespace() }, deviceName, platform)
    }
}

// What an invite still needs before it is accepted, or null when it can go.
fun inviteProblem(displayName: String, password: String, again: String): String? = when {
    displayName.isBlank() -> "Type your name"
    password.length < MIN_PASSWORD -> "Choose a password of at least $MIN_PASSWORD characters"
    password != again -> "The two passwords are not the same"
    else -> null
}

const val MIN_PASSWORD = 8

// Accepts an invite on this device: the new member's name and password go
// to the family page, which makes the account; then this device gets its
// own pair code there and pairs with it, so it signs in like any device.
suspend fun joinWithInvite(
    address: HttpUrl,
    token: String,
    displayName: String,
    password: String,
    deviceName: String,
    platform: FamilyPlatform,
    http: OkHttpClient,
    home: HttpUrl? = null,
): JoinOutcome = orAtHome(address, home) { at ->
    joining(at, "That invite did not work. Ask for a new one.") {
        val web = FamilyWeb(at, http)
        val username = web.join(token, password, displayName)
        val code = web.addMyDevice(deviceName, FamilyDeviceKind.OctoApp).pairCode
            ?: throw SubsonicException.Server(0, "The server made no pair code for this device.")
        pairWithFamilyCode(at, http, username, code, deviceName, platform)
    }
}

// Joins at the address that works from anywhere; when that can't be
// reached at all and the link named a home address, joins there instead.
// A certificate question from home is asked; otherwise the first answer
// stands.
private suspend fun orAtHome(address: HttpUrl, home: HttpUrl?, join: suspend (HttpUrl) -> JoinOutcome): JoinOutcome {
    val first = join(address)
    val unreached = (first is JoinOutcome.Failed && first.unreachable) || first is JoinOutcome.Untrusted
    if (home == null || home == address || !unreached) return first
    val second = join(home)
    return when {
        second is JoinOutcome.Paired -> second
        second is JoinOutcome.Untrusted && first !is JoinOutcome.Untrusted -> second
        else -> first
    }
}

private suspend fun joining(address: HttpUrl, refused: String, pair: suspend () -> FamilyPair): JoinOutcome = try {
    val made = pair()
    if (made.secret.isBlank()) JoinOutcome.Failed("The server did not answer with a sign-in. Ask for a new code.") else JoinOutcome.Paired(made, address)
} catch (e: SubsonicException.WrongCredentials) {
    JoinOutcome.Failed(e.message ?: refused)
} catch (e: SubsonicException.Unreachable) {
    if (e.cause is SSLException) JoinOutcome.Untrusted(address.host) else JoinOutcome.Failed("Octo can't reach that address. Check it and try again.", unreachable = true)
} catch (e: SubsonicException.NotSubsonic) {
    JoinOutcome.Failed(if (e.status == 404) "That server has no family codes. Check the address, or update Octo on the server." else e.message?.takeIf { e.status != null } ?: "That address did not answer like an Octo server.")
} catch (e: SubsonicException) {
    JoinOutcome.Failed(e.message ?: refused)
}
