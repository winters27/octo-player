package app.winters.octo.connection

import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.isPrivateHost
import okhttp3.HttpUrl

// What to do when the usual sign-in with a token fails.
enum class LegacyRetry {
    // Not a token the server refused: nothing to change.
    None,

    // The server takes only the password itself, and the address keeps it
    // safe enough (https, or the home network): sign in that way instead.
    Retry,

    // The server takes only the password itself, but it would cross the
    // internet unencrypted: ask rather than send it.
    Unsafe,
}

// Whether a failed sign-in should be tried again with the password itself.
// Only for error 41, a server that cannot check tokens (an account kept in
// a directory, or an old server).
fun legacyRetry(error: Throwable?, mode: AuthMode, url: HttpUrl): LegacyRetry = when {
    mode != AuthMode.Token -> LegacyRetry.None
    error !is SubsonicException.AuthNotSupported || error.code != 41 -> LegacyRetry.None
    url.isHttps || isPrivateHost(url) -> LegacyRetry.Retry
    else -> LegacyRetry.Unsafe
}

// Said when a server needs the password itself over plain http outside the
// home network.
const val LEGACY_UNSAFE_MESSAGE =
    "This server needs the password sent as it is, which isn't safe over http. Use https, or turn on Legacy sign-in under Advanced."
