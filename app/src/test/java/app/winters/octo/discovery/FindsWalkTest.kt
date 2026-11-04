package app.winters.octo.discovery

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.searchKey
import app.winters.octo.listening.song
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// Waiting finds are matched against the library a page at a time, and
// adopt what adoptions would over every candidate at once, in (search key,
// id) order.
@RunWith(RobolectricTestRunner::class)
class FindsWalkTest {
    private lateinit var db: CatalogDatabase

    @Before
    fun open() {
        db = openTestCatalog()
    }

    @After
    fun close() = db.close()

    private fun find(id: String, title: String, artist: String, durationMs: Long = 200_000) = OnlineSongEntity(
        id = "find:$id", sourceId = "server:x", nativeId = id, title = title, artist = artist, album = "", albumId = null,
        artistId = null, durationMs = durationMs, coverId = null, mimeType = null, bitrate = null, seenAt = 0, requestedAt = 1,
    )

    private class Walked(val adopted: Map<String, String>, val largestPage: Int, val pages: Int)

    private suspend fun walk(waiting: List<OnlineSongEntity>): Walked {
        val adopted = LinkedHashMap<String, String>()
        var largest = 0
        var pages = 0
        adoptWaiting(
            waiting,
            page = { key, after ->
                db.dao().tracksWithKeyPage(key, after, FIND_PAGE).also {
                    pages++
                    largest = maxOf(largest, it.size)
                }
            },
            locked = { it() },
            adopt = { find, track -> adopted[find.id] = track.id },
        )
        return Walked(adopted, largest, pages)
    }

    // What adoptions picks over every candidate, sorted the declared way.
    private suspend fun oracle(waiting: List<OnlineSongEntity>): Map<String, String> {
        val keys = waiting.flatMap { titleKeys(it.title) }.distinct().sorted()
        val candidates: List<TrackEntity> = keys.chunked(900).flatMap { db.dao().tracksWithKeys(it) }
        return adoptions(waiting, candidates.filter { !it.id.startsWith("find:") }).mapValues { it.value.id }
    }

    @Test
    fun theWalkAdoptsWhatEveryCandidateAtOnceWould() = runBlocking {
        val random = Random(7)
        val titles = listOf("Intro", "Outro", "Hello", "Hello (Live)", "Hello - Remastered 2011", "Song 2", "Interlude")
        val artists = listOf("Adele", "Blur", "Adele feat. Blur", "Other")
        db.runInTransaction {
            for (n in 0 until 600) {
                // "Intro" is filed far more often than a page holds.
                val title = if (n < 200) "Intro" else titles[random.nextInt(titles.size)]
                db.song("t%04d".format(random.nextInt(100_000)) + "-$n", title = title, artist = artists[random.nextInt(artists.size)],
                    durationMs = 180_000L + random.nextInt(4) * 1_500L)
            }
        }
        val waiting = (0 until 30).map { n ->
            find("f$n", titles[random.nextInt(titles.size)], artists[random.nextInt(artists.size)], 180_000L + random.nextInt(4) * 1_500L)
        }
        val walked = walk(waiting)
        assertEquals(oracle(waiting), walked.adopted)
        assertTrue("largest page ${walked.largestPage}", walked.largestPage <= FIND_PAGE)
    }

    @Test
    fun aFindReachedOnlyThroughAnotherFindsKeyIsAdopted() = runBlocking {
        val remaster = "Hello (2011 Remaster)"
        db.song("t-remaster", title = remaster, artist = "Adele")
        val other = find("f1", remaster, "Someone Else")
        val plain = find("f2", "Hello", "Adele")
        assertTrue("the remaster is filed apart from the plain title", searchKey(remaster) !in titleKeys(plain.title))
        assertEquals(emptyMap<String, String>(), walk(listOf(plain)).adopted)
        assertEquals(mapOf("find:f2" to "t-remaster"), walk(listOf(other, plain)).adopted)
        assertEquals(oracle(listOf(other, plain)), walk(listOf(other, plain)).adopted)
    }

    @Test
    fun ofTwoSongsAlikeTheFirstByIdIsAdopted_AndTheWalkStopsOnceAllAreFound() = runBlocking {
        db.song("t-b", title = "Hello", artist = "Adele")
        db.song("t-a", title = "Hello", artist = "Adele")
        val walked = walk(listOf(find("f1", "Hello", "Adele")))
        assertEquals(mapOf("find:f1" to "t-a"), walked.adopted)
        assertEquals(1, walked.pages)
    }

    @Test
    fun songsByKeyComeInIdOrder() = runBlocking {
        db.song("t-c", title = "Hello")
        db.song("t-a", title = "Intro")
        db.song("t-b", title = "Hello")
        assertEquals(listOf("t-b", "t-c", "t-a"), db.dao().tracksWithKeys(listOf(searchKey("Intro"), searchKey("Hello"))).map { it.id })
    }
}
