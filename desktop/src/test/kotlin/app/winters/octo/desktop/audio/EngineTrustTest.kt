package app.winters.octo.desktop.audio

import app.winters.octo.desktop.server.ServerSecurity
import app.winters.octo.desktop.settings.SettingsStore
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

// The certificates the listener trusted reaching the audio engine, which
// fetches songs itself: at the start, and whenever one is trusted.
class EngineTrustTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() = scope.cancel()

    private fun settings() = SettingsStore(File(folder.root, "settings.json"))

    @Test
    fun theEngineStartsWithTheSavedPins() {
        val saved = settings()
        saved.update { it.copy(trustedCertificates = mapOf("music.test" to "ab".repeat(32))) }
        val engine = FakeEngine()
        keepTrust(engine, saved, scope)
        // Given before anything could be played, with no coroutine to wait for.
        assertEquals(mapOf("music.test" to "ab".repeat(32)), engine.pins)
    }

    @Test
    fun aCertificateTrustedLaterReachesTheEngineOnce() {
        val saved = settings()
        val engine = FakeEngine()
        keepTrust(engine, saved, scope)
        assertEquals(emptyMap<String, String>(), engine.pins)

        // Trusted the way the sign-in question does it: by host, marked up.
        ServerSecurity(saved).trust("Music.Test", "AB:".repeat(31) + "AB")
        assertEquals(mapOf("music.test" to "ab".repeat(32)), engine.pins)

        // Other settings changing do not send them again.
        saved.update { it.copy(songSort = "title") }
        assertEquals(listOf("trust 0", "trust 1"), engine.calls)
    }
}
