package app.winters.octo.ui.library.health

import app.winters.octo.catalog.read
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.HealthTag
import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A labeled pin. The report built a page at a time in the database finds
// what today's checks over the whole library found: the sets of copies
// (their copies best first, why they are one, why the best is best, the
// library songs they hold and each song's sound line), the split albums
// (their title, artist, parts, songs in album order, reasons and
// differences), the songs with no length and those missing each tag, in
// the lists' order, and every count. Copies joined by tag across buckets,
// lengths unknown, album versions, blank and spaced artists, release ids,
// shared credits, an emoji and a private-use letter. Built in pages of 9
// with a find cache of 16 sets; no statement reads more than 900 rows.
@RunWith(RobolectricTestRunner::class)
class HealthParityTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun theReportIsTodays() = timed(180) {
        for (seed in 1..3) {
            db.openHelper.writableDatabase.execSQL("DELETE FROM source_track")
            db.openHelper.writableDatabase.execSQL("DELETE FROM track")
            db.openHelper.writableDatabase.execSQL("DELETE FROM album")
            db.healthLibrary(albums = 50, seed = seed)
            val rig = HealthRig(db, page = 9, cache = 16).keys()
            db.counted.reset()
            val shown = rig.built()
            assertTrue("${db.counted.maxRead}", db.counted.maxRead!!.rowsRead <= 900)
            val today = rig.today()
            val report = today.report
            assertEquals("seed $seed", report.checked, shown.checked)
            HealthCheck.entries.forEach { assertEquals("seed $seed: $it", report.count(it), shown.count(it)) }
            assertEquals("seed $seed", report.noLength.map { it.id }, rig.songs(HealthCheck.NoLength))
            for (check in listOf(HealthCheck.NoGenre, HealthCheck.NoYear, HealthCheck.NoTrackNumber, HealthCheck.NoCover)) {
                assertEquals("seed $seed: $check", report.missing[check.tag].orEmpty().map { it.id }, rig.songs(check))
            }
            assertEquals("seed $seed", emptyList<String>(), report.missing[HealthTag.AlbumArtist].orEmpty().map { it.id })
            assertEquals(
                "seed $seed",
                today.copyGroups.map { g -> Triple(g.copies.map { it.id }, g.basis.name, g.bestReason.name) },
                rig.groups(shown.gen),
            )
            assertEquals("seed $seed", report.duplicates.flatMap { g -> g.copies.map { it.id } }.distinct(), rig.songs(HealthCheck.Duplicates))
            val quality = db.read("SELECT trackId, text FROM health_quality WHERE gen = ?", shown.gen) { it.getString(0) to it.getString(1) }.toMap()
            assertEquals("seed $seed", today.quality, quality)
            val splits = db.read("SELECT setId, title, artist, reasons, differences FROM health_split WHERE owner = '${HealthBuild.OWNER}' AND gen = ? ORDER BY k1, k2, leadAlbum", shown.gen) {
                listOf(it.getLong(0).toString(), it.getString(1), it.getString(2), it.getString(3), it.getString(4))
            }.map { (setId, title, artist, reasons, differences) ->
                val songs = db.read(
                    "SELECT s.part, s.trackId, t.albumId FROM health_split_song s CROSS JOIN track t ON t.id = s.trackId WHERE s.owner = '${HealthBuild.OWNER}' AND s.gen = ? AND s.setId = ? " +
                        "ORDER BY s.part, s.disc, s.trackKey, s.titleKey, s.trackId",
                    shown.gen, setId.toLong(),
                ) { Triple(it.getInt(0), it.getString(1), it.getString(2)) }
                val parts = songs.groupBy { it.first }.values.map { part -> part.first().third to part.map { it.second } }
                listOf(title, artist, decodeReasons(reasons).toString(), decodeDifferences(differences).toString(), parts.toString())
            }
            val todaySplits = report.splitAlbums.map { album ->
                listOf(album.title, album.artist, album.reasons.toString(), album.differences.toString(), album.parts.map { p -> p.albumId to p.songs.map { it.id } }.toString())
            }
            assertEquals("seed $seed", todaySplits, splits)
            assertEquals("seed $seed", report.splitAlbums.flatMap { a -> a.parts.flatMap { p -> p.songs.map { it.id } } }, rig.songs(HealthCheck.SplitAlbums))
            assertTrue("seed $seed found too little", report.duplicates.isNotEmpty() && report.splitAlbums.isNotEmpty())
        }
    }
}
