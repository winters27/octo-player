package app.winters.octo.server

import app.winters.octo.catalog.AccountSyncEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CopyNeedTest {
    private val now = 1_000_000_000_000L
    private val done = AccountSyncEntity("acc", "server:host", now - 1_000, 10, 2, ROWS_VERSION, null, afterSyncDone = true)

    @Test
    fun aRecentFinishedCopyNeedsNothing() = assertEquals(CopyNeed.Fresh, copyNeed(done, "acc", "acc", "server:host", now))

    @Test
    fun noRecordNeedsACopy() = assertEquals(CopyNeed.Copy, copyNeed(null, "acc", "acc", "server:host", now))

    @Test
    fun anotherAccountsRowsNeedACopy() = assertEquals(CopyNeed.Copy, copyNeed(done, "other", "acc", "server:host", now))

    @Test
    fun anOlderVersionsRowsNeedACopy() = assertEquals(CopyNeed.Copy, copyNeed(done.copy(rowsVersion = ROWS_VERSION - 1), "acc", "acc", "server:host", now))

    @Test
    fun anotherAddressNeedsACopy() = assertEquals(CopyNeed.Copy, copyNeed(done, "acc", "acc", "server:other", now))

    @Test
    fun anOldCopyNeedsACopy() = assertEquals(CopyNeed.Copy, copyNeed(done.copy(syncedAt = now - STALE_MS - 1), "acc", "acc", "server:host", now))

    @Test
    fun aSwappedCopyWhoseStepsWereCutOffNeedsOnlyThem() =
        assertEquals(CopyNeed.AfterSync, copyNeed(done.copy(afterSyncDone = false, syncedAt = 0), "acc", "acc", "server:host", now))

    @Test
    fun aFirstCopyThatFailedNeedsACopy() =
        // The record a first copy makes before its merge: time 0, and the rows still nobody's.
        assertEquals(CopyNeed.Copy, copyNeed(done.copy(syncedAt = 0), null, "acc", "server:host", now))
}
