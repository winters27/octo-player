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

    // Something answered, but not a Subsonic server. `status` is the HTTP
    // status when the answer was an error page.
    class NotSubsonic(message: String, val status: Int? = null) : SubsonicException(message) {
        // A proxy in front of the server saying the server behind it isn't
        // answering (restarting, or down for a moment), not a wrong address.
        val serverBusy: Boolean get() = status in BUSY_STATUSES
    }

    // Could not reach the server at all.
    class Unreachable(cause: IOException) :
        SubsonicException(cause.message ?: "Could not reach the server", cause)
}

// What a proxy or tunnel answers while the server behind it is restarting
// or down: bad gateway, unavailable, gateway timeout, and Cloudflare's own
// "origin not answering" codes.
private val BUSY_STATUSES = setOf(502, 503, 504, 520, 521, 522, 523, 524, 530)
