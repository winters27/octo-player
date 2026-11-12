package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The merge in the database makes the same library as the merge it
// replaced, on a fresh copy and on a second one with rows reordered and
// changed.
@RunWith(RobolectricTestRunner::class)
class MergeParityTest {
    private fun assertSame(expected: MergedCatalog, actual: MergedCatalog) {
        assertEquals(expected.tracks.sortedBy { it.id }, actual.tracks.sortedBy { it.id })
        assertEquals(expected.albums.sortedBy { it.id }, actual.albums.sortedBy { it.id })
        assertEquals(expected.artists.sortedBy { it.id }, actual.artists.sortedBy { it.id })
        assertEquals(expected.mergedIds, actual.mergedIds)
    }

    @Test
    fun aFreshCopyMergesAsBefore() {
        for (seed in 1L..4L) {
            val sources = MergeRowsGen.sources(seed, 2_000)
            assertSame(oldMergeCatalogs(sources), mergeThroughDatabase(sources))
        }
    }

    @Test
    fun aServerAloneMergesAsBefore() {
        val sources = MergeRowsGen.sources(7, 1_500, phone = false)
        assertSame(oldMergeCatalogs(sources), mergeThroughDatabase(sources))
    }

    @Test
    fun aSecondCopyWithReorderedAndChangedRowsMergesAsBefore() = runBlocking {
        val (phone, server) = MergeRowsGen.sources(11, 2_000)
        val db = openTestCatalog()
        try {
            db.put(phone)
            db.put(server)
            val merge = db.testMerge(MergeRowsGen.SERVER)
            merge.rebuild()
            val again = MergeRowsGen.secondCopy(12, server)
            db.put(again)
            merge.rebuild()
            assertSame(oldMergeCatalogs(listOf(phone, again)), db.library())
        } finally {
            db.close()
        }
    }

    @Test
    fun aSecondCopyAsAStageMergesAsBefore() = runBlocking {
        val (phone, server) = MergeRowsGen.sources(21, 1_500)
        val db = openTestCatalog()
        try {
            db.put(phone)
            db.put(server)
            val merge = db.testMerge(MergeRowsGen.SERVER)
            merge.rebuild()
            val again = MergeRowsGen.secondCopy(22, server)
            db.stage(again, generation = 5)
            merge.rebuild()
            merge.awaitStage(MergeRowsGen.SERVER, 5)
            assertSame(oldMergeCatalogs(listOf(phone, again)), db.library())
        } finally {
            db.close()
        }
    }

    // The old merge followed by what ran after it: ratings made on the
    // phone whose song id vanished move to the library song with their
    // relink key (lowest id on a tie), then songs no server rated show them.
    private fun withPhoneRatings(merged: MergedCatalog, ratings: List<TrackRatingEntity>): MergedCatalog {
        val ids = merged.tracks.mapTo(HashSet()) { it.id }
        val shown = ratings.associate { rating ->
            val id = if (rating.trackId in ids) {
                rating.trackId
            } else {
                merged.tracks.filter { it.relinkKey == rating.relinkKey && it.relinkKey.isNotEmpty() }.minOfOrNull { it.id } ?: rating.trackId
            }
            id to rating.rating
        }
        return merged.copy(tracks = merged.tracks.map { t -> if (t.rating == 0) shown[t.id]?.let { t.copy(rating = it) } ?: t else t })
    }

    @Test
    fun aRatedPhoneSongReKeyedAcrossMergesKeepsItsRatingAsBefore() = runBlocking {
        val server = "server:home.test"
        val phone = library(DEVICE, 5)
        val serverRows = library(server, 5)
        val db = openTestCatalog()
        try {
            db.put(phone)
            db.put(serverRows)
            val merge = db.testMerge(server)
            merge.rebuild()
            val rated = TrackRatingEntity("device:s3", "drake|song 3", 4, 1)
            db.userDao().rate(rated)

            // The phone file's title is read anew: another relink key, the
            // same song once SongIdentity reads it, so it keeps its id.
            val retitled = phone.copy(
                tracks = phone.tracks.map {
                    if (it.id == "device:s3") it.copy(title = "Song 3 - Remastered 2011", relinkKey = "drake|song 3 - remastered 2011") else it
                },
            )
            db.put(retitled)
            merge.rebuild()
            assertSame(withPhoneRatings(oldMergeCatalogs(listOf(retitled, serverRows)), listOf(rated)), db.library())

            // The phone file goes: the server copy is the library song now,
            // and the rating follows it by its relink key.
            val without = retitled.copy(tracks = retitled.tracks.filter { it.id != "device:s3" })
            db.put(without)
            merge.rebuild()
            assertSame(withPhoneRatings(oldMergeCatalogs(listOf(without, serverRows)), listOf(rated)), db.library())
            assertEquals(4, db.userDao().rating("$server:s3"))
        } finally {
            db.close()
        }
    }

    @Test
    fun threeSourcesMergeAsBefore() {
        val (phone, server) = MergeRowsGen.sources(31, 1_000)
        val usb = server.copy(
            sourceId = "usb",
            tracks = server.tracks.map { it.copy(id = "usb:" + it.nativeId, sourceId = "usb", albumId = it.albumId.replace(MergeRowsGen.SERVER, "usb"), artistId = it.artistId.replace(MergeRowsGen.SERVER, "usb")) },
            albums = server.albums.map { it.copy(id = it.id.replace(MergeRowsGen.SERVER, "usb"), sourceId = "usb", artistId = it.artistId.replace(MergeRowsGen.SERVER, "usb")) },
            artists = server.artists.map { it.copy(id = it.id.replace(MergeRowsGen.SERVER, "usb"), sourceId = "usb") },
        )
        val sources = listOf(phone, server, usb).sortedBy { if (it.sourceId == "device") "" else it.sourceId }
        assertSame(oldMergeCatalogs(sources), mergeThroughDatabase(sources))
    }
}
