package app.winters.octo.data

import app.winters.octo.subsonic.SubsonicException

// What to tell someone when a request fails.
fun Throwable.userMessage(): String = when (this) {
    is SubsonicException.Unreachable ->
        "Couldn't reach the server. Check the address, and that you're on the right network."
    is SubsonicException.NotSubsonic ->
        "That address answered, but not like a music server. Check the port."
    is SubsonicException.WrongCredentials -> "Wrong username or password."
    is SubsonicException.NotFound -> "That isn't on the server any more."
    is SubsonicException.Server -> "The server said: $message"
    else -> "Something went wrong."
}

fun SignInError.userMessage(): String = when (this) {
    SignInError.BadAddress -> "That doesn't look like a server address."
    is SignInError.Failed -> cause.userMessage()
}
