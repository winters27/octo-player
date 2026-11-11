package app.winters.octo.playback

import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// Every song that could fit is offered to the pool once: the database's
// genre classes fold ASCII case only, so "Rock", "ROCK" and "rock" are one
// class and "Röck" and "RÖCK" two more, each matched by the radio's own key
// ("rock") and walked once; the seed artist's songs in a matched class are
// not offered twice, and their songs in other genres, or with none, are; a
// song with no artist name is the seed artist's by its artist id. Guard:
// the base read the whole library, with no pool to offer to.
@RunWith(RobolectricTestRunner::class)
class RadioCaseVariantsOnceTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun eachSongIsOfferedOnce() = timed(30) {
        val spellings = listOf("Rock", "ROCK", "rock", "Röck", "RÖCK")
        val rock = spellings.flatMapIndexed { s, genre -> (0 until 4).map { radioTrack("r$s-$it", genre = genre, artist = "Other $it") } }
        val band = listOf(
            radioTrack("b1", genre = "rock", artist = "Band"),
            radioTrack("b2", genre = "RÖCK", artist = "Band"),
            radioTrack("b3", genre = "Jazz", artist = "Band"),
            radioTrack("b4", genre = "", artist = "Band"),
            radioTrack("b5", genre = "Pop", artist = "", artistId = "Band"),
        )
        val elsewhere = listOf(radioTrack("j1", genre = "Jazz", artist = "Someone"), radioTrack("p1", genre = "Pop", artist = "Else"))
        db.putTracks(rock + band + elsewhere)
        val rig = RadioRig(db, page = 2).started()
        var seen: Pair<Int, List<String>>? = null
        rig.radio.hooks = object : RadioHooks() {
            override suspend fun walked(offered: Int, kept: List<String>) {
                seen = offered to kept
            }
        }
        rig.radio.songsLike(listOf(radioTrack("seed", genre = "ROCK", artist = "Band")), 10, random = Random(1))
        val (offered, kept) = seen!!
        val expected = (rock + band).map { it.id }.toSet()
        assertEquals(expected.size, offered)
        assertEquals(expected, kept.toSet())
        assertEquals(kept.size, kept.toSet().size)
    }
}
