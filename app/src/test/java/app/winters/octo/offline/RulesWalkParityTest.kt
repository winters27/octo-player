package app.winters.octo.offline

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A labeled pin: the paged rules walk leaves the downloads exactly as
// today's walk over whole sets did (the frozen copies in today/), on a
// library with downloads on two servers and every mix of reasons, liked
// songs in and out of the library, two kept playlists holding songs twice
// (one kept playlist gone), songs that cannot be downloaded (on the phone,
// on no server), a second and better server copy, and ids whose order in
// the database differs from Kotlin's. Pages of 50, so every stream crosses
// many page edges.
@RunWith(RobolectricTestRunner::class)
class RulesWalkParityTest {
    private val walked = openTestCatalog()
    private val today = openTestCatalog()

    @After
    fun close() {
        walked.close()
        today.close()
    }

    private val fffd = "$IN_USE:�"
    private val grin = "$IN_USE:😀"

    private fun id(i: Int) = "$IN_USE:%04d".format(i)

    private suspend fun CatalogDatabase.fill() {
        val ids = (0 until 1_200).map(::id) + listOf(fffd, grin, "$IN_USE:z")
        val songs = ids.mapIndexed { i, id -> librarySong(id, onPhone = i % 5 == 0) }
        val copies = ids.withIndex().flatMap { (i, id) ->
            when {
                i % 13 == 0 -> emptyList()
                i % 7 == 0 -> listOf(serverCopy(id, "n$i"), serverCopy(id, "b$i", bitrate = 320))
                i % 11 == 0 -> listOf(serverCopy(id, "n$i"), serverCopy(id, "o$i", ELSEWHERE, bitrate = 256))
                else -> listOf(serverCopy(id, "n$i"))
            }
        }
        putLibrary(songs, copies)
        val gone = (0 until 200).map { "$IN_USE:gone%03d".format(it) }
        likeAll(ids.filterIndexed { i, _ -> i % 3 == 0 } + gone.filterIndexed { i, _ -> i % 2 == 0 } + listOf(fffd, grin))
        val mixes = listOf(
            setOf(Reasons.MANUAL),
            setOf(Reasons.LIKED),
            setOf(Reasons.playlist("p1")),
            setOf(Reasons.MANUAL, Reasons.LIKED),
            setOf(Reasons.playlist("p3")),
            setOf(Reasons.LIKED, Reasons.playlist("p2")),
            setOf(Reasons.MANUAL, Reasons.playlist("p1"), Reasons.playlist("p2")),
        )
        val states = listOf(DownloadStatus.Done, DownloadStatus.Queued, DownloadStatus.Failed)
        val held = ids.withIndex().filter { (i, _) -> i % 8 == 0 }.map { (i, id) ->
            keptRow(id, "n$i", mixes[(i / 8) % mixes.size], state = states[(i / 8) % states.size], path = if (i % 3 == 0) "/m/$i.flac" else "")
        }
        val elsewhere = ids.withIndex().filter { (i, _) -> i % 10 == 0 }.map { (i, id) -> keptRow(id, "o$i", setOf(Reasons.LIKED), ELSEWHERE) }
        val strangers = gone.mapIndexed { i, id -> keptRow(id, "g$i", mixes[i % mixes.size]) }
        putKept(held + elsewhere + strangers + keptRow(grin, "grin", setOf(Reasons.LIKED), state = DownloadStatus.Done, path = "/m/grin.flac"))
        val p1 = ids.filterIndexed { i, _ -> i % 4 == 0 }.flatMapIndexed { n, id -> if (n % 20 == 0) listOf(id, id) else listOf(id) }
        putPlaylist("p1", p1 + "find:x" + "$IN_USE:gone001")
        putPlaylist("p2", ids.filterIndexed { i, _ -> i % 6 == 0 }.reversed())
        putPlaylist("p3", ids.filterIndexed { i, _ -> i % 9 == 0 })
    }

    private fun parity(keepLiked: Boolean, playlists: Set<String>) = timed(60) {
        walked.fill()
        today.fill()
        val before = walked.keptTable()
        RulesRig(walked, page = 50).rules.walk(keepLiked, playlists, refused = null)
        today.todayRules(keepLiked, playlists)
        val expected = today.keptTable()
        assertTrue("today's walk changed nothing", expected != before)
        assertEquals(expected, walked.keptTable())
    }

    @Test
    fun likedAndTwoPlaylistsKept() = parity(keepLiked = true, playlists = setOf("p1", "p2", "p4"))

    @Test
    fun onlyAPlaylistKept() = parity(keepLiked = false, playlists = setOf("p2"))

    @Test
    fun nothingKept() = parity(keepLiked = false, playlists = emptySet())

    // The streams come in the database's order of ids (UTF-8 bytes), in
    // which U+FFFD comes before U+1F600, the reverse of Kotlin's order. The
    // walk merges in the database's order: the song held for being liked
    // stays as it was, its finished file and all, and the other is added
    // once. A merge in Kotlin's order would see the held song before the
    // liked one, let it go, then download it again.
    @Test
    fun idsMergeInTheDatabasesOrder() = timed {
        walked.putServerSongs(listOf(fffd, grin))
        walked.likeAll(listOf(fffd, grin))
        walked.putKept(listOf(keptRow(grin, "grin", setOf(Reasons.LIKED), state = DownloadStatus.Done, path = "/m/grin.flac")))
        val rig = RulesRig(walked, page = 1)
        rig.rules.walk(keepLiked = true, playlists = emptySet(), refused = null)
        assertEquals(emptyList<DownloadEntity>(), rig.dropped)
        val grinRow = walked.downloadDao().get(IN_USE, "grin")!!
        assertEquals(DownloadStatus.Done, grinRow.state)
        assertEquals("/m/grin.flac", grinRow.path)
        assertEquals(mapOf(fffd to setOf(Reasons.LIKED), grin to setOf(Reasons.LIKED)), walked.heldNow())
        assertEquals(2, walked.downloadDao().all().size)
    }
}
