package app.winters.octo.desktop.a11y

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

    // A bright yellow cover with an orange disc: about the worst a page's
    // light words can be laid over.
    private val yellow: ByteArray = run {
        val surface = Surface.makeRasterN32Premul(300, 300)
        surface.canvas.clear(0xFFFFE000.toInt())
        surface.canvas.drawCircle(150f, 150f, 90f, Paint().apply { color = 0xFFFF8A00.toInt() })
        surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

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

    private fun A11yScene.text(words: String, where: (Rect) -> Boolean = { true }): SemanticsNode? =
        nodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } == words && node.boundsInRoot.width > 0f && where(node.boundsInRoot)
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
    private fun pageSpots(style: AmbienceStyle, strength: Float): List<Spot> =
        A11yScene(folder.newFolder(), cover = yellow, look = { it.copy(appearance = it.appearance.copy(ambience = style, glowStrength = strength)) }).use { s ->
            s.onUi {
                s.app.play(s.app.library!!.index!!.songs, 0)
                s.app.player.togglePlay()
                s.app.navigator.go(Page.Songs)
            }
            val image = s.settle()
            val label = "$style ${(strength * 100).toInt()}%"
            keep(image, "contrast-$style-${(strength * 100).toInt()}")
            val page: (Rect) -> Boolean = { it.left > 240f && it.bottom < 780f }
            val player: (Rect) -> Boolean = { it.top > 780f }
            val sidebar: (Rect) -> Boolean = { it.right < 240f }
            s.spots(image, "$label page", listOf(
                Triple("Songs", "title", 3.0),
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

    @Test(timeout = 180_000)
    fun wordsReadOverAYellowGlowAtFullStrength() = check(pageSpots(AmbienceStyle.Glow, 1f), minimum = 9)

    @Test(timeout = 180_000)
    fun wordsReadOverAYellowGlowAtTheUsualStrength() = check(pageSpots(AmbienceStyle.Glow, 0.5f), minimum = 9)

    @Test(timeout = 180_000)
    fun wordsReadOverAYellowImmersiveWashAtFullStrength() = check(pageSpots(AmbienceStyle.Immersive, 1f), minimum = 9)

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
