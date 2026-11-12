package app.winters.octo.backup

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.TrackRatingEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.data.Drains
import app.winters.octo.data.Exports
import app.winters.octo.playlists.MatchJobs
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

// A song for the backup tests.
internal fun backupTrack(id: String, title: String, artist: String = "Artist", album: String = "Album", durationMs: Long = 200_000, relinkKey: String = "rk-$id") = TrackEntity(
    id = id, sourceId = "device", nativeId = id, title = title, searchKey = searchKey(title), sortKey = sortKey(title),
    artist = artist, artistId = "ar", album = album, albumId = "al", trackNo = null, discNo = null, year = null,
    durationMs = durationMs, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, relinkKey = relinkKey,
)

// The backups' song half over a test library, with pages of `page` rows
// for the matching job.
internal class BackupRig(
    val db: CatalogDatabase,
    page: Int = 50,
    space: () -> Long = { Long.MAX_VALUE },
    val merge: CatalogMerge = db.testMerge(null),
) {
    val sets = PlaylistSets(db)
    val owners = WorkOwners()
    val jobs = MatchJobs(db, owners, FreeSpace { space() }, page)
    val exportDir: File = Files.createTempDirectory("export").toFile()
    val songs = BackupSongs(db, merge, sets, owners, jobs, Exports(Drains(exportDir)), FreeSpace { space() })

    // Whether the last export's copy to the picked file ran holding the
    // merge lock.
    var copiedInLock: Boolean? = null

    // Exports `header` and answers the file's bytes as written, and the
    // answer.
    fun export(header: Backup): Pair<ByteArray?, BackupSave> {
        var bytes: ByteArray? = null
        val answer = runBlocking {
            songs.export(header) { file ->
                copiedInLock = merge.holdsMergeLock()
                bytes = file.readBytes()
                true
            }
        }
        return bytes to answer
    }

    fun library(tracks: List<TrackEntity>) = runBlocking { tracks.chunked(500).forEach { db.dao().insertTracks(it) } }

    fun like(id: String, at: Long, relinkKey: String = "rk-$id") = runBlocking { db.userDao().like(LikedTrackEntity(id, relinkKey, at)) }

    fun rate(id: String, stars: Int, at: Long, relinkKey: String = "rk-$id") = runBlocking { db.userDao().rate(TrackRatingEntity(id, relinkKey, stars, at)) }

    // A playlist only on the phone with these songs, in order.
    fun playlist(id: String, name: String, createdAt: Long, songs: List<String>) = runBlocking {
        sets.create(PlaylistEntity(id, name, createdAt, createdAt)) { emit ->
            songs.chunked(200).forEach { page -> emit(page.map { NewItem(it, "rk-$it") }) }
        }
    }

    fun find(id: String, title: String) = runBlocking {
        db.onlineDao().keep(
            listOf(
                OnlineSongEntity(
                    id = id, sourceId = "server:x", nativeId = id.removePrefix("find:"), title = title, artist = "Online", album = "Somewhere",
                    albumId = null, artistId = null, durationMs = 180_000, coverId = null, mimeType = null, bitrate = null, seenAt = 0,
                ),
            ),
        )
    }

    fun close() = db.close()
}
