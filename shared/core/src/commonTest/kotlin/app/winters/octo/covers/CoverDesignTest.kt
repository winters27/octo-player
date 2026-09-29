package app.winters.octo.covers

import app.winters.octo.player.immersive.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoverDesignTest {
    private val setter = FakeTypesetter()
    private val book = CoverBook.Default

    private fun spec(id: String, name: String, palette: CoverPalette = seededPalette(id)) =
        CoverSpec(id, name, PLAYLIST_COVER_LINE, "12 songs", palette)

    @Test
    fun theSharedBookReads() {
        assertEquals(1, book.version)
        assertEquals(16, book.gradients.size)
        assertTrue(book.gradients.all { it.colours.size == 3 && it.base.stops.isNotEmpty() })
        assertEquals("InterDisplay-SemiBold.ttf", book.fonts.title)
        assertEquals(0.08f, book.layout.margin)
    }

    @Test
    fun aListAlwaysGetsTheSameGradientAndAListOfThemVaries() {
        val ids = (0 until 200).map { "pl-$it" }
        assertEquals(ids.map { coverGradientOf(it) }, ids.map { coverGradientOf(it) })
        val used = ids.groupingBy { coverGradientOf(it) }.eachCount()
        assertEquals(book.gradients.size, used.size)
        // No gradient takes much more than its share.
        assertTrue(used.values.all { it < 200 / book.gradients.size * 3 })
    }

    @Test
    fun theWordsSitWhereTheLayoutSays() {
        val art = composeCover(spec("pl-1", "Late night"), 300, setter)
        val (title, line, footer) = art.words
        assertEquals("Late night", title.text)
        assertEquals(24f, title.left)
        assertEquals(30f, title.top)
        assertEquals(600, title.type.weight)
        assertEquals(PLAYLIST_COVER_LINE, line.text)
        assertEquals(300, line.type.weight)
        assertEquals(title.top + title.height, line.top)
        assertEquals("12 songs", footer.text)
        assertEquals(300f - 24f, footer.top + footer.height, 0.5f)
        assertEquals(400, footer.type.weight)
    }

    @Test
    fun aShortNameIsOneLargeLineALongOneWraps() {
        val short = composeCover(spec("pl-1", "Chill"), 300, setter).words.first()
        assertEquals(1, short.measured.lines)
        assertEquals(300 * book.layout.title.size, short.type.sizePx, 1f)
        val long = composeCover(spec("pl-1", "Everything I have ever loved in the order I found it"), 300, setter).words.first()
        assertTrue(long.measured.lines in 2..3)
        assertTrue(long.type.sizePx <= 300 * book.layout.title.wrapSize)
    }

    @Test
    fun wordsStayInsideTheMarginsAtEverySize() {
        val names = listOf("Chill", "Dinner with friends", "Everything I have ever loved, in the order I found it", "夜のドライブ", "أغاني الصيف", "🔥🔥🔥", "Supercalifragilisticexpialidocious")
        for (side in listOf(32, 48, 72, 96, 128, 160, 240, 512, 1000)) for (name in names) {
            val art = composeCover(spec("pl-$name", name), side, setter)
            for (words in art.words) {
                val (l, t, r, b) = words.inked.toList()
                assertTrue("$name at $side: ${words.text} runs off", l >= 0f && r <= side.toFloat() && t >= 0f && b <= side.toFloat())
                val margin = if (side < book.layout.tinyBelowPx) side * book.layout.tinyMargin else side * book.layout.margin
                assertTrue("$name at $side: ${words.text} crosses the margin", l >= margin - 1f && r <= side - margin + 1f)
            }
            // The name never runs into the foot line.
            if (art.words.size >= 2 && art.words.last().text == "12 songs") {
                assertTrue(art.words.dropLast(1).all { it.top + it.height <= art.words.last().top })
            }
        }
    }

    @Test
    fun tinyCoversShowOneCharacter() {
        val art = composeCover(spec("pl-1", "late night"), 32, setter)
        assertEquals(listOf("L"), art.words.map { it.text })
        assertTrue(art.words.first().type.sizePx >= 32 * 0.3f)
    }

    @Test
    fun aRightToLeftNameSitsOnTheRight() {
        val art = composeCover(spec("pl-1", "أغاني الصيف"), 300, setter)
        assertTrue(art.words.all { it.align == CoverAlign.Right })
        assertEquals(300f - 24f, art.words.first().inked[2], 0.5f)
    }

    // Every word, at its opacity, against every part of the colour under it.
    private fun worst(art: CoverArt): Double = art.words.minOf { words -> worstContrast(art.layers, words.inked, art.side, words.ink) }

    @Test
    fun everyWordReadsOnEveryCover() {
        val random = Random(7)
        repeat(300) { n ->
            val covers = List(random.nextInt(0, 4)) { List(random.nextInt(1, 4)) { Swatch((0xFF shl 24) or random.nextInt(0xFFFFFF), random.nextFloat()) } }
            val id = "pl-$n"
            val side = listOf(96, 160, 300, 1000)[n % 4]
            val art = composeCover(CoverSpec(id, "Name $n", LIVE_LIST_COVER_LINE, "By sam", coverPalette(covers, id)), side, setter)
            assertTrue("cover $n reads at ${worst(art)}", worst(art) >= book.layout.contrast)
            // Always white words, never dark ones.
            assertTrue(art.words.all { it.ink and 0xFFFFFF == 0xFFFFFF })
        }
    }

    @Test
    fun paleColoursUnderTheWordsAreDarkenedJustEnough() {
        // A gradient forced pale: white words need the colour under them darker.
        val pale = book.gradients[0].copy(colours = listOf("#f4eefc", "#fbe9f4", "#ffffff"), light = null)
        val paleBook = book.copy(gradients = listOf(pale))
        val art = composeCover(spec("pl-1", "Late night"), 300, setter, paleBook)
        assertTrue(art.layers.size > gradientLayers(pale, gradientColours(pale, seededPalette("pl-1"), paleBook), 300).size)
        assertTrue(worst(art) >= book.layout.contrast)
        assertTrue(worst(art) < book.layout.contrast + 1.0)
    }

    @Test
    fun theFootLineGoesSolidOnlyWhereItMust() {
        val art = composeCover(spec("pl-2", "Late night"), 300, setter)
        val footer = art.words.last()
        val opacity = (footer.ink ushr 24) / 255f
        assertTrue(opacity >= book.layout.footer.opacity - 0.01f)
        // At its own opacity it reads, or it went just solid enough.
        val darkened = art.layers.size > 1 + book.gradients[coverGradientOf("pl-2")].folds.size + 1
        if (opacity > book.layout.footer.opacity + 0.01f && !darkened) {
            val under = worstContrast(art.layers, footer.inked, 300, (((opacity - 0.03f) * 255).toInt() shl 24) or 0xFFFFFF)
            assertTrue(under < book.layout.contrast + 0.05)
        }
    }

    @Test
    fun musicColoursKeepTheGradientsLightness() {
        val gradient = book.gradients[0]
        val palette = coverPalette(listOf(listOf(Swatch(0xFF2FB4A8.toInt(), 1f))), "x")
        val written = gradient.colours.map(::hexColour)
        val tinted = gradientColours(gradient, palette)
        written.forEachIndexed { i, argb -> assertEquals(toLch(argb).l, toLch(tinted[i]).l, 0.03) }
        // The fold's colour takes the music's hue.
        assertTrue(hueDistance(toLch(tinted[gradient.folds.first().colour]).h, palette.hue.toDouble()) < 3)
        // No covers: the gradient as written.
        assertEquals(written + hexColour(gradient.light!!.colour), gradientColours(gradient, seededPalette("x")))
    }

    @Test
    fun deepColoursNeverTurnMuddy() {
        for (hue in 0 until 360 step 5) {
            for (l in listOf(0.3, 0.5)) {
                val moved = notOlive(hue.toDouble(), l, towardRed = true)
                if (l < 0.45) assertTrue("$hue at $l", moved !in 40.0..170.0 || moved == 20.0)
            }
        }
    }

    @Test
    fun colourAtFollowsTheLayers() {
        val layers = listOf(
            CoverLayer.Linear(0f, 0f, 100f, 0f, listOf(Stop(0f, 0xFF000000.toInt()), Stop(1f, 0xFFFFFFFF.toInt()))),
            CoverLayer.Radial(100f, 50f, 10f, listOf(Stop(0f, 0x80FF0000.toInt()), Stop(1f, 0x00FF0000))),
        )
        assertEquals(0xFF000000.toInt(), colourAt(layers, 0f, 50f))
        assertEquals(0xFF808080.toInt(), colourAt(layers, 50f, 50f))
        // Half red over white at the light's centre.
        assertEquals(0xFFFF7F7F.toInt(), colourAt(layers, 100f, 50f))
        assertTrue(contrastRatio(colourAt(layers, 99f, 50f), 0xFFFFFFFF.toInt()) > 1.0)
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
        assertNotEquals(key, coverArtKey(base.copy(palette = coverPalette(listOf(listOf(Swatch(0xFF2FB4A8.toInt(), 1f))), "pl-1")), 160))
        assertNotEquals(key, coverArtKey(base.copy(id = "pl-2"), 160))
        assertNotEquals(key, coverArtKey(base, 160, book.copy(version = 2)))
        assertTrue(key.contains("v$COVER_DESIGN_VERSION"))
    }
}
