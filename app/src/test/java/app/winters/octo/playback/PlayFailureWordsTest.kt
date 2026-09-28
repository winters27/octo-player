package app.winters.octo.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayFailureWordsTest {
    @Test
    fun aMissingSongIsToldApartFromARefusal() {
        assertEquals(PlayFailure.Missing, playFailureOf(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertEquals(PlayFailure.Refused, playFailureOf(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403))
        assertEquals(PlayFailure.Missing, playFailureOf(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
    }

    @Test
    fun eachKindOfFailureHasItsWords() {
        assertEquals(PlayFailure.Unreachable, playFailureOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(PlayFailure.Unsupported, playFailureOf(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(PlayFailure.Damaged, playFailureOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertEquals(PlayFailure.Device, playFailureOf(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertEquals(PlayFailure.Other, playFailureOf(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test
    fun theLineNamesTheSongAndTheReason() {
        assertEquals("Skipped Airbag. That song isn't on the server any more.", skippedLine("Airbag", PlayFailure.Missing.words))
    }
}
