package app.winters.octo.desktop.library

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.font.createFontFamilyResolver
import app.winters.octo.covers.CoverAlign
import app.winters.octo.covers.CoverBackgrounds
import app.winters.octo.covers.CoverRole
import app.winters.octo.covers.CoverSpec
import app.winters.octo.covers.CoverType
import app.winters.octo.covers.CoverWords
import app.winters.octo.covers.Measured
import app.winters.octo.covers.applyVeil
import app.winters.octo.covers.coverWords
import app.winters.octo.covers.sampleBackground
import app.winters.octo.covers.seededPalette
import app.winters.octo.covers.veilRegions
import app.winters.octo.design.ComposeCoverTypesetter
import app.winters.octo.design.CoverFontFamily
import app.winters.octo.design.coverMeasurer
import app.winters.octo.design.renderCover
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

// Our covers against the reference's golden ones (tools/cover-art/golden,
// made by reference.py): the words' sizes and boxes to the pixel, and the
// veiled background within 2 per channel at every sampled point. Glyphs
// are compared by eye (OCTO_SHOTS=1 writes both side by side). Widths
// differ: Pillow adds up whole-pixel advances with no kerning, Compose kerns
// with fractional advances.
class CoverGoldenTest {
    private val golden = File("../tools/cover-art/golden")
    private val covers = Json.parseToJsonElement(File(golden, "samples.json").readText()).jsonObject["covers"]!!.jsonArray.map { it.jsonObject }
    private val measurer = coverMeasurer(createFontFamilyResolver())
    private val setter = ComposeCoverTypesetter(measurer, CoverFontFamily)

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private fun ours(cover: JsonObject): List<CoverWords> {
        val spec = CoverSpec("golden", cover.text("name")!!, cover.text("line"), cover.text("footer"), seededPalette("golden"))
        return coverWords(spec, cover["side"]!!.jsonPrimitive.int, setter)
    }

    private fun roleOf(name: String) = when (name) {
        "title" -> CoverRole.Title
        "line" -> CoverRole.Line
        else -> CoverRole.Footer
    }

    // The reference's words, as boxes the veil can use.
    private fun theirs(cover: JsonObject): List<CoverWords> {
        val side = cover["side"]!!.jsonPrimitive.int.toFloat()
        return cover["words"]!!.jsonArray.map { it.jsonObject }.map { w ->
            val x = w["x"]!!.jsonPrimitive.double.toFloat()
            val lines = w["lines"]!!.jsonArray.size
            val measured = Measured(lines, w["right"]!!.jsonPrimitive.double.toFloat() - x, w["height"]!!.jsonPrimitive.double.toFloat(), false)
            CoverWords(roleOf(w.text("role")!!), "", CoverType(w["size"]!!.jsonPrimitive.double.toFloat(), 400, 0f, 1f, lines), x, w["top"]!!.jsonPrimitive.double.toFloat(), side - 2 * x, CoverAlign.Left, -1, measured)
        }
    }

    private fun background(cover: JsonObject): IntArray {
        val (full, size) = PlaylistArtStore.decodePixels(CoverBackgrounds.bytes(cover.text("background")!!))
        return sampleBackground(full, size, cover["side"]!!.jsonPrimitive.int)
    }

    @Test
    fun theWordsAreSetAsTheReferenceSetsThem() {
        val notes = mutableListOf<String>()
        for (cover in covers) {
            val words = ours(cover)
            val expected = cover["words"]!!.jsonArray.map { it.jsonObject }
            assertEquals("${cover.text("file")}: which words", expected.map { roleOf(it.text("role")!!) }, words.map { it.role })
            for ((w, e) in words.zip(expected)) {
                val what = "${cover.text("file")} ${w.role}"
                assertEquals("$what size", e["size"]!!.jsonPrimitive.int, w.type.sizePx.toInt())
                assertEquals("$what lines", e["lines"]!!.jsonArray.size, w.measured.lines)
                assertEquals("$what x", e["x"]!!.jsonPrimitive.double, w.left.toDouble(), 0.01)
                assertEquals("$what top", e["top"]!!.jsonPrimitive.double, w.top.toDouble(), 0.01)
                assertEquals("$what height", e["height"]!!.jsonPrimitive.double, w.height.toDouble(), 0.01)
                // The reference's widths are its glyphs' advances added up
                // (Pillow without kerning): ours, letter by letter, match them.
                val right = e["right"]!!.jsonPrimitive.double
                val lines = e["lines"]!!.jsonArray.map { it.jsonPrimitive.content }
                // A space alone measures nothing (a line's end spaces do not count).
                val space = (setter.widthOf("l l", w.type) - setter.widthOf("ll", w.type)).toDouble()
                fun advance(cp: Int) = if (cp == ' '.code) space else setter.widthOf(String(Character.toChars(cp)), w.type).toDouble()
                val unkerned = w.left + lines.maxOf { line -> line.codePoints().toArray().sumOf(::advance) }
                // Pillow's advances are whole pixels, each glyph's rounded,
                // so up to half a pixel a letter either way.
                val glyphs = lines.maxOf { it.codePointCount(0, it.length) }
                assertEquals("$what unkerned right", right, unkerned, 1.0 + glyphs * 0.5)
                // Set as lines, Compose also kerns: within 2% of the width.
                assertEquals("$what right", right, w.inked[2].toDouble(), 1.0 + glyphs * 0.5 + (right - w.left) * 0.02)
                if (abs(w.inked[2] - right) > 0.5) notes += "$what right ${"%.1f".format(w.inked[2])}, the reference's $right (kerning)"
            }
        }
        println(notes.joinToString("\n", "Widths that differ (kerning):\n"))
    }

    // Every sampled point of the veiled background, within `tolerance` per channel.
    private fun check(cover: JsonObject, veiled: IntArray, label: String, tolerance: Int = 2) {
        val side = cover["side"]!!.jsonPrimitive.int
        for (sample in cover["samples"]!!.jsonArray.map { it.jsonObject }) {
            val x = sample["x"]!!.jsonPrimitive.int
            val y = sample["y"]!!.jsonPrimitive.int
            val want = sample["rgb"]!!.jsonArray.map { it.jsonPrimitive.int }
            val p = veiled[y * side + x]
            val got = listOf(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)
            for (c in 0..2) assertTrue("$label ${cover.text("file")} at $x,$y: $got vs $want", abs(got[c] - want[c]) <= tolerance)
        }
    }

    @Test
    fun theVeilIsTheReferences() {
        for (cover in covers) {
            val side = cover["side"]!!.jsonPrimitive.int
            check(cover, applyVeil(background(cover), side, veilRegions(theirs(cover), side)), "their boxes")
        }
    }

    @Test
    fun ourWholeCoverIsCloseToTheReferencesUnderTheWords() {
        for (cover in covers) {
            val side = cover["side"]!!.jsonPrimitive.int
            val words = ours(cover)
            val veiled = applyVeil(background(cover), side, veilRegions(words, side))
            // Our kerned words are a little narrower, so the veil's box
            // ends a little sooner: a few levels at its far edge.
            check(cover, veiled, "our boxes", tolerance = 4)
            if (System.getenv("OCTO_SHOTS") == "1") sideBySide(cover, veiled, words)
        }
    }

    // Ours on the left, the reference's on the right, for looking at the glyphs.
    private fun sideBySide(cover: JsonObject, veiled: IntArray, words: List<CoverWords>) {
        val side = cover["side"]!!.jsonPrimitive.int
        val ours = Image.makeFromBitmap(renderCover(PlaylistArtStore.pictureOf(veiled, side), words, measurer, CoverFontFamily).asSkiaBitmap())
        val theirs = Image.makeFromEncoded(File(golden, cover.text("file")!!).readBytes())
        val surface = Surface.makeRasterN32Premul(side * 2 + 16, side)
        surface.canvas.clear(0xFF0C0C0D.toInt())
        surface.canvas.drawImageRect(ours, Rect.makeXYWH(0f, 0f, side.toFloat(), side.toFloat()), Paint())
        surface.canvas.drawImageRect(theirs, Rect.makeXYWH(side + 16f, 0f, side.toFloat(), side.toFloat()), Paint())
        val out = File("build/shots/golden").apply { mkdirs() }
        File(out, cover.text("file")!!.replace(".webp", ".png")).writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
