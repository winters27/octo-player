package app.winters.octo.desktop.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.font.createFontFamilyResolver
import app.winters.octo.covers.CoverBook
import app.winters.octo.covers.CoverSpec
import app.winters.octo.covers.PLAYLIST_COVER_LINE
import app.winters.octo.covers.LIVE_LIST_COVER_LINE
import app.winters.octo.covers.Swatch
import app.winters.octo.covers.coverGradientOf
import app.winters.octo.covers.coverPalette
import app.winters.octo.design.CoverFontFamily
import app.winters.octo.design.coverMeasurer
import app.winters.octo.design.designCover
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

// Designed playlist covers on their own, for looking at the design: every
// gradient, names long and short and in several writings, from covers and
// without, at the sizes the apps draw them. Only when asked:
// OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*CoverGalleryScreenShotsTest*'
class CoverGalleryScreenShotsTest {
    private val measurer = coverMeasurer(createFontFamilyResolver())

    private fun cover(id: String, name: String, line: String?, footer: String?, swatches: List<List<Swatch>>, side: Int): Image {
        val spec = CoverSpec(id, name, line, footer, coverPalette(swatches, id))
        return Image.makeFromBitmap(designCover(spec, side, measurer, CoverFontFamily).asSkiaBitmap())
    }

    // Tiles in a grid on a dark page, as a list of covers looks in the app.
    private fun sheet(name: String, tiles: List<Image>, side: Int, columns: Int) {
        val gap = maxOf(8, side / 12)
        val rows = (tiles.size + columns - 1) / columns
        val surface = Surface.makeRasterN32Premul(columns * (side + gap) + gap, rows * (side + gap) + gap)
        surface.canvas.clear(0xFF0C0C0D.toInt())
        tiles.forEachIndexed { i, image ->
            val x = gap + (i % columns) * (side + gap)
            val y = gap + (i / columns) * (side + gap)
            surface.canvas.drawImageRect(image, Rect.makeXYWH(x.toFloat(), y.toFloat(), side.toFloat(), side.toFloat()), Paint())
        }
        val out = File("build/shots/covers").apply { mkdirs() }
        File(out, "$name.png").writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private val warm = listOf(listOf(Swatch(0xFFD9552B.toInt(), 0.6f), Swatch(0xFF2B1A12.toInt(), 0.3f)), listOf(Swatch(0xFFF2C14E.toInt(), 0.5f)))
    private val cool = listOf(listOf(Swatch(0xFF1D3F8C.toInt(), 0.7f)), listOf(Swatch(0xFF2FB4A8.toInt(), 0.5f)), listOf(Swatch(0xFF101014.toInt(), 0.8f)))
    private val grey = listOf(listOf(Swatch(0xFF808080.toInt(), 0.9f)), listOf(Swatch(0xFF202020.toInt(), 0.9f)))
    private val neon = listOf(listOf(Swatch(0xFF00FF40.toInt(), 0.8f)), listOf(Swatch(0xFFFFE000.toInt(), 0.7f)))
    private val white = listOf(listOf(Swatch(0xFFFFFFFF.toInt(), 0.9f)), listOf(Swatch(0xFFF4EEDC.toInt(), 0.8f)))

    @Test
    fun drawTheCovers() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val book = CoverBook.Default
        // Every gradient as written (a list with no covers), one id each.
        val ids = (0 until 400).map { "pl-$it" }
        val byGradient = book.gradients.indices.map { g -> ids.first { coverGradientOf(it) == g } }
        val names = listOf("Late night", "Running", "Sunday morning", "Focus", "Dinner with friends", "Chill", "Road trip", "Heavy rotation",
            "Rainy days", "Gym", "Old favourites", "Discover", "Summer 2026", "Deep work", "Kitchen dancing", "Sleep")
        sheet("gradients-written", byGradient.mapIndexed { i, id -> cover(id, names[i], PLAYLIST_COVER_LINE, "${12 + i * 7} songs", emptyList(), 300) }, 300, 4)
        // The same with the music's colours.
        for ((label, swatches) in listOf("warm" to warm, "cool" to cool, "grey" to grey, "neon" to neon, "white" to white)) {
            sheet("gradients-$label", byGradient.mapIndexed { i, id -> cover(id, names[i], PLAYLIST_COVER_LINE, "${12 + i * 7} songs", swatches, 300) }, 300, 4)
        }
        // Names that are hard to set.
        val hard = listOf(
            "Everything I have ever loved, in the order I found it, from the first cassette onwards",
            "Supercalifragilisticexpialidocious",
            "夜のドライブ",
            "東京の夜に聴きたい曲たち、雨の日のためのプレイリスト",
            "أغاني الصيف",
            "שירים לנסיעה ארוכה",
            "🔥🔥🔥",
            "🌙 Night moves 🌙",
            "Ünïcødé Çàfé",
            "A",
            "Музыка для работы",
            "",
        )
        sheet("hard-names", hard.mapIndexed { i, name -> cover(ids[i * 3], name, LIVE_LIST_COVER_LINE.takeIf { i % 2 == 0 } ?: PLAYLIST_COVER_LINE, "By sam", cool.takeIf { i % 3 == 0 } ?: emptyList(), 300) }, 300, 4)
        // Every size the apps draw at.
        for (side in listOf(32, 48, 64, 96, 128, 160, 240, 512, 1000)) {
            val tiles = listOf(0, 1, 2, 3).map { i -> cover(byGradient[i], listOf("Late night", "Dinner with friends", "夜のドライブ", "Everything I have ever loved, in the order I found it")[i], PLAYLIST_COVER_LINE, "12 songs", warm.takeIf { i % 2 == 0 } ?: emptyList(), side) }
            sheet("size-$side", tiles, side, 4)
        }
    }
}
