package app.winters.octo.desktop.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.font.createFontFamilyResolver
import app.winters.octo.covers.CoverBackground
import app.winters.octo.covers.CoverBackgrounds
import app.winters.octo.covers.CoverPalette
import app.winters.octo.covers.CoverSpec
import app.winters.octo.covers.LIVE_LIST_COVER_LINE
import app.winters.octo.covers.PLAYLIST_COVER_LINE
import app.winters.octo.covers.Swatch
import app.winters.octo.covers.applyVeil
import app.winters.octo.covers.chooseBackground
import app.winters.octo.covers.coverPalette
import app.winters.octo.covers.coverWords
import app.winters.octo.covers.sampleBackground
import app.winters.octo.covers.veilRegions
import app.winters.octo.design.ComposeCoverTypesetter
import app.winters.octo.design.CoverFontFamily
import app.winters.octo.design.coverMeasurer
import app.winters.octo.design.designCover
import app.winters.octo.design.renderCover
import app.winters.octo.desktop.library.PlaylistArtStore
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

// Designed playlist covers on their own, for looking at the design: every
// background in the library, the ones music of each colour picks, hard
// names, and every size the apps draw. Only when asked:
// OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*CoverGalleryScreenShotsTest*'
class CoverGalleryScreenShotsTest {
    private val measurer = coverMeasurer(createFontFamilyResolver())
    private val setter = ComposeCoverTypesetter(measurer, CoverFontFamily)
    private val decoded = HashMap<String, Pair<IntArray, Int>>()

    private fun cover(background: CoverBackground, spec: CoverSpec, side: Int): Image {
        val (full, size) = decoded.getOrPut(background.file) { PlaylistArtStore.decodePixels(CoverBackgrounds.bytes(background.file)) }
        val words = coverWords(spec, side, setter)
        val veiled = applyVeil(sampleBackground(full, size, side), side, veilRegions(words, side))
        return Image.makeFromBitmap(renderCover(PlaylistArtStore.pictureOf(veiled, side), words, measurer, CoverFontFamily).asSkiaBitmap())
    }

    private fun cover(spec: CoverSpec, side: Int) = cover(chooseBackground(spec.id, spec.palette), spec, side)

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

    private fun music(argb: Long) = listOf(listOf(Swatch(argb.toInt(), 0.8f)))

    private val names = listOf("Late night", "Running", "Sunday morning", "Focus", "Dinner with friends", "Chill", "Road trip", "Heavy rotation",
        "Rainy days", "Gym", "Old favourites", "Discover", "Summer 2026", "Deep work", "Kitchen dancing", "Sleep")

    // The server's sheet of 24 lists, drawn by the app's own painter (the
    // background chosen and turned, the veil, the words), to check a page
    // of them has no copies. Written to OCTO_SHEET_OUT when set.
    @Test
    fun drawTheVarietySheet() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        // The server's sheet: each list's name and its music's colour as the
        // server measures it (hue, chroma, lightness; hue -1 for none).
        val lists = listOf(
            Triple("Daft Punk Radio", 261, 0.043 to 0.722), Triple("Billie Eilish Radio", 63, 0.061 to 0.575),
            Triple("Tame Impala Radio", 318, 0.041 to 0.453), Triple("Radiohead Radio", 47, 0.159 to 0.657),
            Triple("Kendrick Lamar Radio", 4, 0.068 to 0.41), Triple("Your Mix", 241, 0.039 to 0.732),
            Triple("Discovery Mix", 30, 0.225 to 0.581), Triple("Bad Bunny Radio", 30, 0.225 to 0.581),
            Triple("Jazz & Blues Mix", 225, 0.14 to 0.62), Triple("Metal Mix", 40, 0.163 to 0.601),
            Triple("1970s Mix", 75, 0.14 to 0.62), Triple("Rock Mix", 26, 0.14 to 0.62),
            Triple("Hip-Hop Mix", 61, 0.14 to 0.62), Triple("1990s Mix", 134, 0.14 to 0.62),
            Triple("2020s Mix", 168, 0.14 to 0.62), Triple("Electronic Radio", 250, 0.14 to 0.62),
            Triple("Polka Mix", -1, 0.0 to 0.0), Triple("Red Hot Chili Peppers Radio", -1, 0.0 to 0.0),
            Triple("The Most Unreasonably Long Playlist Name Anyone Ever Typed Into A Music Server Radio", -1, 0.0 to 0.0),
            Triple("宇多田ヒカル Radio", -1, 0.0 to 0.0), Triple("블랙핑크 BLACKPINK Radio", 5, 0.041 to 0.336),
            Triple("فيروز Radio", 69, 0.065 to 0.682), Triple("Late Night 🌙 Chill Mix", 206, 0.14 to 0.62),
            Triple("Ünïcödé Café Mix", -1, 0.0 to 0.0),
        )
        val side = 600
        val tiles = lists.map { (name, hue, colour) ->
            val palette = if (hue < 0) coverPalette(emptyList(), name) else CoverPalette.of(hue.toDouble(), colour.first, colour.second, fromMusic = true)
            val spec = CoverSpec(name, name, if (name.endsWith("Radio")) "Radio" else "Mix", "50 songs", palette)
            Image.makeFromBitmap(designCover(spec, side, measurer, CoverFontFamily, { b -> decoded.getOrPut(b.file) { PlaylistArtStore.decodePixels(CoverBackgrounds.bytes(b.file)) } }, PlaylistArtStore::pictureOf).asSkiaBitmap())
        }
        sheet("variety", tiles, side, 6)
        System.getenv("OCTO_SHEET_OUT")?.let { File("build/shots/covers/variety.png").copyTo(File(it), overwrite = true) }
    }

    @Test
    fun drawTheCovers() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        // Every background in the library.
        val library = CoverBackgrounds.Default.backgrounds
        sheet("library", library.mapIndexed { i, b -> cover(b, CoverSpec("pl-$i", b.name, PLAYLIST_COVER_LINE, "${12 + i} songs", coverPalette(emptyList(), "x")), 300) }, 300, 8)
        // What lists of one colour of music get.
        for ((label, colour) in listOf("red" to 0xFFC8283C, "blue" to 0xFF1E4ED8, "green" to 0xFF2E9E4F, "yellow" to 0xFFF2C94C, "purple" to 0xFF8E3CC8, "none" to 0L)) {
            val swatches = if (colour == 0L) emptyList() else music(colour)
            sheet("music-$label", names.take(8).mapIndexed { i, name ->
                val id = "pl-$label-$i"
                cover(CoverSpec(id, name, PLAYLIST_COVER_LINE, "${12 + i * 7} songs", coverPalette(swatches, id)), 300)
            }, 300, 4)
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
            "Road Trip Playlist",
        )
        sheet("hard-names", hard.mapIndexed { i, name ->
            cover(CoverSpec("hard-$i", name, if (i % 2 == 0) LIVE_LIST_COVER_LINE else PLAYLIST_COVER_LINE, "By sam", coverPalette(emptyList(), "hard-$i")), 300)
        }, 300, 4)
        // Every size the apps draw at.
        for (side in listOf(32, 48, 64, 96, 128, 160, 240, 600, 1200)) {
            val tiles = listOf("Late night", "Dinner with friends", "夜のドライブ", "Everything I have ever loved, in the order I found it").mapIndexed { i, name ->
                cover(CoverSpec("size-$i", name, PLAYLIST_COVER_LINE, "12 songs", coverPalette(emptyList(), "size-$i")), side)
            }
            sheet("size-$side", tiles, side, 4)
        }
    }
}
