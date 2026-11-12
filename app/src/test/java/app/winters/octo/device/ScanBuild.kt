package app.winters.octo.device

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.catalog.SourceAlbumEntity
import app.winters.octo.catalog.SourceArtistEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.server.GIANT_ALBUM
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals

// What one build wrote to the phone's stage, in order.
internal class ScannedCatalog(val tracks: List<SourceTrackEntity>, val albums: List<SourceAlbumEntity>, val artists: List<SourceArtistEntity>)

// Builds `rows` the way a scan does, through scan_file and the saved tags on
// a library of its own: once with every album built whole and once with
// every album of two or more files put in order through the database. The
// two must agree; answers the first.
internal fun buildDeviceCatalog(rows: List<DeviceRow>): ScannedCatalog = runBlocking {
    val whole = buildThroughScan(rows, GIANT_ALBUM)
    val ordered = buildThroughScan(rows, 1)
    assertEquals("songs", whole.tracks, ordered.tracks)
    assertEquals("albums", whole.albums, ordered.albums)
    assertEquals("artists", whole.artists, ordered.artists)
    whole
}

private suspend fun buildThroughScan(rows: List<DeviceRow>, giant: Int): ScannedCatalog {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val db = openTestCatalog()
    try {
        val scan = PhoneScan(db, db.testMerge(null), DeviceScanner(app), TagReader(app)) { Long.MAX_VALUE }.also { it.tuning = ScanTuning(giant = giant) }
        db.phoneScanDao().insertFiles(rows.mapIndexed { i, row -> row.asFile().toScanFile(i.toLong()) })
        db.saveTags(rows.map { row -> savedTags(row.id, 0, row.sizeBytes ?: 0, row.asTags(), TAGS_VERSION) })
        scan.build(emptySet())
        val copies = db.copyDao()
        return ScannedCatalog(
            copies.stageTracks(DEVICE).map { it.row },
            copies.stageAlbums(DEVICE).map { it.row },
            copies.stageArtists(DEVICE).map { it.row },
        )
    } finally {
        db.close()
    }
}

// The file a row was read from, with nothing of its own tags.
private fun DeviceRow.asFile() = DeviceFile(
    id = id,
    uri = uri,
    fileName = fileName,
    folder = folder,
    modifiedAt = 0,
    sizeBytes = sizeBytes ?: 0,
    durationMs = durationMs,
    addedAtSeconds = addedAtSeconds,
    mimeType = mimeType,
    fallback = FileTags(),
)

// The tags that, read from that file, make this row.
private fun DeviceRow.asTags() = tags.copy(
    title = title,
    artist = artist,
    albumArtist = albumArtist,
    album = album,
    trackNo = track,
    discNo = disc,
    year = year,
    compilation = compilation,
    mbAlbumId = mbAlbumId,
    genres = genres,
)
