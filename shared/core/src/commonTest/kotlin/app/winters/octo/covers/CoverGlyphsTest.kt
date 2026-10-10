package app.winters.octo.covers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverGlyphsTest {
    private val setter = FakeTypesetter()
    private val numbers = CoverBook.Default.glyphs!!

    @Test
    fun theBookHasTheGlyphs() {
        assertEquals(0.36f, numbers.box)
        assertEquals(listOf(0.5f, 0.88f), numbers.heart.from)
        assertEquals(0.3f, numbers.magnifier.radius)
        assertEquals(0.56f, numbers.copies.side)
    }

    @Test
    fun everyGlyphSitsInItsCentredBox() {
        for (side in listOf(40, 72, 160, 600)) for (glyph in CoverGlyph.entries) {
            val drawing = glyphDrawing(glyph, side, numbers)
            val (left, top, size) = drawing.box
            assertEquals(side * 0.36f, size, 0.001f)
            assertEquals((side - size) / 2, left, 0.001f)
            assertEquals((side - size) / 2, top, 0.001f)
            assertTrue(drawing.steps.isNotEmpty() && drawing.steps.first().add)
            for (step in drawing.steps) {
                val (l, t, r, b) = step.shape.bounds.toList()
                assertTrue("$glyph at $side: $step", r > l && b > t)
                assertTrue("$glyph at $side runs out of its box: $step", l >= left - 0.01f && t >= top - 0.01f && r <= left + size + 0.01f && b <= top + size + 0.01f)
            }
        }
    }

    @Test
    fun theHeartIsMirrored() {
        val heart = glyphDrawing(CoverGlyph.Heart, 100, numbers).steps.single().shape as GlyphShape.Curves
        val (l, _, r, _) = heart.bounds.toList()
        assertEquals(50f, (l + r) / 2, 0.001f)
        assertEquals(heart.x, heart.cubics.last()[4], 0.001f)
        assertEquals(heart.y, heart.cubics.last()[5], 0.001f)
    }

    // The back square's stroke is cut where it nears the front one.
    @Test
    fun theCopiesCutAGapAroundTheFrontSquare() {
        val steps = glyphDrawing(CoverGlyph.Copies, 100, numbers).steps
        assertEquals(listOf(true, false, false, true), steps.map { it.add })
        val gap = steps[2].shape as GlyphShape.RoundSquare
        val front = steps[3].shape as GlyphShape.RoundSquare
        assertEquals(front.left - 0.06f * 36f, gap.left, 0.001f)
        assertEquals(front.side + 2 * 0.06f * 36f, gap.side, 0.001f)
    }

    @Test
    fun aGlyphCoverHasNoWordsAndAVeilUnderTheGlyph() {
        val spec = CoverSpec("og-liked", "Liked Songs", PLAYLIST_COVER_LINE, "12 songs", seededPalette("og-liked"), glyph = CoverGlyph.Heart)
        val plan = planCover(spec, 600, setter)
        assertTrue(plan.words.isEmpty())
        assertNotNull(plan.glyph)
        val box = plan.veil.single().box
        val grown = 600 * 0.36f * 1.15f
        assertEquals(grown, box[2] - box[0], 0.01f)
        assertEquals(300f, (box[0] + box[2]) / 2, 0.01f)
        assertEquals(chooseBackground("og-liked", spec.palette), plan.background)
        // Without a glyph the same list is words.
        assertNull(planCover(spec.copy(glyph = null), 600, setter).glyph)
    }

    @Test
    fun theGlyphIsPartOfTheKey() {
        val spec = CoverSpec("p", "Review", PLAYLIST_COVER_LINE, null, seededPalette("p"))
        assertNotEquals(coverArtKey(spec, 160), coverArtKey(spec.copy(glyph = CoverGlyph.Magnifier), 160))
        assertNotEquals(coverArtKey(spec.copy(glyph = CoverGlyph.Copies), 160), coverArtKey(spec.copy(glyph = CoverGlyph.Magnifier), 160))
    }

    @Test
    fun marksPickTheirGlyph() {
        assertEquals(CoverGlyph.Magnifier, playlistGlyph("review", null))
        assertEquals(CoverGlyph.Copies, playlistGlyph("duplicates", null))
        assertEquals(CoverGlyph.Heart, playlistGlyph(null, "liked"))
        assertNull(playlistGlyph(null, "newReleases"))
        assertNull(playlistGlyph("somethingNewer", null))
        assertNull(playlistGlyph(null, null))
    }
}
