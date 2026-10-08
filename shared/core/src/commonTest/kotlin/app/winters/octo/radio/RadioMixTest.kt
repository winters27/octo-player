package app.winters.octo.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RadioMixTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun song(
        id: String,
        artist: String,
        genre: String = "Rock",
        year: Int? = 1995,
        album: String? = "al-$id",
        rating: Int = 0,
        lastPlayedAt: Long? = null,
        title: String = id,
        ms: Long = 200_000,
    ) = RadioSong(id, title, listOf(artist), album, listOf(genre), year, ms, rating = rating, lastPlayedAt = lastPlayedAt)

    private fun mix(
        seeds: List<RadioSong>,
        library: List<RadioSong>,
        count: Int,
        seed: Int = 1,
        suggested: List<RadioSong> = emptyList(),
        before: List<RadioSong> = emptyList(),
        tuning: RadioTuning = RadioTuning(),
    ) = radioMix(RadioInput(seeds, library, suggested, before, now = now, tuning = tuning), count, Random(seed)).map { it.id }

    @Test
    fun aSongMustShareAGenreWithTheSeeds() {
        val seed = song("seed", "A")
        val library = listOf(seed, song("rock", "B"), song("jazz", "A", genre = "Jazz"))
        assertEquals(listOf("rock"), mix(listOf(seed), library, 5))
    }

    @Test
    fun theRightEraBeatsAFavouriteFromAnotherEra() {
        // Songs from the seed's era that are not favourites, against
        // favourites from twenty years on: the era wins every time. Ten of
        // them, so a draw can never walk past the last one into the other
        // era (half the total weight is always inside the ten).
        val seed = song("seed", "A", year = 1995)
        val near = (1..10).map { song("near$it", "N$it", year = 1994 + it % 3) }
        val far = (1..6).map { song("far$it", "F$it", year = 2014 + it).copy(liked = true) }
        repeat(50) { run ->
            val picks = mix(listOf(seed), listOf(seed) + near + far, 3, seed = run)
            assertTrue(picks.toString(), picks.all { it.startsWith("near") })
        }
    }

    @Test
    fun songsHeardLatelyComeOnlyWhenNothingElseIs() {
        // The song heard two days ago is by far the better match (same artist,
        // same era, a favourite), so only the wait can put the other first.
        val seed = song("seed", "A")
        val heard = song("heard", "A", lastPlayedAt = now - 2 * day).copy(liked = true)
        val fresh = song("fresh", "B", year = 2015, lastPlayedAt = now - 20 * day)
        val library = listOf(seed, fresh, heard)
        repeat(20) { run ->
            assertEquals(listOf("fresh"), mix(listOf(seed), library, 1, seed = run))
            assertEquals(listOf("fresh", "heard"), mix(listOf(seed), library, 2, seed = run))
        }
    }

    @Test
    fun aOneStarSongNeverPlays() {
        val seed = song("seed", "A")
        val library = listOf(seed, song("bad", "B", rating = 1), song("ok", "C"))
        repeat(50) { assertFalse("bad" in mix(listOf(seed), library, 5, seed = it)) }
    }

    @Test
    fun fillerNeverPlays() {
        val seed = song("seed", "A")
        val library = listOf(seed, song("skit", "B", title = "Skit", ms = 40_000), song("ok", "C"))
        assertEquals(listOf("ok"), mix(listOf(seed), library, 5))
    }

    @Test
    fun anArtistWaitsItsTurn() {
        // Five artists, one of them with five songs: the first five picks are
        // by five different artists, whatever the draw.
        val seed = song("seed", "A")
        val library = listOf(seed) + (1..5).map { song("a$it", "A") } + listOf("B", "C", "D", "E").map { song("x$it", it) }
        repeat(50) { run ->
            val picks = radioMix(RadioInput(listOf(seed), library, now = now), 5, Random(run))
            assertEquals(5, picks.map { it.artists }.distinct().size)
        }
    }

    @Test
    fun twoArtistsTakeTurns() {
        val seed = song("seed", "A")
        val library = listOf(seed, song("a1", "A"), song("a2", "A"), song("b1", "B"))
        repeat(20) { run ->
            val picks = radioMix(RadioInput(listOf(seed), library, now = now), 3, Random(run))
            assertEquals(listOf("A", "B", "A"), picks.map { it.artists.single() })
        }
    }

    @Test
    fun aSmallLibraryStillFillsTheCount() {
        val seed = song("seed", "A")
        val library = listOf(seed, song("a1", "A", album = "one"), song("a2", "A", album = "one"), song("a3", "A", album = "one"))
        assertEquals(3, mix(listOf(seed), library, 3).size)
    }

    @Test
    fun theServersSuggestionsCountWithoutAGenre() {
        val seed = song("seed", "A", genre = "")
        val suggested = listOf(song("s1", "B", genre = ""), song("s2", "C", genre = ""))
        assertEquals(setOf("s1", "s2"), mix(listOf(seed), listOf(seed), 5, suggested = suggested).toSet())
    }

    // A library of close matches and as many suggestions the library does
    // not have, which carry no genre or year, so nothing but the placement
    // decides which kind plays where. "O" is a song from outside, "L" one
    // from the library.
    private val seedSong = song("seed", "A")
    private val plenty = listOf(seedSong) + (1..60).map { song("lib$it", "L$it") }
    private val found = (1..60).map { song("new$it", "S$it", genre = "", year = null) }

    private fun kinds(tuning: RadioTuning = RadioTuning(), runs: Int = 200, count: Int = 30): List<String> =
        (0 until runs).map { run ->
            mix(listOf(seedSong), plenty, count, seed = run, suggested = found, tuning = tuning)
                .joinToString("") { if (it.startsWith("new")) "O" else "L" }
        }

    @Test
    fun outsideSongsLandAtRandomNotEveryThirdSong() {
        val openings = kinds().map { it.take(9) }
        assertTrue(openings.toSet().size.toString(), openings.toSet().size >= 20)
        assertTrue(openings.count { it == "LLOLLOLLO" } < 10)
    }

    @Test
    fun theShareHoldsAcrossRadios() {
        val radios = kinds()
        radios.forEach { assertEquals(30, it.length) }
        assertTrue(radios.toString(), radios.all { it.count { kind -> kind == 'O' } in 8..13 })
        val share = radios.sumOf { it.count { kind -> kind == 'O' } } / (30.0 * radios.size)
        assertTrue(share.toString(), share in 0.33..0.37)
    }

    @Test
    fun neverThreeOutsideSongsInARow() {
        assertTrue(kinds().none { "OOO" in it })
    }

    @Test
    fun mostlyNewNeverPlaysThreeLibrarySongsInARow() {
        val radios = kinds(RadioTuning(discovery = RadioDiscovery.MostlyNew))
        assertTrue(radios.none { "LLL" in it })
        val share = radios.sumOf { it.count { kind -> kind == 'O' } } / (30.0 * radios.size)
        assertTrue(share.toString(), share in 0.82..0.88)
    }

    @Test
    fun eachDiscoveryLevelKeepsItsShare() {
        listOf(RadioDiscovery.ALittle, RadioDiscovery.Lots).forEach { level ->
            val radios = kinds(RadioTuning(discovery = level), runs = 100)
            val share = radios.sumOf { it.count { kind -> kind == 'O' } } / (30.0 * radios.size)
            assertTrue("$level $share", share in level.share - 0.03..level.share + 0.03)
        }
    }

    @Test
    fun onlyMyLibraryNeverPlaysAnOutsideSong() {
        val library = listOf(seedSong, song("a", "B"), song("b", "C"), song("c", "D"))
        val picks = mix(listOf(seedSong), library, 10, suggested = found, tuning = RadioTuning(discovery = RadioDiscovery.LibraryOnly))
        assertEquals(setOf("a", "b", "c"), picks.toSet())
        assertEquals(3, picks.size)
    }

    @Test
    fun outsideDueFollowsItsRules() {
        val random = Random(1)
        assertFalse(outsideDue(0.0, 5, 0, 0, random))
        assertTrue(outsideDue(1.0, 5, 5, 3, random))
        // Too far behind the share: outside, whatever the draw.
        assertTrue(outsideDue(0.35, 4, 0, -4, random))
        // Too far ahead of it: the library.
        assertFalse(outsideDue(0.35, 2, 2, 2, random))
        // Two outside in a row is the most under half; two library in a row over half.
        assertFalse(outsideDue(0.35, 5, 2, 2, random))
        assertTrue(outsideDue(0.85, 5, 5, -2, random))
    }

    @Test
    fun focusedStaysCloserThanWander() {
        // Forty songs a year apart from the seed's year on: the closer, the
        // better the match.
        val seed = song("seed", "A", year = 1995)
        val library = listOf(seed) + (0 until 40).map { song("y$it", "Y$it", year = 1995 + it) }
        fun distance(adventure: RadioAdventure): Double = (0 until 200).sumOf { run ->
            radioMix(RadioInput(listOf(seed), library, now = now, tuning = RadioTuning(adventure = adventure)), 5, Random(run))
                .sumOf { (it.year!! - 1995).toDouble() }
        } / 1000.0
        val focused = distance(RadioAdventure.Focused)
        val balanced = distance(RadioAdventure.Balanced)
        val wander = distance(RadioAdventure.Wander)
        assertTrue("$focused $balanced $wander", focused < balanced && balanced < wander)
    }

    @Test
    fun tightVarietyLetsAnArtistBackSooner() {
        // Six artists with eight songs each. Normal spacing keeps an artist
        // at least four picks apart; Tight lets one back after two others.
        val seed = song("seed", "Z")
        val library = listOf(seed) + (1..6).flatMap { a -> (1..8).map { song("a$a-$it", "Artist $a") } }
        fun closeRepeats(variety: RadioVariety): Int = (0 until 100).count { run ->
            val artists = radioMix(RadioInput(listOf(seed), library, now = now, tuning = RadioTuning(variety = variety)), 12, Random(run))
                .map { it.artists.single() }
            artists.indices.any { i -> (1..3).any { d -> i + d < artists.size && artists[i] == artists[i + d] } }
        }
        assertTrue(closeRepeats(RadioVariety.Tight) > 0)
        assertEquals(0, closeRepeats(RadioVariety.Normal))
    }

    @Test
    fun favoritesMoreOftenPlaysMoreFavorites() {
        // The favorites are four years off the seed, so on merit they come
        // just after the rest; asked for, they come first.
        val seed = song("seed", "A", year = 1995)
        val rest = (1..15).map { song("r$it", "R$it", year = 1995) }
        val liked = (1..15).map { song("f$it", "F$it", year = 1999).copy(liked = true) }
        val library = listOf(seed) + rest + liked
        fun favorites(on: Boolean): Double = (0 until 200).sumOf { run ->
            radioMix(RadioInput(listOf(seed), library, now = now, tuning = RadioTuning(favorites = on)), 10, Random(run))
                .count { it.liked }.toDouble()
        } / 200.0
        val off = favorites(false)
        val on = favorites(true)
        assertTrue("$off $on", on >= off + 1.0)
    }

    @Test
    fun theFirstSeedAndExcludedSongsAreNeverPicked() {
        val seed = song("seed", "A")
        val library = listOf(seed, song("x", "B"), song("y", "C"))
        val picks = radioMix(RadioInput(listOf(seed), library, exclude = setOf("x"), now = now), 5, Random(3)).map { it.id }
        assertEquals(listOf("y"), picks)
    }

    @Test
    fun theSameDrawGivesTheSameMix() {
        val seed = song("seed", "A")
        val library = listOf(seed) + (1..30).map { song("s$it", "Artist ${it % 7}", year = 1990 + it % 10) }
        assertEquals(mix(listOf(seed), library, 10, seed = 9), mix(listOf(seed), library, 10, seed = 9))
    }

    @Test
    fun spacingGrowsWithTheLibrary() {
        assertEquals(8, radioSpacing(0))
        assertEquals(16, radioSpacing(60_000))
        assertEquals(30, radioSpacing(1_000_000))
    }
}
