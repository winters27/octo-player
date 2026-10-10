package app.winters.octo.server

import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException

// What the Change password form holds: the password now, the new one, and
// the new one again.
data class PasswordDraft(val current: String = "", val new: String = "", val confirm: String = "")

// What keeps the form from being sent, in plain words, or null when it can
// go. Nothing is asked of the server for these.
fun passwordDraftProblem(draft: PasswordDraft): String? = when {
    draft.current.isEmpty() -> "Enter your current password."
    draft.new.isEmpty() -> "Enter a new password."
    draft.confirm != draft.new -> "The new passwords don't match."
    draft.new == draft.current -> "That's the password you have now. Choose a new one."
    else -> null
}

// How a change of password went.
sealed interface PasswordChange {
    data object Changed : PasswordChange

    // The password typed as the current one is not it.
    data object WrongCurrent : PasswordChange

    // The server does not let this account change its password.
    data object NotAllowed : PasswordChange

    // The server takes no new passwords from apps (Navidrome answers 501),
    // or would only take one in the address.
    data object NotOffered : PasswordChange

    // Signed in with an API key: there is no password here to change.
    data object UsesKey : PasswordChange

    data class Failed(val message: String) : PasswordChange
}

// Changes the signed-in user's own password. The current one is checked
// first by signing in with it, so a mistyped one is caught before anything
// changes. On success the client signs in with the new one from then on;
// keeping it (the system's store, the phone's vault) is the caller's part.
// A family member's password goes through the family's own call, which
// also signs out every other app and device.
suspend fun changeOwnPassword(client: SubsonicClient, current: String, new: String, family: Boolean = false): PasswordChange {
    if (client.authMode == AuthMode.ApiKey) return PasswordChange.UsesKey
    try {
        client.withSecret(current).ping()
    } catch (e: SubsonicException.WrongCredentials) {
        return PasswordChange.WrongCurrent
    } catch (e: SubsonicException) {
        return failure(e)
    }
    return try {
        if (family) client.changeFamilyPassword(current, new) else client.changePassword(client.username, new)
        PasswordChange.Changed
    } catch (e: SubsonicException) {
        failure(e)
    }
}

private fun failure(e: SubsonicException): PasswordChange = when {
    e is SubsonicException.Server && e.code == 50 -> PasswordChange.NotAllowed
    // A server that ignored the form body misses the new password.
    e is SubsonicException.Server && e.code == 10 -> PasswordChange.NotOffered
    e is SubsonicException.NotSubsonic && e.status in NotThere -> PasswordChange.NotOffered
    e is SubsonicException.Unreachable -> PasswordChange.Failed("Couldn't reach the server, so nothing changed. Try again in a moment.")
    e is SubsonicException.NotSubsonic && e.serverBusy -> PasswordChange.Failed("The server isn't answering right now, so nothing changed. Try again in a moment.")
    else -> PasswordChange.Failed("The server couldn't change the password" + (e.message?.takeIf(String::isNotBlank)?.let { ": $it" } ?: "") + ".")
}

// What a server answers for a call it does not have.
private val NotThere = setOf(404, 405, 501)

// What to tell the listener about a change of password. A family member's
// change signs every other app and device out.
fun passwordChangeWords(result: PasswordChange, family: Boolean = false): String = when (result) {
    PasswordChange.Changed -> if (family) "Your password is changed. Octo uses the new one; every other app and device now needs it." else "Your password is changed. Octo uses the new one from now on."
    PasswordChange.WrongCurrent -> "That isn't your current password."
    PasswordChange.NotAllowed -> "Your server doesn't let this account change its password. Ask whoever runs the server."
    PasswordChange.NotOffered -> "Your server doesn't take new passwords from apps. Change it on the server's own web page."
    PasswordChange.UsesKey -> "You signed in with an API key, so there's no password here to change."
    is PasswordChange.Failed -> result.message
}
