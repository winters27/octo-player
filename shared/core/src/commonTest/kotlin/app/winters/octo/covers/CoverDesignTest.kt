package app.winters.octo.covers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CoverDesignTest {
    private val setter = FakeTypesetter()
    private val book = CoverBook.Default
    private val library = CoverBackgrounds.Default

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun spec(id: String, name: String, palette: CoverPalette = seededPalette(id)) =
        CoverSpec(id, name, PLAYLIST_COVER_LINE, "12 songs", palette)

    private fun music(argb: Int) = coverPalette(listOf(listOf(Swatch(argb, 1f))), "x")

    @Test
    fun theSharedBookReads() {
        assertEquals(1, book.version)
        assertEquals("InterDisplay-Medium.ttf", book.fonts.footer)
        assertEquals(500, book.layout.footer.weight)
        assertEquals(0.06f, book.layout.footer.size)
        assertEquals(0.85f, book.layout.footer.opacity)
        assertEquals(3, book.veil.refine)
        assertEquals(listOf(55.0, 72.0, 128.0, 150.0), book.veil.yellow.hues)
        // The gradients are gone from the file.
        val text = CoverBook::class.java.getResourceAsStream("cover-design.json")!!.use { it.readBytes().decodeToString() }
        assertTrue(!text.contains("\"gradients\"") && !text.contains("\"music\""))
    }

    @Test
    fun everyBackgroundIsInTheBuild() {
        assertEquals(48, library.backgrounds.size)
        assertEquals(1200, library.size)
        for (b in library.backgrounds) {
            val bytes = CoverBackgrounds.bytes(b.file)
            assertEquals("RIFF", bytes.copyOfRange(0, 4).decodeToString())
            assertEquals("WEBP", bytes.copyOfRange(8, 12).decodeToString())
            assertTrue(b.hues.isNotEmpty())
        }
        assertEquals(library.backgrounds.size, library.backgrounds.map { it.file }.toSet().size)
    }

    @Test
    fun aListWithoutCoversGetsAnyBackgroundAlwaysTheSame() {
        val ids = (0 until 400).map { "pl-$it" }
        val picked = ids.map { chooseBackground(it, seededPalette(it)).file }
        assertEquals(picked, ids.map { chooseBackground(it, seededPalette(it)).file })
        val used = picked.groupingBy { it }.eachCount()
        // Nearly the whole library, none taking far more than its share.
        assertTrue(used.size >= library.backgrounds.size - 2)
        assertTrue(used.values.all { it < 400 / library.backgrounds.size * 4 })
    }

    @Test
    fun theMusicPicksABackgroundOfItsColour() {
        for (colour in listOf(rgb(200, 30, 40), rgb(30, 60, 200), rgb(40, 160, 70), rgb(240, 200, 30), rgb(150, 40, 190))) {
            val palette = music(colour)
            val picks = (0 until 60).map { chooseBackground("pl-$it", palette) }.toSet()
            // A few different ones, so lists of one colour vary, never more than three.
            assertTrue(picks.size in 2..3)
            for (b in picks) {
                val nearest = b.hues.minOf { hueDistance(it.h, palette.hue.toDouble()) }
                assertTrue("${b.name} for hue ${palette.hue}: $nearest", nearest < 45)
            }
            assertEquals(chooseBackground("pl-1", palette), chooseBackground("pl-1", palette))
        }
    }

    @Test
    fun theWordsSitWhereTheLayoutSays() {
        // Short enough for the fake text engine to set at the largest size.
        val words = coverWords(spec("pl-1", "Late"), 600, setter)
        val (title, line, footer) = words
        assertEquals(CoverRole.Title, title.role)
        assertEquals(48f, title.left)
        assertEquals(60f, title.top)
        assertEquals(96f, title.type.sizePx)
        assertEquals(96f * 1.08f, title.height, 0.01f)
        assertEquals(CoverRole.Line, line.role)
        assertEquals(300, line.type.weight)
        assertEquals(title.top + title.height, line.top)
        assertEquals(CoverRole.Footer, footer.role)
        assertEquals(36f, footer.type.sizePx)
        assertEquals(500, footer.type.weight)
        assertEquals(508.8f, footer.top, 0.01f)
        assertTrue(abs((0.85f * 255).toInt() - (footer.ink ushr 24)) <= 1)
    }

    @Test
    fun smallCoversAreArtworkOnly() {
        for (side in listOf(16, 32, 48, 71)) {
            assertTrue(coverWords(spec("pl-1", "Late night"), side, setter).isEmpty())
            assertTrue(planCover(spec("pl-1", "Late night"), side, setter).veil.isEmpty())
        }
        assertTrue(coverWords(spec("pl-1", "Late night"), 72, setter).isNotEmpty())
    }

    @Test
    fun wordsStayInsideTheMarginsAtEverySize() {
        val names = listOf("Chill", "Dinner with friends", "Everything I have ever loved, in the order I found it", "夜のドライブ", "أغاني الصيف", "🔥🔥🔥", "Supercalifragilisticexpialidocious")
        for (side in listOf(72, 96, 128, 160, 240, 600, 1000, 1200)) for (name in names) {
            val words = coverWords(spec("pl-$name", name), side, setter)
            for (w in words) {
                val (l, t, r, b) = w.inked.toList()
                val margin = side * book.layout.margin
                assertTrue("$name at $side: ${w.text} runs off", t >= 0f && b <= side.toFloat())
                assertTrue("$name at $side: ${w.text} crosses the margin", l >= margin - 1f && r <= side - margin + 1f)
            }
            val foot = words.firstOrNull { it.role == CoverRole.Footer }
            if (foot != null) assertTrue(words.filter { it.role != CoverRole.Footer }.all { it.top + it.height <= foot.top })
        }
    }

    @Test
    fun aRightToLeftNameAndItsVeilSitOnTheRight() {
        val plan = planCover(spec("pl-1", "أغاني الصيف"), 600, setter)
        assertTrue(plan.words.all { it.align == CoverAlign.Right })
        assertEquals(600f - 48f, plan.words.first().inked[2], 0.5f)
        // Both regions run to the right-hand edge.
        assertTrue(plan.veil.all { it.box[2] == 600f && it.box[0] > 0f })
    }

    @Test
    fun theVeilsLimitsAreTheReferences() {
        assertEquals(1.05 / 4.5 - 0.05, veilLimit(4.5), 1e-12)
        // White at 85% over grey reaches 4.5 at about 0.141, as the file says.
        assertEquals(0.141, veilLimit(4.5, 0.85), 0.0015)
    }

    @Test
    fun theRegionsFollowTheWords() {
        val plan = planCover(spec("pl-1", "Late night"), 600, setter)
        val (title, footer) = plan.veil
        val block = plan.words.filter { it.role != CoverRole.Footer }
        assertEquals(listOf(0f, 0f, block.maxOf { it.inked[2] } + 24f, block.maxOf { it.top + it.height } + 24f), title.box)
        val foot = plan.words.last()
        assertEquals(listOf(0f, foot.top - 30f, foot.inked[2] + 30f, 600f), footer.box)
        assertEquals(1.0, footer.maxDrop, 0.0)
    }

    private fun lum(p: Int) = 0.2126 * channelToLinear((p shr 16 and 0xFF) / 255.0) +
        0.7152 * channelToLinear((p shr 8 and 0xFF) / 255.0) + 0.0722 * channelToLinear((p and 0xFF) / 255.0)

    @Test
    fun theVeilDarkensOnlyWhatIsTooBrightAndKeepsTheColour() {
        val side = 200
        val plan = planCover(spec("pl-1", "Late night"), side, setter)
        for (colour in listOf(rgb(120, 170, 255), rgb(255, 140, 190), rgb(90, 220, 200))) {
            val flat = IntArray(side * side) { colour }
            val veiled = applyVeil(flat, side, plan.veil)
            val title = plan.veil.first()
            // Inside the title's box: at most the region's floor, and the same hue.
            val inside = veiled[(title.box[3] / 2).toInt() * side + (title.box[2] / 2).toInt()]
            assertTrue(lum(inside) <= title.least * 1.02)
            assertTrue(hueDistance(toLch(inside).h, toLch(colour).h) < 4)
            assertTrue(toLch(inside).c > toLch(colour).c * 0.5)
        }
        // A dark cover is left as it is.
        val dark = IntArray(side * side) { rgb(20, 30, 60) }
        assertArrayEquals(dark, applyVeil(dark, side, plan.veil))
        // No words, no veil.
        val light = IntArray(side * side) { rgb(250, 250, 250) }
        assertArrayEquals(light, applyVeil(light, side, emptyList()))
    }

    @Test
    fun deepYellowsTurnGoldNeverOlive() {
        val side = 200
        val plan = planCover(spec("pl-1", "Late night"), side, setter)
        val yellow = rgb(245, 215, 40)
        val veiled = applyVeil(IntArray(side * side) { yellow }, side, plan.veil)
        val inside = toLch(veiled[20 * side + 20])
        // Turned toward amber (about 66), away from olive (over 90).
        assertTrue("hue ${inside.h}", inside.h < toLch(yellow).h - 10 && inside.h > 50)
    }

    @Test
    fun backgroundsComeToSizeAsTheReferenceDoes() {
        val from = 8
        val pixels = IntArray(from * from) { i -> rgb(i % 256, (i * 7) % 256, (i * 13) % 256) }
        val half = sampleBackground(pixels, from, 4)
        for (y in 0 until 4) for (x in 0 until 4) {
            val q = listOf(pixels[2 * y * from + 2 * x], pixels[2 * y * from + 2 * x + 1], pixels[(2 * y + 1) * from + 2 * x], pixels[(2 * y + 1) * from + 2 * x + 1])
            val r = (q.sumOf { it shr 16 and 0xFF } + 2) / 4
            assertEquals(r, half[y * 4 + x] shr 16 and 0xFF)
        }
        // Any other size: each pixel the mean of its area; flat stays flat.
        val flat = sampleBackground(IntArray(1200 * 1200) { rgb(10, 200, 90) }, 1200, 176)
        assertEquals(176 * 176, flat.size)
        assertTrue(flat.all { it == rgb(10, 200, 90) })
        assertArrayEquals(pixels, sampleBackground(pixels, from, from))
        val odd = sampleBackground(pixels, from, 3)
        assertTrue(odd.all { abs((it shr 16 and 0xFF) - 32) < 40 })
    }

    @Test
    fun cacheKeysChangeWithEverythingDrawn() {
        val base = spec("pl-1", "Late night")
        val key = coverArtKey(base, 160)
        assertEquals(key, coverArtKey(base.copy(), 160))
        assertNotEquals(key, coverArtKey(base, 164))
        assertNotEquals(key, coverArtKey(base.copy(name = "Late nights"), 160))
        assertNotEquals(key, coverArtKey(base.copy(footer = "13 songs"), 160))
        assertNotEquals(key, coverArtKey(base.copy(line = LIVE_LIST_COVER_LINE), 160))
        assertNotEquals(key, coverArtKey(base.copy(id = "pl-2"), 160))
        assertNotEquals(key, coverArtKey(base, 160, book.copy(version = 2)))
        assertNotEquals(key, coverArtKey(base, 160, library = library.copy(version = 2)))
        // Music of another colour: another background, another key.
        val red = coverArtKey(base.copy(palette = music(rgb(200, 30, 40))), 160)
        val blue = coverArtKey(base.copy(palette = music(rgb(30, 60, 200))), 160)
        assertNotEquals(red, blue)
        assertTrue(key.startsWith("playlist-art:v$COVER_DESIGN_VERSION:") && COVER_DESIGN_VERSION >= 2)
    }
}
