package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.design.ArrowKeys
import app.winters.octo.design.FocusVisibility
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.OctoColors
import app.winters.octo.design.ProvideOctoTheme
import app.winters.octo.design.ProvideWindowLook
import app.winters.octo.design.TypingState
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.HeldAtStart
import app.winters.octo.desktop.settings.HeldDisk
import app.winters.octo.desktop.settings.SettingsHold
import app.winters.octo.desktop.settings.SettingsOpen
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.StepClock
import app.winters.octo.desktop.settings.settled
import app.winters.octo.theme.ThemeChoice
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

// The "Settings are locked" window, which waits for a held settings file
// before the app opens: it looks again by itself, Try again looks at once,
// and Open without my settings gives a session that saves nothing. The
// banners over the pages while a file is held, with Try again. With
// OCTO_SHOTS=1 each is drawn into OCTO_SHOTS_DIR (else build/shots) too.
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SettingsLockedTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file() = File(folder.root, "octo/${SettingsStore.FILE_NAME}")

    private class Scene(width: Int, height: Int, scale: Float = 1f, theme: ThemeChoice = ThemeChoice.Octo, content: @Composable () -> Unit) : AutoCloseable {
        lateinit var scene: ImageComposeScene

        init {
            val look = AppSettings().let { it.copy(appearance = it.appearance.copy(theme = theme)) }
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene((width * scale).toInt(), (height * scale).toInt(), Density(scale)) {
                    ProvideOctoTheme(paletteFor(look, deviceDark = false)) {
                        ProvideWindowLook(reduceMotion = true, focus = FocusVisibility(), arrows = ArrowKeys()) {
                            CompositionLocalProvider(LocalTyping provides TypingState()) {
                                Box(Modifier.fillMaxSize().background(OctoColors.Background)) { content() }
                            }
                        }
                    }
                }
            }
            render()
        }

        fun render(frames: Int = 3) = repeat(frames) {
            SwingUtilities.invokeAndWait { scene.render() }
            Thread.sleep(10)
        }

        fun nodes(): List<SemanticsNode> {
            val all = mutableListOf<SemanticsNode>()
            fun walk(node: SemanticsNode) {
                all += node
                node.children.forEach(::walk)
            }
            SwingUtilities.invokeAndWait { scene.semanticsOwners.forEach { walk(it.rootSemanticsNode) } }
            return all
        }

        // The words drawn, a line break read as a space.
        fun words(): List<String> = nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { t -> t.text }?.replace('\n', ' ') }

        fun click(name: String) {
            val node = nodes().first { (it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() ?: it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text) == name && it.config.getOrNull(SemanticsActions.OnClick) != null }
            SwingUtilities.invokeAndWait { node.config[SemanticsActions.OnClick].action?.invoke() }
            render()
        }

        fun until(what: String, check: () -> Boolean) {
            val end = System.currentTimeMillis() + 5_000
            while (!check() && System.currentTimeMillis() < end) render(1)
            assertTrue("waited too long for $what", check())
        }

        fun save(name: String) {
            if (System.getenv("OCTO_SHOTS") != "1") return
            val out = File(System.getenv("OCTO_SHOTS_DIR") ?: "build/shots").apply { mkdirs() }
            render(6)
            lateinit var bytes: ByteArray
            SwingUtilities.invokeAndWait { bytes = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes }
            File(out, "$name.png").writeBytes(bytes)
        }

        override fun close() {
            runCatching { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }

    // The real file, written once.
    private fun writeReal(): AppSettings {
        SettingsStore(file()).update { it.copy(sidePanel = "queue", appearance = it.appearance.copy(homeSections = "charts,-pinned,recentlyPlayed")) }
        return SettingsStore(file()).current
    }

    private fun heldAgainstReads(): Pair<HeldDisk, SettingsOpen.Held> {
        val disk = HeldDisk().apply { refuseReads += SettingsStore.FILE_NAME }
        val open = SettingsStore.openOn(disk, file(), clock = StepClock(), waitMs = 0)
        return disk to (open as SettingsOpen.Held)
    }

    // The window locked window's page, about as wide as the window.
    @Composable
    private fun Locked(held: SettingsOpen.Held, retryEveryMs: Long, onOpen: (SettingsStore) -> Unit) =
        Column(Modifier.width(460.dp)) { SettingsLocked(held, retryEveryMs, onOpen = onOpen) }

    @Test(timeout = 30_000)
    fun theLockedWindowLooksAgainByItselfAndOpensOnTheRealFile() {
        val real = writeReal()
        val (disk, held) = heldAgainstReads()
        val opened = AtomicReference<SettingsStore?>(null)
        Scene(460, 220) { Locked(held, 20) { opened.set(it) } }.use { s ->
            assertTrue(s.words().containsAll(listOf(SETTINGS_LOCKED_TITLE, SETTINGS_LOCKED_TEXT, SETTINGS_TRY_AGAIN, SETTINGS_OPEN_WITHOUT)))
            assertFalse("no file named while another program has it", file().path in s.words())
            assertTrue("the title is a heading", s.nodes().any { it.config.getOrNull(SemanticsProperties.Heading) != null })
            s.render(10)
            assertNull("still held: nothing opens, not on the defaults either", opened.get())
            s.save("settings-locked-window")
            disk.refuseReads.clear()
            s.until("the window's own look") { opened.get() != null }
            val store = opened.get()!!
            assertFalse(store.readOnly)
            assertEquals("the real file, its shelf order as it is", real, store.current)
        }
    }

    @Test(timeout = 30_000)
    fun tryAgainLooksAtOnce() {
        val real = writeReal()
        val (disk, held) = heldAgainstReads()
        val opened = AtomicReference<SettingsStore?>(null)
        Scene(460, 220) { Locked(held, 600_000) { opened.set(it) } }.use { s ->
            s.click(SETTINGS_TRY_AGAIN)
            assertNull(opened.get())
            disk.refuseReads.clear()
            s.click(SETTINGS_TRY_AGAIN)
            assertTrue("Try again opened the file", opened.get() != null)
            assertEquals(real, opened.get()!!.current)
        }
    }

    // A file this account may not read: the window says so and where the
    // file is; once another program has it instead, its own look says that.
    @Test(timeout = 30_000)
    fun aFileOctoMayNotReadSaysSoAndWhereItIs() {
        writeReal()
        val disk = HeldDisk().apply { denyReads += SettingsStore.FILE_NAME }
        val held = SettingsStore.openOn(disk, file(), clock = StepClock(), waitMs = 0) as SettingsOpen.Held
        Scene(460, 300) { Locked(held, 20) {} }.use { s ->
            assertTrue(s.words().toString(), s.words().containsAll(listOf(SETTINGS_NO_ACCESS_TITLE, SETTINGS_NO_ACCESS_TEXT, file().path, SETTINGS_TRY_AGAIN, SETTINGS_OPEN_WITHOUT)))
            disk.denyReads.clear()
            disk.refuseReads += SettingsStore.FILE_NAME
            s.until("the in-use words") { SETTINGS_LOCKED_TEXT in s.words() }
            assertTrue(SETTINGS_LOCKED_TITLE in s.words())
            assertFalse("no file named", file().path in s.words())
        }
    }

    // A damaged file Octo cannot move aside: the window says so and where
    // it is, and opens fresh once the person has moved it, with no banner
    // (Octo set nothing aside).
    @Test(timeout = 30_000)
    fun aDamagedFileOctoCannotMoveSaysSoAndOpensOnceMoved() {
        file().parentFile.mkdirs()
        file().writeText("{ not settings")
        val disk = HeldDisk().apply { refuseMove += SettingsStore.FILE_NAME }
        val held = SettingsStore.openOn(disk, file(), clock = StepClock(), waitMs = 3_000, retryMs = 100) as SettingsOpen.Held
        val opened = AtomicReference<SettingsStore?>(null)
        Scene(460, 300) { Locked(held, 20) { opened.set(it) } }.use { s ->
            assertTrue(s.words().toString(), s.words().containsAll(listOf(SETTINGS_DAMAGED_TITLE, SETTINGS_DAMAGED_TEXT, file().path, SETTINGS_TRY_AGAIN, SETTINGS_OPEN_WITHOUT)))
            s.render(10)
            assertNull(opened.get())
            assertTrue(file().renameTo(File(folder.root, "moved-by-hand.json")))
            s.until("the window's own look") { opened.get() != null }
            assertEquals(AppSettings().settled(), opened.get()!!.current)
            assertNull(opened.get()!!.setAside.value)
        }
    }

    // Open without my settings: a session on the defaults, given once; the
    // window looks no more after it.
    @Test(timeout = 30_000)
    fun openWithoutMySettingsGivesASessionThatSavesNothing() {
        writeReal()
        val before = file().readBytes()
        val (disk, held) = heldAgainstReads()
        val opens = AtomicInteger()
        val opened = AtomicReference<SettingsStore?>(null)
        Scene(460, 220) {
            Locked(held, 20) {
                opens.incrementAndGet()
                opened.set(it)
            }
        }.use { s ->
            s.click(SETTINGS_OPEN_WITHOUT)
            val store = opened.get()!!
            assertTrue(store.readOnly)
            assertEquals(AppSettings().settled(), store.current)
            disk.refuseReads.clear()
            Thread.sleep(120)
            s.render(4)
            assertEquals("given once", 1, opens.get())
            store.update { it.copy(sidePanel = "lyrics") }
            assertTrue(before.contentEquals(file().readBytes()))
        }
    }

    // The banner over the pages while the file is held, with Try again; it
    // goes once the file is written.
    @Test(timeout = 30_000)
    fun theHeldBannerShowsWithTryAgainAndGoesOnceWritten() {
        writeReal()
        val disk = HeldDisk().apply { refuseReplace += SettingsStore.FILE_NAME }
        val store = (SettingsStore.openOn(disk, file(), clock = StepClock()) as SettingsOpen.Opened).store
        Scene(720, 120) { Column(Modifier.padding(16.dp)) { SettingsBanners(store) } }.use { s ->
            assertTrue("nothing while the file is fine", s.words().isEmpty())
            store.update { it.copy(sidePanel = "lyrics") }
            s.render(4)
            assertEquals(SettingsHold.Kept, store.hold.value)
            assertTrue(s.words().toString(), SETTINGS_HELD_KEPT in s.words())
            assertTrue(SETTINGS_TRY_AGAIN in s.words())
            s.click(SETTINGS_TRY_AGAIN)
            assertTrue("still held", SETTINGS_HELD_KEPT in s.words())
            disk.refuseReplace.clear()
            s.click(SETTINGS_TRY_AGAIN)
            assertFalse(store.locked.value)
            assertTrue("gone once written", s.words().isEmpty())
        }
    }

    // Every banner, for looking at, on the darkest and the lightest theme.
    @Test(timeout = 60_000)
    fun drawEveryBanner() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        writeReal()
        val kept = (SettingsStore.openOn(HeldDisk().apply { refuseReplace += SettingsStore.FILE_NAME }, file(), clock = StepClock()) as SettingsOpen.Opened).store
        kept.update { it.copy(sidePanel = "lyrics") }
        val unkept = (SettingsStore.openOn(HeldDisk().apply { refuseReplace += setOf(SettingsStore.FILE_NAME, "settings.pending.json") }, file(), clock = StepClock()) as SettingsOpen.Opened).store
        unkept.update { it.copy(sidePanel = "info") }
        File(folder.root, "broken").mkdirs()
        val brokenFile = File(folder.root, "broken/${SettingsStore.FILE_NAME}").apply { writeText("{ not settings") }
        val fresh = (SettingsStore.openOn(HeldDisk(), brokenFile, clock = StepClock()) as SettingsOpen.Opened).store
        val readOnly = SettingsStore.readOnly()
        for (theme in listOf(ThemeChoice.Octo, ThemeChoice.Daylight)) {
            for ((name, store) in listOf("kept" to kept, "unkept" to unkept, "set-aside" to fresh, "read-only" to readOnly)) {
                Scene(760, 96, scale = 2f, theme = theme) { Column(Modifier.padding(16.dp)) { SettingsBanners(store) } }.use { it.save("settings-banner-$name-${theme.name.lowercase()}") }
            }
            val held = heldAgainstReads().second
            Scene(460, 220, scale = 2f, theme = theme) { Locked(held, 600_000) {} }.use { it.save("settings-locked-window-${theme.name.lowercase()}") }
            // Each hold at the start, at one height, on a path as the app
            // has it on Windows.
            for (why in HeldAtStart.entries) {
                val shown = SettingsOpen.Held(why, File("C:\\Users\\Listener\\AppData\\Roaming\\Octo\\settings.json")) { error("not looked at again") }
                val name = why.name.replace(Regex("(?<=.)([A-Z])"), "-$1").lowercase()
                Scene(460, 300, scale = 2f, theme = theme) { Locked(shown, 600_000) {} }.use { it.save("settings-locked-window-$name-${theme.name.lowercase()}") }
            }
        }
    }
}
