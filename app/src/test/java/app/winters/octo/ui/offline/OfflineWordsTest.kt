package app.winters.octo.ui.offline

import app.winters.octo.offline.DownloadRow
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.Reasons
import app.winters.octo.ui.album.albumDownloadLabel
import app.winters.octo.ui.playlist.keptSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineWordsTest {
    private fun row(state: DownloadStatus, reasons: Set<String>, size: Long = 0, progress: Float = 0f) = DownloadRow(
        trackId = "t", sourceId = "server:a", serverId = "1", title = "Song", artist = "Artist", artwork = null,
        sizeBytes = size, state = state, progress = progress, reason = Reasons.join(reasons),
    )

    @Test
    fun sizesReadInTheirOwnUnits() {
        assertEquals("1 KB", sizeLabel(10))
        assertEquals("850 KB", sizeLabel(850 * 1024))
        assertEquals("12.5 MB", sizeLabel(12 * 1024 * 1024 + 512 * 1024))
        assertEquals("2.0 GB", sizeLabel(2L shl 30))
    }

    @Test
    fun eachDownloadSaysWhereItIsOrWhatKeepsIt() {
        val names = mapOf("gym" to "Gym")
        assertEquals("Waiting", downloadDetail(row(DownloadStatus.Queued, setOf(Reasons.MANUAL)), names))
        assertEquals("Downloading, 42%", downloadDetail(row(DownloadStatus.Downloading, setOf(Reasons.MANUAL), progress = 0.42f), names))
        assertEquals("3.0 MB", downloadDetail(row(DownloadStatus.Done, setOf(Reasons.MANUAL), size = 3L shl 20), names))
        assertEquals("3.0 MB · Kept with Gym", downloadDetail(row(DownloadStatus.Done, setOf(Reasons.playlist("gym")), size = 3L shl 20), names))
        assertEquals("Kept with Liked songs", keptBy(setOf(Reasons.LIKED, Reasons.playlist("gym")), names))
        assertEquals("Kept with a playlist", keptBy(setOf(Reasons.playlist("gone")), names))
        assertNull(keptBy(setOf(Reasons.MANUAL, Reasons.LIKED), names))
    }

    @Test
    fun theAlbumButtonSaysWhatIsLeft() {
        assertEquals("Download album", albumDownloadLabel(songs = 10, held = 4, done = 4))
        assertEquals("Downloading", albumDownloadLabel(songs = 10, held = 10, done = 7))
        assertEquals("Downloaded", albumDownloadLabel(songs = 10, held = 10, done = 10))
    }

    @Test
    fun aKeptListSaysHowFarItHasGot() {
        assertEquals("3 of 12 downloaded", keptSummary(serverOnly = 12, done = 3))
        assertEquals("All 12 downloaded", keptSummary(serverOnly = 12, done = 12))
        assertEquals("Every song is already on this phone", keptSummary(serverOnly = 0, done = 0))
    }
}
