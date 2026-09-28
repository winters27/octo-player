package app.winters.octo.data

import app.winters.octo.subsonic.SubsonicException

// What to tell someone when a request fails.
fun Throwable.userMessage(): String = when (this) {
    is SubsonicException.Unreachable ->
        "Couldn't reach the server. Check the address, and that you're on the right network."
    is SubsonicException.NotSubsonic ->
        "That address answered, but not like a music server. Check the port."
    is SubsonicException.WrongCredentials -> "Wrong username or password."
    is SubsonicException.AuthNotSupported ->
        if (code == 41) "This account can't sign in with a token. Turn on Legacy sign-in under Advanced."
        else "The server doesn't take this way of signing in."
    is SubsonicException.NotFound -> "That isn't on the server any more."
    is SubsonicException.Server -> "The server said: $message"
    else -> "Something went wrong."
}

fun SignInError.userMessage(): String = when (this) {
    SignInError.BadAddress -> "That doesn't look like a server address."
    SignInError.BadHomeAddress -> "The home network address doesn't look like a server address."
    SignInError.MissingSecret -> "Enter the password or key to sign in this way."
    is SignInError.Failed -> cause.userMessage()
    is SignInError.Untrusted -> "The server's certificate isn't trusted by this phone."
}
