package app.winters.octo.catalog

import androidx.room.withTransaction
import app.winters.octo.device.DEVICE
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// The sets of ids that move together (SaveSets, over save_node): the sets
// unions make are the connected parts a brute-force search finds; every
// kind of edge the find makes, on its own, joins its two ends; and a find
// reads at most log2(n) + 1 rows however the unions came.
@RunWith(RobolectricTestRunner::class)
class SaveSetsTest {
    private fun key(i: Int) = SaveNodeKey(RENAME_SONG, "n$i")

    @Test
    fun unionsMakeTheSetsABruteForceSearchFinds() = runBlocking {
        for (seed in 1L..4L) {
            val db = openTestCatalog()
            try {
                val random = Random(seed)
                val n = 300
                val edges = List(260) { random.nextInt(n) to random.nextInt(n) }
                val numbers = SaveNodeNumbers(0)
                // Every node made, then the edges a few to a transaction.
                db.withTransaction { SaveSets(db.openHelper.writableDatabase, numbers).use { s -> (0 until n).forEach { s.touch(key(it)) } } }
                for (batch in edges.chunked(17)) {
                    db.withTransaction { SaveSets(db.openHelper.writableDatabase, numbers).use { s -> batch.forEach { (a, b) -> s.union(key(a), key(b)) } } }
                }
                // The parts by a search over the edges.
                val near = HashMap<Int, MutableList<Int>>()
                for ((a, b) in edges) {
                    near.getOrPut(a) { ArrayList() } += b
                    near.getOrPut(b) { ArrayList() } += a
                }
                val part = IntArray(n) { -1 }
                for (start in 0 until n) {
                    if (part[start] >= 0) continue
                    val stack = ArrayDeque(listOf(start))
                    while (stack.isNotEmpty()) {
                        val at = stack.removeLast()
                        if (part[at] >= 0) continue
                        part[at] = start
                        near[at].orEmpty().forEach { if (part[it] < 0) stack.addLast(it) }
                    }
                }
                val roots = db.withTransaction { SaveSets(db.openHelper.writableDatabase, numbers).use { s -> (0 until n).map { s.find(key(it))!! } } }
                for (a in 0 until n) {
                    for (b in a + 1 until n) {
                        assertEquals("seed $seed: n$a and n$b", part[a] == part[b], roots[a] == roots[b])
                    }
                }
            } finally {
                db.close()
            }
        }
    }

    // Unions that would chain a tree n deep if the smaller root did not go
    // under the larger (each new id joined to the last), and unions of equal
    // trees, which make the deepest trees union by size allows: no find
    // reads more than log2(n) + 1 rows of save_node.
    @Test
    fun aFindReadsAtMostLog2NPlusOneRows() = runBlocking {
        for (shape in listOf("chain", "pairs")) {
            val db = openTestCatalog()
            try {
                val n = 256
                val numbers = SaveNodeNumbers(0)
                val unions = when (shape) {
                    "chain" -> (0 until n - 1).map { it to it + 1 }
                    else -> generateSequence(1) { it * 2 }.takeWhile { it < n }.flatMap { step -> (0 until n step 2 * step).map { it + step - 1 to it + 2 * step - 1 } }.toList()
                }
                db.withTransaction { SaveSets(db.openHelper.writableDatabase, numbers).use { s -> unions.forEach { (a, b) -> s.union(key(a), key(b)) } } }
                val log = db.counted
                val most = (0 until n).maxOf { i ->
                    val (_, statements) = log.record { runBlocking { db.withTransaction { SaveSets(db.openHelper.writableDatabase, numbers).use { s -> s.find(key(i)) } } } }
                    val reads = statements.filter { "FROM save_node" in it.sql && it.sql.startsWith("SELECT") }
                    assertTrue("$shape: no read recorded for n$i", reads.isNotEmpty())
                    reads.size
                }
                val bound = SaveSets.depth(n.toLong())
                assertEquals("$shape: log2($n) + 1", 9L, bound)
                assertTrue("$shape: a find read $most rows, over $bound", most <= bound)
            } finally {
                db.close()
            }
        }
    }

    // A library where every kind of edge the find makes comes up: a phone
    // file gone (its server copy becomes the song, which a like follows), a
    // song retitled into a new song with a gone song's relink key, an album
    // and an artist of the server's renamed (new album and artist ids for
    // its songs), and a gone song that a rename and a like reach.
    @Test
    fun eachKindOfEdgeOnItsOwnJoinsItsEnds() = runBlocking {
        val db = openTestCatalog()
        try {
            val server = "server:sets.test"
            val phone = library(DEVICE, 6)
            val copy = library(server, 12)
            db.put(phone)
            db.put(copy)
            rebuildSettled(db, server)
            insertRow(db, "liked_track", mapOf("trackId" to "$DEVICE:s1", "relinkKey" to "drake|song 1", "likedAt" to 1))
            insertRow(db, "liked_track", mapOf("trackId" to "$server:s9", "relinkKey" to "drake|song 9", "likedAt" to 2))
            db.put(phone.copy(tracks = phone.tracks.filter { it.id != "$DEVICE:s1" }))
            db.stage(
                copy.copy(
                    tracks = copy.tracks.map {
                        when (it.id) {
                            // Gone as a song: new under a new id, with the key it had.
                            "$server:s9" -> it.copy(id = "$server:s9b", nativeId = "s9b")
                            else -> if (it.albumId == "$server:album1") it.copy(albumId = "$server:album7", artistId = "$server:artist2") else it
                        }
                    },
                    albums = copy.albums.map { if (it.id == "$server:album1") it.copy(id = "$server:album7", nativeId = "album7", artistId = "$server:artist2") else it },
                    artists = copy.artists + artistRow(server).copy(id = "$server:artist2", name = "Drake Two", searchKey = "drake two", sortKey = "drake two"),
                ),
                generation = 2,
            )
            val joined = ArrayList<Triple<String, SaveNodeKey, SaveNodeKey>>()
            val hooks = object : SaveFindHooks() {
                override fun joined(why: String, a: SaveNodeKey, b: SaveNodeKey) {
                    joined += Triple(why, a, b)
                }
            }
            SaveFind(db, hooks = hooks).find(claimAndBuild(db, server).second)
            val roots = db.withTransaction {
                SaveSets(db.openHelper.writableDatabase, SaveNodeNumbers(Long.MAX_VALUE / 2)).use { s -> joined.map { (_, a, b) -> s.find(a) to s.find(b) } }
            }
            for (why in listOf(EDGE_REFERENCE, EDGE_RENAME, EDGE_KEY, EDGE_FOLLOW, EDGE_ALBUM, EDGE_ARTIST)) {
                val edges = joined.indices.filter { joined[it].first == why }
                assertTrue("no $why edge came up", edges.isNotEmpty())
                for (i in edges) {
                    val (a, b) = roots[i]
                    assertTrue("$why: ${joined[i].second} and ${joined[i].third} in no set", a != null && b != null)
                    assertEquals("$why: ${joined[i].second} and ${joined[i].third}", a, b)
                }
            }
            // Each set is one root: every node points at it once the find ends.
            val strays = db.longOf("SELECT COUNT(*) FROM save_node n WHERE NOT EXISTS (SELECT 1 FROM save_node r WHERE r.node = n.parent AND r.parent = r.node)")
            assertEquals(0L, strays)
        } finally {
            db.close()
        }
    }
}
