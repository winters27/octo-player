package app.winters.octo.desktop.a11y

import app.winters.octo.desktop.ui.PageContrast
import app.winters.octo.desktop.ui.immersiveOpacity
import app.winters.octo.desktop.ui.pageOpacity
import app.winters.octo.desktop.ui.evenShade
import app.winters.octo.desktop.ui.glowShade
import app.winters.octo.desktop.ui.glowOpacity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import app.winters.octo.desktop.OctoLook
import app.winters.octo.player.immersive.readableAlpha
import app.winters.octo.player.immersive.WashTuning
import app.winters.octo.desktop.player.wash.WashCovers
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.desktop.ui.SongMenu
import app.winters.octo.design.POPUP_TAG
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.SignInWords
import app.winters.octo.desktop.pages.addServer
import app.winters.octo.desktop.pages.rememberCaption
import app.winters.octo.subsonic.gateway.Gateway
import org.junit.Assert.assertEquals
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.pow

// Words and marks measured on the pixels the window really draws, over the
// riskiest backgrounds: a bright yellow cover glowing across the top at
// full strength, the same cover as the immersive wash, the player's glass
// over it, the frame's glass, a menu, and the full player. The words must
// reach WCAG AA: 4.5:1 for small text, 3:1 for large, and the page's main
// and secondary words 7:1.
class ContrastTest {
    @get:Rule val folder = TemporaryFolder()

    // A cover of one colour with a disc of another.
    private fun cover(background: Int, disc: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(300, 300)
        surface.canvas.clear(background)
        surface.canvas.drawCircle(150f, 150f, 90f, Paint().apply { color = disc })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    // A bright yellow cover with an orange disc: about the worst a page's
    // light words can be laid over; white, a bright green, and a dark one
    // that should need no shade at all.
    private val yellow = cover(0xFFFFE000.toInt(), 0xFFFF8A00.toInt())
    private val covers = mapOf(
        "yellow" to yellow,
        "white" to cover(0xFFFFFFFF.toInt(), 0xFFF2EAD8.toInt()),
        "green" to cover(0xFF00FF40.toInt(), 0xFFB0FF00.toInt()),
        "dark" to cover(0xFF101830.toInt(), 0xFF8A1C3C.toInt()),
    )

    // WCAG's relative luminance and contrast.
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

    // The words' contrast as drawn: the background is the most common colour
    // round the edge of their box, the ink the pixel inside that stands
    // furthest from it (a stem's full colour).
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

    // With OCTO_SHOTS=1, the frame measured, to look at.
    private fun keep(image: Image, name: String) {
        if (System.getenv("OCTO_SHOTS") != "1") return
        java.io.File("build/shots").mkdirs()
        java.io.File("build/shots/$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private data class Spot(val name: String, val ratio: Double, val need: Double)

    // Words ending in "..." match any that start so.
    private fun A11yScene.text(words: String, where: (Rect) -> Boolean = { true }): SemanticsNode? =
        nodes().firstOrNull { node ->
            val shown = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: return@firstOrNull false
            val same = if (words.endsWith("...")) shown.startsWith(words.removeSuffix("...")) else shown == words
            same && node.boundsInRoot.width > 0f && where(node.boundsInRoot)
        }

    private fun A11yScene.spots(image: Image, label: String, list: List<Triple<String, String, Double>>, where: (Rect) -> Boolean = { true }): List<Spot> =
        list.mapNotNull { (words, what, need) -> text(words, where)?.let { Spot("$label: $what '$words'", measured(image, it.boundsInRoot), need) } }

    private fun check(spots: List<Spot>, minimum: Int) {
        println(spots.joinToString("\n") { "CONTRAST ${"%.2f".format(it.ratio)} (needs ${it.need}) ${it.name}" })
        assertTrue("measured only ${spots.size} spots", spots.size >= minimum)
        val low = spots.filter { it.ratio < it.need }
        assertTrue("under AA:\n" + low.joinToString("\n") { "${"%.2f".format(it.ratio)} < ${it.need}: ${it.name}" }, low.isEmpty())
    }

    // The same, with every named spot found: exactly `count` of them.
    private fun checkEvery(spots: List<Spot>, count: Int) {
        check(spots, minimum = count)
        assertEquals("every named spot was found", count, spots.size)
    }

    // The Songs page, the sidebar and the player over the cover's colours.
    private fun pageSpots(style: AmbienceStyle, strength: Float, name: String = "yellow"): List<Spot> =
        A11yScene(folder.newFolder(), cover = covers.getValue(name), coverId = "cover-$name", look = { it.copy(appearance = it.appearance.copy(ambience = style, glowStrength = strength)) }).use { s ->
            s.onUi {
                s.app.play(s.app.library!!.index!!.songs, 0)
                s.app.player.togglePlay()
                s.app.navigator.go(Page.Songs)
            }
            // Long enough for a new cover to load and the colours to settle.
            val image = s.settle(3_000)
            val label = "$name $style ${(strength * 100).toInt()}%"
            keep(image, "contrast-$name-$style-${(strength * 100).toInt()}")
            val page: (Rect) -> Boolean = { it.left > 240f && it.bottom < 780f }
            val player: (Rect) -> Boolean = { it.top > 780f }
            val sidebar: (Rect) -> Boolean = { it.right < 240f }
            s.spots(image, "$label page", listOf(
                Triple("Songs", "title", 3.0),
                Triple("5 songs...", "header line (muted)", 4.5),
                Triple("Airbag", "song title", 7.0),
                Triple("Radiohead", "artist (secondary)", 7.0),
                Triple("4:21", "length (muted)", 4.5),
                Triple("ARTIST", "column heading (muted)", 4.5),
            ), page) +
                s.spots(image, "$label sidebar", listOf(
                    Triple("LIBRARY", "group name (muted)", 4.5),
                    Triple("Albums", "place (secondary)", 4.5),
                    Triple("Songs", "open place (primary)", 4.5),
                ), sidebar) +
                s.spots(image, "$label player", listOf(
                    Triple("Airbag", "song", 4.5),
                    Triple("Radiohead", "artist (secondary)", 4.5),
                    Triple("0:00", "time (muted)", 4.5),
                ), player)
        }

    // Every cover at the usual glow, the strongest glow and the strongest
    // immersive wash, and how much of the ambience shows: now, and as the
    // cap on the ambience (dropped for the page's shade) would have had it.
    private fun everyLook(name: String) {
        val prepared = WashCovers.prepared(name, Image.makeFromEncoded(covers.getValue(name)), WashTuning())
        val base = OctoLook.page.toArgb()
        val quiet = OctoLook.muted.compositeOver(OctoLook.page).toArgb()
        for (strength in listOf(0.5f, 1f)) {
            val glow = glowOpacity(strength)
            val capped = readableAlpha(prepared.glowPeak, base, quiet, PageContrast, glow)
            println("AMBIENCE $name glow ${(strength * 100).toInt()}%: shows ${"%.2f".format(glow)} (capped it was ${"%.2f".format(capped)}), shade at the top ${"%.2f".format(glowShade(prepared.glowPeak, glow, OctoLook.page, OctoLook.muted).most)}")
        }
        val wash = prepared.pageOpacity(1f, OctoLook.page, OctoLook.secondary)
        val capped = readableAlpha(prepared.peak, base, quiet, PageContrast, immersiveOpacity(1f))
        println("AMBIENCE $name immersive 100%: shows ${"%.2f".format(wash)} (capped it was ${"%.2f".format(capped)}), shade ${"%.2f".format(evenShade(prepared.peak, wash, OctoLook.page, OctoLook.muted).most)}")
        check(pageSpots(AmbienceStyle.Glow, 0.5f, name) + pageSpots(AmbienceStyle.Glow, 1f, name) + pageSpots(AmbienceStyle.Immersive, 1f, name), minimum = 30)
    }

    @Test(timeout = 400_000)
    fun wordsReadOverAYellowCover() = everyLook("yellow")

    @Test(timeout = 400_000)
    fun wordsReadOverAWhiteCover() = everyLook("white")

    @Test(timeout = 400_000)
    fun wordsReadOverAGreenCover() = everyLook("green")

    @Test(timeout = 400_000)
    fun wordsReadOverADarkCover() = everyLook("dark")

    // Settings' captions, in the muted ink the accent tints, on its glaze
    // cards over the immersive wash of a white cover at full strength, in
    // every accent; with the page's line, a card's title and the list's
    // group names beside them.
    @Test(timeout = 300_000)
    fun settingsCaptionsReadOverAWhiteWashInEveryAccent() {
        val immersive: (AppSettings) -> AppSettings = { it.copy(appearance = it.appearance.copy(ambientGlow = true, ambience = AmbienceStyle.Immersive, glowStrength = 1f)) }
        val spots = A11yScene(folder.newFolder(), height = 1_200, cover = covers.getValue("white"), coverId = "cover-white-settings", look = immersive).use { s ->
            s.onUi {
                s.app.play(s.app.library!!.index!!.songs, 0)
                s.app.player.togglePlay()
                s.app.openSettings(SettingsKey.Look)
            }
            // Long enough for the cover to load and the wash to settle.
            s.settle(3_000).close()
            AccentChoice.entries.flatMap { accent ->
                s.onUi { s.app.settings.update { it.copy(appearance = it.appearance.copy(accent = accent)) } }
                val image = s.settle(700)
                keep(image, "contrast-settings-white-${accent.name.lowercase()}")
                val label = "white immersive, ${accent.label}"
                val page: (Rect) -> Boolean = { it.left > 300f && it.bottom < 1_080f }
                val list: (Rect) -> Boolean = { it.right < 300f }
                s.spots(image, "$label page", listOf(
                    Triple("${accent.label}: ...", "accent caption (muted)", 4.5),
                    Triple("Like Windows follows...", "text size caption (muted)", 4.5),
                    Triple("The cover's colors behind the whole window.", "ambience caption (muted)", 4.5),
                    Triple("Lower it if bright covers make words hard to read.", "brightness cap caption (muted)", 4.5),
                    Triple("How Octo looks: colors, backgrounds and text.", "page line (secondary)", 7.0),
                    Triple("Window background", "card title (primary)", 4.5),
                ), page) + s.spots(image, "$label list", listOf(
                    Triple("LISTENING", "group name (muted)", 4.5),
                    Triple("Appearance", "page shown (primary)", 4.5),
                ), list)
            }
        }
        check(spots, minimum = AccentChoice.entries.size * 6)
    }

    // Settings' quiet action pills: their words over the pill on a glaze
    // card, over the immersive wash of a white cover at full strength, in
    // every accent.
    @Test(timeout = 300_000)
    fun settingsActionPillsReadOverAWhiteWashInEveryAccent() {
        val immersive: (AppSettings) -> AppSettings = { it.copy(appearance = it.appearance.copy(ambientGlow = true, ambience = AmbienceStyle.Immersive, glowStrength = 1f)) }
        val spots = A11yScene(folder.newFolder(), height = 1_200, cover = covers.getValue("white"), coverId = "cover-white-pills", look = immersive).use { s ->
            s.onUi {
                s.app.play(s.app.library!!.index!!.songs, 0)
                s.app.player.togglePlay()
                s.app.openSettings(SettingsKey.Servers)
            }
            s.settle(3_000).close()
            AccentChoice.entries.flatMap { accent ->
                s.onUi { s.app.settings.update { it.copy(appearance = it.appearance.copy(accent = accent)) } }
                val image = s.settle(700)
                keep(image, "contrast-settings-pills-white-${accent.name.lowercase()}")
                s.spots(image, "white immersive, ${accent.label}", listOf(
                    Triple("Add", "Add a server's pill", 4.5),
                    Triple("Read again", "Read the library again's pill", 4.5),
                ), { it.left > 300f && it.bottom < 1_080f })
            }
        }
        check(spots, minimum = AccentChoice.entries.size * 2)
    }

    // A danger action's words on its quiet pill (Remove all's Remove), on a
    // plain row and on the same row lit as asked for, in every accent.
    @Test(timeout = 300_000)
    fun settingsDangerPillWordsReadPlainAndLitInEveryAccent() {
        val spots = A11yScene(
            folder.newFolder(),
            height = 1_200,
            wrap = { content -> androidx.compose.runtime.CompositionLocalProvider(app.winters.octo.desktop.pages.LocalRowFlashMs provides 600_000L) { content() } },
        ).use { s ->
            s.onUi {
                s.app.offline.keepLiked(true)
                s.app.openSettings(SettingsKey.Offline)
            }
            s.settle(1_000).close()
            fun round(state: String) = AccentChoice.entries.flatMap { accent ->
                s.onUi { s.app.settings.update { it.copy(appearance = it.appearance.copy(accent = accent)) } }
                val image = s.settle(500)
                keep(image, "contrast-settings-danger-$state-${accent.name.lowercase()}")
                s.spots(image, "$state row, ${accent.label}", listOf(Triple("Remove", "Remove all's pill", 4.5)), { it.left > 300f })
            }
            val plain = round("plain")
            s.onUi { s.app.openSettings(SettingsKey.Offline, "remove-all") }
            s.until { s.nodes().any { it.config.getOrNull(app.winters.octo.desktop.pages.RowFlash) != null } }
            plain + round("lit")
        }
        check(spots, minimum = AccentChoice.entries.size * 2)
    }

    // Settings' quiet action pills and a danger action's words, plain and
    // lit as asked for, on the plain page in every theme and accent.
    @Test(timeout = 900_000)
    fun settingsPillsAndDangerWordsReadInEveryThemeAndAccent() {
        val plainPage: (AppSettings) -> AppSettings = { it.copy(appearance = it.appearance.copy(ambientGlow = false)) }
        val pills = A11yScene(folder.newFolder(), height = 1_200, look = plainPage).use { s ->
            s.onUi { s.app.openSettings(SettingsKey.Servers) }
            s.settle(1_000).close()
            ThemeChoice.entries.flatMap { theme ->
                s.onUi { s.app.settings.pickTheme(theme) }
                AccentChoice.entries.flatMap { accent ->
                    s.onUi { s.app.settings.update { it.copy(appearance = it.appearance.copy(accent = accent)) } }
                    val image = s.settle(400)
                    s.spots(image, "${theme.name}, ${accent.label}", listOf(
                        Triple("Add", "Add a server's pill", 4.5),
                        Triple("Read again", "Read the library again's pill", 4.5),
                    ), { it.left > 300f && it.bottom < 1_080f })
                }
            }
        }
        check(pills, minimum = ThemeChoice.entries.size * AccentChoice.entries.size * 2)
        val danger = A11yScene(
            folder.newFolder(),
            height = 1_200,
            look = plainPage,
            wrap = { content -> androidx.compose.runtime.CompositionLocalProvider(app.winters.octo.desktop.pages.LocalRowFlashMs provides 600_000L) { content() } },
        ).use { s ->
            s.onUi {
                s.app.offline.keepLiked(true)
                s.app.openSettings(SettingsKey.Offline)
            }
            s.settle(1_000).close()
            fun round(state: String) = ThemeChoice.entries.flatMap { theme ->
                s.onUi { s.app.settings.pickTheme(theme) }
                AccentChoice.entries.flatMap { accent ->
                    s.onUi { s.app.settings.update { it.copy(appearance = it.appearance.copy(accent = accent)) } }
                    val image = s.settle(400)
                    s.spots(image, "$state row, ${theme.name}, ${accent.label}", listOf(Triple("Remove", "Remove all's pill", 4.5)), { it.left > 300f })
                }
            }
            val plain = round("plain")
            s.onUi { s.app.openSettings(SettingsKey.Offline, "remove-all") }
            s.until { s.nodes().any { it.config.getOrNull(app.winters.octo.desktop.pages.RowFlash) != null } }
            plain + round("lit")
        }
        check(danger, minimum = ThemeChoice.entries.size * AccentChoice.entries.size * 2)
    }

    // Where the open pop-up is, for words inside it alone.
    private fun A11yScene.popup(): Rect =
        nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == POPUP_TAG }.boundsInRoot

    // A library list, a song menu and the add a server sheet over it, in
    // every theme, each word at its role's minimum: main and secondary
    // words 7:1, muted words 4.5:1.
    @Test(timeout = 300_000)
    fun aListAMenuAndASheetReadInEveryTheme() {
        val spots = ThemeChoice.entries.flatMap { theme ->
            A11yScene(folder.newFolder()).use { s ->
                s.onUi {
                    s.app.settings.pickTheme(theme)
                    s.app.navigator.go(Page.Songs)
                }
                val page = s.settle(1_000)
                keep(page, "contrast-theme-songs-${theme.name.lowercase()}")
                val list = s.spots(page, "${theme.label} songs", listOf(
                    Triple("Songs", "title (main)", 7.0),
                    Triple("5 songs...", "header line (muted)", 4.5),
                    Triple("Airbag", "song title (main)", 7.0),
                    Triple("Radiohead", "artist (secondary)", 7.0),
                    Triple("4:21", "length (muted)", 4.5),
                    Triple("ARTIST", "column heading (muted)", 4.5),
                ), { it.left > 240f && it.bottom < 780f }) + s.spots(page, "${theme.label} sidebar", listOf(
                    Triple("LIBRARY", "group name (muted)", 4.5),
                    Triple("Albums", "place (secondary)", 7.0),
                    Triple("Songs", "open place (main)", 7.0),
                ), { it.right < 240f })
                s.onUi { s.app.popups.showAt(androidx.compose.ui.unit.IntOffset(400, 120)) { close -> SongMenu(s.app, listOf(s.app.library!!.index!!.songs[2]), close) } }
                // The popups are measured once their fade has ended, however
                // late a loaded run draws their first frame.
                val menu = s.settleStill(700, quiet = 150)
                keep(menu, "contrast-theme-menu-${theme.name.lowercase()}")
                val menuBox = s.popup()
                val inMenu = s.spots(menu, "${theme.label} menu", listOf(
                    Triple("Karma Police", "menu title (secondary)", 7.0),
                    Triple("Play next", "row (main)", 7.0),
                ), { menuBox.contains(it.center) })
                s.onUi {
                    s.app.popups.close()
                    addServer(s.app)
                }
                val sheet = s.settleStill(700, quiet = 150)
                keep(sheet, "contrast-theme-sheet-${theme.name.lowercase()}")
                val sheetBox = s.popup()
                list + inMenu + s.spots(sheet, "${theme.label} sheet", listOf(
                    Triple("Add a server", "sheet title (main)", 7.0),
                    Triple(SignInWords.SERVER_ADDRESS, "field name (main)", 7.0),
                    Triple(SignInWords.SERVER_NAME, "field name (main)", 7.0),
                    Triple(SignInWords.NAME_PLACEHOLDER, "field hint (muted)", 4.5),
                    Triple(SignInWords.OPTIONAL_TAG, "Optional tag on its fill", 4.5),
                ), { sheetBox.contains(it.center) })
            }
        }
        checkEvery(spots, ThemeChoice.entries.size * 16)
    }

    // Settings > Appearance in every theme, each word at its role's minimum.
    @Test(timeout = 300_000)
    fun settingsReadsInEveryTheme() {
        val spots = ThemeChoice.entries.flatMap { theme ->
            A11yScene(folder.newFolder(), height = 1_200).use { s ->
                s.onUi {
                    s.app.settings.pickTheme(theme)
                    s.app.openSettings(SettingsKey.Look)
                }
                val image = s.settle(1_500)
                keep(image, "contrast-theme-settings-${theme.name.lowercase()}")
                s.spots(image, "${theme.label} settings", listOf(
                    Triple("How Octo looks: colors, backgrounds and text.", "page line (secondary)", 7.0),
                    Triple("Window background", "card title (main)", 7.0),
                    Triple("Like Windows follows...", "text size caption (muted)", 4.5),
                ), { it.left > 300f && it.bottom < 1_080f }) + s.spots(image, "${theme.label} settings list", listOf(
                    Triple("LISTENING", "group name (muted)", 4.5),
                    Triple("Appearance", "page shown (main)", 7.0),
                ), { it.right < 300f })
            }
        }
        check(spots, minimum = ThemeChoice.entries.size * 5)
    }

    // The sign-in page, which keeps Octo's dark look, in every theme: closed,
    // with Advanced open, and calling a sign-in page failure out (the error
    // note and the pointer in the error words); then the Optional tag on its
    // fill in two accents. Tall enough that nothing measured is scrolled off.
    @Test(timeout = 400_000)
    fun theSignInPageReadsInEveryTheme() {
        val spots = A11yScene(folder.newFolder(), height = 1_600, reduceMotion = true).use { s ->
            s.onUi { s.app.signOut() }
            s.settle(1_000).close()
            val form = { s.app.signInForm }
            val themes = ThemeChoice.entries.flatMap { theme ->
                s.onUi {
                    s.app.settings.pickTheme(theme)
                    form().advancedOpen = false
                    form().result = null
                }
                val closed = s.settle(700)
                keep(closed, "contrast-theme-sign-in-${theme.name.lowercase()}")
                val plain = s.spots(closed, "${theme.label} sign-in", listOf(
                    Triple("Octo", "name (main)", 7.0),
                    Triple("Sign in to your music server...", "line (secondary)", 7.0),
                    Triple(SignInWords.SERVER_ADDRESS, "field name (main)", 7.0),
                    Triple(SignInWords.REQUIRED, "placeholder (muted)", 4.5),
                    Triple(rememberCaption(true), "switch line (secondary)", 7.0),
                ))
                s.onUi { form().advancedOpen = true }
                val open = s.settle(700)
                keep(open, "contrast-theme-sign-in-advanced-${theme.name.lowercase()}")
                val advanced = s.spots(open, "${theme.label} Advanced", listOf(
                    Triple(SignInWords.ADVANCED, "Advanced's head (main)", 7.0),
                    Triple(SignInWords.ADVANCED_LINE, "section line (secondary)", 7.0),
                    Triple(SignInWords.GROUP_GETTING_THERE, "group title (main)", 7.0),
                    Triple(SignInWords.HOME_NOTE, "note (secondary)", 7.0),
                    Triple(SignInWords.OPTIONAL_TAG, "Optional tag on its fill", 4.5),
                ))
                s.onUi {
                    form().typeAddress("https://music.example.com")
                    form().result = false to GatewayWords.failure(Gateway.Authentik, false)
                    form().failed(form().attemptAddress, Gateway.Authentik)
                }
                val called = s.settle(700)
                keep(called, "contrast-theme-sign-in-callout-${theme.name.lowercase()}")
                val error = s.spots(called, "${theme.label} callout", listOf(
                    Triple(GatewayWords.inGroup(form().result!!.second), "error note (error words)", 4.5),
                    Triple(GatewayWords.POINTER, "pointer (error words)", 4.5),
                ))
                s.onUi {
                    form().result = null
                    form().typeAddress("")
                }
                plain + advanced + error
            }
            val accents = AccentChoice.entries.take(2).flatMap { accent ->
                s.onUi {
                    s.app.settings.update { it.copy(appearance = it.appearance.copy(accent = accent)) }
                    form().advancedOpen = true
                }
                val image = s.settle(700)
                keep(image, "contrast-sign-in-tag-${accent.name.lowercase()}")
                s.spots(image, "${accent.label} tag", listOf(Triple(SignInWords.OPTIONAL_TAG, "Optional tag on its fill", 4.5)))
            }
            themes + accents
        }
        checkEvery(spots, ThemeChoice.entries.size * 12 + 2)
    }

    private fun A11yScene.until(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!what()) {
            kotlin.check(System.currentTimeMillis() < end) { "waited too long" }
            render(2)
        }
    }

    @Test(timeout = 180_000)
    fun aMenuOverTheGlowReads() {
        val spots = A11yScene(folder.newFolder(), cover = yellow, look = { it.copy(appearance = it.appearance.copy(glowStrength = 1f)) }).use { s ->
            s.onUi {
                val songs = s.app.library!!.index!!.songs
                s.app.play(songs, 0)
                s.app.player.togglePlay()
                s.app.navigator.go(Page.Songs)
                s.app.popups.showAt(androidx.compose.ui.unit.IntOffset(400, 120)) { close -> SongMenu(s.app, listOf(songs[2]), close) }
            }
            val image = s.settle()
            s.spots(image, "menu", listOf(
                Triple("Karma Police", "menu title (secondary)", 4.5),
                Triple("Play next", "row", 4.5),
            ), { it.left in 390f..800f && it.top in 110f..700f })
        }
        check(spots, minimum = 2)
    }

    @Test(timeout = 180_000)
    fun theFullPlayerReadsOverAYellowWash() {
        val spots = A11yScene(folder.newFolder(), cover = yellow).use { s ->
            s.onUi {
                s.app.play(s.app.library!!.index!!.songs, 0)
                s.app.player.togglePlay()
                s.app.fullPlayer = true
            }
            val image = s.settle(2_500)
            keep(image, "contrast-full-player")
            s.spots(image, "full player", listOf(
                Triple("Airbag", "song", 3.0),
                Triple("Radiohead", "artist (muted ink)", 4.5),
                Triple("0:00", "time (muted ink)", 4.5),
            ), { it.bottom < 780f })
        }
        check(spots, minimum = 3)
    }
}
