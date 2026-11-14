package app.winters.octo.data

import android.database.sqlite.SQLiteFullException
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.megabytes
import app.winters.octo.connection.DISTRUSTED_MESSAGE
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.SignInWords
import app.winters.octo.connection.isDistrusted
import app.winters.octo.subsonic.SubsonicException

// What to tell someone when a request fails.
fun Throwable.userMessage(): String = when (this) {
    is SubsonicException.Unreachable ->
        if (isDistrusted()) DISTRUSTED_MESSAGE
        else SignInWords.UNREACHABLE
    // A sign-in page in front of the server is named, with how to get through.
    is SubsonicException.NotSubsonic -> GatewayWords.failureFor(this)
        ?: if (serverBusy) SignInWords.RESTARTING
        else SignInWords.NOT_MUSIC
    is SubsonicException.WrongCredentials -> SignInWords.WRONG_PASSWORD
    is SubsonicException.AuthNotSupported ->
        if (code == 41) SignInWords.LEGACY_HINT
        else SignInWords.WAY_REFUSED
    is SubsonicException.NotFound -> "That isn't on the server any more."
    is SubsonicException.Server -> "The server said: $message"
    is NotEnoughSpace -> spaceWords(doing, neededBytes)
    is SQLiteFullException -> spaceWords(SpaceFor.Copy, null)
    else -> SignInWords.SOMETHING_WRONG
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
    SignInError.BadAddress -> SignInWords.BAD_ADDRESS
    SignInError.BadHomeAddress -> SignInWords.BAD_HOME_ADDRESS
    SignInError.MissingSecret -> SignInWords.MISSING_SECRET
    is SignInError.Failed -> cause.userMessage()
    is SignInError.Untrusted -> "The server's certificate isn't trusted by this phone."
    is SignInError.AlreadyKept -> SignInWords.alreadyKept(name)
    SignInError.Gone -> SignInWords.GONE
    is SignInError.Problem -> text
}
