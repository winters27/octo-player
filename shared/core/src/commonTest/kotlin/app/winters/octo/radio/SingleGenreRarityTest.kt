package app.winters.octo.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// A song of one genre weighs its genre the same whatever the genre's
// rarity, so a pool with no rarity picks exactly what one with the
// library's rarity picks; a song of two genres is refused, since there
// rarity would count.
class SingleGenreRarityTest {
    private val now = 1_800_000_000_000L

    private fun song(i: Int, genre: String, artist: String) =
        RadioSong("s$i", "Song $i", listOf(artist), "al${i % 23}", listOfNotNull(genre.takeIf { it.isNotEmpty() }), 1970 + i % 50, 180_000L + i * 1_000, rating = if (i % 29 == 0) 1 else 0)

    @Test
    fun noRarityPicksTheSame() {
        val genres = listOf("Rock", "Jazz", "Pop", "", "Folk", "Rock", "Rock", "Metal")
        val library = (0 until 500).map { song(it, genres[it % genres.size], "Artist ${it % 31}") }
        val suggested = (500 until 520).map { song(it, genres[it % genres.size], "Artist ${it % 7}") }
        for (s in 0 until 20) {
            val seeds = listOf(library[s * 11], library[s * 11 + 3])
            val input = RadioInput(seeds, library, suggested, now = now)
            val pool = RadioPool.of(library, suggested, seeds, Random(s))
            val withRarity = radioMixPooled(pool, input, 50, Random(100 + s)).map { it.id }
            val noRarity = radioMixPooled(RadioPool(pool.librarySize, pool.candidates, null, pool.disliked, pool.owned), input, 50, Random(100 + s)).map { it.id }
            assertEquals(withRarity, noRarity)
            // radioMix is the pool, then the picks, from one Random.
            val whole = Random(s)
            val pooled = Random(s)
            assertEquals(radioMix(input, 50, whole), radioMixPooled(RadioPool.of(library, suggested, seeds, pooled), input, 50, pooled))
        }
    }

    @Test
    fun aSongOfTwoGenresNeedsRarity() {
        val two = RadioSong("two", "Two", listOf("A"), genres = listOf("Rock", "Jazz"))
        val seed = RadioSong("seed", "Seed", listOf("A"), genres = listOf("Rock"))
        val pool = RadioPool(2, listOf(two), null, emptySet(), setOf("two", "seed"))
        val refused = runCatching { radioMixPooled(pool, RadioInput(listOf(seed), emptyList(), now = now), 5) }
        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
    }
}
