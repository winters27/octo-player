package app.winters.octo.output

import app.winters.octo.playback.StreamRef
import app.winters.octo.playback.StreamRequest
import app.winters.octo.playback.openedFileId
import app.winters.octo.playback.radioId
import app.winters.octo.playback.streamUri
import org.junit.Assert.assertEquals
import org.junit.Test

class CastSourcesTest {
    @Test
    fun serverSongsAreFetchedFromTheServer() {
        val ref = StreamRef("server:home", "abc 1", "audio/flac", 900_000)
        assertEquals(CastSource.Server(ref), castSourceOf("song-1", streamUri(ref)))
    }

    @Test
    fun phoneFilesAreServedByThePhone() {
        assertEquals(CastSource.PhoneFile("content://media/external/audio/media/42"), castSourceOf("device:42", "content://media/external/audio/media/42"))
        assertEquals(CastSource.PhoneFile("file:///data/octo/downloads/a.flac"), castSourceOf("song-2", "file:///data/octo/downloads/a.flac"))
        // A bare path is a file on the phone too.
        assertEquals(CastSource.PhoneFile("file:///sdcard/Music/a.mp3"), castSourceOf("song-3", "/sdcard/Music/a.mp3"))
    }

    @Test
    fun radioPassesItsAddressStraightThrough() {
        val id = radioId("https://radio.example/stream.mp3", "Example FM")
        assertEquals(CastSource.Web("https://radio.example/stream.mp3", live = true), castSourceOf(id, "https://radio.example/stream.mp3"))
    }

    @Test
    fun aRadioStationThatIsNotOnTheWebIsSkipped() {
        val id = radioId("rtsp://radio.example/live", "Odd")
        assertEquals(CastSource.Skipped(SkipReason.Unservable), castSourceOf(id, "rtsp://radio.example/live"))
    }

    @Test
    fun filesOpenedFromOtherAppsAreSkipped() {
        val id = openedFileId("content://com.other.app/files/1", "A song")
        assertEquals(CastSource.Skipped(SkipReason.OpenedFile), castSourceOf(id, "content://com.other.app/files/1"))
    }

    @Test
    fun songsWithNowhereToFetchFromAreSkipped() {
        assertEquals(CastSource.Skipped(SkipReason.Unservable), castSourceOf("song-4", null))
        assertEquals(CastSource.Skipped(SkipReason.Unservable), castSourceOf("song-5", ""))
        assertEquals(CastSource.Skipped(SkipReason.Unservable), castSourceOf("song-6", "octo-stream://song/"))
        assertEquals(CastSource.Skipped(SkipReason.Unservable), castSourceOf("song-7", "ftp://host/a.mp3"))
    }

    @Test
    fun aTypeTheDeviceCannotPlayIsMadeAnMp3ByTheServer() {
        val cast = { type: String -> type in CastAudioTypes }
        // FLAC plays as it is.
        assertEquals(StreamRequest(null), requestForDevice(StreamRequest(null), "audio/flac", cast))
        // APE does not, so the server converts it.
        assertEquals(StreamRequest(DEVICE_MP3_KBPS), requestForDevice(StreamRequest(null), "audio/x-ape", cast))
        // An MP3 already asked for stays as asked.
        assertEquals(StreamRequest(192), requestForDevice(StreamRequest(192), "audio/x-ape", cast))
    }

    @Test
    fun typesComeFromNamesWhenNothingElseSays() {
        assertEquals("audio/flac", mimeFromName("Song.FLAC"))
        assertEquals("audio/mpeg", mimeFromName("a.b.mp3"))
        assertEquals(null, mimeFromName("noext"))
        assertEquals("flac", extensionFor("audio/x-flac"))
        assertEquals("jpg", extensionFor("image/jpeg"))
    }
}
