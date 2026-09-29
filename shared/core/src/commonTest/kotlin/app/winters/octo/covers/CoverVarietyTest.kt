package app.winters.octo.covers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// A page of lists must not look like copies: lists whose music is alike
// still get different backgrounds, or the same one turned another way.
class CoverVarietyTest {
    // The server's sheet of 24 lists and the colour of each one's music as
    // the server takes it from real seed covers (hue, chroma, lightness;
    // hue -1 for none), the same data the server's own test uses. The list's
    // name is the hash input, as on the server.
    private val lists = listOf(
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

    private fun paletteOf(list: Triple<String, Int, Pair<Double, Double>>): CoverPalette =
        if (list.second < 0) seededPalette(list.first) else CoverPalette.of(list.second.toDouble(), list.third.first, list.third.second, fromMusic = true)

    @Test
    fun aSheetOfListsHasNoCopies() {
        assertEquals(24, lists.size)
        val picks = lists.map { chooseBackground(it.first, paletteOf(it)).file to coverOrientation(it.first) }
        val pairs = picks.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("the same background turned the same way: $pairs", pairs.isEmpty())
        val backgrounds = picks.groupingBy { it.first }.eachCount().filterValues { it > 3 }
        assertTrue("one background too often: $backgrounds", backgrounds.isEmpty())
        // Dull music picks like no music at all.
        val dull = lists.first { it.first == "Daft Punk Radio" }
        assertEquals(chooseBackground(dull.first, seededPalette(dull.first)), chooseBackground(dull.first, paletteOf(dull)))
        assertTrue(CoverBook.Default.background.lowChromaAsGrey > 0.043)
    }

    @Test
    fun theTurnIsFromTheIdAndCoversAllEight() {
        val turns = (0 until 400).map { coverOrientation("pl-$it") }
        assertEquals(turns, (0 until 400).map { coverOrientation("pl-$it") })
        assertEquals((0 until 8).toSet(), turns.toSet())
        // The rule as written: (coverPick(id) >>> 11) mod 8, with a fixed
        // value so the server's port can check its hash.
        assertEquals(0x098f28ee76f647ceL, coverPick("pl-1"))
        assertEquals(((coverPick("pl-1") ushr 11) % 8).toInt(), coverOrientation("pl-1"))
        assertTrue(CoverBook.Default.background.nearest in 6..8)
    }

    // Numbered ids, as some servers give playlists, differ only in their
    // last letters; they must not all pick alike.
    @Test
    fun numberedListsLookApart() {
        for (ids in listOf((1..12).map { "$it" }, (1..12).map { "p$it" })) {
            val none = ids.map { chooseBackground(it, seededPalette(it)).file to coverOrientation(it) }
            assertTrue("$ids: $none", none.toSet().size == ids.size)
            assertTrue(ids.map { coverOrientation(it) }.toSet().size >= 5)
            val warm = coverPalette(listOf(listOf(Swatch(0xFFE0701F.toInt(), 1f))), "x")
            // Twelve lists of one colour among a few backgrounds and eight
            // turns: an odd repeat can happen, a pile-up must not.
            val picks = ids.map { chooseBackground(it, warm).file to coverOrientation(it) }
            assertTrue("$ids warm: $picks", picks.toSet().size >= ids.size - 2)
        }
    }

    @Test
    fun aBackgroundTurnsAndMirrorsAsTheRuleSays() {
        // 0 1
        // 2 3
        val p = intArrayOf(0, 1, 2, 3)
        assertArrayEquals(p, orientBackground(p, 2, 0))
        // A quarter turn clockwise: the left column becomes the top row.
        assertArrayEquals(intArrayOf(2, 0, 3, 1), orientBackground(p, 2, 1))
        assertArrayEquals(intArrayOf(3, 2, 1, 0), orientBackground(p, 2, 2))
        assertArrayEquals(intArrayOf(1, 3, 0, 2), orientBackground(p, 2, 3))
        // Mirrored after turning.
        assertArrayEquals(intArrayOf(1, 0, 3, 2), orientBackground(p, 2, 4))
        assertArrayEquals(intArrayOf(0, 2, 1, 3), orientBackground(p, 2, 5))
        // A 3 square turned four times comes back.
        val q = IntArray(9) { it }
        var r = q
        repeat(4) { r = orientBackground(r, 3, 1) }
        assertArrayEquals(q, r)
    }
}
