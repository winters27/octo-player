package app.winters.octo.backup

import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.PinKind
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.followAll
import app.winters.octo.catalog.marks
import app.winters.octo.catalog.read
import app.winters.octo.catalog.readLong
import app.winters.octo.catalog.stableRead
import app.winters.octo.data.Exports
import app.winters.octo.data.spaceWords
import app.winters.octo.playlists.MatchJob
import app.winters.octo.playlists.MatchJobs
import app.winters.octo.playlists.MatchKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.io.InputStream

// The song-sized half of backups: the export written a page at a time from
// the library, a file's songs read into a matching job, the plan counted
// from the job, and the restore's playlists, likes and ratings written a
// page at a time from it. Nothing here holds a list of songs.
internal class BackupSongs(
    private val db: CatalogDatabase,
    private val merge: CatalogMerge,
    private val sets: PlaylistSets,
    private val owners: WorkOwners,
    private val jobs: MatchJobs,
    private val exports: Exports,
    private val freeSpace: FreeSpace,
) {
    // Writes `header` with every phone playlist, like and rating to a file
    // of the app's own, a page of songs at a time, as one state of the
    // library (a swap meanwhile writes it again from the top; its last try
    // a Walk hold), each playlist as it stood when the export began, then
    // hands the file to `copy` outside every lock.
    suspend fun export(header: Backup, copy: suspend (File) -> Boolean): BackupSave = withContext(Dispatchers.IO) {
        val rows = db.readLong("SELECT COUNT(*) FROM liked_track") + db.readLong("SELECT COUNT(*) FROM track_rating") +
            db.readLong("SELECT COUNT(*) FROM playlist p CROSS JOIN playlist_item i ON i.playlistId = p.id AND i.itemSet = p.itemSet WHERE p.sourceId IS NULL AND p.itemSet >= 0")
        val need = rows * BACKUP_BYTES_PER_ROW * 11 / 10
        if (freeSpace.bytes() < need) return@withContext BackupSave.Failed(spaceWords(SpaceFor.SaveBackup, need))
        val owner = owners.export()
        val phone = db.read("SELECT id, name FROM playlist WHERE sourceId IS NULL AND itemSet >= 0 ORDER BY createdAt, id") { it.getString(0) to it.getString(1) }
        // Each playlist's songs as it stood when the export began.
        val pinned = phone.mapNotNull { (id, name) -> sets.pin(owner, id)?.let { it to name } }
        try {
            exports.use("backup-${owners.next()}.json") { file ->
                try {
                    stableRead(merge, HoldKind.Walk) {
                        file.parentFile?.mkdirs()
                        file.bufferedWriter(Charsets.UTF_8).use { out -> BackupWriter(db, sets, out).write(header, pinned) }
                    }
                } catch (e: IOException) {
                    return@use BackupSave.Failed(spaceWords(SpaceFor.SaveBackup, null))
                } catch (e: SQLiteFullException) {
                    return@use BackupSave.Failed(spaceWords(SpaceFor.SaveBackup, null))
                }
                if (copy(file)) BackupSave.Saved else BackupSave.Failed(BACKUP_NOT_WRITTEN)
            }
        } finally {
            withContext(NonCancellable) { pinned.forEach { (set, _) -> sets.releasePin(owner, set.playlistId) } }
        }
    }

    // Reads a file a value at a time: its small values kept, its songs
    // staged in a matching job that stays open until the restore or
    // `close`. A file that cannot be opened is not a backup.
    suspend fun read(open: () -> InputStream?): BackupOpened = withContext(Dispatchers.IO) {
        val job = jobs.open(MatchKind.Backup)
        try {
            val opened = jobs.step(SpaceFor.Restore) {
                val input = open() ?: return@step BackupOpened.NotABackup
                input.source().buffer().use { BackupReader(job, stagingRoom { freeSpace.bytes() }).read(it) }
            }
            if (opened !is BackupOpened.Opened) jobs.close(job)
            opened
        } catch (e: NotEnoughSpace) {
            jobs.close(job)
            BackupOpened.Problem(spaceWords(SpaceFor.Restore, e.neededBytes))
        } catch (e: CancellationException) {
            jobs.close(job)
            throw e
        } catch (e: Throwable) {
            jobs.close(job)
            BackupOpened.NotABackup
        }
    }

    suspend fun close(file: BackupFile) = jobs.close(file.job)

    // What restoring would do with the library as it is now, `existing`
    // naming the playlists already here: the file's songs found in one walk
    // of the library, and its albums and artists by today's rules over
    // pages of their names.
    suspend fun plan(file: BackupFile, existing: Collection<String>): BackupPlan = withContext(Dispatchers.IO) {
        val job = file.job
        val backup = file.header
        jobs.step(SpaceFor.Restore) {
            job.walk()
            job.finish()
        }
        val albumFinder = NameFinder(namedPage("SELECT id, searchKey, title, artist FROM album WHERE id > ? ORDER BY id LIMIT ?") { Named(it.getString(0), it.getString(1), it.getString(2), it.getString(3)) })
        val artistFinder = NameFinder(namedPage("SELECT id, searchKey, name FROM artist WHERE id > ? ORDER BY id LIMIT ?") { Named(it.getString(0), it.getString(1), it.getString(2)) })
        val taken = existing.mapTo(HashSet()) { it.trim().lowercase() }
        val (skipped, wanted) = file.playlists.partition { it.name.trim().lowercase() in taken }
        val playlistNames = taken + wanted.map { it.name.trim().lowercase() }
        val offsets = LinkedHashMap<String, Long>()
        var after = -1L
        while (true) {
            val (rows, next) = job.firstFound(Backups.OFFSETS, after)
            rows.forEach { (id, ms) -> offsets[id] = ms.toLong() }
            after = next ?: break
        }
        BackupPlan(
            playlists = wanted.map { PlannedPlaylist(it.name.trim(), it.list, job.counts(it.list).first, it.songs) },
            skippedPlaylists = skipped.map { it.name },
            likes = job.distinctFound(Backups.LIKES),
            likesTotal = file.likes,
            ratings = job.distinctFound(Backups.RATINGS, 1..5),
            ratingsTotal = file.ratings,
            favouriteAlbums = backup.favouriteAlbums.mapNotNull(albumFinder::find).distinct(),
            favouriteAlbumsTotal = backup.favouriteAlbums.size,
            favouriteArtists = backup.favouriteArtists.mapNotNull(artistFinder::find).distinct(),
            favouriteArtistsTotal = backup.favouriteArtists.size,
            pins = backup.pins.mapNotNull { pin ->
                when (PinKind.of(pin.kind)) {
                    PinKind.Album -> albumFinder.find(pin.item)?.let { PinPlan(PinKind.Album, it) }
                    PinKind.Artist -> artistFinder.find(pin.item)?.let { PinPlan(PinKind.Artist, it) }
                    PinKind.Playlist -> pin.item.name.trim().takeIf { it.lowercase() in playlistNames }?.let { PinPlan(PinKind.Playlist, it) }
                    null -> null
                }
            }.distinct(),
            pinsTotal = backup.pins.size,
            lyricsOffsets = offsets,
            lyricsOffsetsTotal = backup.lyrics?.offsets?.size ?: 0,
            keptPlaylists = backup.offline?.keptPlaylists.orEmpty().map { it.trim() }.filter { it.lowercase() in playlistNames }.distinctBy { it.lowercase() },
            keptPlaylistsTotal = backup.offline?.keptPlaylists?.size ?: 0,
        )
    }

    // Makes the planned playlists (each through `create`, a new set a page
    // at a time and one flip), likes and ratings from the job's rows a page
    // at a time. Each like and rating page follows its found songs to the
    // songs they are now and writes them in one transaction inside a Page
    // hold, so a swap since the plan, or during the restore, never writes an
    // old or gone id; the likes are then told to `liked`. Answers the made
    // playlists' ids by name in lower case. Throws NotEnoughSpace when the
    // disk is short.
    suspend fun restore(
        file: BackupFile,
        plan: BackupPlan,
        create: suspend (String, suspend (suspend (List<NewItem>) -> Unit) -> Unit) -> String,
        liked: (List<String>) -> Unit,
        rate: suspend (String, Int) -> Unit,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        jobs.step(SpaceFor.Restore) {
            val need = (plan.playlists.sumOf { it.found.toLong() } + plan.likes + plan.ratings) * BACKUP_BYTES_PER_ROW
            if (freeSpace.bytes() < need) throw NotEnoughSpace(need, SpaceFor.Restore)
            val made = LinkedHashMap<String, String>()
            plan.playlists.filter { it.found > 0 || it.total == 0 }.forEach { planned ->
                made[planned.name.trim().lowercase()] = create(planned.name) { emit -> songPages(file.job, planned.list, emit) }
            }
            restoreLikes(file.job, liked)
            restoreRatings(file.job, rate)
            made
        }
    }

    // A playlist's found songs in the file's order, a page at a time, each
    // the song it is now, repeats kept.
    private suspend fun songPages(job: MatchJob, list: Int, emit: suspend (List<NewItem>) -> Unit) {
        var after = -1L
        while (true) {
            val page = job.rows(list, after, BACKUP_PAGE)
            if (page.rows.isNotEmpty()) {
                val keys = relinkKeysOf(page.rows.map { it.trackId })
                emit(page.rows.map { NewItem(it.trackId, keys[it.trackId].orEmpty()) })
            }
            after = page.next ?: return
        }
    }

    // The liked songs, each once in the order first liked in the file, a
    // page to a transaction that follows each found song to the song it is
    // now and likes it unless it is liked already (in the library, or by
    // this restore under another found id).
    private suspend fun restoreLikes(job: MatchJob, liked: (List<String>) -> Unit) {
        var after = -1L
        val now = System.currentTimeMillis()
        while (true) {
            val (rows, next) = job.firstFound(Backups.LIKES, after, BACKUP_PAGE)
            if (rows.isNotEmpty()) {
                val fresh = merge.betweenRebuilds(HoldKind.Page) {
                    db.withTransaction {
                        val followed = db.followAll(rows.map { it.first })
                        val ids = followed.values.filterNotNull()
                        val keys = relinkKeysOf(ids)
                        val already = likedAmong(ids)
                        val fresh = LinkedHashSet<String>()
                        for ((found, _) in rows) {
                            val id = followed[found] ?: continue
                            if (id in already || !fresh.add(id)) continue
                            db.userDao().like(LikedTrackEntity(id, keys[id].orEmpty(), now))
                        }
                        fresh.toList()
                    }
                }
                if (fresh.isNotEmpty()) liked(fresh)
            }
            after = next ?: return
        }
    }

    // The ratings, 1 to 5 stars only, each song once in the order first
    // rated in the file with its last stars, a page to a transaction that
    // follows each found song first.
    private suspend fun restoreRatings(job: MatchJob, rate: suspend (String, Int) -> Unit) {
        var after = -1L
        while (true) {
            val (rows, next) = job.firstFound(Backups.RATINGS, after, BACKUP_PAGE, 1..5)
            if (rows.isNotEmpty()) {
                merge.betweenRebuilds(HoldKind.Page) {
                    db.withTransaction {
                        val followed = db.followAll(rows.map { it.first })
                        for ((found, stars) in rows) followed[found]?.let { rate(it, stars) }
                    }
                }
            }
            after = next ?: return
        }
    }

    private fun likedAmong(ids: List<String>): Set<String> {
        val liked = HashSet<String>()
        ids.distinct().chunked(ID_CHUNK).forEach { chunk ->
            liked += db.read("SELECT trackId FROM liked_track WHERE trackId IN (${marks(chunk.size)})", *chunk.toTypedArray()) { it.getString(0) }
        }
        return liked
    }

    private fun relinkKeysOf(ids: List<String>): Map<String, String> {
        val keys = HashMap<String, String>()
        ids.distinct().chunked(ID_CHUNK).forEach { chunk ->
            db.read("SELECT id, relinkKey FROM track WHERE id IN (${marks(chunk.size)})", *chunk.toTypedArray()) { it.getString(0) to it.getString(1) }
                .forEach { (id, key) -> keys[id] = key }
        }
        return keys
    }

    // A whole table's names a page at a time, album- or artist-sized.
    private fun <T> namedPage(sql: String, row: (android.database.Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        var after = ""
        while (true) {
            val page = db.read(sql, after, BACKUP_PAGE) { c -> c.getString(0) to row(c) }
            out += page.map { it.second }
            if (page.size < BACKUP_PAGE) return out
            after = page.last().first
        }
    }
}
