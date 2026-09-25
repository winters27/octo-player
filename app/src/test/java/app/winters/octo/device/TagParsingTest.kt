package app.winters.octo.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagParsingTest {
    private fun tags(vararg pairs: Pair<String, List<String>>) =
        parseTags(pairs.associate { (k, v) -> k to v.toTypedArray() })

    @Test
    fun trackAndDiscAcceptSlashForm() {
        val t = tags("TRACKNUMBER" to listOf("3/12"), "DISCNUMBER" to listOf("02"))
        assertEquals(3, t.trackNo)
        assertEquals(2, t.discNo)
    }

    @Test
    fun zeroTrackIsNotSet() {
        assertNull(tags("TRACKNUMBER" to listOf("0")).trackNo)
    }

    @Test
    fun originalDateBeatsDate() {
        assertEquals(1994, tags("DATE" to listOf("2014-03-01"), "ORIGINALDATE" to listOf("1994")).year)
        assertEquals(2015, tags("DATE" to listOf("20150904")).year)
    }

    @Test
    fun multipleArtistValuesAreKept() {
        val t = tags("ARTIST" to listOf("Kavinsky", "Angèle"))
        assertEquals("Kavinsky, Angèle", t.artist)
    }

    @Test
    fun artistsTagWinsOverArtist() {
        assertEquals("A, B", tags("ARTIST" to listOf("A feat. B"), "ARTISTS" to listOf("A", "B")).artist)
    }

    @Test
    fun keysAreCaseInsensitiveAndBlanksIgnored() {
        val t = tags("albumartist" to listOf("  "), "Album Artist" to listOf("Kavinsky"))
        assertEquals("Kavinsky", t.albumArtist)
    }

    @Test
    fun compilationFlag() {
        assertTrue(tags("COMPILATION" to listOf("1")).compilation)
        assertFalse(tags().compilation)
    }

    @Test
    fun genreValuesAreKeptInOrder() {
        assertEquals(listOf("Synthwave", "Electronic"), tags("GENRE" to listOf("Synthwave", " Electronic ")).genres)
        assertEquals(emptyList<String>(), tags().genres)
    }

    @Test
    fun oneGenreValueSplitsOnSemicolonSlashAndComma() {
        assertEquals(listOf("Rock", "Pop", "Indie", "Folk"), parseGenres(listOf("Rock; Pop/Indie , Folk")))
        assertEquals(listOf("Rock", "Pop"), parseGenres(listOf("Rock\u0000Pop")))
    }

    @Test
    fun ampersandNeverSplitsAGenre() {
        assertEquals(listOf("R&B", "Drum & Bass"), parseGenres(listOf("R&B; Drum & Bass")))
    }

    @Test
    fun namesWrittenWithASeparatorStayWhole() {
        assertEquals(listOf("Singer/Songwriter", "Folk"), parseGenres(listOf("Singer/Songwriter; Folk")))
        assertEquals(listOf("hip-hop/rap"), parseGenres(listOf("hip-hop/rap")))
        assertEquals(listOf("Folk, World, & Country", "Pop"), parseGenres(listOf("Folk, World, & Country, Pop")))
        assertEquals(listOf("Rock", "R&B/Soul"), parseGenres(listOf("Rock / R&B/Soul")))
    }

    @Test
    fun keptNamesAreOnlyWholePieces() {
        assertEquals(listOf("Pop", "Soulful House"), parseGenres(listOf("Pop/Soulful House")))
        assertEquals(listOf("Hip-Hop", "Rapcore"), parseGenres(listOf("Hip-Hop/Rapcore")))
    }

    @Test
    fun genresAreTidiedAndDeduplicated() {
        assertEquals(listOf("Deep House"), parseGenres(listOf("  Deep   House ;; deep house", ";", "")))
    }
}
