package app.winters.octo.desktop.audio

import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.playback.StartAfter
import app.winters.octo.subsonic.StreamQuality
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import app.winters.octo.ui.family.streamParams
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// The desktop's own stream quality and "Start playing after", as the engine and
// the server hear them.
class StreamChoicesTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() = scope.cancel()

    @Test
    fun originalAsksForTheFileAsItIs() {
        val client = SubsonicClient("http://music.test/".toHttpUrl(), Credentials("u", "p"), OkHttpClient())
        val original = ServerSongs(params = { streamParams(appPicks = true, quality = StreamQuality.Original) }, client = { client })
        val url = original.addressOf(Song("s1", "A", suffix = "flac"))!!.source
        assertTrue(url, url.contains("format=raw"))
        assertFalse(url, url.contains("maxBitRate"))
        val smaller = ServerSongs(params = { streamParams(appPicks = true, quality = StreamQuality.Standard) }, client = { client })
        val capped = smaller.addressOf(Song("s1", "A", suffix = "flac"))!!.source
        assertTrue(capped, capped.contains("format=opus") && capped.contains("maxBitRate=160"))
    }

    // A lossy song already at or under the quality streams as it is; a
    // bigger or lossless one is made smaller. Red when every song at a
    // smaller quality asks for Opus.
    @Test
    fun aLossySongAtOrUnderTheQualityStreamsAsItIs() {
        val client = SubsonicClient("http://music.test/".toHttpUrl(), Credentials("u", "p"), OkHttpClient())
        val smaller = ServerSongs(params = { streamParams(appPicks = true, quality = StreamQuality.Standard) }, client = { client })
        val small = smaller.addressOf(Song("s1", "A", suffix = "mp3", contentType = "audio/mpeg", bitRate = 128))!!.source
        assertTrue(small, small.contains("format=raw") && !small.contains("maxBitRate"))
        val outside = smaller.addressOf(Song("s2", "B", suffix = "m4a", contentType = "audio/mp4", bitRate = 128))!!.source
        assertTrue(outside, outside.contains("format=raw"))
        val big = smaller.addressOf(Song("s3", "C", suffix = "mp3", contentType = "audio/mpeg", bitRate = 320))!!.source
        assertTrue(big, big.contains("format=opus") && big.contains("maxBitRate=160"))
        val unknown = smaller.addressOf(Song("s4", "D", suffix = "mp3"))!!.source
        assertTrue(unknown, unknown.contains("format=opus"))
        // A song since fetched into the library asks as the quality says.
        val landed = ServerSongs(params = { streamParams(appPicks = true, quality = StreamQuality.Standard) }, landed = { "lib-1" }, client = { client })
        val file = landed.addressOf(Song("s5", "E", suffix = "m4a", contentType = "audio/mp4", bitRate = 128))!!.source
        assertTrue(file, file.contains("id=lib-1") && file.contains("format=opus"))
    }

    // A settings file from before the Download format was kept reads as
    // Opus; a pick is kept. Red when the default is MP3 or the field is lost.
    @Test
    fun theDownloadFormatIsOpusUntilPickedAndAPickIsKept() {
        val file = File(folder.root, "settings.json")
        file.writeText("""{"offline":{"folder":"","downloadQuality":"Standard"}}""")
        val old = SettingsStore(file)
        assertEquals("Opus", old.current.offline.downloadFormat)
        assertEquals(app.winters.octo.playback.DownloadFormat.Opus, app.winters.octo.playback.DownloadFormat.named(old.current.offline.downloadFormat))
        assertEquals("Standard", old.current.offline.downloadQuality)
        old.update { it.copy(offline = it.offline.copy(downloadFormat = app.winters.octo.playback.DownloadFormat.Mp3.name)) }
        assertEquals("Mp3", SettingsStore(file).current.offline.downloadFormat)
    }

    @Test
    fun aPickedQualityIsKept() {
        val file = File(folder.root, "settings.json")
        SettingsStore(file).update { it.copy(playback = it.playback.copy(streamQuality = StreamQuality.DataSaver.name)) }
        assertEquals("DataSaver", SettingsStore(file).current.playback.streamQuality)
    }

    @Test
    fun theDefaultsChangeNothing() {
        val settings = SettingsStore(File(folder.root, "settings.json"))
        assertEquals(StreamQuality.Original.name, settings.current.playback.streamQuality)
        assertEquals(StartAfter.Short, settings.current.playback.startAfter)
    }

    @Test
    fun theEngineHearsStartAfterAtOnceAndOnEachChange() {
        val settings = SettingsStore(File(folder.root, "settings.json"))
        val engine = FakeEngine()
        keepStartAfter(engine, settings, scope)
        settings.update { it.copy(playback = it.playback.copy(startAfter = StartAfter.Safe)) }
        settings.update { it.copy(songSort = "title") }
        assertEquals(listOf("start after 1000", "start after 5000"), engine.calls)
    }
}
