package app.winters.octo.ui.library.health

import app.winters.octo.catalog.FreeSpace
import app.winters.octo.listening.longOf
import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

// Library health short of disk keeps the report it showed and says so:
// before a build, with how many MB to free ("Not enough space to update
// Library health."); when the disk fills mid-build, with "some space", the
// unfinished report's rows gone. A copy takes what HEALTH_BYTES_PER_COPY
// says, measured. Guard: the base wrote nothing.
@RunWith(RobolectricTestRunner::class)
class HealthDiskFullTest {
    @Test
    fun aShortDiskKeepsTheReportAndSaysSo() = timed(90) {
        val db = openTestCatalog()
        try {
            db.healthLibrary(albums = 30)
            var space = 1_000L
            val rig = HealthRig(db, page = 10, space = FreeSpace { space }).keys()
            val short = rig.build.buildNow() as HealthShown.Problem
            assertEquals(null, short.before)
            assertTrue(short.words, short.words.startsWith("Not enough space to update Library health. Free up "))
            assertTrue(short.words, short.words.endsWith(" MB, then try again."))
            space = Long.MAX_VALUE
            val shown = rig.built()
            var members = 0
            db.counted.fullDisk = { sql -> sql.startsWith("INSERT INTO health_member") && ++members > 3 }
            val full = rig.build.buildNow() as HealthShown.Problem
            db.counted.fullDisk = null
            assertEquals("Not enough space to update Library health. Free up some space, then try again.", full.words)
            assertEquals(shown, full.before)
            assertEquals(shown, rig.build.shown())
            listOf("health_song", "health_member", "health_group", "health_node", "health_tag", "health_part").forEach { t ->
                assertEquals(t, 0L, db.longOf("SELECT COUNT(*) FROM $t WHERE gen > ?", shown.gen))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun aCopyTakesWhatItsConstantSays() = timed(120) {
        val dir = createTempDirectory("catalog").toFile()
        val db = openTestCatalog(dir)
        try {
            db.healthLibrary(albums = 600)
            val rig = HealthRig(db).keys()
            db.longOf("PRAGMA wal_checkpoint(TRUNCATE)")
            val before = used(dir)
            rig.built()
            val copies = db.longOf("SELECT COUNT(*) FROM source_track")
            val perCopy = (used(dir) - before) / copies
            println("MEASURED health copies=$copies perCopy=$perCopy")
            println("RESULT health_bytes_per_copy=$perCopy")
            assertTrue("measured $perCopy, constant $HEALTH_BYTES_PER_COPY", perCopy <= HEALTH_BYTES_PER_COPY)
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    private fun used(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
