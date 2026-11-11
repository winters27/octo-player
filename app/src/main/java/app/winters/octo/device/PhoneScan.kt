package app.winters.octo.device

import android.database.sqlite.SQLiteException
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.winters.octo.BuildConfig
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ApplyPending
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FileTagsEntity
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.KeptPlaces
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.STAGE_NONE
import app.winters.octo.catalog.ScanClusterEntity
import app.winters.octo.catalog.ScanFileEntity
import app.winters.octo.catalog.ScanGenreNameEntity
import app.winters.octo.catalog.ScanGroupEntity
import app.winters.octo.catalog.ScanRowEntity
import app.winters.octo.catalog.ScanTallyEntity
import app.winters.octo.catalog.DensePlaces
import app.winters.octo.catalog.Places
import app.winters.octo.catalog.STAGE_ALBUM_TO_COME
import app.winters.octo.catalog.STAGE_ARTIST_TO_COME
import app.winters.octo.catalog.SourceAlbumEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.StageAborted
import app.winters.octo.catalog.StageAlbumEntity
import app.winters.octo.catalog.StageArtistEntity
import app.winters.octo.catalog.StageTrackEntity
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.columnsOf
import app.winters.octo.catalog.joinLines
import app.winters.octo.catalog.placeAlbums
import app.winters.octo.catalog.placeArtists
import app.winters.octo.catalog.placeSongs
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.catalog.splitLines
import app.winters.octo.catalog.toSource
import app.winters.octo.catalog.withKeys
import app.winters.octo.server.GIANT_ALBUM
import app.winters.octo.server.STAGE_BYTES_PER_SONG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest

// Raised whenever reading tags learns something new, so every file is
// read again once. Rows saved before genres were read are version 0.
// Version 2 reads original years, credits, MusicBrainz ids, sort names,
// loudness and the other details, and more spellings of every tag.
internal const val TAGS_VERSION = 2

// Raised whenever the way files become songs, albums and artists changes,
// so the next launch builds the phone's library again.
internal const val PHONE_BUILD_VERSION = 1

// How many files a scan lists, reads or writes at a time.
internal const val SCAN_PAGE = 500

// How many albums a scan puts in order at a time.
private const val GROUP_PAGE = 100

// How many songs of small albums are written together.
private const val FLUSH_TRACKS = GIANT_ALBUM

// How many files are read for tags at once.
private const val READERS = 4

// How many listed pages between looks at the free space.
private const val PAGES_PER_SPACE_CHECK = 10

// Values bound in one lookup at most.
private const val IN_LIMIT = 900

// A phone song (`s`) is still to come in the scan being built while its
// file is still among the scan's rows to build: a built file's rows go in
// the transaction that writes its song into the stage.
internal const val SCAN_TO_COME = "EXISTS (SELECT 1 FROM scan_row r WHERE r.mediaId = CAST(s.nativeId AS INTEGER))"

// The most files an album may have for its file-name numbers to stand in
// for track numbers (namesUsable): more than this many cannot all differ
// within three digits.
private const val NAMED_FILES_MAX = 1_000

// How much the database grows for each listed file (its scan_file and
// scan_row rows with their indexes and its share of the album, artist and
// tally rows) and for each file whose tags are saved for the first time,
// measured on generated phones (PhoneDiskConstantsTest). The stage's own
// rows are STAGE_BYTES_PER_SONG. Free space is checked against twice what
// is still to come.
internal const val SCAN_BYTES_PER_FILE = 550L
internal const val TAG_BYTES_PER_FILE = 250L

// What a test may set differently: the size above which an album is put in
// order through the database, the tags version and the app's version code.
@VisibleForTesting
internal data class ScanTuning(
    val giant: Int = GIANT_ALBUM,
    val tagsVersion: Int = TAGS_VERSION,
    val versionCode: Int = BuildConfig.VERSION_CODE,
)

// Points of a scan, for tests to stop at: after it starts its copy, after
// the files are listed, after their tags are read, once they are filed
// into albums, once the copy is built, and once it is ready for the merge.
enum class ScanPoint { Started, Listed, Tagged, Grouped, Built, Ready }

// How one scan ended, for the log.
internal class ScanResult(val quiet: Boolean, val tracks: Int, val albums: Int, val artists: Int, val reread: Int)

// Builds the phone's songs, albums and artists from its media library
// through the database instead of memory, as a copy the merge swaps in
// with the library, like a server's.
//
// A scan first lists the files without writing anything, into a digest of
// all a build reads besides the saved tags. When it equals the digest of
// the last build that reached the library, nothing changed and nothing is
// written. Otherwise the files are listed again into scan_file, their tags
// brought up to date, the albums grouped, split and built a page at a time
// into the phone's stage, and the stage handed to the merge unless it
// equals the library's phone rows already. Only one page of files, or one
// album of at most `giant` files, is held at once.
//
// The digest is cleared before any saved tags are written and stored only
// once the stage is in the library or found unchanged, so a stored digest
// always means the saved tags are those of the last build.
class PhoneScan(
    private val db: CatalogDatabase,
    private val merge: CatalogMerge,
    internal val scanner: DeviceScanner,
    private val reader: TagReader,
    private val freeSpace: FreeSpace,
) {
    private val dao = db.phoneScanDao()
    private val catalog = db.dao()
    private val copies = db.copyDao()

    @VisibleForTesting
    internal var tuning = ScanTuning()

    // Runs at each point of a scan; tests pause here.
    @VisibleForTesting
    internal var reached: suspend (ScanPoint) -> Unit = {}

    // Whether a scan puts the phone's songs, albums and artists in its
    // order at the last scan's places, kept where that order still holds;
    // off, it numbers each table anew, for tests.
    @VisibleForTesting
    internal var keepPlaces = true

    private fun places(table: String, toCome: String): Places =
        if (keepPlaces) KeptPlaces(db, table, DEVICE, toCome) else DensePlaces()

    // Scans the phone's files with the folders in `excluded` left out,
    // telling `folders` every folder found. Answers how it ended; throws
    // NotEnoughSpace when the phone runs short of room and StageAborted
    // when the merge did not swap the copy in, or when a save part applied
    // holds the sources' writes or stopped with this copy in it
    // (ApplyPending: its resume, not this scan, saves it).
    internal suspend fun run(excluded: Set<String>, folders: (List<MusicFolder>) -> Unit): ScanResult = try {
        scanOnce(excluded, folders)
    } catch (e: ApplyPending) {
        throw StageAborted(e.reason)
    }

    private suspend fun scanOnce(excluded: Set<String>, folders: (List<MusicFolder>) -> Unit): ScanResult = withContext(WriteLabels.of(LABEL)) {
        val stored = dao.state()
        var listed: Long? = null
        if (stored?.digest != null) {
            val look = look(excluded)
            if (look.digest == stored.digest) {
                folders(look.counter.folders(excluded))
                merge.rebuild()
                return@withContext ScanResult(quiet = true, stored.tracks, stored.albums, stored.artists, reread = 0)
            }
            listed = look.files
        }
        val generation = start()
        try {
            reached(ScanPoint.Started)
            val look = list(excluded, listed ?: (dao.liveTrackCount() * 11 / 10))
            reached(ScanPoint.Listed)
            val reread = refreshTags()
            folders(look.counter.folders(excluded))
            reached(ScanPoint.Tagged)
            build(excluded)
            reached(ScanPoint.Built)
            val counts = dao.stageCounts()
            if (unchanged()) {
                if (dao.endUnclaimed(generation) > 0) drainStage()
                dao.saveDigest(look.digest, counts.tracks, counts.albums, counts.artists)
                merge.rebuild()
            } else {
                check(copies.markReady(DEVICE, generation, counts.tracks, counts.albums, counts.artists) == 1) { "The phone's stage is gone" }
                reached(ScanPoint.Ready)
                merge.rebuild()
                merge.awaitStage(DEVICE, generation)
                dao.saveDigest(look.digest, counts.tracks, counts.albums, counts.artists)
            }
            ScanResult(quiet = false, counts.tracks, counts.albums, counts.artists, reread)
        } finally {
            withContext(NonCancellable) { finish(generation) }
        }
    }

    // The files as listed, in a digest, and their folders counted.
    private class Look(val digest: String, val counter: FolderCounter, val files: Long)

    // Lists the files without writing anything.
    private suspend fun look(excluded: Set<String>): Look {
        val digest = PhoneDigest()
        val counter = FolderCounter()
        var files = 0L
        scanner.walk(SCAN_PAGE) { page ->
            for (file in page) {
                digest.add(file)
                counter.add(file.folder)
            }
            files += page.size
        }
        return Look(digest.finish(excluded, tuning), counter, files)
    }

    // Starts the phone's copy once the copy swapped in last has left the
    // stage and no merge holds a claim: what an earlier scan left goes, and
    // the copy takes a new generation. The digest goes in the same step,
    // before any saved tags change.
    private suspend fun start(): Long {
        merge.awaitStageClear(DEVICE)
        if (copies.meta(DEVICE)?.stage.let { it == null || it == STAGE_NONE }) drainStage()
        drainWork()
        val generation = merge.sourceWrite {
            db.withTransaction {
                db.workDao().clearStage(DEVICE)
                copies.ensureMeta(DEVICE)
                val generation = merge.issueGeneration(copies.lastGeneration())
                dao.markBuilding(generation)
                dao.keepState()
                dao.forgetDigest()
                generation
            }
        }
        merge.forgetStagesBefore(DEVICE, generation)
        return generation
    }

    // Lists the files into scan_file in the order they come, the first of
    // a file listed twice kept. `expected` is how many files are likely, for
    // the first look at the free space.
    private suspend fun list(excluded: Set<String>, expected: Long): Look {
        val tagged = dao.fileTagCount()
        checkSpace(listNeed(expected, tagged))
        val digest = PhoneDigest()
        val counter = FolderCounter()
        var seq = 0L
        var pages = 0
        scanner.walk(SCAN_PAGE) { page ->
            for (file in page) {
                digest.add(file)
                counter.add(file.folder)
            }
            dao.insertFiles(page.map { it.toScanFile(seq++) })
            pages++
            if (pages % PAGES_PER_SPACE_CHECK == 0) checkSpace(listNeed(maxOf(expected, seq), tagged))
        }
        return Look(digest.finish(excluded, tuning), counter, seq)
    }

    // Reads the tags of files new or changed since they were saved, a page
    // at a time, and forgets the saved tags of files that are gone.
    // Answers how many files were read.
    private suspend fun refreshTags(): Int {
        var reread = 0
        var after = Long.MIN_VALUE
        while (true) {
            val stale = dao.stalePage(after, tuning.tagsVersion, SCAN_PAGE)
            if (stale.isEmpty()) break
            // Several files at once; each read opens its own handle.
            val fresh = coroutineScope {
                stale.chunked(stale.size / READERS + 1).map { batch ->
                    async(Dispatchers.IO) {
                        batch.map { savedTags(it.mediaId, it.modifiedAt, it.sizeBytes, reader.read(it.uri.toUri()), tuning.tagsVersion) }
                    }
                }.awaitAll().flatten()
            }
            catalog.upsertFileTags(fresh)
            reread += fresh.size
            after = stale.last().mediaId
        }
        after = Long.MIN_VALUE
        while (true) {
            val gone = dao.gonePage(after, SCAN_PAGE)
            if (gone.isEmpty()) break
            catalog.deleteFileTags(gone)
            after = gone.last()
        }
        return reread
    }

    // Builds the stage from scan_file and the saved tags: files are filed
    // under their album names, names split into albums, albums written in
    // order, then their artists.
    @VisibleForTesting
    internal suspend fun build(excluded: Set<String>) {
        resolve(excluded)
        split()
        reached(ScanPoint.Grouped)
        albums()
        artists()
    }

    // Files every file the folder rules let in under its album name, a page
    // at a time in the order listed: each name's first file starts its
    // group, and each name counts how many of its files name a release or
    // an album artist and whether they all name the same one. Every genre
    // spelling is counted.
    private suspend fun resolve(excluded: Set<String>) {
        var after = -1L
        var genreOrder = 0L
        while (true) {
            val page = dao.filePage(after, SCAN_PAGE)
            if (page.isEmpty()) return
            after = page.last().seq
            val tags = readTags(page.map { it.mediaId })
            val files = page.filterNot { switchedOff(it.folder, excluded) }
            if (files.isEmpty()) continue
            val rows = files.map { it.toDeviceFile().toRow(tags[it.mediaId]?.toTags()) }
            val facts = rows.map(::rowFacts)
            val start = genreOrder
            genreOrder += facts.sumOf { it.genres.size }
            db.withTransaction { resolvePage(files, rows, facts, start) }
        }
    }

    private suspend fun resolvePage(files: List<ScanFileEntity>, read: List<DeviceRow>, facts: List<RowFacts>, genreStart: Long) {
        val byName = LinkedHashMap<String, MutableList<Int>>()
        facts.forEachIndexed { i, f -> byName.getOrPut(f.nameKey) { ArrayList() } += i }
        val known = dao.clusters(byName.keys.toList()).associateBy { it.nameKey }
        val clusters = ArrayList<ScanClusterEntity>()
        val rows = ArrayList<ScanRowEntity>(files.size)
        for ((nameKey, members) in byName) {
            val cluster = known[nameKey] ?: run {
                val firstSeq = files[members.first()].seq
                dao.newGroup(nameKey, "", firstSeq, firstSeq)
                val groupId = dao.groupId(nameKey, "")
                dao.newCluster(nameKey, groupId, firstSeq)
                ScanClusterEntity(nameKey, groupId, firstSeq)
            }
            var mbFiles = cluster.mbFiles
            var byFiles = cluster.byFiles
            var mbFirst = cluster.mbFirst
            var byFirst = cluster.byFirst
            var mbVaries = cluster.mbVaries
            var byVaries = cluster.byVaries
            for (i in members) {
                val f = facts[i]
                if (f.mbKey != null) {
                    mbFiles++
                    if (mbFirst == null) mbFirst = f.mbKey else if (f.mbKey != mbFirst) mbVaries = true
                }
                if (f.byKey != null) {
                    byFiles++
                    if (byFirst == null) byFirst = f.byKey else if (f.byKey != byFirst) byVaries = true
                }
                val file = files[i]
                rows += ScanRowEntity(file.mediaId, file.seq, cluster.groupId, read[i].track, f.fileNo, f.discKey, f.numKey, f.nameOrder)
            }
            clusters += cluster.copy(
                files = cluster.files + members.size,
                mbFiles = mbFiles,
                byFiles = byFiles,
                mbFirst = mbFirst,
                byFirst = byFirst,
                mbVaries = mbVaries,
                byVaries = byVaries,
            )
        }
        dao.updateClusters(clusters)
        dao.insertRows(rows)
        // Genre spellings, each with where it was first seen.
        val spellings = LinkedHashMap<Pair<String, String>, Tally>()
        var order = genreStart
        for (f in facts) {
            for ((key, name) in f.genres) {
                val tally = spellings.getOrPut("g:$key" to name) { Tally(order) }
                tally.n++
                order++
            }
        }
        addTallies(spellings)
    }

    // Splits each name whose every file names a release, or else whose
    // every file names an album artist, into one album per release or
    // artist, in the order each first appears. A name whose files all name
    // the same one keeps its one group, only marked.
    private suspend fun split() {
        var page = dao.firstClusters(SCAN_PAGE)
        while (page.isNotEmpty()) {
            for (cluster in page) {
                val (first, varies) = when {
                    cluster.mbFiles == cluster.files -> cluster.mbFirst to cluster.mbVaries
                    cluster.byFiles == cluster.files -> cluster.byFirst to cluster.byVaries
                    else -> continue
                }
                val splitKey = checkNotNull(first)
                dao.setSplit(cluster.groupId, splitKey)
                if (varies) refile(cluster, splitKey, byRelease = cluster.mbFiles == cluster.files)
            }
            page = if (page.size < SCAN_PAGE) emptyList() else dao.clustersAfter(page.last().nameKey, SCAN_PAGE)
        }
    }

    // Moves each file of a split name whose key is not the first one into
    // the group of its own key, made when that key is first seen.
    private suspend fun refile(cluster: ScanClusterEntity, first: String, byRelease: Boolean) {
        var after = -1L
        while (true) {
            val page = dao.groupRows(cluster.groupId, after, SCAN_PAGE)
            if (page.isEmpty()) return
            after = page.last().seq
            val moves = LinkedHashMap<String, MutableList<Long>>()
            val firstSeqs = HashMap<String, Long>()
            for (file in rowsOf(page)) {
                val facts = rowFacts(file.row)
                val key = checkNotNull(if (byRelease) facts.mbKey else facts.byKey)
                if (key == first) continue
                moves.getOrPut(key) { ArrayList() } += file.scanned.mediaId
                firstSeqs.putIfAbsent(key, file.scanned.seq)
            }
            if (moves.isEmpty()) continue
            db.withTransaction {
                for ((key, ids) in moves) {
                    dao.newGroup(cluster.nameKey, key, cluster.firstSeq, firstSeqs.getValue(key))
                    dao.moveRows(dao.groupId(cluster.nameKey, key), ids)
                }
            }
        }
    }

    // Writes every album in order, each name's albums in the order of their
    // first files, with its songs in album order, then counts its artist.
    // A small album is built whole by the same rules as ever; a giant one a
    // page at a time through the database.
    private suspend fun albums() {
        checkSpace(2 * dao.rowCount() * STAGE_BYTES_PER_SONG)
        val built = Built()
        var page = dao.firstGroups(GROUP_PAGE)
        while (page.isNotEmpty()) {
            for (group in page) {
                val count = dao.groupSize(group.id)
                if (count == 0) continue
                if (count <= tuning.giant) {
                    small(group, built)
                } else {
                    built.flush()
                    giant(group, count, built)
                }
            }
            val last = page.last()
            page = dao.groupsAfter(SimpleSQLiteQuery(PhoneScanSql.GROUPS_AFTER, arrayOf<Any>(last.clusterSeq, last.firstSeq, GROUP_PAGE)))
        }
        built.flush()
    }

    private suspend fun small(group: ScanGroupEntity, built: Built) {
        val files = ArrayList<Scanned>()
        var after = -1L
        while (true) {
            val page = dao.groupRows(group.id, after, SCAN_PAGE)
            if (page.isEmpty()) break
            files += rowsOf(page)
            after = page.last().seq
        }
        val rows = files.map { it.row }
        val spelling = spellings(rows)
        val album = albumRows(group.nameKey, group.splitKey, rows, spelling::getValue)
        if (built.pendingTracks + files.size > FLUSH_TRACKS) built.flush()
        val byId = files.associateBy { it.scanned.mediaId }
        val source = album.album.toSource().copy(seq = built.nextAlbumSeq++)
        val tracks = album.tracks.map { track -> built.track(track, byId.getValue(track.nativeId.toLong()).scanned) }
        built.add(source, tracks, files.map { it.scanned.mediaId }, rows.firstNotNullOfOrNull { it.tags.sortAlbumArtist.orNull() })
    }

    // An album too big to hold: its name and artist are counted through
    // the database, then its files are read back in album order a page at
    // a time and written as they come. The album row is written last.
    private suspend fun giant(group: ScanGroupEntity, count: Int, built: Built) {
        var compilation = false
        var unnumbered = false
        var after = -1L
        while (true) {
            val page = dao.groupRows(group.id, after, SCAN_PAGE)
            if (page.isEmpty()) break
            after = page.last().seq
            val tallies = LinkedHashMap<Pair<String, String>, Tally>()
            for (file in rowsOf(page)) {
                val row = file.row
                val seq = file.scanned.seq
                tallies.getOrPut("t:${group.id}" to albumName(row)) { Tally(seq) }.n++
                row.albumArtist.orNull()?.let { tallies.getOrPut("aa:${group.id}" to it) { Tally(seq) }.n++ }
                row.artist.orNull()?.let { tallies.getOrPut("a:${group.id}" to it) { Tally(seq) }.n++ }
                if (row.compilation) compilation = true
            }
            if (page.any { it.fileNo == null }) unnumbered = true
            db.withTransaction { addTallies(tallies) }
        }
        val title = checkNotNull(mostCommon("t:${group.id}"))
        val artist = mostCommon("aa:${group.id}")
            ?: (if (compilation) VARIOUS_ARTISTS else null)
            ?: mostCommon("a:${group.id}")
            ?: UNKNOWN_ARTIST
        val artistId = artistIdOf(artist)
        val byNames = count in 2..NAMED_FILES_MAX && !unnumbered && namesUsable(dao.fileNumbers(group.id, NAMED_FILES_MAX + 1))
        if (byNames) dao.numberByNames(group.id)

        val albumId = albumIdOf(group.nameKey, group.splitKey)
        var artwork: String? = null
        var index = 0
        var duration = 0L
        var addedAt = Long.MIN_VALUE
        var year: Int? = null
        val sortAlbum = FirstBySeq()
        val sortArtist = FirstBySeq()
        var page = dao.firstOrdered(group.id, SCAN_PAGE)
        while (page.isNotEmpty()) {
            val files = rowsOf(page)
            val cover = artwork ?: albumArtwork(albumId, files.first().row.uri).also { artwork = it }
            val spelling = spellings(files.map { it.row })
            val tracks = files.map { file ->
                val row = file.row
                duration += row.durationMs
                addedAt = maxOf(addedAt, row.addedAtSeconds)
                (row.tags.originalYear ?: row.year)?.let { y -> year = maxOf(year ?: y, y) }
                sortAlbum.offer(file.scanned.seq, row.tags.sortAlbum.orNull())
                sortArtist.offer(file.scanned.seq, row.tags.sortAlbumArtist.orNull())
                val number = row.track ?: file.order.fileNo.takeIf { byNames }
                built.track(trackRow(row, number, index++, albumId, title, artist, artistId, cover, spelling::getValue), file.scanned)
            }
            val ids = files.map { it.scanned.mediaId }
            val placed = built.songs.placeSongs(tracks)
            db.withTransaction {
                dao.insertStageTracks(placed.map(::StageTrackEntity))
                deleteDone(ids)
                db.workDao().addMoved(DEVICE, built.songs.takeMoved(), 0, 0)
            }
            val last = page.last()
            page = dao.orderedAfter(
                SimpleSQLiteQuery(PhoneScanSql.ORDERED_AFTER, arrayOf<Any>(group.id, last.discKey, last.numKey, last.nameOrder, last.seq, SCAN_PAGE)),
            )
        }
        val album = AlbumEntity(
            id = albumId,
            sourceId = DEVICE,
            nativeId = albumNativeId(group.nameKey, group.splitKey),
            title = title,
            searchKey = searchKey("$title $artist"),
            sortKey = sortKey(sortAlbum.value ?: title),
            artist = artist,
            artistId = artistId,
            year = year,
            songCount = count,
            durationMs = duration,
            addedAt = addedAt,
            artwork = artwork,
        ).toSource().copy(seq = built.nextAlbumSeq++)
        val placed = built.albums.placeAlbums(listOf(album))
        db.withTransaction {
            dao.putStageAlbums(placed.map(::StageAlbumEntity))
            built.countArtist(album, count, sortArtist.value)
            built.applyArtists()
            db.workDao().addMoved(DEVICE, 0, built.albums.takeMoved(), 0)
        }
    }

    // Writes each album artist, in the order of its first album, under the
    // name most of its albums give it.
    private suspend fun artists() {
        val places = places("source_artist", STAGE_ARTIST_TO_COME)
        var after = -1L
        while (true) {
            val page = dao.artistPage(after, SCAN_PAGE)
            if (page.isEmpty()) return
            after = page.last().firstSeq
            val rows = places.placeArtists(
                page.map { a ->
                    val name = mostCommon("n:${a.artistId}") ?: UNKNOWN_ARTIST
                    ArtistEntity(a.artistId, DEVICE, name, searchKey(name), sortKey(a.sortName ?: name), a.albums, a.songs, a.artwork).toSource()
                },
            )
            db.withTransaction {
                dao.insertStageArtists(rows.map(::StageArtistEntity))
                db.workDao().addMoved(DEVICE, 0, 0, places.takeMoved())
            }
        }
    }

    // Whether the stage holds exactly the library's phone rows, each table
    // checked in one statement (the merge's own id aside).
    private suspend fun unchanged(): Boolean = withContext(Dispatchers.IO) {
        val sql = db.openHelper.writableDatabase
        listOf("track", "album", "artist").all { kind ->
            val columns = columnsOf(sql, "source_$kind") - "mergedId"
            sql.query(sameRows(kind, columns)).use { it.moveToFirst() && it.getInt(0) == 1 }
        }
    }

    // Ends the copy however the scan ended: a copy still this scan's and not
    // claimed by the merge goes, as do the scan's working rows. A write the
    // disk refuses now is left for the next scan to clear.
    private suspend fun finish(generation: Long) {
        try {
            if (dao.endUnclaimed(generation) > 0) drainStage()
            drainWork()
        } catch (e: SQLiteException) {
            Log.w("Octo", "phone scan cleanup left rows: ${e.javaClass.simpleName}")
        } finally {
            merge.forgetStage(DEVICE, generation)
        }
    }

    private suspend fun drainStage() {
        drain(dao::drainStageTracks, dao::drainStageTracksRest)
        drain(dao::drainStageAlbums, dao::drainStageAlbumsRest)
        drain(dao::drainStageArtists, dao::drainStageArtistsRest)
    }

    private suspend fun drainWork() {
        drain(dao::drainFiles, dao::drainFilesRest)
        drain(dao::drainRows, dao::drainRowsRest)
        drain(dao::drainClusters, dao::drainClustersRest)
        drain(dao::drainGroups, dao::drainGroupsRest)
        drain(dao::drainTallies, dao::drainTalliesRest)
        drain(dao::drainGenreNames, dao::drainGenreNamesRest)
        drain(dao::drainArtists, dao::drainArtistsRest)
    }

    // A chunk at a time, each its own transaction, until less than a chunk
    // is left, which goes in one more.
    private suspend fun drain(chunk: suspend () -> Int, rest: suspend () -> Int) {
        do {
            val taken = chunk()
        } while (taken > 0)
        rest()
    }

    // The saved tags of these files that could be read, by media id.
    private suspend fun readTags(ids: List<Long>): Map<Long, FileTagsEntity> =
        ids.chunked(IN_LIMIT).flatMap { dao.readTags(it) }.associateBy { it.mediaId }

    // A page of an album's files with what is listed and known of each, in
    // the page's order.
    private suspend fun rowsOf(page: List<ScanRowEntity>): List<Scanned> {
        val ids = page.map { it.mediaId }
        val files = dao.filesById(ids).associateBy { it.mediaId }
        val tags = readTags(ids)
        return page.map { order ->
            val file = files.getValue(order.mediaId)
            Scanned(order, file, file.toDeviceFile().toRow(tags[order.mediaId]?.toTags()))
        }
    }

    // The spelling of each genre these rows name, by its key: the one most
    // files across the phone use, worked out once per genre.
    private suspend fun spellings(rows: List<DeviceRow>): Map<String, String> {
        val keys = rows.flatMapTo(LinkedHashSet()) { row -> row.genres.map(::genreKey) }
        if (keys.isEmpty()) return emptyMap()
        val known = HashMap<String, String>()
        keys.chunked(IN_LIMIT).forEach { chunk -> dao.genreNames(chunk).forEach { known[it.genreKey] = it.name } }
        val missing = keys.filterNot(known::containsKey)
        if (missing.isNotEmpty()) {
            val made = missing.map { key -> ScanGenreNameEntity(key, checkNotNull(mostCommon("g:$key")) { "no spelling counted for $key" }) }
            dao.insertGenreNames(made)
            made.forEach { known[it.genreKey] = it.name }
        }
        return known
    }

    // The value counted most for `owner`; of values counted equally often,
    // the longest; of those, the first seen. Null when none was counted.
    private suspend fun mostCommon(owner: String): String? {
        var best: ScanTallyEntity? = null
        var page = dao.firstTallies(owner, SCAN_PAGE)
        while (page.isNotEmpty()) {
            for (t in page) {
                val b = best
                if (b == null || t.n > b.n || (t.n == b.n && (t.value.length > b.value.length || (t.value.length == b.value.length && t.first < b.first)))) {
                    best = t
                }
            }
            page = if (page.size < SCAN_PAGE) emptyList() else dao.talliesAfter(owner, page.last().value, SCAN_PAGE)
        }
        return best?.value
    }

    // Adds counts, each with where its value was first seen. Inside a
    // transaction.
    private suspend fun addTallies(tallies: Map<Pair<String, String>, Tally>) {
        for ((key, tally) in tallies) {
            dao.newTally(key.first, key.second, tally.first)
            dao.addTally(key.first, key.second, tally.n)
        }
    }

    // Files written to the stage leave the working tables. Inside a
    // transaction.
    private suspend fun deleteDone(ids: List<Long>) {
        ids.chunked(IN_LIMIT).forEach {
            dao.deleteRows(it)
            dao.deleteFiles(it)
        }
    }

    private fun listNeed(files: Long, tagged: Long): Long =
        2 * (files * (SCAN_BYTES_PER_FILE + STAGE_BYTES_PER_SONG) + maxOf(0, files - tagged) * TAG_BYTES_PER_FILE)

    private fun checkSpace(need: Long) {
        if (freeSpace.bytes() < need) throw NotEnoughSpace(need)
    }

    // One count while it is gathered: how often, and the place first seen.
    private class Tally(val first: Long) {
        var n = 0
    }

    // The value of the file listed first among those that have one.
    private class FirstBySeq {
        private var seq = Long.MAX_VALUE
        var value: String? = null
            private set

        fun offer(at: Long, candidate: String?) {
            if (candidate != null && at < seq) {
                seq = at
                value = candidate
            }
        }
    }

    // One file of an album: its place, as listed, and as read.
    private class Scanned(val order: ScanRowEntity, val scanned: ScanFileEntity, val row: DeviceRow)

    // The albums built and not yet written, the next album's order among
    // the albums (which orders its artist), where the songs and albums go in
    // the phone's order as they are written, and the artists of the albums
    // not yet counted.
    private inner class Built {
        var nextAlbumSeq = 0L
        val songs = places("source_track", SCAN_TO_COME)
        val albums = places("source_album", STAGE_ALBUM_TO_COME)
        private val built = ArrayList<SourceAlbumEntity>()
        private val tracks = ArrayList<SourceTrackEntity>()
        private val done = ArrayList<Long>()
        private val artists = LinkedHashMap<String, ArtistTally>()
        val pendingTracks get() = tracks.size

        // A song as the stage keeps it: with its keys and where its file is;
        // its place is given as it is written.
        fun track(row: SourceTrackEntity, file: ScanFileEntity): SourceTrackEntity =
            row.copy(mergedId = "", folder = file.folder, fileName = file.fileName, storageVolume = file.volume).withKeys()

        fun add(album: SourceAlbumEntity, songs: List<SourceTrackEntity>, ids: List<Long>, sortArtist: String?) {
            built += album
            tracks += songs
            done += ids
            countArtist(album, songs.size, sortArtist)
        }

        // Counts an album for its artist, in album order.
        fun countArtist(album: SourceAlbumEntity, songs: Int, sortArtist: String?) {
            val tally = artists.getOrPut(album.artistId) { ArtistTally(album.seq, album.addedAt, album.artwork) }
            tally.albums++
            tally.songs += songs
            if (sortArtist != null) tally.sortName = sortArtist
            // The most recently added album; of two added at once, the first.
            if (album.addedAt > tally.newestAt) {
                tally.newestAt = album.addedAt
                tally.artwork = album.artwork
            }
            tally.names.getOrPut(album.artist) { Tally(album.seq) }.n++
        }

        suspend fun flush() {
            if (built.isEmpty() && artists.isEmpty()) return
            val placed = songs.placeSongs(tracks)
            val placedAlbums = albums.placeAlbums(built)
            db.withTransaction {
                if (placedAlbums.isNotEmpty()) dao.putStageAlbums(placedAlbums.map(::StageAlbumEntity))
                if (placed.isNotEmpty()) dao.insertStageTracks(placed.map(::StageTrackEntity))
                deleteDone(done)
                applyArtists()
                db.workDao().addMoved(DEVICE, songs.takeMoved(), albums.takeMoved(), 0)
            }
            built.clear()
            tracks.clear()
            done.clear()
        }

        // The counted artists into scan_artist, as if album by album: the
        // first album places the artist, the last sort name wins, and a
        // newer album's cover replaces an older one's. Inside a transaction.
        suspend fun applyArtists() {
            for ((id, tally) in artists) {
                dao.newArtist(id, tally.firstSeq, tally.firstAddedAt, tally.firstArtwork)
                dao.addArtist(id, tally.albums, tally.songs, tally.sortName)
                dao.newerArtwork(id, tally.newestAt, tally.artwork)
                addTallies(tally.names.mapKeys { (name, _) -> "n:$id" to name })
            }
            artists.clear()
        }
    }

    // One artist's albums since the last write.
    private class ArtistTally(val firstSeq: Long, val firstAddedAt: Long, val firstArtwork: String?) {
        var albums = 0
        var songs = 0
        var sortName: String? = null
        var newestAt = firstAddedAt
        var artwork = firstArtwork
        val names = LinkedHashMap<String, Tally>()
    }

    private companion object {
        const val LABEL = "phone scan"
    }
}

// The statement that answers 1 when the phone's stage of `kind` holds
// exactly the library's phone rows of that kind in every one of `columns`.
internal fun sameRows(kind: String, columns: List<String>): String {
    val equal = columns.joinToString(" AND ") { "g.`$it` IS t.`$it` COLLATE BINARY" }
    return """
        SELECT (SELECT COUNT(*) FROM stage_$kind WHERE sourceId = 'device') = (SELECT COUNT(*) FROM source_$kind WHERE sourceId = 'device')
          AND NOT EXISTS (
            SELECT 1 FROM stage_$kind g WHERE g.sourceId = 'device'
              AND NOT EXISTS (SELECT 1 FROM source_$kind t WHERE t.id = g.id AND $equal)
          )
    """.trimIndent()
}

// A file as the scan keeps it, `seq` its place as listed.
internal fun DeviceFile.toScanFile(seq: Long) = ScanFileEntity(
    mediaId = id,
    seq = seq,
    uri = uri,
    fileName = fileName,
    folder = folder,
    volume = volume,
    modifiedAt = modifiedAt,
    sizeBytes = sizeBytes,
    durationMs = durationMs,
    addedAt = addedAtSeconds,
    mimeType = mimeType,
    fbTitle = fallback.title,
    fbArtist = fallback.artist,
    fbAlbumArtist = fallback.albumArtist,
    fbAlbum = fallback.album,
    fbTrackNo = fallback.trackNo,
    fbDiscNo = fallback.discNo,
    fbYear = fallback.year,
    fbGenres = joinLines(fallback.genres),
)

internal fun ScanFileEntity.toDeviceFile() = DeviceFile(
    id = mediaId,
    uri = uri,
    fileName = fileName,
    folder = folder,
    modifiedAt = modifiedAt,
    sizeBytes = sizeBytes,
    durationMs = durationMs,
    addedAtSeconds = addedAt,
    mimeType = mimeType,
    fallback = FileTags(
        title = fbTitle,
        artist = fbArtist,
        albumArtist = fbAlbumArtist,
        album = fbAlbum,
        trackNo = fbTrackNo,
        discNo = fbDiscNo,
        year = fbYear,
        genres = splitLines(fbGenres),
    ),
    volume = volume,
)

// A digest of all a build reads besides the saved tags: every file as
// listed, in order, each field with its length, then the folders switched
// off and the versions of the reading and building code. Equal digests
// build equal libraries from equal saved tags.
internal class PhoneDigest {
    private val sha = MessageDigest.getInstance("SHA-256")
    private val number = ByteBuffer.allocate(Long.SIZE_BYTES)

    fun add(file: DeviceFile) {
        long(file.id)
        text(file.uri)
        text(file.fileName)
        text(file.folder)
        text(file.volume)
        long(file.modifiedAt)
        long(file.sizeBytes)
        long(file.durationMs)
        long(file.addedAtSeconds)
        text(file.mimeType)
        val fallback = file.fallback
        text(fallback.title)
        text(fallback.artist)
        text(fallback.albumArtist)
        text(fallback.album)
        int(fallback.trackNo)
        int(fallback.discNo)
        int(fallback.year)
        long(fallback.genres.size.toLong())
        fallback.genres.forEach(::text)
    }

    fun finish(excluded: Set<String>, tuning: ScanTuning): String {
        long(excluded.size.toLong())
        excluded.sorted().forEach(::text)
        long(tuning.tagsVersion.toLong())
        long(PHONE_BUILD_VERSION.toLong())
        long(tuning.versionCode.toLong())
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    private fun long(value: Long) {
        number.clear()
        number.putLong(value)
        sha.update(number.array())
    }

    private fun int(value: Int?) {
        if (value == null) long(-1L) else {
            long(0L)
            long(value.toLong())
        }
    }

    private fun text(value: String?) {
        if (value == null) {
            long(-1L)
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        long(bytes.size.toLong())
        sha.update(bytes)
    }
}
