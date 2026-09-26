package app.winters.octo.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUndoTest {
    @Test
    fun aFinishedDownloadWhoseFileWasKeptCanComeBack() {
        assertTrue(canRestore(listOf(DownloadStatus.Done to true)))
    }

    @Test
    fun aFinishedDownloadWhoseFileIsGoneCannot() {
        assertFalse(canRestore(listOf(DownloadStatus.Done to false)))
        assertFalse(canRestore(listOf(DownloadStatus.Done to true, DownloadStatus.Done to false)))
    }

    @Test
    fun unfinishedDownloadsOnlyNeedToWaitAgain() {
        assertTrue(canRestore(listOf(DownloadStatus.Queued to false, DownloadStatus.Downloading to false, DownloadStatus.Failed to false)))
    }

    @Test
    fun nothingRemovedHasNothingToBringBack() {
        assertFalse(canRestore(emptyList()))
    }
}
