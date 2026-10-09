package app.winters.octo.ui.family

import app.winters.octo.subsonic.FamilyPair
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.isFamilyCode
import app.winters.octo.subsonic.pairWithFamilyCode
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

// What is missing from a family code join, in plain words, or null when it
// can be sent.
fun joinProblem(address: HttpUrl?, username: String, code: String): String? = when {
    address == null -> "Type the server's address"
    username.isBlank() -> "Type your username"
    !isFamilyCode(code.filter { !it.isWhitespace() }) -> "The family code is 6 digits"
    else -> null
}

// What joining came to: the account and its secret, to sign in with like
// any password, or why it did not work.
sealed interface JoinOutcome {
    class Paired(val pair: FamilyPair) : JoinOutcome
    class Failed(val message: String) : JoinOutcome
}

// Pairs this device with a family code. The secret it answers is this
// device's password from then on; the caller signs in with it.
suspend fun joinFamily(
    address: HttpUrl,
    username: String,
    code: String,
    deviceName: String,
    platform: FamilyPlatform,
    http: OkHttpClient,
): JoinOutcome = try {
    val pair = pairWithFamilyCode(address, http, username, code.filter { !it.isWhitespace() }, deviceName, platform)
    if (pair.secret.isBlank()) JoinOutcome.Failed("The server did not answer with a sign-in. Ask for a new code.") else JoinOutcome.Paired(pair)
} catch (e: SubsonicException.WrongCredentials) {
    JoinOutcome.Failed(e.message ?: "That code did not work. Ask for a new one.")
} catch (e: SubsonicException.Unreachable) {
    JoinOutcome.Failed("Octo can't reach that address. Check it and try again.")
} catch (e: SubsonicException.NotSubsonic) {
    JoinOutcome.Failed(if (e.status == 404) "That server has no family codes. Check the address, or update Octo on the server." else "That address did not answer like an Octo server.")
} catch (e: SubsonicException) {
    JoinOutcome.Failed(e.message ?: "That code did not work. Ask for a new one.")
}
