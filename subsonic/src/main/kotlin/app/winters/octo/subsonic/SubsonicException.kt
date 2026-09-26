package app.winters.octo.subsonic

import java.io.IOException

sealed class SubsonicException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    // Username, password or API key rejected (codes 40 and 44).
    class WrongCredentials(message: String) : SubsonicException(message)

    // The server cannot check this way of signing in (codes 41 and 42):
    // a token for a directory-backed account, or a kind it does not take.
    class AuthNotSupported(val code: Int, message: String) : SubsonicException(message)

    // The thing asked for does not exist (code 70).
    class NotFound(message: String) : SubsonicException(message)

    // Any other error the server reported.
    class Server(val code: Int, message: String) : SubsonicException(message)

    // Something answered, but not a Subsonic server.
    class NotSubsonic(message: String) : SubsonicException(message)

    // Could not reach the server at all.
    class Unreachable(cause: IOException) :
        SubsonicException(cause.message ?: "Could not reach the server", cause)
}
