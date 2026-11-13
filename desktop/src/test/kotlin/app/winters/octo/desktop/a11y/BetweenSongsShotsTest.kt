package app.winters.octo.desktop.a11y

import androidx.compose.runtime.CompositionLocalProvider
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.pages.LocalRowFlashMs
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.BetweenSongsChange
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.currentOs
import app.winters.octo.playback.BetweenSongs
import app.winters.octo.theme.ThemeChoice
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream

// Pictures of Settings' Playback page with the Between songs card in each
// choice, on a dark and a light theme and in a small window with large
// text; a row the choice hides asked for, lighting the choice; and the
// settings file's banners over the page: OCTO_SHOTS=1 ./gradlew
// :desktop:test --tests '*BetweenSongsShotsTest*' writes OCTO_SHOTS_DIR
// (else build/shots/between-songs).
class BetweenSongsShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private val out by lazy { File(System.getenv("OCTO_SHOTS_DIR") ?: "build/shots/between-songs").apply { mkdirs() } }

    private fun scene(
        width: Int = 1440,
        height: Int = 900,
        text: Float = 1f,
        dir: File = folder.newFolder(),
        flashMs: Long = 60_000,
        look: (AppSettings) -> AppSettings = { it },
    ) = A11yScene(
        dir,
        reduceMotion = true,
        width = width,
        height = height,
        textScale = text,
        player = SettingsWalk.ShapingPlayer(),
        look = look,
        wrap = { content -> CompositionLocalProvider(LocalRowFlashMs provides flashMs) { content() } },
    )

    // Saved to this test's folder (A11yScene's own shot keeps to build/shots).
    private fun A11yScene.picture(name: String) {
        val image = settle(700)
        File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        image.close()
    }

    private fun A11yScene.choose(choice: BetweenSongs) {
        onUi { app.settings.editBetweenSongs(BetweenSongsChange.Choose(choice)) }
        render(4)
    }

    private fun A11yScene.open(row: String? = null) {
        onUi { app.openSettings(SettingsKey.Playback, row) }
        render(6)
    }

    @Test(timeout = 300_000)
    fun drawTheCardInEachChoice() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        for (theme in listOf(ThemeChoice.Octo, ThemeChoice.Daylight)) {
            scene(look = { it.copy(appearance = it.appearance.copy(theme = theme)) }).use { s ->
                s.open()
                for (choice in BetweenSongs.entries) {
                    s.choose(choice)
                    s.picture("between-songs-${choice.name.lowercase()}-${theme.name.lowercase()}")
                }
            }
        }
        // A small window with the largest text, brought to the card by its
        // id with the light let go at once.
        scene(width = 960, height = 640, text = 1.3f, flashMs = 1).use { s ->
            for (choice in BetweenSongs.entries) {
                s.choose(choice)
                s.open("crossfade")
                s.picture("between-songs-${choice.name.lowercase()}-960x640-text130")
            }
        }
        // Length asked for while Automix hides it: the choice is lit.
        scene().use { s ->
            s.open("crossfade.longest-blend")
            s.picture("between-songs-automix-length-asked")
        }
    }

    @Test(timeout = 300_000)
    fun drawTheBannersOverThePage() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        // Another program reading the file without sharing it: Windows lets
        // it be read and refuses its replace.
        if (currentOs() == DesktopOs.Windows) {
            val dir = folder.newFolder()
            scene(dir = dir).use { s ->
                s.open()
                FileInputStream(File(dir, "settings.json")).use { reader ->
                    s.onUi { s.app.settings.update { it.copy(sidePanel = "queue") } }
                    s.render(6)
                    assertTrue("held", s.app.settings.locked.value)
                    s.picture("banner-held-over-playback")
                    reader.read()
                }
                s.onUi { s.app.settings.tryAgain() }
                s.render(6)
                s.picture("banner-gone-after-try-again")
            }
        }
        // Open without my settings: a session on the defaults has no server,
        // so it opens on the sign-in page, under its banner.
        A11yScene(folder.newFolder(), reduceMotion = true, store = SettingsStore.readOnly(), signedOut = true).use { s ->
            s.render(6)
            s.picture("banner-read-only-over-sign-in")
        }
        // A settings file that is not settings, set aside before the window.
        val broken = folder.newFolder()
        File(broken, "settings.json").writeText("{ not settings")
        scene(dir = broken).use { s ->
            s.open()
            assertTrue(s.app.settings.setAside.value != null)
            s.picture("banner-set-aside-over-playback")
        }
    }
}
