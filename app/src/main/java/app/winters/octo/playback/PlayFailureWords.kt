package app.winters.octo.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import app.winters.octo.connection.GatewayWords
import app.winters.octo.subsonic.gateway.SignInPageAnswer
import javax.net.ssl.SSLException

// Which of the shared reasons a player error is, from Media3's error code
// and, for an HTTP answer, its status. A song found online (`outside`) was
// never on the server, so a "not found" for it is the server not sending it.
fun playFailureOf(errorCode: Int, httpStatus: Int? = null, outside: Boolean = false): PlayFailure = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        if ((httpStatus == 404 || httpStatus == 410) && !outside) PlayFailure.Missing else PlayFailure.Refused
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlayFailure.Missing
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_TIMEOUT,
    -> PlayFailure.Unreachable
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    -> PlayFailure.Unsupported
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    -> PlayFailure.Damaged
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    -> PlayFailure.Device
    else -> PlayFailure.Other
}

fun playFailureOf(error: PlaybackException, outside: Boolean = false): PlayFailure =
    playFailureOf(error.errorCode, (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode, outside)

// The words for a song that would not play: a sign-in page answering in
// place of the server says so, wherever it sits in the error's causes
// (Media3 wraps what the HTTP client threw); anything else, its reason's.
fun playFailureWords(error: PlaybackException, outside: Boolean = false): String =
    if (signInPageIn(error)) GatewayWords.PLAY_FAILURE else playFailureOf(error, outside).words

// Whether the connection's TLS failed (a certificate turned down, say),
// anywhere in this error's causes.
fun tlsFailureIn(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(16).any { it is SSLException }

// Whether a sign-in page answered in place of the server, anywhere in this
// error's causes.
fun signInPageIn(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(16).any { it is SignInPageAnswer }
