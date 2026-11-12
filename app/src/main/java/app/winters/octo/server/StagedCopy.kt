package app.winters.octo.server

import android.database.sqlite.SQLiteFullException
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.winters.octo.catalog.AccountSyncEntity
import app.winters.octo.catalog.COPY_PAGES_CLEAR
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.CopyNote
import app.winters.octo.catalog.DensePlaces
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.KeptPlaces
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.Places
import app.winters.octo.catalog.STAGE_ARTIST_TO_COME
import app.winters.octo.catalog.STAGE_BUILDING
import app.winters.octo.catalog.STAGE_CLEAR_ROWS
import app.winters.octo.catalog.STAGE_NONE
import app.winters.octo.catalog.STAGE_READY
import app.winters.octo.catalog.SourceAlbumEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.StageAlbumEntity
import app.winters.octo.catalog.StageArtistEntity
import app.winters.octo.catalog.StageTrackEntity
import app.winters.octo.catalog.SyncAlbumEntity
import app.winters.octo.catalog.SyncArtistEntity
import app.winters.octo.catalog.SyncOrderEntity
import app.winters.octo.catalog.SyncSongEntity
import app.winters.octo.catalog.TakeInLogEntity
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.placeArtists
import app.winters.octo.catalog.placeSongs
import app.winters.octo.catalog.sortKey
import app.winters.octo.catalog.withKeys
import app.winters.octo.device.DEVICE
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.LIBRARY_PAGE
import app.winters.octo.subsonic.LibraryJson
import app.winters.octo.subsonic.LibrarySink
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.readLibraryInto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

// How many rows a copy reads or writes at a time once the server's pages are in.
private const val PAGE = 500

// Songs built and written together. An album with more is put in order
// through the database instead of in memory.
internal const val GIANT_ALBUM = 2_000

// How many song pages a copy reads between looks at the free space.
private const val PAGES_PER_SPACE_CHECK = 10

// How many song pages a copy reads between covers it shows.
private const val PAGES_PER_COVER = 10

// How much the database grows for each song a copy reads (the server's
// pages as they came, with their indexes) and for each song it builds (the
// stage's rows, with their indexes), as the file's pages in use, measured on
// generated libraries (StagedCopyTest.diskConstantsHoldWithinTenPercent):
// the read within 10% either way, and the build's 607 bytes a song held
// between the measure and 10% over it. Free space is checked against twice
// what is still to come.
internal const val SYNC_BYTES_PER_SONG = 400L
internal const val STAGE_BYTES_PER_SONG = 630L

// A song of the source (`s`) is still to come in the copy being built
// while the server listed it and it is not in the stage yet: a built album
// leaves the server's pages as it goes into the stage, a giant one's pages
// go into the stage first.
internal const val COPY_TO_COME =
    "EXISTS (SELECT 1 FROM sync_song y WHERE y.songId = s.nativeId) AND NOT EXISTS (SELECT 1 FROM stage_track g WHERE g.id = s.id)"

// An album of the source still to come in the walk of the server's list of
// albums: any but a listed one already given its place, since one the list
// does not hold follows every listed one.
internal const val LISTED_TO_COME = "NOT EXISTS (SELECT 1 FROM sync_album y WHERE y.id = s.nativeId AND y.place IS NOT NULL)"

// An album of the source the list does not hold (made up from its songs)
// still to come: not listed, and not in the stage yet.
internal const val UNLISTED_TO_COME =
    "NOT EXISTS (SELECT 1 FROM sync_album y WHERE y.id = s.nativeId) AND NOT EXISTS (SELECT 1 FROM stage_album g WHERE g.id = s.id)"

// How many listed albums the walk of the list places at a time (their old
// places are looked up ID_CHUNK at a time).
private const val LISTED_WALK = 2_000

// How the build's writes into the stage are told apart when counted, and
// a copy start's clear of what earlier copies left.
internal const val COPY_STAGE_PAGE = "copy stage page"
internal const val COPY_START_CLEAR = "copy start clear"

// How much of its save a copy's Save step says is in, in SAVE_PARTS.
internal fun savedParts(saved: Float): Long = (saved.coerceIn(0f, 1f) * SAVE_PARTS).toLong()

// Points of a copy, for tests to pause at.
enum class CopyPoint { Page, Build, Ready }

// How many rows a copy staged.
data class CopyCounts(val tracks: Int, val albums: Int, val artists: Int)

// What the server in use needs: a whole copy, only the steps that follow
// one (a copy was swapped in but its steps were cut off), or nothing.
enum class CopyNeed { Copy, AfterSync, Fresh }

// Decides from the account's record of its last copy and the account whose
// copy the address's rows are. A copy is needed when there is none, it is
// another account's, it was made by an older version or for another
// address, or it is old.
internal fun copyNeed(row: AccountSyncEntity?, copyOf: String?, account: String, sourceId: String, now: Long): CopyNeed = when {
    row == null || copyOf != account || row.rowsVersion != ROWS_VERSION || row.sourceId != sourceId -> CopyNeed.Copy
    !row.afterSyncDone -> CopyNeed.AfterSync
    now - row.syncedAt > STALE_MS -> CopyNeed.Copy
    else -> CopyNeed.Fresh
}

// A text's UTF-16 code units as fixed-width hex, so the database's byte
// order of the result is Kotlin's String order of the text.
internal fun sortKeyHex(text: String): String = buildString(text.length * 4) {
    for (unit in text) {
        val code = unit.code
        append(HEX[code shr 12 and 15])
        append(HEX[code shr 8 and 15])
        append(HEX[code shr 4 and 15])
        append(HEX[code and 15])
    }
}

private const val HEX = "0123456789abcdef"

// Copies one server's library through the database instead of memory: the
// server's pages are kept as they arrive, built album by album into a
// stage, and the merge swaps the stage in together with the library. A
// copy that fails or is stopped leaves the library as it was.
@Singleton
class StagedCopy @Inject constructor(
    private val db: CatalogDatabase,
    private val merge: CatalogMerge,
    private val freeSpace: FreeSpace,
) {
    private val dao get() = db.copyDao()

    // Runs at each point of a copy; tests pause here.
    @VisibleForTesting
    internal var reached: suspend (CopyPoint) -> Unit = {}

    // Whether a copy puts its songs, albums and artists in the source's
    // order at the last copy's places, kept where that order still holds;
    // off, it numbers each table anew, for tests.
    @VisibleForTesting
    internal var keepPlaces = true

    private fun places(sourceId: String, table: String, toCome: String, after: Long = -1L): Places =
        if (keepPlaces) KeptPlaces(db, table, sourceId, toCome, after) else DensePlaces(after + 1)

    // The free space the last check asked for, for wording a full disk.
    @Volatile private var lastNeed = 0L

    // Copies the library and waits until the merge swapped it in, saying how
    // far it is to `report`. Throws StageAborted when the merge could not
    // swap it, NotEnoughSpace when the phone runs short of room, and
    // ApplyPending when its save stopped after its first group (it is
    // resumed, and finished by the resume). `scan` is the server's last scan
    // as the copy read it, kept with the save's record for a resume.
    suspend fun copy(account: String, sourceId: String, client: SubsonicClient, report: CopyReport = CopyReport.None, scan: String? = null): CopyCounts {
        val generation = start(account, sourceId)
        merge.noteCopy(sourceId, generation, CopyNote(scan))
        try {
            val counts = fill(account, sourceId, generation, client, report)
            report.staged(counts.tracks, counts.albums)
            reached(CopyPoint.Ready)
            report.at(CopyStep.Add, 0, counts.tracks.toLong())
            adding(sourceId, generation, report) {
                merge.rebuild()
                merge.awaitStage(sourceId, generation)
            }
            return counts
        } catch (e: SQLiteFullException) {
            throw NotEnoughSpace(maxOf(lastNeed, 1))
        } finally {
            withContext(NonCancellable) { clearUnclaimed(sourceId, generation) }
            merge.forgetStage(sourceId, generation)
        }
    }

    // Runs `swap` while what the merge says of this copy goes on to
    // `report`: its songs placed, then saving. Listening starts before the
    // merge is asked for and ends with `swap`, however it ends.
    private suspend fun adding(sourceId: String, generation: Long, report: CopyReport, swap: suspend () -> Unit) = coroutineScope {
        val forward = launch(start = CoroutineStart.UNDISPATCHED) {
            merge.stageProgress.collect { progress ->
                if (progress == null || progress.sourceId != sourceId || progress.generation != generation) return@collect
                if (progress.saving) {
                    val saved = progress.saved
                    if (saved == null) report.at(CopyStep.Save, 0, null) else report.at(CopyStep.Save, savedParts(saved), SAVE_PARTS)
                } else {
                    report.at(CopyStep.Add, progress.placed.toLong(), progress.of.toLong())
                }
            }
        }
        try {
            swap()
        } finally {
            forward.cancelAndJoin()
        }
    }

    // Starts a stage for the source, once the copy swapped in last has left
    // the stage and the merge holds no claim on one: clears what an earlier
    // copy left and takes a new generation, from which on music taken in is
    // noted for this stage.
    @VisibleForTesting
    internal suspend fun start(account: String, sourceId: String): Long {
        merge.awaitStageClear(sourceId)
        awaitPagesClear(sourceId)
        clearStartWork()
        return merge.sourceWrite {
            db.withTransaction {
                dao.clearStage(sourceId)
                dao.ensureMeta(sourceId)
                endRatingNotes(sourceId)
                val generation = merge.issueGeneration(dao.lastGeneration())
                dao.markBuilding(sourceId, generation, account)
                generation
            }.also { generation -> merge.forgetStagesBefore(sourceId, generation) }
        }
    }

    // Reads the server into the stage and marks it ready for the merge.
    @VisibleForTesting
    internal suspend fun fill(
        account: String,
        sourceId: String,
        generation: Long,
        client: SubsonicClient,
        report: CopyReport = CopyReport.None,
    ): CopyCounts {
        val songs = read(client, sourceId, report)
        val counts = build(sourceId, generation, songs, report)
        db.withTransaction {
            check(dao.markReady(sourceId, generation, counts.tracks, counts.albums, counts.artists) == 1) {
                "The copy's stage for $sourceId is gone"
            }
            dao.ensureAccountSync(account, sourceId, ROWS_VERSION)
        }
        return counts
    }

    // Keeps each page of the server's library as it arrives, one transaction
    // a page. Answers how many songs came. Says to `report` how many songs
    // went in, of the songs the listed albums hold once every album is
    // listed, and now and then a cover that came.
    @VisibleForTesting
    internal suspend fun read(client: SubsonicClient, sourceId: String, report: CopyReport = CopyReport.None): Long {
        var songs = 0L
        var pages = 0
        // Albums and songs arrive side by side.
        val inserted = AtomicLong(0)
        val listedSongs = AtomicLong(0)
        val total = AtomicReference<Long?>(null)
        checkSpace(readNeed(0))
        client.readLibraryInto(
            object : LibrarySink {
                override suspend fun albums(page: List<Album>, offset: Int) {
                    val ids = dao.insertSyncAlbums(page.mapIndexed { i, album -> SyncAlbumEntity(album.id, (offset + i).toLong(), LibraryJson.encodeAlbum(album)) })
                    // An album a shifted page lists again counts once.
                    listedSongs.addAndGet(page.zip(ids).sumOf { (album, id) -> if (id == -1L) 0L else album.songCount.toLong() })
                }

                override suspend fun albumsListed() {
                    total.set(listedSongs.get())
                    report.at(CopyStep.Read, inserted.get(), total.get())
                }

                override suspend fun artists(artists: List<Artist>) {
                    artists.withIndex().chunked(PAGE).forEach { chunk ->
                        dao.insertSyncArtists(chunk.map { (i, artist) -> SyncArtistEntity(artist.id, i.toLong(), LibraryJson.encodeArtist(artist)) })
                    }
                }

                override suspend fun songs(page: List<Song>) {
                    val ids = dao.insertSyncSongs(page.map { SyncSongEntity(songId = it.id, albumKey = serverAlbumKey(it), json = LibraryJson.encodeSong(it)) })
                    val went = inserted.addAndGet(ids.count { it != -1L }.toLong())
                    songs += page.size
                    pages++
                    if (pages % PAGES_PER_SPACE_CHECK == 0) checkSpace(readNeed(songs))
                    val cover = if (pages == 1 || pages % PAGES_PER_COVER == 0) page.firstNotNullOfOrNull { serverArt(sourceId, it.coverArt) } else null
                    report.at(CopyStep.Read, went, total.get(), cover)
                    reached(CopyPoint.Page)
                }
            },
        )
        return dao.syncSongCount().toLong()
    }

    // Builds the stage album by album in the order of each album's first
    // song, writing as it goes, so only one album (or one page of a giant
    // one), the albums' keys and the artists are held at once. Says to
    // `report` how many albums with songs are built, each time some are
    // written.
    @VisibleForTesting
    internal suspend fun build(sourceId: String, generation: Long, songs: Long, report: CopyReport = CopyReport.None): CopyCounts =
        withContext(WriteLabels.of(COPY_STAGE_PAGE)) { buildPages(sourceId, generation, songs, report) }

    private suspend fun buildPages(sourceId: String, generation: Long, songs: Long, report: CopyReport): CopyCounts {
        checkSpace(2 * songs * STAGE_BYTES_PER_SONG)
        reached(CopyPoint.Build)
        val owner = "$sourceId#$generation"
        // The server's artists by row id, and their MusicBrainz ids by server id.
        val listedArtists = HashMap<String, Artist>()
        val artistMusicIds = HashMap<String, String>()
        var artistAfter = -1L
        while (true) {
            val page = dao.syncArtistPage(artistAfter, PAGE)
            if (page.isEmpty()) break
            for (row in page) {
                val artist = LibraryJson.decodeArtist(row.json)
                listedArtists["$sourceId:${artist.id}"] = artist
                serverMusicId(artist.musicBrainzId)?.let { artistMusicIds[artist.id] = it }
            }
            artistAfter = page.last().seq
        }
        val albumKeys = albumKeysInOrder()
        val withSongs = albumKeys.size.toLong()
        // Listed albums take their places in the order of the server's list
        // first; albums made up from songs follow them, in the order of their
        // first songs. Songs and artists take theirs in the order they are
        // built, a batch at a time. Each keeps the last copy's place where
        // that order still holds. An album's order among the others (the
        // list's place, then the made up ones' after) orders its artist.
        val lastListed = placeListed(sourceId)
        val firstUnlistedSeq = (dao.maxSyncAlbumSeq() ?: -1L) + 1
        val built = Built()
        val batch = Batch()
        val songPlaces = places(sourceId, "source_track", COPY_TO_COME)
        val unlistedPlaces = places(sourceId, "source_album", UNLISTED_TO_COME, after = lastListed)
        report.at(CopyStep.Build, 0, withSongs)

        // Writes the albums built so far and says how many there are.
        suspend fun written() {
            if (batch.albums.isEmpty()) return
            val cover = batch.albums.firstNotNullOfOrNull { it.artwork }
            flush(sourceId, batch, songPlaces, unlistedPlaces)
            report.at(CopyStep.Build, built.albums.toLong(), withSongs, cover)
        }

        for (key in albumKeys) {
            val listedRow = dao.syncAlbum(key)
            val listed = listedRow?.let { LibraryJson.decodeAlbum(it.json) }
            val order = listedRow?.seq ?: (firstUnlistedSeq + built.unlisted++)
            val place = listedRow?.let { row -> checkNotNull(row.place) { "listed album ${row.id} has no place" } }
            if (dao.albumSongCount(key) > GIANT_ALBUM) {
                written()
                giant(sourceId, owner, key, listed, order, place, artistMusicIds, built, songPlaces, unlistedPlaces)
                report.at(CopyStep.Build, built.albums.toLong(), withSongs)
                continue
            }
            val inAlbum = dao.albumSongs(key).map { LibraryJson.decodeSong(it.json) }
            val record = listed ?: albumFromSongs(key, inAlbum)
            val ordered = inAlbumOrder(inAlbum)
            val album = serverAlbumRow(sourceId, record, ordered.first().coverArt).copy(seq = order)
            val tracks = ordered.mapIndexed { index, song ->
                serverTrackRow(sourceId, song, album, record, index, artistMusicIds).withKeys()
            }
            if (batch.tracks.size + tracks.size > GIANT_ALBUM) written()
            batch.add(key, album, place, tracks)
            built.album(album, tracks.size)
        }
        written()
        songlessAlbums(sourceId, albumKeys, built)
        writeArtists(sourceId, listedArtists, built)
        return CopyCounts(built.tracks, built.albums, built.artists)
    }

    // Puts in the rows of music taken in ahead of the next copy, in one go
    // under the merge's gate: only what the source still lacks, after the
    // source's other rows, noted for a copy being built meanwhile so its swap
    // keeps them. Answers whether anything went in.
    suspend fun takeIn(sourceId: String, rows: ServerCatalog, readAlbums: Set<String>): Boolean = merge.sourceWrite {
        db.withTransaction {
            val wanted = rowsToTakeIn(
                rows,
                haveSongs = rows.tracks.map { it.nativeId }.chunked(900).flatMapTo(HashSet()) { dao.knownTracks(sourceId, it) },
                haveAlbums = rows.albums.map { it.nativeId }.chunked(900).flatMapTo(HashSet()) { dao.knownAlbums(sourceId, it) },
                readAlbums = readAlbums,
                haveArtists = rows.artists.map { it.id }.chunked(900).flatMapTo(HashSet()) { dao.knownArtists(it) },
            )
            if (wanted.tracks.isEmpty() && wanted.albums.isEmpty()) return@withTransaction false
            var trackSeq = (dao.maxTrackSeq(sourceId) ?: -1L) + 1
            var albumSeq = (dao.maxAlbumSeq(sourceId) ?: -1L) + 1
            var artistSeq = (dao.maxArtistSeq(sourceId) ?: -1L) + 1
            // An album read whole again keeps its place.
            val kept = wanted.albums.map { it.id }.chunked(900).flatMap { dao.albumSeqs(it) }.associate { it.id to it.seq }
            val artists = wanted.artists.map { it.copy(seq = artistSeq++) }
            val albums = wanted.albums.map { it.copy(seq = kept[it.id] ?: albumSeq++) }
            val tracks = wanted.tracks.map { it.copy(seq = trackSeq++).withKeys() }
            dao.insertSourceArtists(artists)
            dao.upsertSourceAlbums(albums)
            dao.insertSourceTracks(tracks)
            dao.ensureMeta(sourceId)
            dao.bumpStamp(sourceId)
            val meta = dao.meta(sourceId)
            if (meta != null && (meta.stage == STAGE_BUILDING || meta.stage == STAGE_READY)) {
                dao.logTakeIns(
                    tracks.map { TakeInLogEntity(meta.generation, "track", it.id) } +
                        albums.map { TakeInLogEntity(meta.generation, "album", it.id) } +
                        artists.map { TakeInLogEntity(meta.generation, "artist", it.id) },
                )
            }
            true
        }
    }

    // Gives every listed album its place in the source, in the order of the
    // server's list, a page of the list at a time; answers the last place
    // given, or -1 with none listed.
    private suspend fun placeListed(sourceId: String): Long {
        val places = places(sourceId, "source_album", LISTED_TO_COME)
        val work = db.workDao()
        var last = -1L
        var after = -1L
        while (true) {
            val page = dao.syncAlbumOrder(after, LISTED_WALK)
            if (page.isEmpty()) return last
            val seqs = places.of(page.map { "$sourceId:${it.id}" })
            db.withTransaction {
                page.forEachIndexed { i, row -> dao.setSyncAlbumPlace(row.id, seqs[i]) }
                work.addMoved(sourceId, 0, places.takeMoved(), 0)
            }
            last = seqs.last()
            after = page.last().seq
        }
    }

    // The keys of every album with songs, in the order of each one's first song.
    private suspend fun albumKeysInOrder(): LinkedHashSet<String> {
        val keys = LinkedHashSet<String>()
        var after = 0L
        while (true) {
            val page = dao.songAlbumKeys(after, GIANT_ALBUM)
            if (page.isEmpty()) return keys
            page.forEach { keys += it.albumKey }
            after = page.last().seq
        }
    }

    // An album too big to put in order in memory: its songs' order keys go
    // to sync_order a page at a time, and are read back in order a page at
    // a time to build its rows. `order` is its order among the albums,
    // `place` its place from the walk of the list, null when the list does
    // not hold it.
    private suspend fun giant(
        sourceId: String,
        owner: String,
        key: String,
        listed: Album?,
        order: Long,
        place: Long?,
        artistMusicIds: Map<String, String>,
        built: Built,
        songPlaces: Places,
        unlistedPlaces: Places,
    ) {
        val work = db.workDao()
        var first: Song? = null
        var count = 0
        var duration = 0
        var year: Int? = null
        var created: String? = null
        var after = -1L
        while (true) {
            val page = dao.albumSongPage(key, after, PAGE)
            if (page.isEmpty()) break
            val songs = page.map { LibraryJson.decodeSong(it.json) }
            dao.insertSyncOrder(
                page.zip(songs) { row, song ->
                    SyncOrderEntity(owner, songDiscKey(song), songTrackKey(song), sortKeyHex(sortKey(song.title)), row.seq, song.id)
                },
            )
            for (song in songs) {
                if (first == null) first = song
                count++
                duration += song.duration
                if (year == null) year = song.year?.takeIf { it > 0 }
                val songCreated = song.created
                if (songCreated != null && (created == null || songCreated > created)) created = songCreated
            }
            after = page.last().seq
        }
        val record = listed ?: albumFromSongTally(key, checkNotNull(first), count, duration, year, created)
        val head = dao.firstOrdered(owner, 1).first()
        val cover = LibraryJson.decodeSong(dao.syncSongsByIds(listOf(head.songId)).single().json).coverArt
        val album = serverAlbumRow(sourceId, record, cover).copy(seq = order)
        val placed = album.copy(seq = place ?: unlistedPlaces.of(listOf(album.id)).single())
        db.withTransaction {
            dao.insertStageAlbums(listOf(StageAlbumEntity(placed)))
            work.addMoved(sourceId, 0, unlistedPlaces.takeMoved(), 0)
        }
        var index = 0
        var page = dao.firstOrdered(owner, PAGE)
        while (page.isNotEmpty()) {
            val byId = dao.syncSongsByIds(page.map { it.songId }).associate { it.songId to LibraryJson.decodeSong(it.json) }
            val tracks = songPlaces.placeSongs(
                page.map { row -> serverTrackRow(sourceId, byId.getValue(row.songId), album, record, index++, artistMusicIds).withKeys() },
            )
            db.withTransaction {
                dao.insertStageTracks(tracks.map(::StageTrackEntity))
                work.addMoved(sourceId, songPlaces.takeMoved(), 0, 0)
            }
            val last = page.last()
            page = dao.orderedAfter(
                SimpleSQLiteQuery(CopySql.ORDERED_AFTER, arrayOf<Any>(owner, last.discKey, last.trackKey, last.sortKeyHex, last.seq, PAGE)),
            )
        }
        db.withTransaction {
            dao.deleteSyncOrder(owner)
            dao.deleteAlbumSongs(listOf(key))
        }
        built.album(album, count)
    }

    // Listed albums with no songs, in list order, each at its place from the
    // walk of the list.
    private suspend fun songlessAlbums(sourceId: String, withSongs: Set<String>, built: Built) {
        var after = -1L
        while (true) {
            val page = dao.syncAlbumPage(after, PAGE)
            if (page.isEmpty()) return
            val rows = page.filter { it.id !in withSongs }
                .map { row -> row to serverAlbumRow(sourceId, LibraryJson.decodeAlbum(row.json), null).copy(seq = row.seq) }
            if (rows.isNotEmpty()) {
                dao.insertStageAlbums(rows.map { (row, album) -> StageAlbumEntity(album.copy(seq = checkNotNull(row.place) { "listed album ${row.id} has no place" })) })
            }
            rows.forEach { (_, album) -> built.album(album, 0) }
            after = page.last().seq
        }
    }

    // The album artists, in the order of each one's first album.
    private suspend fun writeArtists(sourceId: String, listedArtists: Map<String, Artist>, built: Built) {
        val ordered = built.byArtist.values.sortedBy { it.firstSeq }
        val places = places(sourceId, "source_artist", STAGE_ARTIST_TO_COME)
        val work = db.workDao()
        for (chunk in ordered.chunked(PAGE)) {
            val rows = places.placeArtists(
                chunk.map { tally ->
                    serverArtistRow(
                        sourceId,
                        tally.id,
                        listedArtists[tally.id],
                        firstAlbumArtist = tally.firstName,
                        albumCount = tally.albums,
                        songCount = tally.songs,
                        newestAlbumArtwork = tally.newestArtwork,
                    )
                },
            )
            db.withTransaction {
                dao.insertStageArtists(rows.map(::StageArtistEntity))
                work.addMoved(sourceId, 0, 0, places.takeMoved())
            }
        }
        built.artists = ordered.size
    }

    private suspend fun flush(sourceId: String, batch: Batch, songPlaces: Places, unlistedPlaces: Places) {
        if (batch.albums.isEmpty()) return
        val tracks = songPlaces.placeSongs(batch.tracks)
        val albums = albumsAtPlaces(batch, unlistedPlaces)
        val work = db.workDao()
        db.withTransaction {
            dao.insertStageAlbums(albums.map(::StageAlbumEntity))
            dao.insertStageTracks(tracks.map(::StageTrackEntity))
            batch.keys.chunked(PAGE).forEach { dao.deleteAlbumSongs(it) }
            work.addMoved(sourceId, songPlaces.takeMoved(), unlistedPlaces.takeMoved(), 0)
        }
        batch.clear()
    }

    // The batch's albums at their places: a listed one's from the walk of
    // the list, the others' from `unlisted`, in the order they were built.
    private suspend fun albumsAtPlaces(batch: Batch, unlisted: Places): List<SourceAlbumEntity> {
        val made = batch.albums.indices.filter { batch.places[it] == null }
        val seqs = unlisted.of(made.map { batch.albums[it].id })
        val at = HashMap<Int, Long>()
        made.forEachIndexed { k, i -> at[i] = seqs[k] }
        return batch.albums.mapIndexed { i, album -> album.copy(seq = batch.places[i] ?: at.getValue(i)) }
    }

    // Clears the stage when it is still this copy's and no merge claimed it,
    // and the copy's pages in any case, each in the background a page at a
    // time: the stage is marked clearing, so the source's next copy waits
    // for it, and every next copy waits for the pages (awaitPagesClear).
    private suspend fun clearUnclaimed(sourceId: String, generation: Long) {
        val unclaimed = db.withTransaction {
            val meta = dao.meta(sourceId)
            val ours = meta != null && meta.generation == generation && (meta.stage == STAGE_BUILDING || meta.stage == STAGE_READY)
            if (ours) {
                dao.markNone(sourceId)
                db.workDao().markStageClearing(sourceId)
                endRatingNotes(sourceId)
            }
            ours
        }
        if (unclaimed) merge.clearStageLater(sourceId)
        merge.clearLater(COPY_PAGES_CLEAR) { clearPages(sourceId) }
    }

    // What a copy's own work tables hold from before its start, beside its
    // pages: giant albums' order rows of any copy, and the stars an earlier
    // copy's steps read. Each goes STAGE_CLEAR_ROWS a transaction. Copies and
    // the steps after them run one at a time (ServerSync's job), so nothing
    // reads these while a copy starts.
    private suspend fun clearStartWork() = withContext(WriteLabels.of(COPY_START_CLEAR)) {
        for (page in listOf(dao::deleteAnySyncOrderPage, dao::deleteSyncStarredPage)) {
            while (true) {
                val gone = db.withTransaction { page(STAGE_CLEAR_ROWS) }
                if (gone < STAGE_CLEAR_ROWS) break
            }
        }
    }

    // Waits until the last copy's pages of the server's library are gone:
    // the clear running, or, with pages left (a clear that failed), one now.
    private suspend fun awaitPagesClear(sourceId: String) {
        merge.awaitClear(COPY_PAGES_CLEAR)
        if (dao.hasSyncPages()) merge.clearLater(COPY_PAGES_CLEAR) { clearPages(sourceId) }.await()
    }

    // The copy's pages go STAGE_CLEAR_ROWS a transaction, so no write holds
    // the lock for a whole library's pages. A delete that fails leaves
    // pages, and the next copy clears what is left first.
    private suspend fun clearPages(sourceId: String) {
        try {
            val rows = withContext(WriteLabels.of(COPY_PAGES_CLEAR)) {
                val pages = listOf<suspend () -> Int>(
                    { dao.deleteSyncSongPage(STAGE_CLEAR_ROWS) },
                    { dao.deleteSyncAlbumPage(STAGE_CLEAR_ROWS) },
                    { dao.deleteSyncArtistPage(STAGE_CLEAR_ROWS) },
                    { dao.deleteSyncOrderPage("$sourceId#", STAGE_CLEAR_ROWS) },
                )
                var rows = 0L
                for (page in pages) {
                    while (true) {
                        val gone = page()
                        rows += gone
                        merge.hooks.clearPage(COPY_PAGES_CLEAR)
                        if (gone < STAGE_CLEAR_ROWS) break
                    }
                }
                rows
            }
            merge.hooks.cleared(COPY_PAGES_CLEAR, rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Octo", "copy: its pages of the server's library stay for now: ${e.javaClass.simpleName}")
        }
    }

    // Rating notes kept for a copy end with it, unless another server's
    // copy is still on its way. The phone's copy brings no server ratings.
    private suspend fun endRatingNotes(sourceId: String) {
        val work = db.workDao()
        if (work.metas().none { it.sourceId != sourceId && it.sourceId != DEVICE && it.stage != STAGE_NONE }) work.clearRatingNotes()
    }

    // What the read still needs room for: the next pages, and the stage of
    // everything read by then.
    private fun readNeed(songs: Long): Long {
        val ahead = PAGES_PER_SPACE_CHECK.toLong() * LIBRARY_PAGE
        return 2 * (ahead * SYNC_BYTES_PER_SONG + (songs + ahead) * STAGE_BYTES_PER_SONG)
    }

    private fun checkSpace(need: Long) {
        lastNeed = need
        if (freeSpace.bytes() < need) throw NotEnoughSpace(need)
    }

    // Albums built and not yet written: each at its order among the albums,
    // with its place from the walk of the list, null for one the list does
    // not hold (placed as it is written).
    private class Batch {
        val keys = ArrayList<String>()
        val albums = ArrayList<SourceAlbumEntity>()
        val places = ArrayList<Long?>()
        val tracks = ArrayList<SourceTrackEntity>()

        fun add(key: String, album: SourceAlbumEntity, place: Long?, rows: List<SourceTrackEntity>) {
            keys += key
            albums += album
            places += place
            tracks += rows
        }

        fun clear() {
            keys.clear()
            albums.clear()
            places.clear()
            tracks.clear()
        }
    }

    // What a build made so far: counts, the next made up album's place, and
    // each artist's albums.
    private class Built {
        var tracks = 0
        var albums = 0
        var artists = 0
        var unlisted = 0L
        val byArtist = HashMap<String, ArtistTally>()

        fun album(album: SourceAlbumEntity, songs: Int) {
            albums++
            tracks += songs
            val tally = byArtist.getOrPut(album.artistId) { ArtistTally(album.artistId, album.seq, album.artist, album.addedAt, album.seq, album.artwork) }
            tally.albums++
            tally.songs += songs
            if (album.seq < tally.firstSeq) {
                tally.firstSeq = album.seq
                tally.firstName = album.artist
            }
            // The most recently added album; of two added at once, the first.
            if (album.addedAt > tally.newestAt || (album.addedAt == tally.newestAt && album.seq < tally.newestSeq)) {
                tally.newestAt = album.addedAt
                tally.newestSeq = album.seq
                tally.newestArtwork = album.artwork
            }
        }
    }

    // One album artist while the build runs: its first album's place and
    // artist name, its newest album's time, place and cover, and its counts.
    private class ArtistTally(
        val id: String,
        var firstSeq: Long,
        var firstName: String,
        var newestAt: Long,
        var newestSeq: Long,
        var newestArtwork: String?,
    ) {
        var albums = 0
        var songs = 0
    }
}

// One copy of a server's library and the steps that follow it, start to
// end: the copy (unless only the steps are needed again), each step in
// order, then the account's record says the copy is done.
class CopyRun(
    private val copy: StagedCopy,
    private val records: CopyDao,
    private val afterSync: List<suspend () -> Unit>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // `swapped` runs once a whole copy is in the library, before the steps
    // after it. `report` hears how far the copy is, and that it is in the
    // library before anything after it can fail.
    suspend fun run(
        account: String,
        sourceId: String,
        client: SubsonicClient,
        copyAll: Boolean,
        swapped: suspend () -> Unit,
        report: CopyReport = CopyReport.None,
    ): CopyDone {
        // When the server last read its folders, before the copy, so a look
        // afterwards reads on only once it has read them again.
        val scan = if (copyAll) lastScanOf(client) else null
        if (copyAll) copy.copy(account, sourceId, client, report, scan)
        return complete(account, sourceId, copied = copyAll, scan, swapped, report)
    }

    // A whole copy is in the library (`copied`), from its own run or a
    // resume of its save: `report` hears it, the account's record says so,
    // and `swapped` runs; then the steps after a copy, in order, and the
    // record takes the copy's time, counts and `scan` (kept as it was when
    // only the steps ran).
    suspend fun complete(
        account: String,
        sourceId: String,
        copied: Boolean,
        scan: String?,
        swapped: suspend () -> Unit,
        report: CopyReport = CopyReport.None,
    ): CopyDone {
        if (copied) {
            report.swapped()
            records.markSwapped(account, ROWS_VERSION)
            swapped()
        }
        afterSync.forEach { it() }
        val songs = records.liveTrackCount(sourceId)
        val albums = records.liveAlbumCount(sourceId)
        records.finish(account, sourceId, clock(), songs, albums, scan, keepScan = !copied)
        return CopyDone(songs, albums)
    }
}

// How much of the server's library the library holds after a copy.
data class CopyDone(val songs: Int, val albums: Int)
