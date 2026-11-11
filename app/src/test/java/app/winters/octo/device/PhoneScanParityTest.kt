package app.winters.octo.device

import app.winters.octo.testing.FakeMediaStore
import app.winters.octo.testing.TestFolderRules
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A labeled pin: the paged scan makes, row for row and column by column,
// what today's scan makes (device/today/TodayDeviceCatalog.kt), with every
// album built whole (the threshold as shipped) and with every album of two
// or more files put in order through the database (threshold 1), on a
// first scan and on a second one after files changed, moved, went and came.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneScanParityTest {
    private val db = openTestCatalog()
    private val rules = TestFolderRules()

    @After
    fun close() = db.close()

    @Test
    fun albumsBuiltWholeMatchToday() = matchesToday(giant = 2_000)

    @Test
    fun albumsPutInOrderThroughTheDatabaseMatchToday() = matchesToday(giant = 1)

    private fun matchesToday(giant: Int) = runBlocking {
        val first = skewedPhone()
        val phone = db.phoneLibrary(first.listed(), tuning = ScanTuning(giant = giant), now = PHONE_NOW, rules = rules)
        rules.switchedOff.value = setOf("WhatsApp")
        // The files read before keep their tags; the rest are read now and
        // cannot be.
        val saved = first.saved()
        db.saveTags(saved)
        val today = todayOf(saved, setOf("WhatsApp"))
        phone.rescan()
        assertPhoneIsToday(db, phone, today)

        // The second scan: some files changed (read again, and unreadable
        // now), some gone, some new, and the order the phone lists them in
        // turned about.
        val before = db.dao().fileTags()
        val second = first.changed(seed = 11)
        FakeMediaStore.current!!.files = second.listed()
        val again = todayOf(before, setOf("WhatsApp"))
        phone.rescan()
        assertPhoneIsToday(db, phone, again)
        assertEquals(again.forgotten, before.map { it.mediaId }.toSet() - db.dao().fileTags().map { it.mediaId }.toSet())
    }
}
