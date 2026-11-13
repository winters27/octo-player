package app.winters.octo.desktop.a11y

import app.winters.octo.desktop.hotkeys.FakeHotkeys
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.settings.AppPlaces
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.HeldDisk
import app.winters.octo.desktop.settings.SettingsHold
import app.winters.octo.desktop.settings.SettingsOpen
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.StepClock
import app.winters.octo.desktop.system.Notifier
import app.winters.octo.desktop.system.SystemIntegration
import app.winters.octo.desktop.ui.SETTINGS_HELD_KEPT
import app.winters.octo.desktop.ui.SETTINGS_NOT_SAVED
import app.winters.octo.desktop.ui.SETTINGS_TRY_AGAIN
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

// A held settings file through the whole window and the real ties to the
// system: the banner over Home and over the sign-in page, and the system
// notice once per hold, told to a pretend notifier. With OCTO_SHOTS=1 the
// two pages are drawn into OCTO_SHOTS_DIR (else build/shots) too.
class SettingsHeldWindowTest {
    @get:Rule val folder = TemporaryFolder()

    private fun heldStore(dir: File, disk: HeldDisk): SettingsStore =
        (SettingsStore.openOn(disk, File(dir, SettingsStore.FILE_NAME), clock = StepClock()) as SettingsOpen.Opened).store

    private class Told : Notifier {
        val shown = CopyOnWriteArrayList<Pair<String, String>>()

        override fun show(title: String, text: String) {
            shown += title to text
        }
    }

    @Test(timeout = 120_000)
    fun theHeldBannerShowsOverHomeAndOverSignIn() {
        for (signedOut in listOf(false, true)) {
            val where = if (signedOut) "the sign-in page" else "Home"
            val dir = folder.newFolder()
            // The scene's own first change is refused, so the file is held
            // before the window draws.
            val store = heldStore(dir, HeldDisk().apply { refuseReplace += SettingsStore.FILE_NAME })
            A11yScene(dir, reduceMotion = true, store = store, signedOut = signedOut).use { s ->
                s.render(6)
                assertEquals(where, SettingsHold.Kept, store.hold.value)
                if (signedOut) assertNull(s.app.connection) else assertEquals(Page.Home, s.app.navigator.current.page)
                val nodes = s.nodes()
                val banner = nodes.firstOrNull { it.name() == SETTINGS_HELD_KEPT }
                assertNotNull("the banner over $where", banner)
                assertTrue("Try again on it", s.nodes(merged = true).any { it.name() == SETTINGS_TRY_AGAIN && it.clickable })
                val top = banner!!.boundsInRoot
                // The page itself, under the banner and within its width.
                val below = nodes.filter { it !== banner && it.boundsInRoot.top >= top.bottom && it.boundsInRoot.left >= top.left - 1 }
                assertTrue("$where's words under it", below.any { it.name().isNotBlank() })
                if (signedOut) assertTrue("the sign-in page's Octo under it", below.any { it.name() == "Octo" })
                if (System.getenv("OCTO_SHOTS") == "1") {
                    val out = File(System.getenv("OCTO_SHOTS_DIR") ?: "build/shots").apply { mkdirs() }
                    val image = s.settle(700)
                    File(out, "banner-held-over-${if (signedOut) "sign-in" else "home"}.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    image.close()
                }
            }
        }
    }

    // The system notice, driven through the app's own system ties: one for
    // a hold however many writes it refuses (and when the pending file is
    // refused too), one more for the next hold after the file was written.
    @Test(timeout = 60_000)
    fun aHeldWriteIsToldOncePerHold() {
        val dir = folder.newFolder()
        val disk = HeldDisk()
        val store = heldStore(dir, disk)
        A11yScene(dir, reduceMotion = true, store = store).use { s ->
            val told = Told()
            lateinit var system: SystemIntegration
            s.onUi {
                system = SystemIntegration(
                    s.app,
                    AppPlaces(File(dir, "config").apply { mkdirs() }, File(dir, "cache").apply { mkdirs() }),
                    DesktopOs.Windows,
                    null,
                    hotkeys = FakeHotkeys(),
                    notifications = told,
                )
            }
            try {
                system.tellSettingsHolds()
                // The notices are told on the window's thread: whatever was
                // handed to it has run once it answers twice.
                fun handled() = repeat(2) { s.onUi {} }
                handled()
                assertTrue("nothing while the file is fine", told.shown.isEmpty())
                disk.refuseReplace += SettingsStore.FILE_NAME
                store.update { it.copy(sidePanel = "lyrics") }
                handled()
                store.update { it.copy(sidePanel = "queue") }
                handled()
                disk.refuseReplace += "settings.pending.json"
                store.update { it.copy(sidePanel = "info") }
                handled()
                assertEquals(SettingsHold.Unkept, store.hold.value)
                assertEquals("one notice for the hold", listOf(SETTINGS_NOT_SAVED to SETTINGS_HELD_KEPT), told.shown.toList())
                disk.refuseReplace.clear()
                store.tryAgain()
                handled()
                assertEquals(SettingsHold.Free, store.hold.value)
                disk.refuseReplace += SettingsStore.FILE_NAME
                store.update { it.copy(sidePanel = "lyrics") }
                handled()
                assertEquals("one more for the next hold", List(2) { SETTINGS_NOT_SAVED to SETTINGS_HELD_KEPT }, told.shown.toList())
            } finally {
                system.close()
            }
        }
    }
}
