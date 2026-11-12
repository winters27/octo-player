package app.winters.octo.data

import android.database.sqlite.SQLiteFullException
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.megabytes
import app.winters.octo.connection.DISTRUSTED_MESSAGE
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.isDistrusted
import app.winters.octo.subsonic.SubsonicException

// What to tell someone when a request fails.
fun Throwable.userMessage(): String = when (this) {
    is SubsonicException.Unreachable ->
        if (isDistrusted()) DISTRUSTED_MESSAGE
        else "Couldn't reach the server. Check the address, and that you're on the right network."
    // A sign-in page in front of the server is named, with how to get through.
    is SubsonicException.NotSubsonic -> GatewayWords.failureFor(this)
        ?: if (serverBusy) "The server isn't answering right now. It may be restarting, so try again in a moment."
        else "That address answered, but not like a music server. Check the port."
    is SubsonicException.WrongCredentials -> "Wrong username or password."
    is SubsonicException.AuthNotSupported ->
        if (code == 41) "This account can't sign in with a token. Turn on Legacy sign-in under Advanced."
        else "The server doesn't take this way of signing in."
    is SubsonicException.NotFound -> "That isn't on the server any more."
    is SubsonicException.Server -> "The server said: $message"
    is NotEnoughSpace -> spaceWords(doing, neededBytes)
    is SQLiteFullException -> spaceWords(SpaceFor.Copy, null)
    else -> "Something went wrong."
}

// The words for running out of room while doing something: how much to
// free when that is known, then what to do again. A queue is played again;
// everything else is tried again.
fun spaceWords(doing: SpaceFor, neededBytes: Long?): String {
    val free = if (neededBytes != null) "Free up %,d MB".format(megabytes(neededBytes)) else "Free up some space"
    val again = if (doing == SpaceFor.Queue) "play it again" else "try again"
    return "Not enough space to ${doing.doing}. $free, then $again."
}

fun SignInError.userMessage(): String = when (this) {
    SignInError.BadAddress -> "That doesn't look like a server address."
    SignInError.BadHomeAddress -> "The home network address doesn't look like a server address."
    SignInError.MissingSecret -> "Enter the password or key to sign in this way."
    is SignInError.Failed -> cause.userMessage()
    is SignInError.Untrusted -> "The server's certificate isn't trusted by this phone."
    is SignInError.AlreadyKept -> "$name is already in your list with that address and username."
    SignInError.Gone -> "That server isn't in your list any more."
    is SignInError.Problem -> text
}
