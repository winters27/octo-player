package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.winters.octo.design.GlassField
import app.winters.octo.design.OctoColors
import app.winters.octo.design.ProvideOctoTheme
import app.winters.octo.design.surfacesOf
import app.winters.octo.desktop.a11y.A11yScene
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.pages.addServer
import app.winters.octo.desktop.readIn
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.themes
import app.winters.octo.desktop.songJson
import app.winters.octo.player.immersive.CoverFadeMs
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.pickTheme
import app.winters.octo.theme.resolvePalette
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.swing.SwingUtilities
import kotlin.math.pow

// How many steps of 255 the full player's wash may move a channel between
// two starts of the same window.
private const val WASH_STEPS = 2

// The themes as the window draws them: the tokens follow the theme, what
// sits over the full player keeps Octo's dark look, a theme switched while
// the window is open draws exactly what a fresh start in it draws, and a
// field's hint reads in every theme. With OCTO_SHOTS=1 every theme's main
// places are kept as pictures (in OCTO_SHOTS_DIR when set).
class ThemeShotsTest {
    @get:Rule val folder = TemporaryFolder()

    // The settings with `theme` picked and the window's glow off, so what is
    // drawn holds still; `before` picked first, as a window switched
    // through them has them in its Day and Night slots.
    private fun inTheme(theme: ThemeChoice, accent: AccentChoice = AccentChoice.Octo, glow: Boolean = false, before: List<ThemeChoice> = emptyList()): (AppSettings) -> AppSettings = { settings ->
        val themes = (before + theme).fold(settings.appearance.themes, ::pickTheme)
        settings.copy(
            appearance = settings.appearance.copy(
                ambientGlow = glow,
                accent = accent,
                theme = themes.theme,
                themeLight = themes.light,
                themeDark = themes.dark,
                themeMatch = themes.match,
            ),
        )
    }

    private fun Image.pixels(): ByteArray = Bitmap.makeFromImage(this).readPixels()!!

    // How two frames differ: how many pixels, and the most any channel
    // differs by, and where.
    private class Difference(val count: Int, val most: Int, val at: Pair<Int, Int>) {
        override fun toString() = if (count == 0) "the same" else "$count pixels differ, at most by $most of 255 at $at"
    }

    // Pixels inside `skip` are left out.
    private fun difference(a: Image, b: Image, skip: List<Rect> = emptyList()): Difference {
        check(a.width == b.width && a.height == b.height) { "sizes differ" }
        val one = a.pixels()
        val two = b.pixels()
        var count = 0
        var most = 0
        var mostAt = 0
        for (i in one.indices step 4) {
            val x = (i / 4 % a.width).toFloat()
            val y = (i / 4 / a.width).toFloat()
            if (skip.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom }) continue
            var differs = false
            for (c in 0 until 4) {
                val delta = kotlin.math.abs((one[i + c].toInt() and 0xFF) - (two[i + c].toInt() and 0xFF))
                if (delta > 0) differs = true
                if (delta > most) {
                    most = delta
                    mostAt = i / 4
                }
            }
            if (differs) count++
        }
        return Difference(count, most, (mostAt % a.width) to (mostAt / a.width))
    }

    // `seen` matches `fresh` as closely as a second fresh start does: the
    // full player's wash is drawn from the clock, so two fresh starts may
    // differ by a step or two in a channel, whichever pair is measured;
    // anything past that spread is the switch's. Pixels inside `skip` are
    // left out.
    private fun sameAsFresh(what: String, seen: Image, fresh: Image, again: Image, skip: List<Rect> = emptyList()): String? {
        val spread = difference(fresh, again, skip)
        val found = difference(seen, fresh, skip)
        val allowed = maxOf(spread.most, WASH_STEPS)
        println("FLIP $what: against fresh $found; two fresh starts: $spread")
        return if (found.most <= allowed) null else "$what: $found, while two fresh starts differ at most by $allowed"
    }

    private fun keep(image: Image, name: String) {
        if (System.getenv("OCTO_SHOTS") != "1") return
        val dir = File(System.getenv("OCTO_SHOTS_DIR") ?: "build/shots").apply { mkdirs() }
        File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    // Each token reads the theme it is drawn in, and the window's page is
    // drawn in the theme's page color.
    @Test(timeout = 120_000)
    fun tokensFollowTheTheme() {
        for (theme in ThemeChoice.entries) {
            val palette = resolvePalette(theme, AccentChoice.Octo, null, false)
            val surfaces = surfacesOf(palette)
            val read = readIn(palette) {
                listOf(OctoColors.Background, OctoColors.BackgroundTertiary, OctoColors.SurfaceRaised, OctoColors.Edge, OctoColors.TextPrimary, OctoColors.TextSecondary, OctoColors.TextMuted, OctoColors.OffTrack, OctoColors.Complement, OctoColors.Third, OctoColors.Accent, OctoColors.AccentSelected, OctoColors.OnAccent)
            }
            val expected = listOf(surfaces.page, surfaces.card, surfaces.raised, surfaces.edge, surfaces.text, surfaces.secondary, surfaces.muted, surfaces.offTrack, surfaces.complement, surfaces.third, Color(palette.accents.accent), Color(palette.accents.selected), Color(palette.accents.onAccent))
            assertEquals("${theme.label}'s tokens", expected, read)
        }
        A11yScene(folder.newFolder(), look = inTheme(ThemeChoice.Octo)).use { s ->
            s.onUi { s.app.navigator.go(Page.Songs) }
            s.settle(600).close()
            for (theme in ThemeChoice.entries) {
                s.onUi { s.app.settings.pickTheme(theme) }
                val image = s.settle(700)
                val bitmap = Bitmap.makeFromImage(image)
                val counts = HashMap<Int, Int>()
                for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) counts.merge(bitmap.getColor(x, y), 1, Int::plus)
                val most = counts.maxBy { it.value }.key
                val page = resolvePalette(theme, AccentChoice.Octo, null, false).page
                assertEquals("${theme.label}: the window's most common color is its page", Integer.toHexString(page), Integer.toHexString(most))
                image.close()
            }
        }
    }

    // While the full player shows, a song menu and a server sheet opened
    // over it, and the title bar, draw in Octo's dark look in a light theme,
    // as Octo itself draws them (as closely as two Octo starts agree).
    // The field a sheet focuses draws a text cursor that blinks on its own
    // clock, so that field's box is left out of the comparison.
    @Test(timeout = 180_000)
    fun overArtKeepsTheDarkLook() {
        fun drawn(theme: ThemeChoice, popup: String): Pair<Image, Rect?> =
            A11yScene(folder.newFolder(), reduceMotion = true, look = inTheme(theme, AccentChoice.Coral)).use { s ->
                s.onUi {
                    val songs = s.app.library!!.index!!.songs
                    s.app.play(songs, 0)
                    s.player.pause()
                    s.player.seekTo(0)
                    s.app.fullPlayer = true
                }
                s.settle(1_000).close()
                s.onUi {
                    if (popup == "menu") s.app.popups.showAt(IntOffset(400, 120)) { close -> SongMenu(s.app, listOf(s.app.library!!.index!!.songs[2]), close) } else addServer(s.app)
                }
                // Compared once the pop-up and the cover have stopped moving.
                val image = s.settleStill(1_200, quiet = CoverFadeMs).also { keep(it, "theme-over-art-$popup-${theme.name.lowercase()}") }
                image to s.focused()?.let { Rect(it.positionInRoot, it.size.toSize()) }
            }
        val problems = mutableListOf<String>()
        for (popup in listOf("menu", "sheet")) {
            val (octo, octoFocus) = drawn(ThemeChoice.Octo, popup)
            val (again, againFocus) = drawn(ThemeChoice.Octo, popup)
            for (theme in listOf(ThemeChoice.Daylight, ThemeChoice.Paper)) {
                val (light, lightFocus) = drawn(theme, popup)
                val skip = listOfNotNull(octoFocus, againFocus, lightFocus)
                sameAsFresh("${theme.label}, the $popup over the full player, against Octo", light, octo, again, skip)?.let(problems::add)
                light.close()
            }
            octo.close()
            again.close()
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // Settings, a library list, a menu and the full player, switched from
    // Octo to Daylight to Moss in one window, draw what a window started in
    // Moss draws, as closely as two fresh starts agree.
    @Test(timeout = 300_000)
    fun liveFlipMatchesFreshRender() {
        val places = listOf<Pair<String, (A11yScene) -> Unit>>(
            "settings" to { s -> s.onUi { s.app.openSettings(SettingsKey.Look) } },
            "songs" to { s -> s.onUi { s.app.navigator.go(Page.Songs) } },
            "menu" to { s ->
                s.onUi {
                    s.app.navigator.go(Page.Songs)
                    s.app.popups.showAt(IntOffset(400, 120)) { close -> SongMenu(s.app, listOf(s.app.library!!.index!!.songs[2]), close) }
                }
            },
            "player" to { s ->
                s.onUi {
                    s.app.play(s.app.library!!.index!!.songs, 0)
                    s.player.pause()
                    s.player.seekTo(0)
                    s.app.fullPlayer = true
                }
            },
        )
        // Octo to Daylight to Moss everywhere; the pages also from a dark
        // theme into a light one, Dusk to Daylight.
        val runs = places.map { it to listOf(ThemeChoice.Daylight, ThemeChoice.Moss) } +
            places.filter { it.first == "settings" || it.first == "songs" }.map { it to listOf(ThemeChoice.Dusk, ThemeChoice.Daylight) }
        val problems = mutableListOf<String>()
        for ((place, picks) in runs) {
            val (name, open) = place
            val last = picks.last()
            val flipped = A11yScene(folder.newFolder(), reduceMotion = true, look = inTheme(ThemeChoice.Octo, glow = true)).use { s ->
                open(s)
                s.settle(800).close()
                // A menu shows the look it opened with, so it is opened again
                // after the switch, over a window that has switched.
                if (name == "menu") s.onUi { s.app.popups.close() }
                for (theme in picks) {
                    s.onUi { s.app.settings.pickTheme(theme) }
                    s.settle(800).close()
                }
                if (name == "menu") open(s)
                // The colors fade into the new theme: compared once the fade
                // has finished.
                s.settleStill(1_200, quiet = CoverFadeMs)
            }
            fun freshStart() = A11yScene(folder.newFolder(), reduceMotion = true, look = inTheme(last, glow = true, before = listOf(ThemeChoice.Octo) + picks.dropLast(1))).use { s ->
                open(s)
                s.settleStill(2_000, quiet = CoverFadeMs)
            }
            val fresh = freshStart()
            val again = freshStart()
            val tag = "theme-flip-$name-${last.name.lowercase()}"
            keep(flipped, "$tag-switched")
            keep(fresh, "$tag-fresh")
            sameAsFresh("$name, switched to ${last.label}", flipped, fresh, again)?.let(problems::add)
            again.close()
            flipped.close()
            fresh.close()
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // An empty field's hint against the field, in every theme: 4.5:1 or
    // more, as muted words.
    @Test(timeout = 60_000)
    fun placeholderReads() {
        val hint = "Search your library"
        val low = mutableListOf<String>()
        for (theme in ThemeChoice.entries) {
            val palette = resolvePalette(theme, AccentChoice.Octo, null, false)
            var ratio = 0.0
            SwingUtilities.invokeAndWait {
                val scene = ImageComposeScene(400, 120, Density(1f)) {
                    ProvideOctoTheme(palette) {
                        Box(Modifier.fillMaxSize().background(OctoColors.Background).padding(40.dp)) {
                            GlassField("", {}, Modifier.width(320.dp), placeholder = hint)
                        }
                    }
                }
                try {
                    scene.render(0L)
                    val image = scene.render(1_000_000_000L)
                    keep(image, "theme-placeholder-${theme.name.lowercase()}")
                    val node = scene.semanticsOwners.flatMap { all(it.unmergedRootSemanticsNode) }
                        .first { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { t -> t.text } == hint }
                    ratio = measured(image, node.boundsInRoot)
                } finally {
                    scene.close()
                }
            }
            println("CONTRAST ${"%.2f".format(ratio)} (needs 4.5) ${theme.label}: empty field's hint")
            if (ratio < 4.5) low += "${theme.label}: ${"%.2f".format(ratio)}"
        }
        assertTrue("hints under 4.5:1: $low", low.isEmpty())
    }

    // The pretend server's OK Computer, with its three songs, for the album
    // page.
    private val okComputer = listOf(
        songJson("s1", "Airbag", artist = "Radiohead", album = "OK Computer", albumId = "a1", duration = 284),
        songJson("s2", "Paranoid Android", artist = "Radiohead", album = "OK Computer", albumId = "a1", duration = 387),
        songJson("s3", "Karma Police", artist = "Radiohead", album = "OK Computer", albumId = "a1", duration = 261),
    ).joinToString(",").let { """"album":{"id":"a1","name":"OK Computer","artist":"Radiohead","year":1997,"songCount":3,"song":[$it]}""" }

    // Every theme's main places, kept as pictures for looking at with
    // OCTO_SHOTS=1: Settings > Appearance, the Songs list, an album, a song
    // menu, the add a server sheet (also with Remember password off), the
    // full player and the sign-in page.
    @Test(timeout = 600_000)
    fun everyThemesPlacesAsPictures() {
        if (System.getenv("OCTO_SHOTS") != "1") return
        for (theme in ThemeChoice.entries) {
            val tag = theme.name.lowercase()
            A11yScene(folder.newFolder(), height = 1_000, look = inTheme(theme, glow = true), serve = { it.answer("getAlbum", okComputer) }).use { s ->
                s.onUi { s.app.openSettings(SettingsKey.Look) }
                keep(s.settle(1_500), "theme-$tag-1-settings-appearance")
                s.onUi { s.app.navigator.go(Page.Songs) }
                keep(s.settle(1_200), "theme-$tag-2-library-songs")
                s.onUi { s.app.navigator.go(Page.Album("a1")) }
                keep(s.settle(1_500), "theme-$tag-3-album")
                s.onUi {
                    s.app.navigator.go(Page.Songs)
                    s.app.popups.showAt(IntOffset(400, 160)) { close -> SongMenu(s.app, listOf(s.app.library!!.index!!.songs[2]), close) }
                }
                keep(s.settle(1_200), "theme-$tag-4-menu")
                s.onUi {
                    s.app.popups.close()
                    addServer(s.app)
                }
                keep(s.settle(1_200), "theme-$tag-5-sheet")
                val remember = s.nodes(merged = true).first { node ->
                    node.config.getOrNull(SemanticsProperties.ToggleableState) != null &&
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Remember password" }
                }
                s.onUi { remember.config[SemanticsActions.OnClick].action?.invoke() }
                keep(s.settle(1_200), "theme-$tag-5-sheet-remember-off")
                s.onUi {
                    s.app.popups.close()
                    s.app.play(s.app.library!!.index!!.songs, 0)
                    s.app.player.togglePlay()
                    s.app.fullPlayer = true
                }
                keep(s.settle(2_000), "theme-$tag-6-full-player")
                s.onUi {
                    s.app.fullPlayer = false
                    s.app.player.togglePlay()
                    s.app.signOut()
                }
                keep(s.settle(1_500), "theme-$tag-7-sign-in")
            }
        }
    }

    private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)

    private fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(argb shr 16 and 0xFF) + 0.7152 * ch(argb shr 8 and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    // The words' contrast as drawn: the background is the most common color
    // round the edge of their box, the ink the pixel inside that stands
    // furthest from it.
    private fun measured(image: Image, box: Rect): Double {
        val bitmap = Bitmap.makeFromImage(image)
        val left = box.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = box.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = box.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val edge = ArrayList<Int>()
        for (x in left until right) {
            edge += bitmap.getColor(x, top)
            edge += bitmap.getColor(x, bottom - 1)
        }
        for (y in top until bottom) {
            edge += bitmap.getColor(left, y)
            edge += bitmap.getColor(right - 1, y)
        }
        val background = edge.sortedBy { luminance(it) }[edge.size / 2]
        var best = 1.0
        for (y in top until bottom) for (x in left until right) best = maxOf(best, contrast(bitmap.getColor(x, y), background))
        return best
    }
}
