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
    ) = radioMix(RadioInput(seeds, library, suggested, before, now = now), count, Random(seed)).map { it.id }

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

    @Test
    fun songsFromOutsideTheLibraryKeepTheirShare() {
        // A library of close matches against suggestions the library does
        // not have, which carry no genre or year: about a third still come
        // from the suggestions, and none of them first.
        val seed = song("seed", "A")
        val library = listOf(seed) + (1..30).map { song("lib$it", "L$it") }
        val suggested = (1..30).map { song("new$it", "S$it", genre = "", year = null) }
        repeat(20) { run ->
            val picks = mix(listOf(seed), library, 30, seed = run, suggested = suggested)
            assertEquals(30, picks.size)
            assertTrue(picks.toString(), picks.count { it.startsWith("new") } in 9..11)
            assertTrue(picks.first().startsWith("lib"))
        }
    }

    @Test
    fun noDiscoveryShareMeansTheLibraryWinsOnMerit() {
        val seed = song("seed", "A")
        val library = listOf(seed) + (1..30).map { song("lib$it", "L$it") }
        val suggested = (1..30).map { song("new$it", "S$it", genre = "", year = null) }
        val picks = radioMix(RadioInput(listOf(seed), library, suggested, now = now, discoveryShare = 0.0), 10, Random(1))
        assertTrue(picks.all { it.id.startsWith("lib") })
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
