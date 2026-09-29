package app.winters.octo.desktop.a11y

import app.winters.octo.desktop.ui.PageContrast
import app.winters.octo.desktop.ui.immersiveOpacity
import app.winters.octo.desktop.ui.pageOpacity
import app.winters.octo.desktop.ui.evenShade
import app.winters.octo.desktop.ui.glowShade
import app.winters.octo.desktop.ui.glowOpacity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import app.winters.octo.design.OctoColors
import app.winters.octo.player.immersive.readableAlpha
import app.winters.octo.player.immersive.WashTuning
import app.winters.octo.desktop.player.wash.WashCovers
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.desktop.ui.SongMenu
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
// reach WCAG AA: 4.5:1 for small text, 3:1 for large.
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
                Triple("Airbag", "song title", 4.5),
                Triple("Radiohead", "artist (secondary)", 4.5),
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
        val base = OctoColors.Background.toArgb()
        val quiet = OctoColors.TextMuted.compositeOver(OctoColors.Background).toArgb()
        for (strength in listOf(0.5f, 1f)) {
            val glow = glowOpacity(strength)
            val capped = readableAlpha(prepared.glowPeak, base, quiet, PageContrast, glow)
            println("AMBIENCE $name glow ${(strength * 100).toInt()}%: shows ${"%.2f".format(glow)} (capped it was ${"%.2f".format(capped)}), shade at the top ${"%.2f".format(glowShade(prepared.glowPeak, glow).most)}")
        }
        val wash = prepared.pageOpacity(1f)
        val capped = readableAlpha(prepared.peak, base, quiet, PageContrast, immersiveOpacity(1f))
        println("AMBIENCE $name immersive 100%: shows ${"%.2f".format(wash)} (capped it was ${"%.2f".format(capped)}), shade ${"%.2f".format(evenShade(prepared.peak, wash).most)}")
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
