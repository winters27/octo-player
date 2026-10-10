package app.winters.octo.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

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
