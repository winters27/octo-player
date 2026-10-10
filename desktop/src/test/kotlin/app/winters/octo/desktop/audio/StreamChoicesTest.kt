package app.winters.octo.desktop.audio

import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.playback.StartAfter
import app.winters.octo.playback.StreamQuality
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// The desktop's stream quality and "Start playing after", as the engine and
// the server hear them.
class StreamChoicesTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() = scope.cancel()

    @Test
    fun theOriginalFileUnlessASmallerSizeIsChosen() {
        val flac = Song("s1", "A", suffix = "flac", bitRate = 1_000)
        assertEquals(mapOf("format" to "raw"), streamFormat(flac, StreamQuality.Original))
        assertEquals(mapOf("format" to "mp3", "maxBitRate" to "192", "estimateContentLength" to "true"), streamFormat(flac, StreamQuality.Kbps192))
        // A lossy file already within the size plays as it is.
        assertEquals(mapOf("format" to "raw"), streamFormat(Song("s2", "B", suffix = "mp3", bitRate = 128), StreamQuality.Kbps192))
        assertEquals("mp3", streamFormat(Song("s3", "C", suffix = "mp3", bitRate = 320), StreamQuality.Kbps192)["format"])
    }

    @Test
    fun theDefaultsChangeNothing() {
        val settings = SettingsStore(File(folder.root, "settings.json"))
        assertEquals(StreamQuality.Original, settings.current.playback.streamQuality)
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
