package app.winters.octo.playback

import app.winters.octo.catalog.QueueListEntity
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlValue
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The queue's numbers and keys: each list's rows are one range of numbers
// that no other list shares; a play's phase 1 sorts before its phase 0
// whatever their lengths; a run fits between two keys or is spaced out; and
// a build's place in its list reads back exactly after a restart.
@RunWith(RobolectricTestRunner::class)
class EntryNumbersTest {
    @Test
    fun aListsNumbersAreItsOwn() {
        val a = entryOf(7, 0)
        val b = entryOf(7, 0xFFFFFFFFL)
        val c = entryOf(8, 0)
        assertEquals(7L, listOfEntry(a))
        assertEquals(7L, listOfEntry(b))
        assertTrue(b < c)
        assertEquals(0xFFFFFFFFL, numberOfEntry(b))
        assertEquals(entryOf(7, 12), entryOfKey(entryKey(entryOf(7, 12))))
        assertNull(entryOfKey("q:3"))
    }

    @Test
    fun phaseOneSortsBeforeTheStartAndPhaseZeroAfter() {
        val lastBefore = playKey(PHASE1_BASE + PHASE1_BASE - 1)
        val start = playKey(0)
        val firstAfter = playKey(1)
        val lastAfter = playKey(PHASE1_BASE - 1)
        assertTrue(playKey(PHASE1_BASE) < lastBefore)
        assertTrue(lastBefore < start)
        assertTrue(start < firstAfter)
        assertTrue(firstAfter < lastAfter)
        assertTrue(lastAfter > 0)
    }

    @Test
    fun runsFitBetweenKeysOrAreSpacedOutFirst() {
        val keys = spacedKeys(0, 1_000_000, 10)!!
        assertTrue(keys.toList().zipWithNext().all { (x, y) -> x < y })
        assertTrue(keys.first() > 0 && keys.last() < 1_000_000)
        assertNull(spacedKeys(0, MIN_KEY_GAP * 3, 10))
        val after = spacedKeys(Long.MAX_VALUE - 10, null, 3)
        assertNull(after)
        val end = spacedKeys(5, null, 3)!!
        assertEquals(listOf(5 + KEY_STEP, 5 + 2 * KEY_STEP, 5 + 3 * KEY_STEP), end.toList())
        val (base, step) = addSpacing(KEY_MID, KEY_MID + KEY_STEP)!!
        assertTrue(base + (PHASE1_BASE + 1) * step <= KEY_MID + KEY_STEP)
    }

    // Every kind of value a row's key holds reads back the same through a
    // list's stored place.
    @Test
    fun aBuildsPlaceReadsBackExactly() = runBlocking {
        val db = openTestCatalog()
        try {
            val key = RowKey(
                2,
                listOf(SqlValue.Null, SqlValue.Int64(-5), SqlValue.Real(1.5), SqlValue.Text("Ångström 🎵"), SqlValue.Blob(byteArrayOf(0, -1, 7).toByteString())),
                "s1",
            )
            val list = QueueListEntity(1, 1, null, false, null, 9, QueueBuilds.BUILDING, 0, key.token, key.token, 0, 0, null, null, null, 0, null, null, null, null, null)
            db.queueDao().putList(list)
            val back = db.queueDao().list(1)
            assertNotNull(back)
            assertEquals(key, RowKey.of(back!!.cursor!!))
            assertEquals(key, RowKey.of(back.anchor!!))
        } finally {
            db.close()
        }
    }
}
