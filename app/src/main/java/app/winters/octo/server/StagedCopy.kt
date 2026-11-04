package app.winters.octo.server

import android.database.sqlite.SQLiteFullException
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.winters.octo.catalog.AccountSyncEntity
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.STAGE_BUILDING
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
import app.winters.octo.catalog.sortKey
import app.winters.octo.catalog.withKeys
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.LIBRARY_PAGE
import app.winters.octo.subsonic.LibraryJson
import app.winters.octo.subsonic.LibrarySink
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.readLibraryInto
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// How many rows a copy reads or writes at a time once the server's pages are in.
private const val PAGE = 500

// Songs built and written together. An album with more is put in order
// through the database instead of in memory.
internal const val GIANT_ALBUM = 2_000

// How many song pages a copy reads between looks at the free space.
private const val PAGES_PER_SPACE_CHECK = 10

// How much the database grows for each song a copy reads (the server's
// pages as they came, with their indexes) and for each song it builds (the
// stage's rows, with their indexes), measured on generated libraries
// (StagedCopyTest.diskConstantsHoldWithinTenPercent). Free space is checked
// against twice what is still to come.
internal const val SYNC_BYTES_PER_SONG = 400L
internal const val STAGE_BYTES_PER_SONG = 600L

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

    // The free space the last check asked for, for wording a full disk.
    @Volatile private var lastNeed = 0L

    // Copies the library and waits until the merge swapped it in. Throws
    // StageAborted when the merge could not swap it, NotEnoughSpace when the
    // phone runs short of room.
    suspend fun copy(account: String, sourceId: String, client: SubsonicClient): CopyCounts {
        val generation = start(account, sourceId)
        try {
            val counts = fill(account, sourceId, generation, client)
            reached(CopyPoint.Ready)
            merge.rebuild()
            merge.awaitStage(sourceId, generation)
            return counts
        } catch (e: SQLiteFullException) {
            throw NotEnoughSpace(maxOf(lastNeed, 1))
        } finally {
            withContext(NonCancellable) { clearUnclaimed(sourceId, generation) }
            merge.forgetStage(sourceId, generation)
        }
    }

    // The last generation this process gave a copy, so none is given twice
    // even after a source and its rows are removed and added again.
    private val issued = AtomicLong(0)

    // Starts a stage for the source, once the merge holds no claim on one:
    // clears what an earlier copy left and takes a new generation, from
    // which on music taken in is noted for this stage.
    @VisibleForTesting
    internal suspend fun start(account: String, sourceId: String): Long = merge.sourceWrite {
        db.withTransaction {
            dao.clearStage(sourceId)
            dao.clearSync(sourceId)
            dao.ensureMeta(sourceId)
            endRatingNotes(sourceId)
            val generation = maxOf(dao.lastGeneration() ?: 0L, issued.get()) + 1
            issued.set(generation)
            dao.markBuilding(sourceId, generation, account)
            generation
        }.also { generation -> merge.forgetStagesBefore(sourceId, generation) }
    }

    // Reads the server into the stage and marks it ready for the merge.
    @VisibleForTesting
    internal suspend fun fill(account: String, sourceId: String, generation: Long, client: SubsonicClient): CopyCounts {
        val songs = read(client)
        val counts = build(sourceId, generation, songs)
        db.withTransaction {
            check(dao.markReady(sourceId, generation, counts.tracks, counts.albums, counts.artists) == 1) {
                "The copy's stage for $sourceId is gone"
            }
            dao.ensureAccountSync(account, sourceId, ROWS_VERSION)
        }
        return counts
    }

    // Keeps each page of the server's library as it arrives, one transaction
    // a page. Answers how many songs came.
    @VisibleForTesting
    internal suspend fun read(client: SubsonicClient): Long {
        var songs = 0L
        var pages = 0
        checkSpace(readNeed(0))
        client.readLibraryInto(
            object : LibrarySink {
                override suspend fun albums(page: List<Album>, offset: Int) {
                    dao.insertSyncAlbums(page.mapIndexed { i, album -> SyncAlbumEntity(album.id, (offset + i).toLong(), LibraryJson.encodeAlbum(album)) })
                }

                override suspend fun artists(artists: List<Artist>) {
                    artists.withIndex().chunked(PAGE).forEach { chunk ->
                        dao.insertSyncArtists(chunk.map { (i, artist) -> SyncArtistEntity(artist.id, i.toLong(), LibraryJson.encodeArtist(artist)) })
                    }
                }

                override suspend fun songs(page: List<Song>) {
                    dao.insertSyncSongs(page.map { SyncSongEntity(songId = it.id, albumKey = serverAlbumKey(it), json = LibraryJson.encodeSong(it)) })
                    songs += page.size
                    if (++pages % PAGES_PER_SPACE_CHECK == 0) checkSpace(readNeed(songs))
                    reached(CopyPoint.Page)
                }
            },
        )
        return dao.syncSongCount().toLong()
    }

    // Builds the stage album by album in the order of each album's first
    // song, writing as it goes, so only one album (or one page of a giant
    // one), the albums' keys and the artists are held at once.
    @VisibleForTesting
    internal suspend fun build(sourceId: String, generation: Long, songs: Long): CopyCounts {
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
        // Listed albums keep their place in the server's list; albums made up
        // from songs follow, in the order of their first songs.
        val firstUnlistedSeq = (dao.maxSyncAlbumSeq() ?: -1L) + 1
        val built = Built()
        val batch = Batch()
        for (key in albumKeys) {
            val listedRow = dao.syncAlbum(key)
            val listed = listedRow?.let { LibraryJson.decodeAlbum(it.json) }
            val albumSeq = listedRow?.seq ?: (firstUnlistedSeq + built.unlisted++)
            if (dao.albumSongCount(key) > GIANT_ALBUM) {
                flush(batch)
                giant(sourceId, owner, key, listed, albumSeq, artistMusicIds, built)
                continue
            }
            val inAlbum = dao.albumSongs(key).map { LibraryJson.decodeSong(it.json) }
            val record = listed ?: albumFromSongs(key, inAlbum)
            val ordered = inAlbumOrder(inAlbum)
            val album = serverAlbumRow(sourceId, record, ordered.first().coverArt).copy(seq = albumSeq)
            val tracks = ordered.mapIndexed { index, song ->
                serverTrackRow(sourceId, song, album, record, index, artistMusicIds).copy(seq = built.nextTrackSeq++).withKeys()
            }
            if (batch.tracks.size + tracks.size > GIANT_ALBUM) flush(batch)
            batch.add(key, album, tracks)
            built.album(album, tracks.size)
        }
        flush(batch)
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
    // a time to build its rows.
    private suspend fun giant(
        sourceId: String,
        owner: String,
        key: String,
        listed: Album?,
        albumSeq: Long,
        artistMusicIds: Map<String, String>,
        built: Built,
    ) {
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
        val album = serverAlbumRow(sourceId, record, cover).copy(seq = albumSeq)
        dao.insertStageAlbums(listOf(StageAlbumEntity(album)))
        var index = 0
        var page = dao.firstOrdered(owner, PAGE)
        while (page.isNotEmpty()) {
            val byId = dao.syncSongsByIds(page.map { it.songId }).associate { it.songId to LibraryJson.decodeSong(it.json) }
            val tracks = page.map { order ->
                serverTrackRow(sourceId, byId.getValue(order.songId), album, record, index++, artistMusicIds)
                    .copy(seq = built.nextTrackSeq++)
                    .withKeys()
            }
            dao.insertStageTracks(tracks.map(::StageTrackEntity))
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

    // Listed albums with no songs, in list order.
    private suspend fun songlessAlbums(sourceId: String, withSongs: Set<String>, built: Built) {
        var after = -1L
        while (true) {
            val page = dao.syncAlbumPage(after, PAGE)
            if (page.isEmpty()) return
            val rows = page.filter { it.id !in withSongs }
                .map { row -> serverAlbumRow(sourceId, LibraryJson.decodeAlbum(row.json), null).copy(seq = row.seq) }
            if (rows.isNotEmpty()) dao.insertStageAlbums(rows.map(::StageAlbumEntity))
            rows.forEach { built.album(it, 0) }
            after = page.last().seq
        }
    }

    // The album artists, in the order of each one's first album.
    private suspend fun writeArtists(sourceId: String, listedArtists: Map<String, Artist>, built: Built) {
        val ordered = built.byArtist.values.sortedBy { it.firstSeq }
        var seq = 0L
        for (chunk in ordered.chunked(PAGE)) {
            dao.insertStageArtists(
                chunk.map { tally ->
                    StageArtistEntity(
                        serverArtistRow(
                            sourceId,
                            tally.id,
                            listedArtists[tally.id],
                            firstAlbumArtist = tally.firstName,
                            albumCount = tally.albums,
                            songCount = tally.songs,
                            newestAlbumArtwork = tally.newestArtwork,
                        ).copy(seq = seq++),
                    )
                },
            )
        }
        built.artists = ordered.size
    }

    private suspend fun flush(batch: Batch) {
        if (batch.albums.isEmpty()) return
        db.withTransaction {
            dao.insertStageAlbums(batch.albums.map(::StageAlbumEntity))
            dao.insertStageTracks(batch.tracks.map(::StageTrackEntity))
            batch.keys.chunked(PAGE).forEach { dao.deleteAlbumSongs(it) }
        }
        batch.clear()
    }

    // Clears the stage when it is still this copy's and no merge claimed it,
    // and the copy's pages in any case.
    private suspend fun clearUnclaimed(sourceId: String, generation: Long) = db.withTransaction {
        val meta = dao.meta(sourceId)
        if (meta != null && meta.generation == generation && (meta.stage == STAGE_BUILDING || meta.stage == STAGE_READY)) {
            dao.clearStage(sourceId)
            dao.markNone(sourceId)
            endRatingNotes(sourceId)
        }
        dao.clearSync(sourceId)
    }

    // Rating notes kept for a copy end with it, unless another source's
    // copy is still on its way.
    private suspend fun endRatingNotes(sourceId: String) {
        val work = db.workDao()
        if (work.metas().none { it.sourceId != sourceId && it.stage != STAGE_NONE }) work.clearRatingNotes()
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

    // Albums built and not yet written.
    private class Batch {
        val keys = ArrayList<String>()
        val albums = ArrayList<SourceAlbumEntity>()
        val tracks = ArrayList<SourceTrackEntity>()

        fun add(key: String, album: SourceAlbumEntity, rows: List<SourceTrackEntity>) {
            keys += key
            albums += album
            tracks += rows
        }

        fun clear() {
            keys.clear()
            albums.clear()
            tracks.clear()
        }
    }

    // What a build made so far: counts, the next song's place, the next made
    // up album's, and each artist's albums.
    private class Built {
        var tracks = 0
        var albums = 0
        var artists = 0
        var nextTrackSeq = 0L
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
    // after it.
    suspend fun run(account: String, sourceId: String, client: SubsonicClient, copyAll: Boolean, swapped: suspend () -> Unit = {}): CopyDone {
        // When the server last read its folders, before the copy, so a look
        // afterwards reads on only once it has read them again.
        val scan = if (copyAll) lastScanOf(client) else null
        if (copyAll) {
            copy.copy(account, sourceId, client)
            records.markSwapped(account, ROWS_VERSION)
            swapped()
        }
        afterSync.forEach { it() }
        val songs = records.liveTrackCount(sourceId)
        val albums = records.liveAlbumCount(sourceId)
        records.finish(account, sourceId, clock(), songs, albums, scan, keepScan = !copyAll)
        return CopyDone(songs, albums)
    }
}

// How much of the server's library the library holds after a copy.
data class CopyDone(val songs: Int, val albums: Int)
