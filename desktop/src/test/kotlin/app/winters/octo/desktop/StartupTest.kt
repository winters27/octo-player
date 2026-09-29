package app.winters.octo.desktop

import app.winters.octo.desktop.audio.FakeEngine
import app.winters.octo.desktop.audio.openPlayer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.settings.AppPlaces
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.currentOs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// What gets ready beside the window: the engine opened off the window's
// thread reaches the player, and one that would not open still leaves a
// working, silent app.
class StartupTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() = scope.cancel()

    private fun places() = AppPlaces(File(folder.root, "config"), File(folder.root, "cache"))

    private fun settings(places: AppPlaces) = SettingsStore(File(places.config, SettingsStore.FILE_NAME))

    @Test
    fun theEngineOpenedBesideTheWindowPlays() = runBlocking {
        val places = places()
        val settings = settings(places)
        val engine = FakeEngine()
        val parts = Startup(settings, places, currentOs()) { engine }.parts()
        assertSame(engine, parts.engine.getOrNull())
        // Signed out: nothing to restore.
        assertNull(parts.restored)
        val opened = openPlayer(settings, scope, { null }, opened = parts.engine)
        assertNull(opened.problem)
    }

    @Test
    fun anEngineThatWouldNotOpenLeavesTheSilentPlayer() = runBlocking {
        val places = places()
        val settings = settings(places)
        val parts = Startup(settings, places, currentOs()) { throw UnsatisfiedLinkError("no engine here") }.parts()
        assertTrue(parts.engine.isFailure)
        val opened = openPlayer(settings, scope, { null }, opened = parts.engine)
        assertTrue(opened.player is SilentPlayer)
        assertTrue(opened.problem!!.contains("no engine here"))
    }
}
