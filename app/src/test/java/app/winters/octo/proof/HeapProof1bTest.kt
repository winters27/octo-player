package app.winters.octo.proof

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.backup.Backup
import app.winters.octo.backup.PlayerBackup
import app.winters.octo.backup.BackupOpened
import app.winters.octo.backup.BackupSave
import app.winters.octo.backup.BackupSongs
import app.winters.octo.catalog.CATALOG_FILE
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.Flip
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.MergeHooks
import app.winters.octo.catalog.PlaylistEdits
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.STAGE_CLEAR_ROWS
import app.winters.octo.catalog.SongToAdd
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.StageAborted
import app.winters.octo.catalog.StageProgress
import app.winters.octo.catalog.WorkDao
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.MERGE_PAGE
import app.winters.octo.catalog.SAVE_APPLY
import app.winters.octo.catalog.SAVE_COST_TABLES
import app.winters.octo.catalog.SAVE_FIND
import app.winters.octo.catalog.SAVE_GROUP_ENTRIES
import app.winters.octo.catalog.SAVE_GROUP_FLOOR
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.SaveKind
import app.winters.octo.catalog.SaveClaim
import app.winters.octo.catalog.SaveCosts
import app.winters.octo.catalog.SaveFind
import app.winters.octo.catalog.SaveOracle
import app.winters.octo.catalog.TodayClaim
import app.winters.octo.catalog.boundMerge
import app.winters.octo.catalog.claimAndBuild
import app.winters.octo.catalog.copyCatalog
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.read
import app.winters.octo.catalog.readLong
import app.winters.octo.catalog.todayAdoptions
import app.winters.octo.catalog.todayKeys
import app.winters.octo.catalog.todaySwap
import androidx.room.Room
import androidx.room.RoomDatabase
import app.winters.octo.catalog.MIGRATION_18_19
import app.winters.octo.catalog.RENAME_SONG
import app.winters.octo.testing.CountingOpenHelperFactory
import app.winters.octo.testing.OldSqlite
import app.winters.octo.testing.testContext
import app.winters.octo.catalog.STAGE_CLAIMED
import app.winters.octo.catalog.SongIds
import app.winters.octo.discovery.SERVER_PAGE
import app.winters.octo.discovery.ServerHooks
import app.winters.octo.discovery.drainedEntries
import app.winters.octo.discovery.serverRoom
import app.winters.octo.livelists.LIVE_PAGE
import app.winters.octo.playback.FindMap
import app.winters.octo.playback.QUEUE_START_WRITE
import app.winters.octo.playback.RatingStore
import app.winters.octo.playback.RealLengths
import kotlinx.coroutines.CompletableDeferred
import app.winters.octo.server.CopyReport
import app.winters.octo.server.CopyStep
import app.winters.octo.server.STAGE_BYTES_PER_SONG
import app.winters.octo.server.SYNC_BYTES_PER_SONG
import app.winters.octo.subsonic.LIBRARY_PAGE
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.discovery.asTrack
import app.winters.octo.output.OutputSwitch
import app.winters.octo.output.QueueState
import app.winters.octo.output.RemotePlayer
import app.winters.octo.playback.AnyMedia
import app.winters.octo.playback.FillHooks
import app.winters.octo.playback.Floors
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.ListStart
import app.winters.octo.playback.OctoPlayer
import app.winters.octo.playback.OnlineKind
import app.winters.octo.playback.PlayAnswer
import app.winters.octo.playback.PlayGuard
import app.winters.octo.playback.PlayRequest
import app.winters.octo.playback.QueueBuilds
import app.winters.octo.playback.QueueOrder
import app.winters.octo.playback.QueueStarts
import app.winters.octo.playback.QueueWindow
import app.winters.octo.playback.QuietDevice
import app.winters.octo.playback.SK_FIRST
import app.winters.octo.playback.WindowSizes
import app.winters.octo.playback.entryId
import app.winters.octo.playback.entryOf
import app.winters.octo.playback.entryOfKey
import app.winters.octo.playback.listOfEntry
import app.winters.octo.playback.songOf
import app.winters.octo.playback.upcoming
import app.winters.octo.server.NEW_MUSIC_LOOK_MS
import app.winters.octo.server.NewMusic
import app.winters.octo.server.asListed
import app.winters.octo.server.buildServerCatalog
import app.winters.octo.server.lastScanOf
import app.winters.octo.server.lookForNewMusic
import app.winters.octo.server.newMusicLookDue
import app.winters.octo.subsonic.Library
import app.winters.octo.ui.paging.ListLoaders
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.write
import app.winters.octo.data.Drains
import app.winters.octo.data.Exports
import app.winters.octo.data.Session
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.ServerReads
import app.winters.octo.discovery.ServerSide
import app.winters.octo.health.HealthCheck
import app.winters.octo.listening.HISTORY_RECENT
import app.winters.octo.listening.ListeningStore
import app.winters.octo.listening.ListeningSync
import app.winters.octo.listening.MemoryPrefs
import app.winters.octo.listening.PlayHistory
import app.winters.octo.listening.PlayTotals
import app.winters.octo.listening.TestRebuilds
import app.winters.octo.listening.value
import app.winters.octo.livelists.LiveHooks
import app.winters.octo.livelists.LivePicks
import app.winters.octo.livelists.LiveShown
import app.winters.octo.offline.RulesRig
import app.winters.octo.playback.ArtistOrder
import app.winters.octo.playback.AutoplayPicker
import app.winters.octo.playback.CarNode
import app.winters.octo.playback.CarRig
import app.winters.octo.playback.LibraryRadio
import app.winters.octo.playback.QueueStore
import app.winters.octo.playback.parseCarId
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.PlaylistSummaries
import app.winters.octo.playback.RADIO_LENGTH
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.playlists.M3uEntry
import app.winters.octo.playlists.MatchJobs
import app.winters.octo.playlists.MatchKind
import app.winters.octo.playlists.MatchSong
import app.winters.octo.playlists.PLAYLIST_JSON_BYTES
import app.winters.octo.playlists.PLAYLIST_STEP_BYTES
import app.winters.octo.playlists.PlaylistFiles
import app.winters.octo.playlists.PlaylistReads
import app.winters.octo.playlists.PlaylistStep
import app.winters.octo.playlists.PlaylistSteps
import app.winters.octo.playlists.PlaylistWalks
import app.winters.octo.playlists.ReadHooks
import app.winters.octo.playlists.StepHooks
import app.winters.octo.playlists.SyncServer
import app.winters.octo.playlists.stampOf
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryOp
import app.winters.octo.query.QueryRule
import app.winters.octo.query.QuerySort
import app.winters.octo.server.CopyTracker
import app.winters.octo.server.StagedCopy
import app.winters.octo.sort.DownloadSort
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.Counted
import app.winters.octo.testing.FakeMediaStore
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.album.copyIsStale
import app.winters.octo.ui.album.libraryOtherDisc
import app.winters.octo.catalog.copiesInChunks
import app.winters.octo.data.serverSongIds
import app.winters.octo.ui.library.health.FixAllPlans
import app.winters.octo.ui.library.health.HealthBuild
import app.winters.octo.ui.library.health.HealthShown
import app.winters.octo.ui.library.health.TrackHealth
import app.winters.octo.ui.library.health.joinPlanOf
import app.winters.octo.ui.library.health.splitAlbumOf
import app.winters.octo.ui.paging.AlbumListing
import app.winters.octo.ui.paging.ArtistLoaderSource
import app.winters.octo.ui.paging.DownloadsLists
import app.winters.octo.ui.paging.FilterInputs
import app.winters.octo.ui.paging.FilteredLoader
import app.winters.octo.ui.paging.GroupsLoader
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.PhoneFolder
import app.winters.octo.ui.paging.PhoneFolders
import app.winters.octo.ui.paging.PlaylistLoader
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SearchLoader
import app.winters.octo.ui.paging.SongLists
import app.winters.octo.ui.paging.albumCopyLoader
import app.winters.octo.ui.paging.albumLibraryLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.Closeable
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import java.util.Collections
import kotlin.random.Random

// The proof library: 200,000 songs, half of them in "Unknown album" and the
// rest five to an album in 20,000 albums (each odd album the second part of
// the one before), 5,000 artists, 120,000 songs in one genre, 50,000
// starred, server plays on six songs in seven, and the server's playlists.
private const val PROOF_SONGS = 200_000
private const val PROOF_STARRED = 50_000
private const val BIG_GENRE = 120_000
private const val PAGE = 60

// The server list G4 reads, and the M3U file it imports.
private const val SERVER_LIST = 100_000
private const val M3U_LINES = 80_000

// Round 1b's heap proof: every reader of a big library at 200,000 songs in a
// heap the size a phone gives an app (-PoctoHeapProof, 256 MB), one section
// a test. Each run of a section is its own JVM when run alone (the build's
// forkEvery, or one --tests filter a run). Each prints a RESULT line with
// its peak heap: the library it is given (its setup) and its own steps are
// measured apart, and the most rows one statement read during its steps,
// counted at the database. A section passes by finishing under the cap.
// Timings at proof size print `RESULT time-1b` lines beside them.
@RunWith(RobolectricTestRunner::class)
class HeapProof1bTest {
    // Part A: the copy, the merge and the steps after it, the totals' first
    // build, Home's shelves and History's lists, then an everyday sync (the
    // server read its folders again) and Home after it; both swaps timed.
    @Test
    fun copyAndHomeAtProofSize() = proof("copy-home") { steps ->
        ProofWorld().use { w ->
            steps.started(w.db)
            steps.step("first copy") { w.copy(arriving = true) }
            val first = w.swaps.last()
            steps.step("steps after the copy") { w.afterSync() }
            assertEquals(PROOF_SONGS.toLong(), w.db.readLong("SELECT COUNT(*) FROM track"))
            val totals = w.totals()
            steps.step("the totals' first build") {
                totals.start()
                totals.builtThrough.first { it >= 0 }
            }
            val history = w.history(totals)
            val shelves = steps.step("Home's shelves") { value(history.shelves.first()) }
            assertTrue("most played", shelves.mostPlayed.isNotEmpty())
            steps.step("History, most played in six months") { value(history.mostPlayed(System.currentTimeMillis() - 182 * DAY, 100).first()) }
            steps.step("History, most played ever") { value(history.mostPlayed(null, 100).first()) }
            steps.step("History, recent plays") { history.recentPlays(HISTORY_RECENT).first() }

            w.library.changed = true
            steps.step("everyday sync") { w.copy(arriving = false) }
            val everyday = w.swaps.last()
            steps.step("Home's shelves after the sync") {
                totals.awaitBuilt()
                value(history.shelves.first())
            }
            assertEquals((PROOF_SONGS - PROOF_REMOVED + PROOF_ADDED).toLong(), w.db.readLong("SELECT COUNT(*) FROM track"))
            println("RESULT time-1b first-copy swap ${first.millis} ms (${first.rows} rows; no bound, measured 13,642 ms at 01b11500 before the code)")
            println("RESULT time-1b everyday swap ${everyday.millis} ms (${everyday.rows} rows; no bound)")
            first.report("first-copy")
            everyday.report("everyday")

            // An everyday sync that adds an album the server lists first of
            // all, by an artist of its own: only its rows are new.
            w.library.leadAlbum = true
            steps.step("everyday sync adding a listed album first") { w.copy(arriving = false) }
            val lead = w.swaps.last()
            assertEquals((PROOF_SONGS - PROOF_REMOVED + PROOF_ADDED + PROOF_LEAD_SONGS).toLong(), w.db.readLong("SELECT COUNT(*) FROM track"))
            println("RESULT time-1b lead-album swap ${lead.millis} ms (${lead.rows} rows; no bound)")
            lead.report("lead-album")
            albumLookups(w)
        }
    }

    // Where Split albums' Fix all count spends its time, over the first
    // 2,000 split albums of the report: each album read as its songs are
    // now, its songs' copies on the server, and its join's plan, each part
    // timed on its own.
    private suspend fun fixAllSplitParts(w: ProofWorld, gen: Long) {
        // Read 500 at a time, so the section's most rows read stays the app's.
        val sets = (0 until 4).flatMap { page ->
            w.db.read("SELECT setId FROM health_split WHERE owner = ? AND gen = ? ORDER BY k1, k2, leadAlbum LIMIT 500 OFFSET ?", WorkOwners.REPORT, gen, page * 500) { it.getLong(0) }
        }
        var read = 0L
        var copied = 0L
        var planned = 0L
        var joins = 0
        for (setId in sets) {
            var at = System.nanoTime()
            val album = splitAlbumOf(w.db, gen, setId) ?: continue
            read += System.nanoTime() - at
            at = System.nanoTime()
            val ids = serverSongIds(w.db.sourceDao().copiesInChunks(album.parts.flatMap { part -> part.songs.map { it.id } }), w.source)
            copied += System.nanoTime() - at
            at = System.nanoTime()
            if (joinPlanOf(album, TrackHealth(), ids) != null) joins++
            planned += System.nanoTime() - at
        }
        println(
            "RESULT time-1b SplitAlbums Fix all's count, its parts over ${sets.size} albums: the album read ${read / 1_000_000} ms, " +
                "its copies ${copied / 1_000_000} ms, its join's plan ${planned / 1_000_000} ms ($joins joins; no bound)",
        )
    }

    // A source's albums looked up by the source's own ids, 900 to a
    // statement as their callers chunk them: Discovery's and ServerSync's
    // reads and a copy's take-in. Each timed and planned on the proof
    // library with the index on (sourceId, nativeId), then without it.
    private suspend fun albumLookups(w: ProofWorld) {
        val ids = w.db.read("SELECT nativeId FROM source_album WHERE sourceId = ? ORDER BY rowid DESC LIMIT 900", w.source) { it.getString(0) }
        val albums = w.db.readLong("SELECT COUNT(*) FROM source_album WHERE sourceId = ?", w.source)
        val sql = w.db.openHelper.writableDatabase
        for (indexed in listOf(true, false)) {
            if (!indexed) sql.execSQL("DROP INDEX `index_source_album_sourceId_nativeId`")
            // Planned on the connection that dropped the index, whose schema
            // is current (a pooled reader's may not be read again for a plan).
            val plan = sql.query("EXPLAIN QUERY PLAN SELECT nativeId FROM source_album WHERE sourceId = ? AND nativeId IN (?, ?)", arrayOf<Any?>(w.source, ids[0], ids[1])).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(3)) }
            }
            val label = if (indexed) "with the index" else "without the index"
            println("RESULT time-1b album lookups by nativeId $label, plan: ${plan.joinToString(" / ")} ($albums albums on the source)")
            val known = timed("album lookups by nativeId $label: SourceDao.knownAlbums, ${ids.size} ids", "no bound") { w.db.sourceDao().knownAlbums(w.source, ids) }
            val counts = timed("album lookups by nativeId $label: SourceDao.albumSongCounts, ${ids.size} ids", "no bound") { w.db.sourceDao().albumSongCounts(w.source, ids) }
            val taken = timed("album lookups by nativeId $label: CopyDao.knownAlbums, ${ids.size} ids", "no bound") { w.db.copyDao().knownAlbums(w.source, ids) }
            assertEquals(ids.size, known.size)
            assertEquals(ids.size, counts.size)
            assertEquals(ids.size, taken.size)
        }
        sql.execSQL("CREATE INDEX IF NOT EXISTS `index_source_album_sourceId_nativeId` ON `source_album` (`sourceId`, `nativeId`)")
    }

    // The swaps with the library's indexes as base had them: round 1b's
    // indexes on track and source_track dropped and the three they replaced
    // made again before the first copy, then Part A's copy, the steps after
    // it and its everyday sync, each swap's steps printed. Their lengths
    // beside Part A's say how much of the swap the added indexes take.
    @Test
    fun swapStepsWithBaseIndexesAtProofSize() = proof("swap-base-indexes") { steps ->
        ProofWorld().use { w ->
            steps.setup("round 1b's indexes on track and source_track dropped") {
                val sql = w.db.openHelper.writableDatabase
                for (index in INDEXES_ADDED) sql.execSQL("DROP INDEX `$index`")
                for ((index, column) in INDEXES_REPLACED) sql.execSQL("CREATE INDEX `$index` ON `track` (`$column`)")
            }
            steps.started(w.db)
            steps.step("first copy") { w.copy(arriving = true) }
            val first = w.swaps.last()
            steps.step("steps after the copy") { w.afterSync() }
            w.library.changed = true
            steps.step("everyday sync") { w.copy(arriving = false) }
            val everyday = w.swaps.last()
            assertEquals((PROOF_SONGS - PROOF_REMOVED + PROOF_ADDED).toLong(), w.db.readLong("SELECT COUNT(*) FROM track"))
            first.report("base indexes first-copy")
            everyday.report("base indexes everyday")
        }
    }

    // Parts B and C: likes, offline marks for a page, playlist summaries
    // with the 200,000-entry playlist, and Search with a common word.
    @Test
    fun likesMarksSummariesAndSearchAtProofSize() = proof("likes-marks-summaries-search") { steps ->
        ProofWorld().use { w ->
            steps.setup("library") { w.copy(arriving = true); w.afterSync() }
            val big = steps.setup("a 200,000-song playlist") { w.bigPlaylist() }
            steps.setup("50,000 liked songs downloaded") {
                RulesRig(w.db, w.merge, server = w.source).rules.walk(keepLiked = true, playlists = emptySet(), refused = null)
                w.db.write("UPDATE download SET state = 'Done', progress = 1, path = 'kept'")
            }
            steps.started(w.db)
            val sync = w.listeningSync()
            val likes = LikeStore(w.db.userDao(), w.db.dao(), sync, w.db, app.winters.octo.catalog.SongIds(w.db, w.merge))
            val page = w.db.read("SELECT trackId FROM liked_track ORDER BY likedAt DESC LIMIT $PAGE") { it.getString(0) }
            steps.step("liked flows") {
                assertTrue(likes.likedFlow(page.first()).first())
                assertEquals(page.toSet(), likes.likedAmong(page).first())
                likes.changes.first()
            }
            steps.step("offline marks for a page") {
                assertEquals(page.toSet(), w.db.downloadDao().doneAmong(page).toSet())
                page.forEach { assertTrue(w.db.downloadDao().isDone(it)) }
            }
            steps.step("playlist summaries") {
                val summaries = PlaylistSummaries(w.db, WorkOwners(), MutableStateFlow(w.source), releasePin = PlaylistSets(w.db)::releasePin)
                val shown = summaries.all.first().first { it.id == big }
                assertTrue("${shown.songCount}", shown.songCount >= PROOF_SONGS * 9 / 10)
            }
            steps.step("Search with a common word") {
                // Everything at once, then the Songs filter, as the search
                // screen asks.
                val q = searchKey(PROOF_COMMON_WORD)
                w.db.dao().searchArtists(q, 11)
                w.db.dao().searchAlbums(q, 21)
                assertEquals(51, w.db.dao().searchTracks(q, 51).size)
                assertEquals(1_001, w.db.dao().searchTracks(q, 1_001).size)
            }
        }
    }

    // Part D: Keep liked songs on, 50,000 downloads asked for by the rules
    // walk a page at a time with the family playlist kept, then the walk
    // after the next sync, which changes nothing.
    @Test
    fun rulesWalkAtProofSize() = proof("rules-walk") { steps ->
        ProofWorld().use { w ->
            steps.setup("library") { w.copy(arriving = true); w.afterSync() }
            val family = steps.setup("the family playlist") { w.import(FAMILY_ID) }
            steps.started(w.db)
            val rig = RulesRig(w.db, w.merge, server = w.source)
            steps.step("rules walk, Keep liked on") {
                rig.rules.refreshSwapped()
                rig.rules.walk(keepLiked = true, playlists = setOf(family), refused = null)
            }
            assertTrue(w.db.readLong("SELECT COUNT(*) FROM download") >= PROOF_STARRED)
            w.db.counted.reset()
            steps.step("rules walk after the next sync") {
                rig.rules.refreshSwapped()
                rig.rules.walk(keepLiked = true, playlists = setOf(family), refused = null)
            }
            // The walk pins the kept playlist's set while it reads it and lets
            // it go; nothing else is written.
            val wrote = w.db.counted.changedByTable.filterKeys { it != "playlist_set_pin" }
            assertTrue("the walk after wrote $wrote", wrote.isEmpty())
        }
    }

    // Part E: 200,000 phone files (50,000 untagged in one folder, 150,000 in
    // 15,000 albums): a first scan, a quiet scan, a scan with one change,
    // each with its merge, then the Folders screen's tree and the big
    // folder's pages.
    @Test
    fun phoneScanAtProofSize() = proof("phone-scan") { steps ->
        val dir = Files.createTempDirectory("heap1b").toFile()
        val db = openTestCatalog(dir)
        try {
            val files = PhoneFiles.albums(files = 150_000, perAlbum = 10, unknownAlbum = 50_000)
            val merge = db.testMerge(null)
            val phone = db.phoneLibrary(files, merge)
            steps.started(db)
            steps.step("first scan") { phone.rescan() }
            assertEquals(200_000L, db.readLong("SELECT COUNT(*) FROM source_track WHERE sourceId = 'device'"))
            assertEquals(200_000L, db.readLong("SELECT COUNT(*) FROM track"))
            steps.step("quiet scan") { phone.rescan() }
            FakeMediaStore.current!!.files = PhoneFiles(files.count) { i -> files.file(i).let { if (i == 7) it.copy(modifiedSeconds = it.modifiedSeconds + 60, title = "Changed") else it } }
            steps.step("scan with one change") { phone.rescan() }
            assertEquals(1L, db.readLong("SELECT COUNT(*) FROM track WHERE title = 'Changed'"))
            val folders = PhoneFolders(db, merge, FreeSpace { Long.MAX_VALUE })
            val tree = steps.step("Folders tree") { folders.tree()!! }
            fun biggest(folder: PhoneFolder): PhoneFolder = (folder.folders.map { biggest(it) } + folder).maxBy { it.own }
            val downloads = biggest(tree.root)
            assertEquals(50_000, downloads.own)
            steps.step("the 50,000-song folder's pages") {
                folders.copy(WorkOwners.marked("vm:folder"), listOf(downloads), SortOrder(SongSort.Title, false), ranked = false)
                assertTrue(walkPages(folders.loader(WorkOwners.marked("vm:folder"))) >= 50_000)
            }
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    // Part F. The 200,000-entry mixed playlist (100,000 repeats of one song,
    // 30,000 finds, 20,000 title matches) imported whole: its read and links
    // and its title pool's walk timed, the pool's size, pages and parses
    // recorded, and an entry's disk on this generator measured. Then
    // 200,000 library songs imported, a pull with 1,000 rows
    // only on the phone, a family push by form posts with 100,000 removals
    // and 50,000 adds, and a replace of 200,000 by form posts. Records each
    // step's longest write, its highest write-back try and its resolutions.
    @Test
    fun playlistSyncAtProofSize() = proof("playlist-sync") { steps ->
        ProofWorld(familySize = 150_000).use { w ->
            steps.setup("library") { w.copy(arriving = true); w.afterSync() }
            steps.started(w.db)
            val tries = Collections.synchronizedList(ArrayList<Int>())
            val resolutions = Collections.synchronizedMap(HashMap<Long, Int>())
            w.playlistSteps.hooks = object : StepHooks() {
                override suspend fun flipped(playlistId: String, attempt: Int, flip: Flip) {
                    tries += attempt
                }
            }

            // An answer's entry on this generator, drained to a file.
            val file = File(Files.createTempDirectory("heap1b").toFile(), "answer.json")
            val json = try {
                w.fake.client().playlistInto(BIG_ID, file) {}
                file.length() / PROOF_SONGS
            } finally {
                file.parentFile.deleteRecursively()
            }
            fun size() = w.db.readLong("PRAGMA page_count") * w.db.readLong("PRAGMA page_size")
            val pool = PoolProbe(w.db, size())
            w.reads.hooks = pool
            w.db.counted.reset()
            val start = System.nanoTime()
            val mixed = steps.step("mixed import") { w.import(BIG_ID) }
            val whole = (System.nanoTime() - start) / 1_000_000
            val rows = w.db.readLong("SELECT COUNT(*) FROM playlist p CROSS JOIN playlist_item i ON i.playlistId = p.id AND i.itemSet = p.itemSet WHERE p.id = ?", mixed)
            val finds = w.db.readLong("SELECT COUNT(*) FROM online_song")
            assertTrue("$rows rows", rows > 0)
            val stepBytes = if (pool.sizeAtPool > 0) (pool.sizeAtPool - pool.sizeBefore) / PROOF_SONGS else null
            println("RESULT time-1b playlist constants entries=$PROOF_SONGS jsonPerEntry=$json (bound $PLAYLIST_JSON_BYTES) stepPerEntry=$stepBytes before the pool (bound $PLAYLIST_STEP_BYTES)")
            println(
                "RESULT time-1b mixed import of $PROOF_SONGS finished in $whole ms: read and links ${pool.toPoolMs} ms, then the pool walked ${pool.pages} pages of 50 songs " +
                    "in ${pool.walkedMs} ms (a pool of ${pool.poolSongs} songs, ${pool.songsParsed} song and ${pool.groupsParsed} group parses); $rows rows, $finds finds kept; no bound",
            )
            val longest = w.db.counted.longestWrite
            println("RESULT heap-1b playlist-sync mixed import: longest write ${longest?.heldNanos?.div(1_000_000)} ms, ${longest?.rowsChanged} rows (${longest?.label})")
            w.reads.hooks = object : ReadHooks() {
                override fun resolving(read: Long) {
                    resolutions.merge(read, 1, Int::plus)
                }
            }
            fun report(step: String) {
                val longest = w.db.counted.longestWrite
                println("RESULT heap-1b playlist-sync $step: longest write ${longest?.heldNanos?.div(1_000_000)} ms, ${longest?.rowsChanged} rows (${longest?.label}), highest try ${tries.maxOrNull()}, resolutions ${resolutions.values.maxOrNull()}")
                w.db.counted.reset()
                tries.clear()
                resolutions.clear()
            }

            w.db.counted.reset()
            val plain = steps.step("import of 200,000 library songs") { w.import(PLAIN_ID) }
            report("import")
            val edits = PlaylistEdits(w.db, w.sets)
            steps.step("1,000 songs added on the phone") { w.addLibrarySongs(edits, plain, 1_000, from = 0) }
            w.db.counted.reset()
            steps.step("pull with 1,000 rows only on the phone") {
                w.playlistSteps.run(w.server(formPost = false), PlaylistStep.Pull(plain, PLAIN_ID, w.stamp(PLAIN_ID), keepName = false), counts = mapOf(PLAIN_ID to PROOF_SONGS))
            }
            report("pull")

            val family = steps.step("family playlist import") { w.import(FAMILY_ID, formPost = true) }
            w.db.counted.reset()
            steps.step("100,000 removed and 50,000 added on the phone") {
                w.removeFirst(edits, family, 100_000)
                w.addLibrarySongs(edits, family, 50_000, from = 100_000)
            }
            w.db.counted.reset()
            steps.step("family push by form posts") { w.playlistSteps.run(w.server(formPost = true), PlaylistStep.Push(family, FAMILY_ID)) }
            report("family push")

            steps.step("a move on the phone") { w.moveOne(edits, plain) }
            w.db.counted.reset()
            steps.step("replace of 200,000 by form posts") { w.playlistSteps.run(w.server(formPost = true), PlaylistStep.Push(plain, PLAIN_ID)) }
            report("replace")
            assertTrue("a write-back took a third try", (tries.maxOrNull() ?: 1) < 3)
        }
    }

    // G2: every paged list screen at proof size. Songs in three orders both
    // ways with rail jumps and a filter on a common word, the 120,000-song genre,
    // 50,000 liked, Albums and Artists, the 200,000-entry playlist's page
    // with a move, 50,000 downloads, the 100,000-song album's page (its
    // library songs and its listing's copy, with the disc and staleness
    // checks timed), the 100,000-song artist's See all, and Search. Every
    // list's small check is read too.
    @Test
    fun listsAtProofSize() = proof("lists") { steps ->
        ProofWorld().use { w ->
            steps.setup("library") { w.copy(arriving = true); w.afterSync() }
            val big = steps.setup("a 200,000-song playlist") { w.bigPlaylist() }
            steps.setup("50,000 liked songs downloaded") {
                RulesRig(w.db, w.merge, server = w.source).rules.walk(keepLiked = true, playlists = emptySet(), refused = null)
                w.db.write("UPDATE download SET state = 'Done', progress = 1, path = 'kept', fileSize = 30000000")
            }
            val totals = w.totals()
            steps.setup("the totals' first build") {
                totals.start()
                totals.builtThrough.first { it >= 0 }
            }
            steps.started(w.db)
            val dao = w.db.listsDao()
            val songs = SongLists(dao)
            steps.step("Songs by title, artist and most played, both ways") {
                for (sort in listOf(SongSort.Title, SongSort.Artist, SongSort.MostPlayed)) {
                    for (down in listOf(false, true)) {
                        browse("Songs by $sort${if (down) ", down" else ""}", songs.loader(SongScope.All, SortOrder(sort, down)), expect = if (sort == SongSort.MostPlayed) null else PROOF_SONGS)
                    }
                }
                for (sort in listOf(SongSort.RecentlyPlayed, SongSort.Liked, SongSort.Year)) {
                    browse("Songs by $sort", songs.loader(SongScope.All, SortOrder(sort, true)), expect = if (sort == SongSort.RecentlyPlayed) null else PROOF_SONGS)
                }
            }
            steps.step("the letter rail to U and to the end") {
                val loader = songs.loader(SongScope.All, SortOrder(SongSort.Title, false))
                val runs = loader.survey(null).runs!!
                for (run in listOf(runs.first { it.letter == 'U' }, runs.last())) {
                    val key = loader.keyAt(run.start)!!
                    loader.survey(key)
                    loader.after(key, PAGE)
                    loader.before(key, PAGE)
                }
            }
            steps.step("Songs filtered by \"$PROOF_COMMON_WORD\"") {
                val filter = LibraryQuery(rules = listOf(QueryRule(QueryField.Title, QueryOp.Contains, text = PROOF_COMMON_WORD)))
                val inputs = FilterInputs(liked = { emptySet() }, plays = { emptyMap() }, songs = { ids -> ids.chunked(900).flatMap { w.db.dao().tracksByIdsUnordered(it) } })
                browse("Songs filtered by \"$PROOF_COMMON_WORD\"", FilteredLoader(songs.loader(SongScope.All, SortOrder(SongSort.Title, false)), filter, inputs), expect = null)
            }
            steps.step("the 120,000-song genre") { browse("the 120,000-song genre", songs.loader(SongScope.Genre("Genre big"), SortOrder(SongSort.Title, false)), expect = BIG_GENRE) }
            steps.step("a 2,000-song genre by artist") { browse("a 2,000-song genre by artist", songs.loader(SongScope.Genre("Genre 5"), SortOrder(SongSort.Artist, false)), expect = 2_000) }
            steps.step("50,000 liked") { browse("50,000 liked", songs.loader(SongScope.Liked, SortOrder(SongSort.DateLiked, true)), expect = PROOF_STARRED) }
            steps.step("Albums and Artists") {
                val albums = w.db.read("SELECT id FROM album ORDER BY rowid") { it.getString(0) }
                browse("Albums", GroupsLoader(dao, artists = false, groupIds = { albums }, tablesRead = arrayOf("track", "album")), expect = null)
                val artists = w.db.read("SELECT id FROM artist ORDER BY rowid") { it.getString(0) }
                browse("Artists", GroupsLoader(dao, artists = true, groupIds = { artists }, tablesRead = arrayOf("track", "artist")), expect = null)
            }
            steps.step("the 200,000-entry playlist's page with a move") {
                val loader = PlaylistLoader(w.db, w.sets, big, WorkOwners.marked("vm:playlist"))
                try {
                    browse("the 200,000-entry playlist", loader, expect = null)
                    w.moveOne(PlaylistEdits(w.db, w.sets), big)
                    browse("the 200,000-entry playlist after a move", loader, expect = null)
                } finally {
                    loader.retire()
                }
            }
            steps.step("50,000 downloads") {
                for (sort in DownloadSort.entries) browse("Downloads by $sort", DownloadsLists(dao).loader(w.source, SortOrder(sort, sort.startsDescending)), expect = PROOF_STARRED)
            }
            val unknown = w.db.read("SELECT id, artistId FROM album WHERE title = 'Unknown album'") { it.getString(0) to it.getString(1) }.single()
            steps.step("the 100,000-song album's page") { browse("the 100,000-song album", albumLibraryLoader(dao, unknown.first), expect = PROOF_SONGS / 2) }
            timed("album top disc check, 100,000 songs", "no bound") { libraryOtherDisc(w.db, unknown.first, 1) }
            steps.step("the 100,000-song album's listing copy") {
                val listing = AlbumListing(w.db, w.merge, Drains(File(w.dir, "album-drain").apply { mkdirs() }), FreeSpace { Long.MAX_VALUE }, { emptyMap() })
                listing.build(WorkOwners.marked("vm:album"), unknown.first, w.fake.client(), runsOcto = false)
                browse("the 100,000-song album's copy", albumCopyLoader(dao, WorkOwners.marked("vm:album")), expect = null)
            }
            val stale = timed("album staleness check, 100,000 songs", "no bound") { copyIsStale(w.db, WorkOwners.marked("vm:album"), unknown.first) }
            println("RESULT heap-1b lists the album's copy is stale: $stale")
            steps.step("the 100,000-song artist's See all") {
                for (order in ArtistOrder.entries) browse("the 100,000-song artist by $order", ArtistLoaderSource(dao).loader(unknown.second, order), expect = null)
            }
            steps.step("Search for a common word") { browse("Search for \"$PROOF_COMMON_WORD\"", SearchLoader(dao, searchKey(PROOF_COMMON_WORD), 1_000), expect = null) }
        }
    }

    // G4: a song radio's start, a live list matching 150,000 songs, Library
    // health's build and its check pages (keyAt, a row's place, Fix all's
    // count and No length's two reads timed), and matching: a restore of
    // 50,000 likes and an M3U of 80,000 lines.
    @Test
    fun readersAtProofSize() = proof("readers") { steps ->
        ProofWorld().use { w ->
            steps.setup("library") { w.copy(arriving = true); w.afterSync() }
            val totals = w.totals()
            steps.setup("the totals' first build") {
                totals.start()
                totals.builtThrough.first { it >= 0 }
                w.merge.start()
                w.merge.keysReady.first { it }
            }
            steps.started(w.db)
            val seed = w.db.dao().tracksByIdsUnordered(w.db.read("SELECT id FROM track ORDER BY rowid LIMIT 1 OFFSET 777") { it.getString(0) }).single()
            steps.step("song radio") {
                val radio = LibraryRadio(w.db, totals, { _, _ -> emptyList() }, flowOf(PlayerPrefs()))
                assertEquals(RADIO_LENGTH, radio.songsLike(listOf(seed), RADIO_LENGTH, random = Random(3)).size)
            }
            steps.step("a live list of 150,000 songs") {
                val picks = LivePicks(w.db, w.merge, totals, WorkOwners(), FreeSpace { Long.MAX_VALUE }, System::currentTimeMillis, 500)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val shown = MutableStateFlow<LiveShown>(LiveShown.Loading)
                    val query = MutableStateFlow(LibraryQuery(rules = listOf(QueryRule(QueryField.Duration, QueryOp.AtLeast, number = 195))))
                    scope.launch { picks.watch(WorkOwners.marked("live:proof"), "proof", query).collect { shown.value = it } }
                    val list = shown.first { it !is LiveShown.Loading }
                    assertTrue("$list", list is LiveShown.Shown && list.count in 140_000..160_000)
                    browse("a live list of 150,000 songs", picks.loader(WorkOwners.marked("live:proof")), expect = (list as LiveShown.Shown).count)
                } finally {
                    scope.cancel()
                }
            }
            val owners = WorkOwners()
            val build = HealthBuild(w.db, w.merge, owners, FreeSpace { Long.MAX_VALUE }, 500, 4_096)
            val report = steps.step("Library health build") { build.buildNow() as HealthShown.Shown }
            assertEquals(PROOF_SONGS, report.checked)
            val plans = FixAllPlans(w.db, build)
            steps.step("health check pages") {
                for (check in HealthCheck.entries) {
                    val lines = build.lines(check, w.source)
                    val n = timed("health ${check.name}'s count", "no bound") { lines.count() }
                    println("RESULT heap-1b readers ${check.name}: ${report.count(check)} problems, $n lines")
                    browse("health ${check.name}", lines, expect = n)
                }
            }
            for (check in listOf(HealthCheck.Duplicates, HealthCheck.SplitAlbums)) {
                val lines = build.lines(check, w.source)
                val n = lines.count()
                if (n == 0) continue
                val last = timed("${check.name} keyAt the last line of $n", "no bound") { lines.keyAt(n - 1)!! }
                timed("${check.name} keyAt the middle line", "no bound") { lines.keyAt(n / 2) }
                timed("${check.name} a row's place (the last line's)", "no bound") { lines.survey(last).anchorAt }
                timed("${check.name} the page before the last line", "no bound") { lines.before(last, PAGE) }
                val song = lines.after(lines.keyAt(n - 2), 2).firstNotNullOfOrNull { lines.trackIdOf(it.row) }
                if (song != null) timed("${check.name} a song's line (keyOf, near the end)", "no bound") { lines.keyOf(song) }
                timed("${check.name} Fix all's count", "no bound") { plans.count(check, w.source, edit = false) }
            }
            fixAllSplitParts(w, report.gen)
            for ((label, n) in listOf("a handful" to 12, "more than 5,000" to 6_000)) {
                w.db.write("UPDATE track SET durationMs = 200000 WHERE durationMs < 1000")
                w.db.write("UPDATE track SET durationMs = 0 WHERE rowid IN (SELECT rowid FROM track ORDER BY rowid LIMIT ? OFFSET 1000)", n)
                val lines = build.lines(HealthCheck.NoLength, w.source)
                val (counted, rows) = timed("No length's first page, $label ($n songs)", "no bound") { lines.count() to lines.after(null, PAGE).size }
                println("RESULT heap-1b readers No length with $n songs: counted $counted, first page $rows lines")
            }
            val jobs = MatchJobs(w.db, WorkOwners(), FreeSpace { Long.MAX_VALUE })
            steps.step("matching: a restore of 50,000 likes") {
                jobs.run(MatchKind.Backup, SpaceFor.Restore, PROOF_STARRED.toLong()) { job ->
                    var after = ""
                    while (true) {
                        val page = w.db.read("SELECT id, relinkKey, title, artist, album, durationMs FROM track WHERE id > ? ORDER BY id LIMIT 500", after) {
                            it.getString(0) to MatchSong(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5))
                        }
                        if (page.isEmpty()) break
                        job.stageSongs(0, page.filterIndexed { i, _ -> i % 4 == 0 }.map { it.second })
                        after = page.last().first
                    }
                    job.walk()
                    job.finish()
                    assertTrue(walkMatches { job.rows(0, it) } >= PROOF_STARRED * 9 / 10)
                }
            }
            steps.step("matching: an M3U of 80,000 lines") {
                jobs.run(MatchKind.Playlist, SpaceFor.KeepList, 80_000) { job ->
                    for (start in 0 until 80_000 step 500) {
                        job.stageEntries(
                            (start until start + 500).map { k ->
                                val song = w.library.song(k * 2)
                                M3uEntry("Music/${song.artist}/${song.album}/${song.title}.flac", song.duration, song.artist, song.title, line = k)
                            },
                        )
                    }
                    job.walk()
                    job.finish()
                    walkMatches { job.rows(0, it) }
                }
            }
        }
    }

    // The queue at proof size, each case over a fresh copy of one library
    // copied once: Play all and Shuffle all of the 200,000 songs, a tap on
    // row 150,000, the same tap with shuffle on (that song plays first),
    // shuffle turned on while the list is written and after it joined (the
    // song playing first, then every other song once), a 20,000-song Play
    // next while a list is written, and a restore after the join. Each case
    // prints its RESULT queue-1b line: tap to first sound, to the join, to
    // ten songs and to a full window in Up next, the Nexts that waited, rows
    // and bytes written, and its peak heap. QueueSync's view, which the
    // service reads at every song change, is timed shuffled and in play
    // order with the statements it runs. The queue's player needs the SDK
    // the playback tests run on.
    @Test
    @Config(sdk = [35])
    fun queueAtProofSize() = proof("queue") { steps ->
        val fixture = steps.setup("the proof library") { queueFixture() }
        try {
            for (case in QueueCaseKind.entries) steps.step("case: ${case.label}") { QueueCase(fixture, case, steps).use { it.run() } }
        } finally {
            fixture.deleteRecursively()
        }
    }

    // The longest write a tap can land behind at proof size, with every
    // write of the library labeled by its writer: the first copy, with an
    // album the server answers for tapped as its save begins; the steps
    // after it; a freshen (a look that finds the newest albums, taken in
    // with no stage, on a clock the section moves); a whole re-copy of the
    // changed library with the steps after a sync writing beside it, and a
    // library list and the server's album tapped as its save begins; then a
    // copy given up for want of room once its songs are placed. Each tap is
    // timed to its start entry playing. Prints the swaps' lengths, the save
    // step, the abort's, the longest write of each writer, and RESULT
    // tap-wait; fails when any write carries no writer's label.
    @Test
    @Config(sdk = [35])
    fun tapWaitSection() = proof("tap-wait") { steps ->
        val space = AtomicLong(Long.MAX_VALUE)
        val taps = TapHooks()
        ProofWorld(hooks = taps, space = FreeSpace { space.get() }).use { w ->
            val owners = WorkOwners()
            val reads = ServerReads(
                w.db, Drains(File(w.dir, "tap-drain").apply { mkdirs() }), FreeSpace { Long.MAX_VALUE }, owners, w.merge,
                server = { ServerSide(w.session.client, w.source, indexedQueue = false) },
            )
            val rig = ProofQueue(w, reads, owners)
            val hooks = CaseHooks()
            rig.builds.hooks = hooks
            val copies = TapRun(w, rig, taps, hooks)
            try {
                steps.started(w.db)
                val seen = lastScanOf(w.session.client)
                val online = PlayRequest(ListSpec.Online(OnlineKind.Album, ONLINE_ALBUM_ID), null, shownOverWindow = true)
                val firstTaps = steps.step("first copy, the server's album tapped as its save begins") { copies.copy(arriving = true, listOf(online)) }
                assertTrue("the first copy claims a stage", firstTaps.claimed)
                val first = firstTaps.swap!!
                steps.step("steps after the copy") { withContext(WriteLabels.of(AFTER_SYNC_WRITE)) { w.afterSync() } }
                val totals = w.totals()
                steps.step("the totals' first build") {
                    totals.start()
                    totals.builtThrough.first { it >= 0 }
                }

                // A freshen: the server read its folders again, and its newest
                // albums are new; the look is due on the section's clock.
                w.library.changed = true
                val clock = 1_000_000L
                assertTrue(newMusicLookDue(null, clock))
                val freshened = steps.step("freshen: a look and a take-in") { copies.freshen(seen) }
                assertTrue("the freshen took albums in", freshened)
                assertTrue("the freshen claims no stage", !copies.lastClaimed)
                assertTrue("a second look is not due within its wait", !newMusicLookDue(clock, clock + 60_000))
                assertTrue("a look is due once its wait is over", newMusicLookDue(clock, clock + NEW_MUSIC_LOOK_MS))
                val freshen = w.swaps.last()

                // A whole re-copy with the steps after a sync writing beside
                // it, and a library list and the server's album tapped.
                val row = rig.rowAt(150_000)
                val library = PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), row, shownOverWindow = true)
                val wholeTaps = steps.step("whole re-copy, a library list and the server's album tapped as its save begins") {
                    copies.copy(arriving = false, listOf(library, online), beside = { copies.besideSync(totals) })
                }
                assertTrue("the whole re-copy claims a stage", wholeTaps.claimed)
                val whole = wholeTaps.swap!!

                // A copy given up once its songs are placed: no room for its swap.
                val aborted = steps.step("a copy given up once its songs are placed") {
                    taps.onSaving = { space.set(0) }
                    try {
                        copies.copy(arriving = false, emptyList(), expectAbort = true)
                    } finally {
                        taps.onSaving = null
                        space.set(Long.MAX_VALUE)
                    }
                }

                val log = w.db.counted
                for ((label, t) in log.longestByLabel.entries.sortedByDescending { it.value.heldNanos }) {
                    println("RESULT time-1b tap-wait longest write of \"${label ?: "unlabeled"}\": ${t.heldNanos / 1_000_000} ms, ${t.rowsChanged} rows, ${t.statements} statements (waited ${t.waitNanos / 1_000_000} ms for its BEGIN)")
                }
                val longest = log.longestByLabel.filterKeys { it != null }.maxByOrNull { it.value.heldNanos }!!
                val libraryMs = wholeTaps.waits[0]
                val networkMs = wholeTaps.waits[1]
                println("RESULT tap-wait longest write ${longest.value.heldNanos / 1_000_000} ms (${longest.key}), library play $libraryMs ms, network play $networkMs ms")
                println(
                    "RESULT tap-wait first-copy swap ${first.millis} ms, whole re-copy swap ${whole.millis} ms, freshen swap ${freshen.millis} ms, " +
                        "abort ${aborted.abortMs} ms (its write ${log.longestByLabel[ABORT_WRITE]?.heldNanos?.div(1_000_000)} ms); save step: first copy ${firstTaps.saveStepMs} ms, " +
                        "whole re-copy ${wholeTaps.saveStepMs} ms; first copy's network play ${firstTaps.waits[0]} ms; started as the re-copy's save began, once its last play started: ${wholeTaps.beside}",
                )
                val unlabeled = log.unlabeled
                assertTrue("writes no writer labeled: $unlabeled", unlabeled.none)
            } finally {
                rig.die()
            }
        }
    }

    // G4's server half: a 100,000-entry server list drained, read into its
    // owner's rows, resolved and paged; a backup of the 50,000 likes and the
    // 200,000-song playlist exported, read back, planned and restored; an
    // M3U of 80,000 lines imported, and the 200,000-song playlist exported as
    // one.
    @Test
    fun serverListsBackupsAndM3uAtProofSize() = proof("server-lists-backups-m3u") { steps ->
        ProofWorld(familySize = SERVER_LIST).use { w ->
            steps.setup("library") { w.copy(arriving = true); w.afterSync() }
            val big = steps.setup("a 200,000-song playlist") { w.bigPlaylist() }
            assertEquals(PROOF_STARRED.toLong(), w.db.readLong("SELECT COUNT(*) FROM liked_track"))
            steps.started(w.db)
            val owners = WorkOwners()
            val reads = ServerReads(
                w.db, Drains(File(w.dir, "server-drain").apply { mkdirs() }), FreeSpace { Long.MAX_VALUE }, owners, w.merge,
                server = { ServerSide(w.session.client, w.source, indexedQueue = false) },
            )
            steps.step("a 100,000-entry server list") {
                timed("server list of $SERVER_LIST entries read and resolved", "no bound") { assertTrue(reads.read(ServerListKind.List, FAMILY_ID, WorkOwners.marked("vm:list")) != null) }
                val facts = reads.facts(WorkOwners.marked("vm:list"))
                println("RESULT heap-1b server-lists-backups-m3u the server list shows ${facts.count} songs, ${facts.finds} of them finds")
                browse("a $SERVER_LIST-entry server list", reads.loader(WorkOwners.marked("vm:list")), expect = null)
                reads.clear(WorkOwners.marked("vm:list"))
            }

            val jobs = MatchJobs(w.db, owners, FreeSpace { Long.MAX_VALUE })
            val backups = BackupSongs(w.db, w.merge, w.sets, owners, jobs, Exports(Drains(File(w.dir, "exports").apply { mkdirs() })), FreeSpace { Long.MAX_VALUE })
            val kept = File(w.dir, "backup.json")
            steps.step("backup export: 50,000 likes and the 200,000-song playlist") {
                val saved = timed("backup export", "no bound") { backups.export(Backup(createdAt = System.currentTimeMillis(), player = PlayerBackup(PlayerPrefs()))) { file -> file.copyTo(kept, overwrite = true); true } }
                assertEquals(BackupSave.Saved, saved)
            }
            var liked = 0
            var made = 0
            steps.step("backup read, plan and restore") {
                val opened = timed("backup read", "no bound") { backups.read { kept.inputStream() } } as BackupOpened.Opened
                try {
                    val plan = timed("backup plan", "no bound") { backups.plan(opened.file, existing = emptyList()) }
                    assertEquals(PROOF_STARRED, plan.likes)
                    // Restored as onto a phone that has none of the likes, so
                    // every one is written.
                    w.db.write("DELETE FROM liked_track")
                    timed("backup restore", "no bound") {
                        backups.restore(
                            opened.file, plan,
                            create = { name, pages ->
                                pages { made += it.size }
                                "made-$name"
                            },
                            liked = { liked += it.size },
                            rate = { _, _ -> },
                        )
                    }
                } finally {
                    backups.close(opened.file)
                }
            }
            println("RESULT heap-1b server-lists-backups-m3u backup of ${kept.length()} bytes: restored $liked likes and a playlist of $made songs")
            assertEquals(PROOF_STARRED, liked)
            assertEquals(PROOF_SONGS, made)

            var imported = 0
            val files = PlaylistFiles(
                ApplicationProvider.getApplicationContext<Context>(), w.db, w.merge, w.sets, owners, jobs,
                Exports(Drains(File(w.dir, "m3u-exports").apply { mkdirs() })), FreeSpace { Long.MAX_VALUE },
                create = { _, pages ->
                    pages { imported += it.size }
                    "imported"
                },
            )
            val m3u = File(w.dir, "Big.m3u8")
            m3u.bufferedWriter().use { out ->
                out.write("#EXTM3U\n")
                for (k in 0 until M3U_LINES) {
                    val song = w.library.song(k * 2)
                    out.write("#EXTINF:${song.duration},${song.artist} - ${song.title}\nMusic/${song.artist}/${song.album}/${song.title}.flac\n")
                }
            }
            val report = steps.step("M3U import of 80,000 lines") { timed("M3U import of $M3U_LINES lines", "no bound") { files.import(Uri.fromFile(m3u))!! } }
            assertEquals(M3U_LINES, report.total)
            println("RESULT heap-1b server-lists-backups-m3u M3U import: ${report.matched} of ${report.total} lines found, $imported songs in the playlist")
            var exported = 0L
            files.copy = { file, _ ->
                exported = file.length()
                true
            }
            steps.step("M3U export of the 200,000-song playlist") {
                assertNull(timed("M3U export of $PROOF_SONGS songs", "no bound") { files.export(big, Uri.parse("content://picked/big.m3u")) })
            }
            println("RESULT heap-1b server-lists-backups-m3u M3U export: $exported bytes")
        }
    }

    // G4's car and Autoplay: the car's Songs answer and its last part, a
    // live list of 150,000 songs answered twice (its walk, then none), the
    // 100,000-song album opened, and Autoplay's picks after a song of the
    // 100,000-song artist, with its fallbacks walked. Each answer is timed.
    @Test
    fun carAndAutoplayAtProofSize() = proof("car-autoplay") { steps ->
        ProofWorld().use { w ->
            steps.setup("library") { w.copy(arriving = true); w.afterSync() }
            val car = CarRig(w.db)
            try {
                steps.setup("the totals' first build") { car.totals.started() }
                steps.started(w.db)
                steps.step("the car's Songs and its last part") {
                    var items = timed("car Songs answer", "no bound") { car.answer(CarNode.Songs) }
                    var level = 0
                    while (true) {
                        val node = items.lastOrNull()?.let { parseCarId(it.mediaId) }?.takeIf { it is CarNode.Heading || it is CarNode.Part } ?: break
                        level++
                        items = timed("car Songs' last item, level $level ($node)", "no bound") { car.answer(node) }
                    }
                    println("RESULT heap-1b car-autoplay the car's Songs: $level levels down to ${items.size} songs")
                    assertTrue("the last part holds songs", level > 0 && items.isNotEmpty())
                }
                steps.step("the car's live list of 150,000 songs, answered twice") {
                    car.liveLists.value = mapOf("proof" to LibraryQuery(rules = listOf(QueryRule(QueryField.Duration, QueryOp.AtLeast, number = 195)), sort = QuerySort(SongSort.Title.id)))
                    val first = timed("car live list, first answer (its walk)", "no bound") { car.answer(CarNode.LiveList("proof")) }
                    val second = timed("car live list, second answer", "no bound") { car.answer(CarNode.LiveList("proof")) }
                    assertEquals(first.map { it.mediaId }, second.map { it.mediaId })
                    assertTrue("${first.size} items", first.isNotEmpty())
                    val part = parseCarId(second.last().mediaId)
                    if (part is CarNode.Part) {
                        val songs = timed("car live list, its last part ($part)", "no bound") { car.answer(part) }
                        println("RESULT heap-1b car-autoplay the live list's last part: ${songs.size} items")
                    }
                }
                val unknown = w.db.read("SELECT id FROM album WHERE title = 'Unknown album'") { it.getString(0) }.single()
                steps.step("the car opens the 100,000-song album") {
                    val items = timed("car album open, 100,000 songs", "no bound") { car.answer(CarNode.Album(unknown), fresh = true) }
                    assertTrue("${items.size} items", items.isNotEmpty())
                }
                val seed = w.db.read("SELECT id FROM track WHERE albumId = ? ORDER BY rowid LIMIT 1 OFFSET 500", unknown) { it.getString(0) }.single()
                steps.step("Autoplay after a song of the 100,000-song artist") {
                    val radio = LibraryRadio(w.db, car.totals.totals, { _, _ -> emptyList() }, flowOf(PlayerPrefs()))
                    val picker = AutoplayPicker(radio, w.db, car.merge, car.totals.history, QueueStore(w.db)).also { it.random = Random(5) }
                    val picks = timed("Autoplay's picks", "no bound") { picker.picksAfter(seed, before = emptyList(), anchors = emptyList(), held = emptySet()) }
                    val fallbacks = timed("Autoplay's fallbacks, the artist's albums and genre walked", "no bound") { picker.fallbacks(w.db.dao().track(seed)!!) { emptySet() } }
                    println("RESULT heap-1b car-autoplay Autoplay picked ${picks.size}, its fallbacks ${fallbacks.size}")
                    assertTrue(picks.isNotEmpty() && fallbacks.isNotEmpty())
                }
            } finally {
                car.scope.coroutineContext[Job]!!.cancelAndJoin()
                car.drains.deleteRecursively()
            }
        }
    }

    // A change of the library while the car keeps its copy of a live list of
    // 150,000 songs: the walk's length, the rows it wrote and the most rows
    // the list held beyond its songs, for each way a walk can keep the list.
    @Test
    fun carLiveListChangeAtProofSize() = proof("car-live-change") { steps ->
        ProofWorld().use { w ->
            steps.setup("library") { w.copy(arriving = true); w.afterSync() }
            steps.started(w.db)
            val query = LibraryQuery(rules = listOf(QueryRule(QueryField.Duration, QueryOp.AtLeast, number = 195)), sort = QuerySort(SongSort.Title.id))
            val ways = listOf(
                Triple("changed in place (every reader but the car)", false, false),
                Triple("kept as the car keeps it, no answer holding it", true, false),
                Triple("kept as the car keeps it, an answer holding it", true, true),
            )
            for ((label, keepHeld, held) in ways) {
                steps.step("a library change, the list $label") { liveChange(w, label, query, keepHeld, held) }
            }
        }
    }

    // The save's bound at proof size. The proof library is copied in, its
    // steps after the copy run, a 200,000-entry queue and 100,000 plays are
    // written and the totals built. Each case, a server switch (the same
    // library on another server) and a mass retag (every album and artist
    // under new tags), is then staged, claimed and built once from a copy of
    // that library, and saved from a copy of that at each bound with another
    // writer beside it, and from another copy by today's swap and today's
    // totals rebuild. Each save: every table equal to today's by key (the
    // oracle), no save transaction over its bound and none of the merge's
    // writes outside a transaction, the writer beside it waiting out one
    // save transaction at most; its groups, its largest transaction's cost,
    // its run's length, its longest transaction and the longest wait beside
    // it printed. Printed too, at the default bound: each walk of the find,
    // none reading more than MERGE_PAGE rows in one statement, and the
    // copy's stage asks and the save's swap ask beside the room each took.
    // -PoctoSaveBounds and -PoctoSaveCases run some of them, and with
    // -PoctoSaveFixtures each case's built and today's libraries are kept in
    // that folder, so a later run of other bounds starts from them.
    @Test
    fun saveBoundsAtProofSize() = proof("save-bounds") { steps ->
        val bounds = System.getProperty("octo.saveBounds")?.split(',')?.map { it.trim().toLong() } ?: SAVE_BOUNDS
        val cases = System.getProperty("octo.saveCases")?.split(',')?.map { it.trim() } ?: listOf(SWITCH_CASE, RETAG_CASE)
        val keep = System.getProperty("octo.saveFixtures")?.let(::File)
        var world: ProofWorld? = null
        var other: LazySubsonic? = null
        val rows = ArrayList<BoundRow>()
        try {
            for (case in cases) {
                val kept = keep?.let { File(it, case) }
                val (staged, today) = if (kept != null && KeptCase.ready(kept)) {
                    steps.setup("$case: the kept fixture") { KeptCase.open(kept) }
                } else {
                    val w = world ?: ProofWorld(songs = proofSongs()).also { w ->
                        world = w
                        steps.setup("library") { w.copy(arriving = true); w.afterSync() }
                        steps.setup("a 200,000-entry queue, 100,000 plays and the totals built") { queueAndPlays(w) }
                    }
                    val lazy = other ?: LazySubsonic(w.library, starredSongs = w.starred, onlineAlbumSongs = 600).also { other = it }
                    val switch = case == SWITCH_CASE
                    val client = if (switch) lazy.client() else w.session.client
                    val server = if (switch) Session(client, serverType = null, serverVersion = null, isOcto = false, adminReachable = false, extensions = emptySet(), id = "proof2").sourceId else w.source
                    w.library.retagged = !switch
                    try {
                        steps.started(w.db)
                        val s = steps.step("$case: staged, claimed and built") { stagedCase(w, case, client, server) }
                        val t = steps.step("$case: the find's walks, then today's swap and its totals rebuild") { todayOf(s) }
                        if (kept != null) KeptCase.keep(kept, s, t)
                        s to t
                    } finally {
                        w.library.retagged = false
                    }
                }
                try {
                    for (bound in bounds) {
                        rows += steps.step("$case at bound $bound") { boundRun(staged, today, bound, steps) }
                        println("RESULT bound-1b ${rows.last()}")
                    }
                } finally {
                    closeCopy(today)
                    closeCopy(staged.db)
                }
            }
        } finally {
            other?.close()
            world?.close()
        }
        for (row in rows) println("RESULT bound-1b $row")
        // The writer beside each save waited out one save transaction at
        // most; held after every bound has run, so each row is printed.
        // A write waits out at most one save step once it is queued; its own
        // lead-in, from its ask to Room's queue, may add one.
        val waitedLong = rows.filter { it.queued !in 0..1 || it.waitedOut > 2 }
        assertTrue(
            "a write beside the save waited out more than one save transaction once queued, or more than two from its ask: " +
                waitedLong.joinToString { "${it.case} at ${it.bound}: ${it.queued} once queued, ${it.waitedOut} from the ask" },
            waitedLong.isEmpty(),
        )
    }

    // The short save at proof size as the app runs it: the first copy, a
    // server switch (the same library on another server) and a mass retag,
    // each a whole copy that saves through the merge. Beside each save run
    // another writer, and holds of every kind and stable reads, which must
    // enter between its groups. During the switch and the retag the user's
    // hearts and stars are written on songs' old ids, and at parks between
    // groups each shows as written, and the play totals equal a recount (a
    // window of songs at sampled parks, every song at three). At a park
    // inside a page of the switch's run (its transaction held, renames in
    // force) a library list, the server's album and its 100,000-song list
    // are played, then again with no save: none writes or begins a write
    // before it hands over, each timed to sound. No save transaction costs
    // more than its bound and none of the merge's writes runs outside one.
    // Then the free-space asks beside the room each took: the first copy's
    // and each save's, an unlimited live list over every song, and the
    // 100,000-entry server list's drained answer.
    @Test
    @Config(sdk = [35])
    fun shortSaveAtProofSize() = proof("short-save") { steps ->
        val hooks = SaveHooks()
        val songs = proofSongs()
        ProofWorld(familySize = songs / 2, hooks = hooks, songs = songs).use { w ->
            val watch = SaveWatch(w.db.openHelper.writableDatabase, SAVE_GROUP_ENTRIES)
            w.db.counted.watch = watch
            val space = SpaceProbe(w.db)
            hooks.db = w.db
            hooks.space = space
            val other = LazySubsonic(
                w.library, starredSongs = w.starred,
                playlists = listOf(
                    LazyPlaylist.family(w.library, FAMILY_ID, "Family mix", songs / 2),
                    LazyPlaylist.family(w.library, ASKS_ID, "Asks", songs / 2),
                ),
                onlineAlbumSongs = 600,
            )
            val session2 = Session(other.client(), serverType = null, serverVersion = null, isOcto = false, adminReachable = false, extensions = emptySet(), id = "proof2")
            var serverClient = w.session.client
            val owners = WorkOwners()
            val drains = File(w.dir, "save-drain").apply { mkdirs() }
            val reads = ServerReads(w.db, Drains(drains), FreeSpace { Long.MAX_VALUE }, owners, w.merge, server = { ServerSide(serverClient, w.serverInUse, indexedQueue = false) })
            val rig = ProofQueue(w, reads, owners)
            val taps = CaseHooks()
            rig.builds.hooks = taps
            try {
                steps.started(w.db)
                val first = steps.step("first copy") { saved(w, hooks, watch, "first copy") { w.copy(arriving = true) } }
                steps.step("steps after the copy") { w.afterSync() }
                steps.step("a 200,000-entry queue, 100,000 plays and the totals built") { queueAndPlays(w) }
                steps.step("favorite albums and artists, pinned") { favourites(w) }
                val engine = hasDbstat(w.db.openHelper.writableDatabase)
                val bundled = runCatching {
                    OldSqlite.open().use { conn -> conn.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM dbstat").use { it.next() } } }
                }.isSuccess
                println("RESULT space-1b dbstat on the test's engine (Robolectric's SQLite): $engine; on sqlite-jdbc 3.21 (OldSqlite): $bundled")
                // An everyday copy: the server read its folders again (100
                // songs out, 100 in, 2,000 retitled), its writes against its
                // output.
                w.library.changed = true
                val everyday = steps.step("everyday copy") { saved(w, hooks, watch, "everyday copy") { w.copy(arriving = false) } }
                // Every like keeps a key its song or one of the song's copies
                // is filed under: the retitled songs' likes took their new keys.
                val unkeyed = w.db.readLong(
                    "SELECT COUNT(*) FROM liked_track l JOIN track t ON t.id = l.trackId WHERE t.relinkKey != '' AND l.relinkKey != t.relinkKey " +
                        "AND NOT EXISTS (SELECT 1 FROM source_track s WHERE s.mergedId = l.trackId AND s.relinkKey = l.relinkKey)",
                )
                val likes = w.db.readLong("SELECT COUNT(*) FROM liked_track")
                println("RESULT likes-1b after the everyday copy: $unkeyed of $likes likes under a key no copy of their song holds")
                assertEquals("likes under a key no copy of their song holds after the everyday copy", 0L, unkeyed)
                // Every favorite and pin keeps a key its album or artist, or
                // one of its copies, is filed under: the renamed ones took
                // their new keys.
                val favouritesUnkeyed = favouriteCounts(w, FAVOURITES_UNKEYED)
                println("RESULT favourites-1b after the everyday copy, under a key no copy holds: $favouritesUnkeyed of ${favouriteCounts(w, FAVOURITES_ALL)}")
                assertEquals("favorites under a key no copy holds after the everyday copy", FAVOURITES_NONE, favouritesUnkeyed)
                // Favorites of the albums whose every song the copy took out:
                // those albums are gone, and their favorites wait for them.
                val favouritesGone = favouriteCounts(w, FAVOURITES_LOST)
                println("RESULT favourites-1b after the everyday copy, on ids the library lacks: $favouritesGone ${lostFavourites(w)}")
                val user = UserWrites(w)

                // The switch: the other server's copy, its songs the same as
                // this one's, the library's songs renamed to them.
                w.serverInUse = session2.sourceId
                serverClient = session2.client
                val parked = ParkedStarts(rig, taps, w, hooks, steps)
                // The parked starts play the server's 600-song album and its
                // half-library list, which a dry run's small library cannot
                // serve: below PARKED_STARTS_FROM songs the switch saves
                // with no starts beside it.
                val starts = songs >= PARKED_STARTS_FROM
                val switch = steps.step("server switch") {
                    val save: suspend () -> SaveResult = { saved(w, hooks, watch, "server switch", user) { w.copy(arriving = true, client = session2.client, sourceId = session2.sourceId) } }
                    if (starts) parked.during { save() } else save()
                }
                // Every like is on a song of the new server's library.
                val lost = w.db.readLong("SELECT COUNT(*) FROM liked_track l WHERE NOT EXISTS (SELECT 1 FROM track t WHERE t.id = l.trackId)")
                println("RESULT likes-1b after the switch: $lost of ${w.db.readLong("SELECT COUNT(*) FROM liked_track")} likes on ids no song has")
                assertEquals("likes on ids no song has after the switch", 0L, lost)
                // Every favorite and pin the everyday copy left on an album or
                // artist is on one of the new server's library.
                val favouritesLost = favouriteCounts(w, FAVOURITES_LOST)
                println("RESULT favourites-1b after the switch, on ids the library lacks: $favouritesLost of ${favouriteCounts(w, FAVOURITES_ALL)} ${lostFavourites(w)}")
                assertEquals("favorites on ids the library lacks after the switch, beyond the albums the copy emptied", favouritesGone, favouritesLost)
                // The retag: every album and artist under new tags, on the
                // server in use.
                w.library.retagged = true
                val retag = steps.step("mass retag") { saved(w, hooks, watch, "mass retag", user) { w.copy(arriving = false, client = session2.client, sourceId = session2.sourceId) } }
                // For the record: the retag lists every album and artist under
                // a new name and a new id, so neither a copy nor a key leads a
                // favorite to them.
                println("RESULT favourites-1b after the retag, on ids the library lacks: ${favouriteCounts(w, FAVOURITES_LOST)} of ${favouriteCounts(w, FAVOURITES_ALL)}")
                if (starts) steps.step("the starts with no save") { parked.free() }
                for (r in listOf(first, everyday, switch, retag)) println("RESULT save-1b $r")
                val (runMs, runPages) = switch.run
                println("RESULT time-1b the server switch's run ${runMs} ms over $runPages pages (no bound)")

                // The asks of a live list over every song and of the
                // 100,000-entry server list, beside the room each took.
                val live = steps.step("an unlimited live list of every song") { liveAsk(w, space) }
                val list = steps.step("the 100,000-entry server list") { serverListAsk(w, reads, space, drains) }

                // Each save asked as its kind, none below the room it took
                // from its build's end, the first copy, the switch and the
                // retag close to it; the everyday copy asks a retag's room,
                // close to what the retag took. The lists' asks are at
                // least their room here (their close measure is their
                // constants' tests, on long names).
                val everydayAsRetag = Ask("everyday copy against the retag's room", everyday.taken.asked, retag.taken.inUse, retag.taken.log)
                holdAsks(
                    close = listOf(first.taken, switch.taken, retag.taken, everydayAsRetag), atLeast = listOf(everyday.taken, live, list),
                    over = if (LONG_NAMES_RUN) ASK_OVER else ASK_OVER_SHORT_NAMES,
                )
                assertEquals(
                    listOf(SaveKind.FirstCopy, SaveKind.Recopy, SaveKind.Switch, SaveKind.Recopy),
                    listOf(first.kind, everyday.kind, switch.kind, retag.kind),
                )
            } finally {
                rig.die()
                other.close()
                w.db.counted.watch = null
            }
        }
    }
}

// The library's size for the save sections: the proof's, unless
// -PoctoProofSongs asks a smaller one for a dry run of the harness.
private fun proofSongs(): Int = System.getProperty("octo.proofSongs")?.toInt() ?: PROOF_SONGS

// The bounds the bound section saves at, the floor first.
private val SAVE_BOUNDS = listOf(SAVE_GROUP_FLOOR, 512L, 1_024L, 2_048L, 4_096L, 8_192L)

// `full` of the proof's size, scaled to a library of `songs` (in Long, as
// the product passes an Int at the proof's size).
private fun scaled(full: Int, songs: Int): Int = (full.toLong() * songs / PROOF_SONGS).toInt()

// Turns between two measures of the tables' rows and bytes during a save's
// groups.
private const val PEAK_EVERY = 250L

private const val SWITCH_CASE = "switch"
private const val RETAG_CASE = "retag"

// A 200,000-entry queue (every song, one list, joined) and 100,000 plays on
// the phone (every second song's), written beside the proof library, and
// the totals built from them, as the find and the totals walk them.
private suspend fun queueAndPlays(w: ProofWorld) {
    w.db.write("INSERT INTO queue_list (listId, queueId, autoplay, seed, fillState) VALUES (1, 1, 0, 0, ?)", QueueBuilds.JOINED)
    w.db.write("INSERT INTO queue_entry (queueId, entry, trackId, listId, qk, sk, durationMs) SELECT 1, rowid, id, 1, rowid, rowid, durationMs FROM track")
    w.db.write("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) SELECT id, relinkKey, 1700000000000 + rowid, 60000, durationMs FROM track WHERE rowid % 2 = 0")
    val totals = w.totals()
    totals.start()
    totals.builtThrough.first { it >= 0 }
}

// 300 favorite albums and 50 favorite artists (the first of each, so the
// ones the everyday copy renames among them), the first 10 of each pinned,
// under the keys the app gives them: the library's search keys.
private suspend fun favourites(w: ProofWorld) {
    fun native(prefix: String, n: Int) = (0 until n).joinToString { "'$prefix$it'" }
    val artistNative = "substr(id, length(sourceId) + 2)"
    w.db.write("INSERT OR IGNORE INTO liked_album (albumId, relinkKey, likedAt) SELECT id, searchKey, 1 FROM album WHERE nativeId IN (${native("al", 300)})")
    w.db.write("INSERT OR IGNORE INTO liked_artist (artistId, relinkKey, likedAt) SELECT id, searchKey, 1 FROM artist WHERE $artistNative IN (${native("ar", 50)})")
    w.db.write("INSERT OR IGNORE INTO pinned_item (kind, itemId, relinkKey, position) SELECT 'album', id, searchKey, 0 FROM album WHERE nativeId IN (${native("al", 10)})")
    w.db.write("INSERT OR IGNORE INTO pinned_item (kind, itemId, relinkKey, position) SELECT 'artist', id, searchKey, 1 FROM artist WHERE $artistNative IN (${native("ar", 10)})")
    println("RESULT favourites-1b written: ${favouriteCounts(w, FAVOURITES_ALL)}")
}

// Which favorites `favouriteCounts` counts, each as the condition on a row
// `l` of the table whose id column is `col`, naming an album or artist of
// table `x`: every one; those on an id the library lacks; those on one it
// has, under a key neither it nor any of its copies (the source albums or
// artists of its songs' copies) holds.
private const val FAVOURITES_ALL = "1"
private const val FAVOURITES_LOST = "NOT EXISTS (SELECT 1 FROM {x} x WHERE x.id = l.{col})"
private const val FAVOURITES_UNKEYED = "EXISTS (SELECT 1 FROM {x} x WHERE x.id = l.{col} AND x.searchKey != '' AND x.searchKey != l.relinkKey " +
    "AND NOT EXISTS (SELECT 1 FROM track t JOIN source_track s ON s.mergedId = t.id JOIN source_{x} a ON a.id = s.{x}Id WHERE t.{x}Id = x.id AND a.searchKey = l.relinkKey))"
private val FAVOURITES_NONE = listOf(0L, 0L, 0L, 0L)

// Favorite albums, favorite artists, pinned albums and pinned artists,
// counted by `which`.
private fun favouriteCounts(w: ProofWorld, which: String): List<Long> =
    listOf(Triple("liked_album", "albumId", "album"), Triple("liked_artist", "artistId", "artist"), Triple("pinned_item", "itemId", "album"), Triple("pinned_item", "itemId", "artist"))
        .map { (table, col, x) ->
            val pin = if (table == "pinned_item") "l.kind = '$x' AND " else ""
            w.db.readLong("SELECT COUNT(*) FROM $table l WHERE $pin${which.replace("{x}", x).replace("{col}", col)}")
        }

// A few favorite albums on ids the library lacks, each with its key, how
// many albums are filed under that key, and how many favorites hold it.
private suspend fun lostFavourites(w: ProofWorld): List<String> = w.db.read(
    "SELECT l.albumId, l.relinkKey, (SELECT COUNT(*) FROM album a WHERE a.searchKey = l.relinkKey), (SELECT COUNT(*) FROM liked_album o WHERE o.relinkKey = l.relinkKey) " +
        "FROM liked_album l WHERE NOT EXISTS (SELECT 1 FROM album x WHERE x.id = l.albumId) ORDER BY l.albumId LIMIT 5",
) { "${it.getString(0)} '${it.getString(1)}' albums ${it.getLong(2)} favorites ${it.getLong(3)}" }

// A case staged, claimed and built in a library copy of its own, its claim
// as the save and today's swap take it.
private class StagedCase(val label: String, val db: CatalogDatabase, val server: String, val claim: SaveClaim, val todayClaim: TodayClaim)

// A case's built and today's libraries kept in a folder between runs, with
// its claim (label, server, sources, then each claimed copy's generation).
private object KeptCase {
    fun ready(dir: File) = File(dir, "claim.txt").isFile && File(dir, "built/$CATALOG_FILE").isFile && File(dir, "today/$CATALOG_FILE").isFile

    fun keep(dir: File, s: StagedCase, today: CatalogDatabase) {
        dir.deleteRecursively()
        for ((name, db) in listOf("built" to s.db, "today" to today)) {
            val sql = db.openHelper.writableDatabase
            sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            val from = File(sql.path!!)
            val to = File(dir, name).apply { mkdirs() }
            from.copyTo(File(to, CATALOG_FILE))
            File(from.path + "-wal").takeIf { it.exists() }?.copyTo(File(to, "$CATALOG_FILE-wal"))
        }
        val staged = s.claim.staged.entries.joinToString("\t") { "${it.key}=${it.value}" }
        File(dir, "claim.txt").writeText(listOf(s.label, s.server, s.claim.sources.joinToString("\t"), staged).joinToString("\n"))
    }

    // The kept libraries, each opened from a copy of its own.
    fun open(dir: File): Pair<StagedCase, CatalogDatabase> {
        val (label, server, sources, staged) = File(dir, "claim.txt").readText().split("\n")
        val ids = sources.split('\t').filter { it.isNotEmpty() }
        val claimed = staged.split('\t').filter { it.isNotEmpty() }.associate { it.substringBeforeLast('=') to it.substringAfterLast('=').toLong() }
        val built = openCopy(File(dir, "built"))
        val today = openCopy(File(dir, "today"))
        return StagedCase(label, built, server, SaveClaim(ids, claimed), TodayClaim(ids, claimed, server)) to today
    }

    // A library file and its log copied into a folder of their own and
    // opened as copyCatalog opens one, with no open cleanup.
    private fun openCopy(from: File): CatalogDatabase {
        val dir = Files.createTempDirectory("catalog-copy").toFile()
        from.listFiles()!!.filter { it.isFile }.forEach { it.copyTo(File(dir, it.name)) }
        return Room.databaseBuilder(testContext(), CatalogDatabase::class.java, File(dir, CATALOG_FILE).path)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(MIGRATION_18_19)
            .openHelperFactory(CountingOpenHelperFactory())
            .allowMainThreadQueries()
            .build()
    }
}

// A library copy, closed and its folder gone.
private fun closeCopy(db: CatalogDatabase) {
    val dir = File(db.openHelper.writableDatabase.path!!).parentFile
    db.close()
    dir?.deleteRecursively()
}

// Stages the case's copy of `server` (the client's library as it is now)
// into a copy of the world's library, as a copy does, and claims and builds
// it as a merge does; prints the copy's asks beside the room it took.
private suspend fun stagedCase(w: ProofWorld, label: String, client: SubsonicClient, server: String): StagedCase {
    val db = copyCatalog(w.db)
    val merge = db.boundMerge(server, SAVE_GROUP_ENTRIES)
    val copy = StagedCopy(db, merge, FreeSpace { Long.MAX_VALUE })
    val space = SpaceProbe(db)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val generation = copy.start("proof", server)
        val start = space.inUse()
        var readEnd = 0L
        val report = object : CopyReport {
            override fun at(step: CopyStep, done: Long, of: Long?, artwork: String?) {
                if (step == CopyStep.Build && readEnd == 0L) readEnd = space.inUse()
            }
        }
        space.start(scope)
        val counts = copy.fill("proof", server, generation, client, report)
        space.stop()
        // The songs read, which the build's ask counts (its pages leave as
        // their albums go into the stage).
        val songs = counts.tracks.toLong()
        // StagedCopy.readNeed at the read's last look (ten pages ahead) and
        // the build's own ask, each twice what is still to come.
        val ahead = 10L * LIBRARY_PAGE
        val readAsk = 2 * (ahead * SYNC_BYTES_PER_SONG + (songs + ahead) * STAGE_BYTES_PER_SONG)
        val buildAsk = 2 * songs * STAGE_BYTES_PER_SONG
        println(
            "RESULT ask-1b $label stage: ${counts.tracks} songs; the read took ${(readEnd - start).mb()} MB; the build's ask ${buildAsk.mb()} MB " +
                "(the read's last ${readAsk.mb()} MB), the room it took ${(space.mostInUse - readEnd).mb()} MB in use and the log's most ${space.mostLog.mb()} MB",
        )
        val (todayClaim, claim) = claimAndBuild(db, server)
        return StagedCase(label, db, server, claim, todayClaim)
    } catch (e: Throwable) {
        closeCopy(db)
        throw e
    } finally {
        scope.cancel()
    }
}

// From a copy of the case: the find's walks at the default bound (its rows
// are the find's own, which the oracle leaves out), then today's swap, the
// adoption it lacked, and today's totals rebuild.
private suspend fun todayOf(s: StagedCase): CatalogDatabase {
    val today = copyCatalog(s.db)
    try {
        val watch = SaveWatch(today.openHelper.writableDatabase, SAVE_GROUP_ENTRIES)
        today.counted.watch = watch
        val found = SaveFind(today, SAVE_GROUP_ENTRIES, WalkHooks(watch)).find(s.claim)
        today.counted.watch = null
        val walks = watch.walks()
        for (walk in walks) println("RESULT walk-1b ${s.label} $walk")
        println("RESULT walk-1b ${s.label} found $found")
        assertTrue("${s.label}: no walk of the find was counted", walks.any { it.rows > 0 })
        val over = walks.filter { it.most > MERGE_PAGE }
        assertTrue("${s.label}: a walk of the find read more than $MERGE_PAGE rows in one statement: $over", over.isEmpty())
        todaySwap(today, s.todayClaim)
        todayAdoptions(today)
        todayKeys(today)
        val totals = PlayTotals(today, today.testMerge(s.server), FreeSpace { Long.MAX_VALUE }, MutableStateFlow("proof"), {})
        totals.start()
        totals.builtThrough.first { it >= 0 }
        return today
    } catch (e: Throwable) {
        closeCopy(today)
        throw e
    }
}

// One save at proof size, as the bound section prints it.
private class BoundRow(
    val case: String,
    val bound: Long,
    val groups: Long,
    val runs: Int,
    val transactions: Long,
    val largest: Long,
    val runMs: Long,
    val longestMs: Long,
    val waitMs: Long,
    val waitedOut: Int,
    val saveMs: Long,
    val medianMs: Long,
    val p99Ms: Long,
    val overOne: Int,
    val queued: Int,
) {
    override fun toString() =
        "case=$case bound=$bound groups=$groups runs=$runs transactions=$transactions largestCost=$largest runMs=$runMs " +
            "longestTransactionMs=$longestMs transactionMedianMs=$medianMs transactionP99Ms=$p99Ms longestWaitBesideMs=$waitMs mostWaitedOut=$waitedOut " +
            "mostOnceQueued=$queued waitsOverOne=$overOne saveMs=$saveMs"
}

// What a save's groups did, as the bound section and the save section read
// them: the groups planned and which are runs (a set over what a group
// holds, written a page a transaction), the transactions, and each run's
// length, from its first page to the end of its last.
private class GroupStats {
    @Volatile var planned = 0L
        private set
    @Volatile var transactions = 0L
        private set
    private val runs = HashSet<Long>()
    private val first = HashMap<Long, Long>()
    private val last = HashMap<Long, Long>()
    private val pages = HashMap<Long, Int>()
    private var current = 0L

    // At the save's check, before its first group: the plan's groups and its
    // runs, for a save of `bound`.
    suspend fun plan(db: CatalogDatabase, bound: Long) = withContext(WriteLabels.of(PROOF_READ)) {
        val capacity = bound - SaveCosts.of(db.openHelper.writableDatabase, SAVE_COST_TABLES).groupOverhead()
        planned = maxOf(db.readLong("SELECT COALESCE(MAX(groupId), 0) FROM save_set"), db.readLong("SELECT COALESCE(MAX(groupId), 0) FROM save_found"))
        // A page of MERGE_PAGE sets at a time, so no read of the proof's own
        // reads more than the app's read page.
        var after = Long.MIN_VALUE
        while (true) {
            val found = db.read("SELECT setId, groupId FROM save_set WHERE setId > ? AND cost > ? ORDER BY setId LIMIT ?", after, capacity, MERGE_PAGE) { it.getLong(0) to it.getLong(1) }
            synchronized(this@GroupStats) { found.forEach { runs += it.second } }
            if (found.size < MERGE_PAGE) break
            after = found.last().first
        }
    }

    @Synchronized fun inGroup(group: Long) {
        current = group
        if (group in runs) {
            first.putIfAbsent(group, System.nanoTime())
            pages.merge(group, 1, Int::plus)
        }
    }

    @Synchronized fun between() {
        transactions++
        if (current in runs) last[current] = System.nanoTime()
    }

    @Synchronized fun runCount(): Int = runs.size

    @Synchronized fun isRun(group: Long): Boolean = group in runs

    // The pages of a run begun so far.
    @Synchronized fun pagesOf(group: Long): Int = pages[group] ?: 0

    // The longest run's length in milliseconds, and its pages.
    @Synchronized fun longestRun(): Pair<Long, Int> =
        runs.filter { it in first && it in last }.map { (last.getValue(it) - first.getValue(it)) / 1_000_000 to pages.getValue(it) }.maxByOrNull { it.first } ?: (0L to 0)

    // Whether a run took more than one page.
    @Synchronized fun runPaged(): Boolean = pages.values.any { it > 1 }
}

// The case saved from a copy of it at `bound`, another writer beside it,
// and held to today's (`today`) by the oracle.
private suspend fun boundRun(s: StagedCase, today: CatalogDatabase, bound: Long, steps: Steps): BoundRow {
    val db = copyCatalog(s.db)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        steps.counting(db)
        val sql = db.openHelper.writableDatabase
        val watch = SaveWatch(sql, bound)
        val groups = GroupStats()
        var turnsDone = 0L
        val space = SpaceProbe(db)
        val hooks = object : MergeHooks() {
            override suspend fun checking() {
                groups.plan(db, watch.bound)
            }

            override suspend fun inGroup(turn: Long, group: Long) = groups.inGroup(group)

            override suspend fun betweenGroups(turn: Long) = groups.between()

            override fun applied(turns: Long, rows: Long, millis: Long) {
                turnsDone = turns
            }
        }
        val merge = db.boundMerge(s.server, bound, hooks)
        watch.bound = merge.saveBoundFor(s.claim.sources.size.toLong())
        db.counted.watch = watch
        val rival = Rival(db, merge)
        val countBefore = db.workDao().swapCount()
        val start = space.inUse()
        rival.start(scope)
        space.start(scope)
        val at = System.nanoTime()
        merge.saveBuilt(s.claim.sources, s.claim.staged, s.server)
        val saveMs = (System.nanoTime() - at) / 1_000_000
        rival.stop()
        space.stop()
        db.counted.watch = null
        val where = "${s.label}, bound $bound"
        val costs = watch.costs()
        costs.forEach { println("RESULT cost-1b $where $it") }
        val apply = watch.costOf(SAVE_APPLY)
        val find = watch.costOf(SAVE_FIND)
        assertTrue("$where: no apply transaction measured", apply != null && apply.transactions > 1)
        assertTrue("$where: no find transaction measured", find != null && find.transactions > 0)
        for (c in listOfNotNull(apply, find)) assertEquals("$where: ${c.label} transactions over the bound ${watch.bound}: ${c.firstOver}", 0L, c.over)
        val (outside, outsideSql) = watch.outsideMerge()
        assertEquals("$where: the merge's writes outside a transaction (the last: $outsideSql)", 0L, outside)
        val wait = rival.waits.summary(watch)
        println("RESULT wait-1b $where $wait")
        assertTrue("$where: the writer beside the save never wrote while it was part way", wait.during > 0)
        assertTrue("$where: ${groups.planned} groups", groups.planned > 1)
        assertTrue("$where: no run", groups.runCount() > 0 && groups.runPaged())
        if (bound == SAVE_GROUP_ENTRIES) {
            // The save from its built claim (no free space check runs here;
            // the short save section holds the asks).
            println("RESULT ask-1b ${s.label} save: the room it took ${(space.mostInUse - start).mb()} MB in use and the log's most ${space.mostLog.mb()} MB")
        }
        // The oracle, and its carve-outs; its own reads are no app's, so the
        // most read so far is kept first and its counts dropped after.
        steps.keepMostRead()
        val result = StreamedOracle.compare(today.openHelper.writableDatabase, sql, ORACLE_SKIPPED, SaveOracle.CARVED)
        db.counted.reset()
        println("RESULT oracle-1b $where: ${result.rows} rows of ${result.tables} tables compared, ${result.differences.size} differences")
        assertTrue("$where: ${result.differences.joinToString("\n")}", result.differences.isEmpty())
        assertTrue("$where: nothing compared", result.rows > db.readLong("SELECT COUNT(*) FROM track"))
        assertEquals("$where: the swap count rises once a group", countBefore + turnsDone, db.workDao().swapCount())
        assertNull("$where: an apply record is left", db.read("SELECT applyClaim FROM merge_state WHERE id = 0") { it.getString(0) }.single())
        assertEquals("$where: the totals' account", "proof", db.read("SELECT totalsAccount FROM merge_state WHERE id = 0") { it.getString(0) }.single())
        val (runMs, runPages) = groups.longestRun()
        println("RESULT run-1b $where: the longest run ${runMs} ms over $runPages pages")
        return BoundRow(
            s.label, bound, groups.planned, groups.runCount(), groups.transactions, apply!!.largest, runMs, apply.longestNanos / 1_000_000,
            wait.longestMs, wait.mostGroups, saveMs, apply.holdAt(0.5), apply.holdAt(0.99), wait.overOne, wait.mostQueued,
        )
    } finally {
        scope.cancel()
        closeCopy(db)
    }
}

// Tables the oracle at proof size leaves out: SaveOracle's (the merge's
// work rows and the renames in force), the table of the writer beside the
// save, and the totals rebuild's own work rows, which today's side runs and
// the save does not (cleared at the next rebuild's opening).
private val ORACLE_SKIPPED = SaveOracle.CLEARED + SaveOracle.CARVED_TABLES + setOf(RIVAL_TABLE, "play_total_diff")

private fun Long.mb(): Long = this / 1_000_000

private const val DAY = 86_400_000L

// Round 1b's indexes on the library's songs and their copies, and the
// three on `track` it replaced, as base had them.
private val INDEXES_ADDED = listOf(
    "index_track_sortKey_id", "index_track_title", "index_track_by_artist", "index_track_by_album", "index_track_added",
    "index_track_year", "index_track_length", "index_track_rating", "index_track_genre", "index_track_album_songs",
    "index_track_artist_songs", "index_track_artist_artistId_rating", "index_source_track_folder", "index_source_track_sourceId_matchKey",
)
private val INDEXES_REPLACED = listOf("index_track_albumId" to "albumId", "index_track_artistId" to "artistId", "index_track_sortKey" to "sortKey")

private const val BIG_ID = "pl-big"
private const val ASKS_ID = "pl-asks"
private const val FAMILY_ID = "pl-family"
private const val PLAIN_ID = "pl-plain"

// Watches a playlist read's resolution: the database's size before it and
// when its pool walk begins (the work rows written by then), how long the
// read and links took, how many songs the pool holds, and the walk's pages,
// parses and length.
private class PoolProbe(private val db: CatalogDatabase, val sizeBefore: Long) : ReadHooks() {
    private val started = System.nanoTime()
    private var poolStart = 0L
    var sizeAtPool = 0L
    var toPoolMs = 0L
    var pages = 0
    var walkedMs = 0L
    var poolSongs = 0L
    var songsParsed = 0L
    var groupsParsed = 0L

    override fun parsed(read: Long, songs: Int, groups: Int) {
        if (poolStart == 0L) {
            poolStart = System.nanoTime()
            toPoolMs = (poolStart - started) / 1_000_000
            sizeAtPool = db.readLong("PRAGMA page_count") * db.readLong("PRAGMA page_size")
            poolSongs = db.readLong("SELECT COUNT(*) FROM playlist_key k CROSS JOIN track t ON t.searchKey = k.`key` WHERE k.read = ?", read)
        }
        songsParsed += songs
        groupsParsed += groups
    }

    override suspend fun pooled(read: Long, pages: Int) {
        this.pages = pages
        walkedMs = (System.nanoTime() - poolStart) / 1_000_000
    }
}

// A section run with its steps recorded, under the heap proof's switch.
private fun proof(section: String, block: suspend (Steps) -> Unit) {
    assumeTrue(System.getProperty("octo.heapProof") == "true")
    val steps = Steps(section)
    try {
        runBlocking { block(steps) }
    } catch (failure: Throwable) {
        println("RESULT heap-1b $section FAILED, cap ${Heap.capMb()} MB, setup peak ${steps.setupPeak} MB, section peak ${steps.sectionPeak} MB, at \"${steps.now}\": $failure")
        throw failure
    }
    println(
        "RESULT heap-1b $section passed, $PROOF_SONGS songs, cap ${Heap.capMb()} MB, setup peak ${steps.setupPeak} MB, " +
            "section peak ${steps.sectionPeak} MB (in use at most ${steps.sectionInUse} MB at one moment), the log's most ${steps.sectionLog / 1_000_000} MB, " +
            "most rows one statement read ${steps.mostRead()}",
    )
}

private object Heap {
    private val pools get() = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }

    fun reset() {
        System.gc()
        pools.forEach { it.resetPeakUsage() }
    }

    fun peakMb(): Long = pools.sumOf { it.peakUsage.used } / 1_000_000

    fun capMb(): Long = Runtime.getRuntime().maxMemory() / 1_000_000

    // The heap in use, read every 20 ms on a thread of its own until
    // stopped: the most at one moment.
    // With `log`, the write-ahead log file's length too: its most.
    class Sampler(private val log: File? = null) {
        @Volatile private var running = true
        @Volatile var mostMb = 0L
            private set
        @Volatile var mostLog = 0L
            private set
        private val thread = Thread {
            val memory = ManagementFactory.getMemoryMXBean()
            while (running) {
                mostMb = maxOf(mostMb, memory.heapMemoryUsage.used / 1_000_000)
                log?.let { mostLog = maxOf(mostLog, it.length()) }
                try {
                    Thread.sleep(20)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        fun stop() {
            running = false
            thread.interrupt()
            thread.join()
        }
    }
}

// Each step's time and peak heap, the section's setup apart from its own
// steps, and the most rows one statement read once the setup ended. A
// section may reset the database's counts between its steps for its own
// lines; the most read is taken at the end of every step, so it outlives
// those resets.
private class Steps(private val section: String) {
    var setupPeak = 0L
    var sectionPeak = 0L
    var now = "start"
    private var db: CatalogDatabase? = null
    private var mostRead: Counted? = null

    suspend fun <T> setup(name: String, block: suspend () -> T): T = run(name, true, block)

    suspend fun <T> step(name: String, block: suspend () -> T): T = run(name, false, block)

    // The setup ended: statements are counted from here.
    fun started(db: CatalogDatabase) {
        this.db = db
        db.counted.reset()
        mostRead = null
    }

    // Statements of another database are counted from here, the most read
    // so far kept.
    fun counting(db: CatalogDatabase) {
        keepMostRead()
        this.db = db
        db.counted.reset()
    }

    fun mostRead(): String {
        keepMostRead()
        return mostRead?.let { "${it.rowsRead} (${it.sql.take(100)})" } ?: "none counted"
    }

    // Keeps the most read so far; a step calls it before it resets the
    // database's counts for lines of its own.
    fun keepMostRead() {
        val now = db?.counted?.maxRead ?: return
        if (mostRead.let { it == null || now.rowsRead > it.rowsRead }) mostRead = now
    }

    // The most heap in use at one moment during the section's own steps,
    // sampled (the pools' peaks summed can pass the cap, each pool peaking
    // at another moment).
    var sectionInUse = 0L

    // The library's write-ahead log at its longest during the section's own
    // steps.
    var sectionLog = 0L

    private suspend fun <T> run(name: String, setup: Boolean, block: suspend () -> T): T {
        now = name
        progress("begin $section ${if (setup) "setup " else ""}$name")
        Heap.reset()
        val sampler = Heap.Sampler(db?.openHelper?.writableDatabase?.path?.let { File("$it-wal") })
        val start = System.nanoTime()
        val out = try {
            block()
        } finally {
            sampler.stop()
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        val peak = Heap.peakMb()
        if (setup) setupPeak = maxOf(setupPeak, peak) else sectionPeak = maxOf(sectionPeak, peak)
        if (!setup) sectionInUse = maxOf(sectionInUse, sampler.mostMb)
        if (!setup) sectionLog = maxOf(sectionLog, sampler.mostLog)
        if (!setup) keepMostRead()
        val line = "STEP heap-1b $section ${if (setup) "setup " else ""}$name: $ms ms, peak heap $peak MB, in use at most ${sampler.mostMb} MB, the log's most ${sampler.mostLog / 1_000_000} MB"
        println(line)
        progress(line)
        return out
    }
}

// A line of a proof's progress, also kept as it happens in the module's
// build folder (the test's output reaches its report only at the end).
internal fun progress(line: String) {
    runCatching { File("build/proof-progress.txt").appendText("${java.time.LocalTime.now()} $line\n") }
}

// A probe timed at proof size, with the bound it is held to.
private suspend fun <T> timed(name: String, bound: String, block: suspend () -> T): T {
    val start = System.nanoTime()
    val out = block()
    println("RESULT time-1b $name ${(System.nanoTime() - start) / 1_000_000} ms ($bound)")
    return out
}

// What a screen does with a list: its count, its survey, three pages from
// the top, a jump to the middle with a page each way, a jump to the end,
// and a tap's small check, which is timed.
private suspend fun <T : Any> browse(name: String, loader: ListLoader<T>, expect: Int?) {
    val n = loader.count()
    if (expect != null) assertEquals(expect, n)
    loader.survey(null)
    var after: RowKey? = null
    repeat(3) { after = loader.after(after, PAGE).lastOrNull()?.key ?: return@repeat }
    for (at in listOf(n / 2, n - 1)) {
        if (at < 0) continue
        val key = loader.keyAt(at) ?: continue
        loader.survey(key)
        loader.before(key, PAGE)
        loader.after(key, PAGE)
    }
    val small = timed("small check, $name ($n rows)", "no bound; one bounded read at a tap") { loader.small() }
    if (n > 500) assertNull("a list of $n is big", small)
}

// Every row of a list, a page at a time; how many.
private suspend fun <T : Any> walkPages(loader: ListLoader<T>): Int {
    var n = 0
    var after: RowKey? = null
    while (true) {
        val page = loader.after(after, 500)
        n += page.size
        if (page.size < 500) return n
        after = page.last().key
    }
}

// Rows the database's writing connection has written since it opened.
private suspend fun totalChanges(db: CatalogDatabase): Long = db.withTransaction { db.readLong("SELECT total_changes()") }

// One change of about 2,000 songs' lengths, some of them crossing the list's
// rule, while a list of `query` is kept the way `keepHeld` says, with an
// answer holding the shown generation through the walk when `held`; prints
// the walk's length, the rows it wrote, and the most rows the list held
// during the walk beyond the larger of its counts before and after.
private suspend fun liveChange(w: ProofWorld, label: String, query: LibraryQuery, keepHeld: Boolean, held: Boolean) {
    val owner = WorkOwners.marked("car:proof-${if (keepHeld) "kept" else "in-place"}-${if (held) "held" else "free"}")
    val picks = LivePicks(w.db, w.merge, w.totals(), WorkOwners(), FreeSpace { Long.MAX_VALUE }, System::currentTimeMillis, 500)
    val walked = Channel<Long>(Channel.UNLIMITED)
    val most = AtomicLong()
    fun sample() = most.accumulateAndGet(w.db.readLong("SELECT COUNT(*) FROM live_song WHERE owner = ?", owner), ::maxOf)
    picks.hooks = object : LiveHooks() {
        override suspend fun page(owner: String, n: Int) {
            if (n % 25 == 0) sample()
        }

        override suspend fun published(owner: String, gen: Long) {
            walked.trySend(gen)
        }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        scope.launch { picks.watch(owner, null, flowOf(query), keepHeld).collect {} }
        walked.receive()
        val before = checkNotNull(picks.shown(owner))
        val pin = if (held) checkNotNull(picks.pinShown(owner)) else null
        most.set(0)
        w.db.write("UPDATE track SET durationMs = durationMs + 30000 WHERE rowid % 100 = 0")
        val from = totalChanges(w.db)
        val start = System.nanoTime()
        walked.receive()
        val millis = (System.nanoTime() - start) / 1_000_000
        sample()
        val wrote = totalChanges(w.db) - from
        val after = checkNotNull(picks.shown(owner))
        val extra = most.get() - maxOf(before.count, after.count)
        pin?.let { picks.unpinShown(owner, it.gen) }
        val left = w.db.readLong("SELECT COUNT(*) FROM live_song WHERE owner = ?", owner)
        println(
            "RESULT time-1b car live list of ${before.count} songs, a change of 2,000 songs' lengths, $label: the walk $millis ms, " +
                "$wrote rows written, ${maxOf(extra, 0)} rows held beyond the list (generation ${before.gen} to ${after.gen}, ${after.count} songs after, " +
                "$left rows once nothing holds it)",
        )
        assertTrue("the list holds about 150,000 songs: ${before.count}", before.count in 140_000..160_000)
        assertEquals("only the shown generation is kept once nothing holds another", after.count.toLong(), left)
    } finally {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        picks.clear(owner)
        w.db.write("UPDATE track SET durationMs = durationMs - 30000 WHERE rowid % 100 = 0")
    }
}

// Every row of a matching job's answers, a page at a time; how many.
private suspend fun walkMatches(page: suspend (Long) -> app.winters.octo.playlists.MatchPage): Int {
    var n = 0
    var after = -1L
    while (true) {
        val next = page(after)
        n += next.rows.size
        after = next.next ?: return n
    }
}

// Each claimed merge's save: its rows and length, as the merge's timing line
// gives them, each of its steps' rows summed over its groups, and each
// group's rows (its cost as written).
private open class SwapTimes : MergeHooks() {
    class Step(val name: String, val rows: Long, val millis: Long)

    class Swap(val rows: Long, val millis: Long, val steps: List<Step>, val groups: List<Long> = emptyList()) {
        // The copy's stage emptied after the save, page by page.
        @Volatile var cleared: Step? = null

        // Prints each step that wrote, then the save's groups and its sum,
        // then the stage's clear after it.
        fun report(label: String) {
            for (s in steps) if (s.rows > 0) println("RESULT time-1b $label save step \"${s.name}\": ${s.rows} rows")
            println(
                "RESULT time-1b $label save: $rows rows in $millis ms, ${groups.size} groups, the largest ${groups.maxOrNull() ?: 0} rows, " +
                    "its steps ${steps.sumOf { it.rows }} rows",
            )
            cleared?.let { println("RESULT time-1b $label stage cleared after the save: ${it.rows} rows in ${it.millis} ms, $STAGE_CLEAR_ROWS rows a transaction") }
        }
    }

    private val seen = Collections.synchronizedList(ArrayList<Swap>())
    private val current = Collections.synchronizedMap(LinkedHashMap<String, Long>())
    private val groups = Collections.synchronizedMap(LinkedHashMap<Long, Long>())
    @Volatile private var claimed = false

    fun last(): Swap = seen.last()

    override suspend fun inSwap() {
        claimed = true
    }

    override fun groupStep(turn: Long, step: String, rows: Long) {
        current.merge(step, rows, Long::plus)
        groups.merge(turn, rows, Long::plus)
    }

    override fun applied(turns: Long, rows: Long, millis: Long) {
        if (claimed) seen += Swap(rows, millis, current.map { (name, n) -> Step(name, n, 0) }, groups.values.toList())
        claimed = false
        current.clear()
        groups.clear()
    }

    override fun stageCleared(sourceId: String, rows: Long, millis: Long) {
        seen.lastOrNull()?.cleared = Step("stage clear", rows, millis)
    }
}

// The proof library served by a fake server, copied into a library file of
// its own, with the pieces each section drives. `familySize` sets the
// family playlist's length; `fixture`, a folder holding a library file a
// world made before, is copied in to start from; `hooks`, `space` and
// `work` are the merge's, for a section that watches or stops it, and
// `bound` its save's bound.
private class ProofWorld(
    familySize: Int = 1_000,
    fixture: File? = null,
    hooks: SwapTimes = SwapTimes(),
    space: FreeSpace = FreeSpace { Long.MAX_VALUE },
    work: (CatalogDatabase) -> WorkDao = { it.workDao() },
    bound: Long = SAVE_GROUP_ENTRIES,
    // The library's songs: the proof's size, or a small one for a section's
    // dry run (the rest scaled from it).
    val songs: Int = PROOF_SONGS,
) : Closeable {
    val starred = scaled(PROOF_STARRED, songs)
    val library = LazyLibrary(
        songs = songs, albums = songs / 10 + 1, artists = songs / 40, unknownAlbumSongs = songs / 2,
        bigGenreSongs = scaled(BIG_GENRE, songs), pairedAlbums = true,
    )
    val fake = LazySubsonic(
        library,
        starredSongs = starred,
        playlists = listOf(
            LazyPlaylist.mixed(library, BIG_ID, "Everything, mixed", songs),
            LazyPlaylist.family(library, FAMILY_ID, "Family mix", familySize),
            LazyPlaylist(PLAIN_ID, "Every song", songs) { k -> library.song(k) },
        ),
        onlineAlbumSongs = 600,
    )
    private val sizes = mapOf(BIG_ID to songs, FAMILY_ID to familySize, PLAIN_ID to songs)
    val dir: File = Files.createTempDirectory("heap1b").toFile().also { dir -> fixture?.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(dir, it.name)) } }
    val db = openTestCatalog(dir)
    val swaps = hooks
    val session = Session(fake.client(), serverType = null, serverVersion = null, isOcto = false, adminReachable = false, extensions = emptySet(), id = "proof")
    val source = session.sourceId

    // The server in use, which the library is of: this world's own until a
    // section switches it.
    @Volatile var serverInUse: String = source
    val merge = CatalogMerge(db, space, { serverInUse }, work(db), bound).also { it.hooks = swaps }
    val account = MutableStateFlow<String?>("proof")
    private val store = ListeningStore(MemoryPrefs(), account, db.listeningDao())
    val sets = PlaylistSets(db)
    val reads = PlaylistReads(db, Drains(File(dir, "drain").apply { mkdirs() }), FreeSpace { Long.MAX_VALUE }, { source })
    val playlistSteps = PlaylistSteps(db, sets, merge, reads, PlaylistWalks(db, sets, 500), { null }, { _, _ -> })

    // A whole copy as Sync now makes one, the copy's progress reported as
    // the app's is: of this world's server, or of another one's.
    suspend fun copy(arriving: Boolean, client: SubsonicClient = session.client, sourceId: String = source) {
        val tracker = CopyTracker()
        val token = tracker.begin("proof", "Proof", arriving = arriving, whole = true)
        val pass = tracker.nextPass(token, arriving = arriving, whole = true)!!
        tracker.planned(token, pass, arriving = arriving, whole = true)
        StagedCopy(db, merge, FreeSpace { Long.MAX_VALUE }).copy("proof", sourceId, client, tracker.report(token, pass))
        // The copy's stage leaves in the background after its swap; the
        // section's next step starts once it has.
        merge.awaitStageClear(sourceId)
    }

    fun listeningSync() = ListeningSync({ session }, db.sourceDao(), db.userDao(), db.dao(), db.listeningDao(), store, TestRebuilds())

    suspend fun afterSync() = listeningSync().afterSync()

    fun totals() = PlayTotals(db, merge, FreeSpace { Long.MAX_VALUE }, account, {})

    fun history(totals: PlayTotals) = PlayHistory(store, totals, merge, db, 500, System::currentTimeMillis, keepMillis = 0)

    fun server(formPost: Boolean) = SyncServer(session.client, source, "proof", formPost, familyRules = true)

    // A server playlist's stamp as the list of playlists gives it.
    fun stamp(serverId: String): String = stampOf("2026-11-01T00:00:00Z", sizes.getValue(serverId))

    // Imports a server playlist; its id on the phone.
    suspend fun import(serverId: String, formPost: Boolean = false): String {
        playlistSteps.run(server(formPost), PlaylistStep.Import(serverId, stamp(serverId)), counts = mapOf(serverId to sizes.getValue(serverId)))
        return db.read("SELECT id FROM playlist WHERE serverId = ?", serverId) { it.getString(0) }.single()
    }

    // A phone playlist of every library song, made by the app's own add, a
    // page at a time; its id.
    suspend fun bigPlaylist(): String {
        val now = System.currentTimeMillis()
        db.userDao().insertPlaylist(PlaylistEntity("phone-big", "Every song", createdAt = now, updatedAt = now))
        addLibrarySongs(PlaylistEdits(db, sets), "phone-big", PROOF_SONGS, from = 0)
        return "phone-big"
    }

    // Adds `n` library songs to a playlist, from the song at `from` in
    // library rowid order, a page at a time.
    suspend fun addLibrarySongs(edits: PlaylistEdits, playlistId: String, n: Int, from: Int) {
        edits.add(
            playlistId,
            { emit ->
                for (start in from until from + n step 500) {
                    emit(db.read("SELECT id, relinkKey FROM track ORDER BY rowid LIMIT ? OFFSET ?", minOf(500, from + n - start), start) { SongToAdd(it.getString(0), it.getString(1)) })
                }
            },
            distinct = false, newOnly = false, now = System.currentTimeMillis(),
        )
    }

    // Removes a playlist's first `n` rows, a page at a time.
    suspend fun removeFirst(edits: PlaylistEdits, playlistId: String, n: Int) {
        var left = n
        while (left > 0) {
            val keys = db.read(
                "SELECT i.itemKey FROM playlist p CROSS JOIN playlist_item i ON i.playlistId = p.id AND i.itemSet = p.itemSet WHERE p.id = ? ORDER BY i.ord, i.itemKey LIMIT ?",
                playlistId, minOf(500, left),
            ) { it.getLong(0) }
            if (keys.isEmpty()) return
            edits.removeAll(playlistId, keys, System.currentTimeMillis())
            left -= keys.size
        }
    }

    // Moves the playlist's row at place 10 to just past place 150,000.
    suspend fun moveOne(edits: PlaylistEdits, playlistId: String) {
        fun keyAt(at: Int) = db.read(
            "SELECT i.itemKey FROM playlist p CROSS JOIN playlist_item i ON i.playlistId = p.id AND i.itemSet = p.itemSet WHERE p.id = ? ORDER BY i.ord, i.itemKey LIMIT 1 OFFSET ?",
            playlistId, at,
        ) { it.getLong(0) }.single()
        edits.move(playlistId, keyAt(10), keyAt(150_000), System.currentTimeMillis())
    }

    override fun close() {
        db.close()
        fake.close()
        dir.deleteRecursively()
    }
}

// The labels the tap wait reads writers by: the copy's stage pages and the
// merge's abort as the app labels them, and the take-in and the steps after
// a sync as the section labels them where it calls them, as the app's sync
// calls them.
private const val COPY_WRITE = "copy stage page"
private const val TAKE_IN_WRITE = "take-in"
private const val ABORT_WRITE = "abort"
private const val AFTER_SYNC_WRITE = "after-sync"

// How long a queue case waits for its list, or a tap for its copy, at most.
private const val CASE_MINUTES = 20L

// The merge as the tap wait watches it: whether the copy claimed a stage,
// and when its save began (all its songs placed) and ended (its progress
// gone).
private class TapHooks : SwapTimes() {
    @Volatile var onSaving: (() -> Unit)? = null
    @Volatile var claimedStage = false
    @Volatile var savingAt = 0L
    @Volatile var savedAt = 0L

    // The swap of the last merge that claimed a copy.
    @Volatile var copySwap: SwapTimes.Swap? = null

    fun reset() {
        claimedStage = false
        savingAt = 0L
        savedAt = 0L
        copySwap = null
    }

    override suspend fun claimed() {
        claimedStage = false
    }

    override fun applied(turns: Long, rows: Long, millis: Long) {
        super.applied(turns, rows, millis)
        if (claimedStage) copySwap = last()
    }

    override fun progress(progress: StageProgress?) {
        if (progress == null) {
            if (savingAt != 0L && savedAt == 0L) savedAt = System.nanoTime()
            return
        }
        claimedStage = true
        if (progress.saving && savingAt == 0L) {
            savingAt = System.nanoTime()
            onSaving?.invoke()
        }
    }
}

// What a big list's fill tells a queue case or a tap, each with the time
// it came: a play's start entry asked to play (by list, and the first since
// the tap), Up next at ten songs and full, each Next that waited, and each
// list's join.
private class CaseHooks : FillHooks() {
    @Volatile var since = System.nanoTime()
    @Volatile var first: Long? = null
    @Volatile var ten: Long? = null
    @Volatile var full: Long? = null
    val startedAt = ConcurrentHashMap<Long, Long>()
    val filled = ConcurrentHashMap<Long, Long>()
    val waits: MutableList<Long> = Collections.synchronizedList(ArrayList())

    private fun now() = (System.nanoTime() - since) / 1_000_000

    override fun startPlaying(listId: Long) {
        startedAt.putIfAbsent(listId, System.nanoTime())
        if (first == null) first = now()
    }

    override fun upNextHolds(listId: Long, count: Int) {
        if (ten == null) ten = now() else if (full == null) full = now()
    }

    override fun nextWaited(millis: Long) {
        waits += millis
    }

    override fun filled(listId: Long, rows: Long, bytes: Long) {
        filled.putIfAbsent(listId, now())
    }
}

// The queue as the service holds it over a proof world's library: the
// session's player (the phone's, swapped for a quiet device's so nothing
// loads), the window over Room with the service's find map, its builders
// and first reads at the app's window sizes and pages, and the server's
// lists when given. Songs play as library songs or finds (from the map
// until their rows are written, as the service's items read them), and the
// ids asked of them are counted.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ProofQueue(private val w: ProofWorld, reads: ServerReads? = null, val owners: WorkOwners = WorkOwners()) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    val scope = MainScope()
    val finds = FindMap(w.db.onlineDao(), RealLengths(), scope)
    private val totals = PlayTotals(w.db, w.merge, FreeSpace { Long.MAX_VALUE }, MutableStateFlow("home"), {}, 500)
    private val live = LivePicks(w.db, w.merge, totals, owners, FreeSpace { Long.MAX_VALUE }, System::currentTimeMillis, 500)
    private val health = HealthBuild(w.db, w.merge, owners, FreeSpace { Long.MAX_VALUE }, 500, 100)
    val loaders = ListLoaders(
        w.db, w.merge, FreeSpace { Long.MAX_VALUE }, PlaylistSets(w.db), owners, Drains(File(w.dir, "queue-drain").apply { mkdirs() }), totals, { null },
        { error("no downloads") }, { error("no sorted library") }, { health }, { live }, server = { w.source },
        servers = reads?.let { r -> dagger.Lazy<ServerReads> { r } },
    )
    val store = QueueStore(w.db)
    val builds = QueueBuilds(store, { loaders }, { live }, { health }, w.merge, totals, { Long.MAX_VALUE }, owners, { error("no radio") })
    private val starts = if (reads == null) {
        QueueStarts(w.db, { loaders }, { live }, { health }, owners)
    } else {
        QueueStarts(w.db, { loaders }, { live }, { health }, owners, { reads })
    }
    private val local = OctoPlayer(context, ExoPlayer.Builder(context).build(), ExoPlayer.Builder(context).build())
    private val remote = RemotePlayer(QuietDevice(), AnyMedia(), scope) {}
    lateinit var window: QueueWindow
    val player = OutputSwitch(local) { if (::window.isInitialized) window else null }
    val itemIds = AtomicLong()

    init {
        remote.start(QueueState(emptyList(), 0, 0, playing = false))
        player.useRemote(remote)
        window = QueueWindow(
            player, { player.active }, store, builds, starts, ::items, { "home" }, PlayGuard({ player.replacements }, { "home" }), scope, WindowSizes(),
            finds = finds,
        )
    }

    private suspend fun items(ids: List<String>): List<MediaItem?> {
        itemIds.addAndGet(ids.size.toLong())
        val (found, songs) = ids.distinct().partition(::isFind)
        val held = found.mapNotNull { finds[it] }.associateBy { it.id }
        val tracks = songs.chunked(900).flatMap { w.db.dao().tracksByIdsUnordered(it) }.associateBy { it.id } +
            held.mapValues { it.value.asTrack() } +
            found.filter { it !in held }.chunked(900).flatMap { w.db.onlineDao().byIds(it) }.associate { it.id to it.asTrack() }
        return ids.map { id ->
            tracks[id]?.let { t ->
                MediaItem.Builder().setMediaId(t.id).setUri("http://server/${t.id}")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setDurationMs(t.durationMs).build()).build()
            }
        }
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    // Every job posted to the worker so far has run, and the main looper
    // has nothing left.
    fun settle() = repeat(4) {
        idle()
        run { store.inTurn { } }
        idle()
    }

    // Waits on the work until `done`, the main looper and the worker
    // turning, at most CASE_MINUTES.
    fun until(what: String, done: () -> Boolean) {
        val end = System.nanoTime() + CASE_MINUTES * 60_000_000_000L
        while (!done()) {
            settle()
            if (System.nanoTime() > end) throw AssertionError("never: $what")
            Thread.sleep(5)
        }
    }

    fun current(): String? = player.currentMediaItem?.mediaId

    fun currentEntry(): Long? = entryOfKey(player.currentMediaItem?.entryId)

    // Next as a controller presses it; a Next at the window's edge waits for
    // a read, so this waits for the move unless the queue ends here.
    fun next() {
        val before = currentEntry()
        player.seekToNext()
        settle()
        if (currentEntry() == before) until("Next moved from $before") { currentEntry() != before || (!window.stepping && window.atLogicalEnd) }
    }

    // The row at a place of Songs by title, as a tap names it.
    fun rowAt(place: Int): ListStart.Row = run {
        @Suppress("UNCHECKED_CAST")
        val loader = loaders.forSpec(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), owners.walk()) as ListLoader<Any>
        val row = loader.after(loader.keyAt(place - 1)!!, 1).single()
        ListStart.Row(songOf(loader, row.row)!!, row.key)
    }

    // The service went away: the queue stops where it is, its database kept.
    fun die() {
        window.release()
        player.release()
        scope.cancel()
        store.scope.cancel()
        idle()
    }
}

// The queue cases, in the order the section measures them.
private enum class QueueCaseKind(val label: String) {
    PlayAll("Play all"),
    ShuffleAll("Shuffle all"),
    Row("a tap on row 150,000"),
    ShuffledRow("a tap on row 150,000 with shuffle on"),
    OnWhileWritten("shuffle on while the list is written"),
    OnAfterJoin("shuffle on after the join"),
    PlayNext("a 20,000-song Play next while a list is written"),
    Restore("a restore after the join"),
}

// The queue's library: the proof library copied and its steps after the
// copy run, kept in a folder of its own for every case to start from.
private suspend fun queueFixture(): File {
    val dir = Files.createTempDirectory("heap1b-fixture").toFile()
    ProofWorld().use { w ->
        w.copy(arriving = true)
        w.afterSync()
        w.db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        w.dir.listFiles()!!.filter { it.isFile }.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
    }
    return dir
}

// One queue case over a fresh copy of the queue's library.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class QueueCase(fixture: File, private val case: QueueCaseKind, private val steps: Steps) : Closeable {
    private val w = ProofWorld(fixture = fixture)
    private val hooks = CaseHooks()
    private var rig = ProofQueue(w).also { it.builds.hooks = hooks }
    private val extra = LinkedHashMap<String, Any?>()
    private val titles = ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false))

    // About 22,000 songs: every artist's whose name holds "Artist 1".
    private val filtered = ListSpec.Songs(
        SongScope.All, SortOrder(SongSort.Title, false), LibraryQuery(rules = listOf(QueryRule(QueryField.Artist, QueryOp.Contains, text = "Artist 1"))),
    )
    private var before = 0L
    private var joinMs: Long? = null
    private var rows = 0L
    private var bytes = 0L

    private fun fileBytes(): Long {
        w.db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        return w.dir.listFiles()!!.filter { it.isFile && it.name.startsWith(CATALOG_FILE) }.sumOf { it.length() }
    }

    // A tap: the clock starts, the play answers; its list's id.
    private fun tap(request: PlayRequest): Long {
        val listId = rig.run { rig.store.claimList() }
        hooks.since = System.nanoTime()
        var answer: PlayAnswer? = null
        rig.scope.launch { answer = rig.window.play(request.copy(token = listId)) }
        rig.until("case ${case.label}: the play answered") { answer != null }
        assertTrue("case ${case.label}: ${answer!!.refusal}", answer!!.started)
        return listId
    }

    private fun joined(listId: Long) = rig.until("case ${case.label}: the list joined") { hooks.filled.containsKey(listId) }

    // Up next full, or a minute gone; the time stays unknown then.
    private fun upNextFilled() {
        val end = System.nanoTime() + 60_000_000_000L
        while (hooks.full == null && System.nanoTime() < end) {
            rig.settle()
            Thread.sleep(5)
        }
    }

    // The list joined and Up next full: its join's time, the rows written
    // into the queue and the file's growth since the tap.
    private fun written(listId: Long) {
        joined(listId)
        upNextFilled()
        joinMs = hooks.filled[listId]
        steps.keepMostRead()
        rows = w.db.counted.changedByTable["queue_entry"] ?: 0
        bytes = fileBytes() - before
    }

    private fun nexts(n: Int) {
        val at = System.nanoTime()
        repeat(n) { rig.next() }
        extra["${n}NextsMs"] = (System.nanoTime() - at) / 1_000_000
    }

    private fun toggle(on: Boolean): Long {
        val at = System.nanoTime()
        rig.player.shuffleModeEnabled = on
        rig.settle()
        return (System.nanoTime() - at) / 1_000_000
    }

    private fun queueId() = rig.window.queueId

    // Brandon's rule for shuffle turned on and for a tapped row with shuffle
    // on: the song playing first in the order that plays, then every other
    // song of the queue once; Next plays the second.
    private fun assertPlayingFirstThenEveryOtherOnce() {
        val current = rig.currentEntry()
        val ahead = rig.run { rig.store.inTurn { rig.store.page(queueId(), QueueOrder.Shuffle, null, true, 2, Floors.NONE) } }
        assertEquals("case ${case.label}: the song playing comes first", current, ahead.first().entry)
        val (n, songs) = w.db.read("SELECT COUNT(*), COUNT(DISTINCT trackId) FROM queue_entry WHERE queueId = ?", queueId()) { it.getLong(0) to it.getLong(1) }.single()
        assertEquals("case ${case.label}: every song once", PROOF_SONGS.toLong(), n)
        assertEquals("case ${case.label}: every song once", n, songs)
        rig.next()
        assertEquals("case ${case.label}: Next plays the second", ahead[1].entry, rig.currentEntry())
    }

    // QueueSync's view, as the service reads it at every song change: ten
    // song changes, each view timed, with the statements it ran.
    private fun serverViews(label: String) {
        steps.keepMostRead()
        val ms = ArrayList<Long>()
        var statements = 0L
        var most = 0
        var songs = 0
        repeat(10) {
            rig.next()
            w.db.counted.reset()
            val at = System.nanoTime()
            val view = rig.run { rig.window.serverView() }
            ms += (System.nanoTime() - at) / 1_000_000
            statements = maxOf(statements, w.db.counted.count)
            most = maxOf(most, w.db.counted.maxRead?.rowsRead ?: 0)
            songs = view.trackIds.size
        }
        println(
            "RESULT time-1b QueueSync's view $label, $PROOF_SONGS songs: ${ms.sorted()[ms.size / 2]} ms median, ${ms.max()} ms longest, " +
                "$statements statements a song change (bookkeeping included), at most $most rows a statement, $songs songs in the view (no bound)",
        )
    }

    fun run() {
        steps.counting(w.db)
        before = fileBytes()
        when (case) {
            QueueCaseKind.PlayAll -> written(tap(PlayRequest(titles, null, shownOverWindow = true)))
            QueueCaseKind.ShuffleAll -> {
                written(tap(PlayRequest(titles, null, shuffle = true, shownOverWindow = true)))
                nexts(1_000)
                serverViews("shuffled")
                extra["shuffleOffMs"] = toggle(false)
            }
            QueueCaseKind.Row -> {
                val row = rig.rowAt(150_000)
                written(tap(PlayRequest(titles, row, shownOverWindow = true)))
                nexts(1_000)
                serverViews("in play order")
            }
            QueueCaseKind.ShuffledRow -> {
                val row = rig.rowAt(150_000)
                val listId = tap(PlayRequest(titles, row, shuffle = true, shownOverWindow = true))
                assertEquals("case ${case.label}: the tapped song plays at once", row.trackId, rig.current())
                written(listId)
                val sk = w.db.read("SELECT sk FROM queue_entry WHERE queueId = ? AND entry = ?", queueId(), entryOf(listId, 0)) { it.getLong(0) }.single()
                assertEquals("case ${case.label}: the tapped row holds the first key", SK_FIRST, sk)
                assertPlayingFirstThenEveryOtherOnce()
            }
            QueueCaseKind.OnWhileWritten -> {
                val row = rig.rowAt(150_000)
                val listId = tap(PlayRequest(titles, row, shownOverWindow = true))
                assertTrue("case ${case.label}: the list is still written", !hooks.filled.containsKey(listId))
                extra["shuffleOnMs"] = toggle(true)
                written(listId)
                assertPlayingFirstThenEveryOtherOnce()
            }
            QueueCaseKind.OnAfterJoin -> {
                val row = rig.rowAt(150_000)
                written(tap(PlayRequest(titles, row, shownOverWindow = true)))
                repeat(5) { rig.next() }
                extra["shuffleOnMs"] = toggle(true)
                assertPlayingFirstThenEveryOtherOnce()
            }
            QueueCaseKind.PlayNext -> playNext()
            QueueCaseKind.Restore -> restore()
        }
        val waits = hooks.waits.toList()
        val more = extra.entries.joinToString("") { " ${it.key}=${it.value}" }
        println(
            "RESULT queue-1b case=\"${case.label}\" first=${hooks.first} ms join=$joinMs ms upNext10=${hooks.ten} ms upNextFull=${hooks.full} ms " +
                "nextWaits=${waits.size} (longest ${waits.maxOrNull() ?: 0} ms) rows=$rows bytes=$bytes heapPeakMB=${Heap.peakMb()}$more",
        )
    }

    // A tap on row 150,000, then at once a Play next of a filtered list of
    // about 22,000 songs, its first song next, ten of its songs ahead and
    // its last row written timed from that tap.
    private fun playNext() {
        val row = rig.rowAt(150_000)
        val play = tap(PlayRequest(titles, row, shownOverWindow = true))
        val tapped = System.nanoTime()
        val added = rig.run { rig.window.addList(filtered, next = true, source = null) }!!
        fun ahead() = upcoming(rig.player).count { i -> entryOfKey(rig.player.getMediaItemAt(i).entryId)?.let(::listOfEntry) == added.listId }
        var firstNext: Long? = null
        var tenNext: Long? = null
        rig.until("case ${case.label}: the add was all written") {
            val n = ahead()
            if (n >= 1 && firstNext == null) firstNext = System.nanoTime()
            if (n >= 10 && tenNext == null) tenNext = System.nanoTime()
            hooks.filled.containsKey(added.listId) && tenNext != null
        }
        extra["addFirstMs"] = firstNext?.let { (it - tapped) / 1_000_000 }
        extra["addTenMs"] = tenNext?.let { (it - tapped) / 1_000_000 }
        extra["addJoinMs"] = hooks.filled[added.listId]?.let { it - (tapped - hooks.since) / 1_000_000 }
        written(play)
        extra["addRows"] = w.db.readLong("SELECT COUNT(*) FROM queue_entry WHERE queueId = ? AND listId = ?", queueId(), added.listId)
    }

    // A tap on row 150,000, its join and five Nexts; the service dies, and a
    // new window restores the queue from Room: how long until the player
    // holds it, how many ids it asked the items for, and the song it plays.
    private fun restore() {
        val row = rig.rowAt(150_000)
        written(tap(PlayRequest(titles, row, shownOverWindow = true)))
        repeat(5) { rig.next() }
        val playing = rig.current()
        rig.window.saveState()
        rig.settle()
        rig.die()
        rig = ProofQueue(w).also { it.builds.hooks = hooks }
        val at = System.nanoTime()
        val saved = rig.run { rig.window.readSaved("home") }!!
        assertTrue("case ${case.label}: the queue restored", rig.window.restore(saved, "home"))
        extra["restoreMs"] = (System.nanoTime() - at) / 1_000_000
        extra["idsAsked"] = rig.itemIds.get()
        rig.settle()
        assertEquals("case ${case.label}: the song it was on", playing, rig.current())
        rig.next()
        extra["restoredHolds"] = rig.player.mediaItemCount
    }

    override fun close() {
        rig.die()
        w.close()
    }
}

// Copies run beside the queue as the app runs them: the copy off the test's
// thread while the main looper and the queue's worker turn, and each play
// tapped as the copy's save begins, timed from its tap to its start entry
// playing.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class TapRun(private val w: ProofWorld, private val rig: ProofQueue, private val taps: TapHooks, private val hooks: CaseHooks) {
    // A copy's taps: each play's wait in the order tapped, the save step's
    // length, whether the copy claimed a stage and its swap, which writers
    // beside it were still writing once the last play started, and an
    // abort's length.
    class Taps(val waits: List<Long>, val saveStepMs: Long?, val claimed: Boolean, val swap: SwapTimes.Swap?, val beside: String, val abortMs: Long?)

    // Whether the last merge watched claimed a copy.
    var lastClaimed = false
        private set

    // A whole copy run off the test's thread; as its save begins, the
    // writers `beside` gives are started and `plays` are tapped one after
    // another, each waited for until its start entry plays; then the copy
    // and the writers are waited for.
    fun copy(arriving: Boolean, plays: List<PlayRequest>, beside: (() -> List<Pair<String, Job>>)? = null, expectAbort: Boolean = false): Taps {
        taps.reset()
        val besides = ArrayList<Pair<String, Job>>()
        var failure: Throwable? = null
        var failedAt = 0L
        val copying = CoroutineScope(Dispatchers.Default).launch {
            try {
                withContext(WriteLabels.of(COPY_WRITE)) { w.copy(arriving) }
            } catch (e: Throwable) {
                failedAt = System.nanoTime()
                failure = e
            }
        }
        val waits = ArrayList<Long>()
        var atTap = "none"
        var claimed = false
        var swap: SwapTimes.Swap? = null
        var saveStep: Long? = null
        rig.until("the copy's save began") { taps.savingAt != 0L || copying.isCompleted }
        if (taps.savingAt != 0L) {
            beside?.let { besides += it() }
            for (play in plays) waits += tapAndWait(play)
            if (besides.isNotEmpty()) atTap = besides.joinToString { (name, job) -> "$name ${if (job.isActive) "writing" else "done"}" }
        }
        rig.until("the copy ended") { copying.isCompleted }
        // The copy's own merge, before any merge a writer beside it asks for.
        claimed = taps.claimedStage
        swap = taps.copySwap
        if (taps.savingAt != 0L && taps.savedAt != 0L) saveStep = (taps.savedAt - taps.savingAt) / 1_000_000
        rig.until("the writers beside the copy ended") { besides.all { it.second.isCompleted } }
        lastClaimed = claimed
        var abortMs: Long? = null
        if (expectAbort) {
            assertTrue("the copy was given up: $failure", generateSequence(failure) { it.cause }.any { it is StageAborted })
            abortMs = (failedAt - taps.savedAt) / 1_000_000
        } else {
            failure?.let { throw it }
        }
        return Taps(waits, saveStep, claimed, swap, atTap, abortMs)
    }

    // A tap: its wait, from the tap to its start entry playing.
    private fun tapAndWait(request: PlayRequest): Long {
        val listId = rig.run { rig.store.claimList() }
        val at = System.nanoTime()
        var answer: PlayAnswer? = null
        rig.scope.launch { answer = rig.window.play(request.copy(token = listId)) }
        rig.until("the play of ${request.spec} started") { hooks.startedAt.containsKey(listId) || answer?.started == false }
        assertTrue("the play of ${request.spec} was refused: ${answer?.refusal}", hooks.startedAt.containsKey(listId))
        return (hooks.startedAt.getValue(listId) - at) / 1_000_000
    }

    // The steps after a sync, each writing beside a copy: the plays and
    // stars read again, the totals rebuilt, and the rules walk with Keep
    // liked songs on.
    fun besideSync(totals: PlayTotals): List<Pair<String, Job>> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return listOf(
            "the plays and stars read again" to scope.launch(WriteLabels.of(AFTER_SYNC_WRITE)) { w.afterSync() },
            "the totals rebuilt" to scope.launch {
                totals.rebuild()
                totals.awaitBuilt()
            },
            "the rules walk, Keep liked songs on" to scope.launch(WriteLabels.of(AFTER_SYNC_WRITE)) {
                RulesRig(w.db, w.merge, server = w.source).rules.walk(keepLiked = true, playlists = emptySet(), refused = null)
            },
        )
    }

    // A look for new music, as the app's sync looks: the server read its
    // folders since `seen`, so its newest albums are read, those the copy
    // lacks or holds short are taken in under the take-in's label, and the
    // library is merged again. Answers whether any went in.
    suspend fun freshen(seen: String?): Boolean {
        taps.reset()
        val client = w.session.client
        val look = lookForNewMusic(client, seen) { ids ->
            ids.chunked(900).flatMap { w.db.sourceDao().albumSongCounts(w.source, it) }.associate { it.serverId to it.songCount }
        }
        val found = look.found as? NewMusic.TakeIn ?: return false
        val listed = found.albums.associateBy { it.id }
        val read = found.albums.map { client.album(it.id) }
        val songs = read.flatMap { it.song }.filterNot { it.isExternal }.distinctBy { it.id }
        val rows = buildServerCatalog(w.source, Library(songs, read.map { listed[it.id] ?: it.asListed() }, emptyList()))
        val took = withContext(WriteLabels.of(TAKE_IN_WRITE)) {
            StagedCopy(w.db, w.merge, FreeSpace { Long.MAX_VALUE }).takeIn(w.source, rows, readAlbums = read.mapTo(HashSet()) { it.id })
        }
        if (!took) return false
        withContext(WriteLabels.of(TAKE_IN_WRITE)) { w.merge.rebuild() }
        lastClaimed = taps.claimedStage
        return true
    }
}

// The merge as the save section watches it: each save's groups and runs,
// the room in use at its claim and at its check, the free space it asks for
// at its build's end and the kind of save it took it for (spaceAsked), the
// room it took from there (the log emptied there first, `logAtMark` what a
// reader left in it), and the section's own steps at its check (`started`),
// inside each group before it writes (`inside`) and between groups
// (`between`).
private class SaveHooks : SwapTimes() {
    @Volatile var db: CatalogDatabase? = null
    @Volatile var space: SpaceProbe? = null
    @Volatile var watch: SaveWatch? = null
    @Volatile var stats = GroupStats()
    @Volatile var atClaim = 0L
    @Volatile var atCheck = 0L
    @Volatile var asked = 0L
    @Volatile var kind: SaveKind? = null
    @Volatile var logAtMark = 0L
    @Volatile var turnsDone = 0L
    @Volatile var started: (suspend () -> Unit)? = null
    @Volatile var inside: (suspend (Long, Long) -> Unit)? = null
    @Volatile var between: (suspend (Long) -> Unit)? = null
    @Volatile var parkBetween: (suspend (Long) -> Unit)? = null

    // The writer beside the save in use, paused while a start is counted.
    @Volatile var rival: Rival? = null

    // Each table's rows and bytes at the build's end, after the find and at
    // the save's end, and the rows each table's writes changed from the
    // build's end to the save's end, for the save last watched.
    @Volatile var atBuilt: Map<String, Pair<Long, Long>>? = null
    @Volatile var atFound: Map<String, Pair<Long, Long>>? = null
    @Volatile var atEnd: Map<String, Pair<Long, Long>>? = null

    // Each table's most rows and bytes over the save's groups, measured
    // between groups every PEAK_EVERY turns and at the first.
    @Volatile var atPeak: Map<String, Pair<Long, Long>>? = null
    @Volatile var changedAtBuilt: Map<String, Long> = emptyMap()
    @Volatile var changedBySave: Map<String, Long> = emptyMap()
    @Volatile var placed = 0L

    override suspend fun built() {
        super.built()
        progress("the build's end")
        val db = db ?: return
        withContext(WriteLabels.of(PROOF_READ)) {
            atBuilt = tableSpace(db.openHelper.writableDatabase)
            placed = db.readLong("SELECT COUNT(*) FROM merge_candidate")
            logAtMark = space?.mark() ?: 0
        }
        changedAtBuilt = db.counted.changedByTable
    }

    override fun spaceAsked(kind: SaveKind, bytes: Long) {
        super.spaceAsked(kind, bytes)
        this.kind = kind
        asked = bytes
    }

    override suspend fun claimed() {
        super.claimed()
        atClaim = withContext(WriteLabels.of(PROOF_READ)) { space?.inUse() ?: 0 }
    }

    override suspend fun checking() {
        super.checking()
        progress("the free space check")
        val db = db ?: return
        withContext(WriteLabels.of(PROOF_READ)) {
            atCheck = space?.inUse() ?: 0
            atFound = tableSpace(db.openHelper.writableDatabase)
            progress("the tables measured after the find")
            stats.plan(db, watch?.bound ?: SAVE_GROUP_ENTRIES)
            progress("the plan read")
        }
        started?.invoke()
    }

    override suspend fun inGroup(turn: Long, group: Long) {
        super.inGroup(turn, group)
        if (turn <= 2) progress("in group $group, turn $turn")
        stats.inGroup(group)
        inside?.let { withContext(WriteLabels.of(PROOF_READ)) { it(turn, group) } }
    }

    override suspend fun betweenGroups(turn: Long) {
        super.betweenGroups(turn)
        stats.between()
        val db = db
        if (turn % 50 == 0L) progress("between groups, turn $turn")
        if (db != null && (turn == 1L || turn % PEAK_EVERY == 0L)) {
            withContext(WriteLabels.of(PROOF_READ)) {
                tableSpace(db.openHelper.writableDatabase).let { now ->
                    val was = atPeak.orEmpty()
                    atPeak = (was.keys + now.keys).associateWith { t ->
                        val a = was[t] ?: (0L to 0L)
                        val b = now[t] ?: (0L to 0L)
                        maxOf(a.first, b.first) to maxOf(a.second, b.second)
                    }
                }
            }
        }
        between?.let { withContext(WriteLabels.of(PROOF_READ)) { it(turn) } }
        parkBetween?.let { withContext(WriteLabels.of(PROOF_READ)) { it(turn) } }
    }

    override fun applied(turns: Long, rows: Long, millis: Long) {
        super.applied(turns, rows, millis)
        progress("applied, $turns turns")
        turnsDone = turns
        space?.sample()
        db?.let { db ->
            atEnd = tableSpace(db.openHelper.writableDatabase)
            val now = db.counted.changedByTable
            changedBySave = now.mapValues { (t, n) -> n - (changedAtBuilt[t] ?: 0) }.filterValues { it > 0 }
        }
    }
}

// One save of the save section: what its transactions cost, its groups,
// runs and transactions, its longest run, the waits beside it, the asks and
// the room it took, and what its parks checked.
private class SaveResult(
    val label: String,
    val costs: List<LabelCosts>,
    val groups: Long,
    val runs: Int,
    val transactions: Long,
    val run: Pair<Long, Int>,
    val waits: List<Waits.Summary>,
    val asked: Long,
    val fromClaim: Long,
    val fromCheck: Long,
    val log: Long,
    val parks: Int,
    val totalsChecked: Long,
    val saveMs: Long,
    val kind: SaveKind?,
    val taken: Ask,
) {
    override fun toString() =
        "$label: $groups groups, $runs runs, $transactions transactions, the longest run ${run.first} ms over ${run.second} pages, the copy $saveMs ms; " +
            "the swap's ask ${asked.mb()} MB, the room it took ${fromClaim.mb()} MB in use from the claim (${fromCheck.mb()} MB from the check), the log's most ${log.mb()} MB; " +
            "$parks parks checked, ${totalsChecked} totals recounted; costs: ${costs.joinToString("; ")}; waits: ${waits.joinToString("; ")}"
}

// A free-space ask beside the room its work took from where it asked: the
// most the file's pages in use grew (`inUse`) and the most the log held
// after it was emptied there (`log`; `logLeft` a reader kept in it).
private class Ask(val label: String, val asked: Long, val inUse: Long, val log: Long, val logLeft: Long = 0) {
    val taken: Long get() = inUse + log

    override fun toString() =
        "$label: asked ${asked.mb()} MB ($asked B), took ${taken.mb()} MB ($taken B: $inUse B in use, the log's most $log B" +
            (if (logLeft > 0) ", $logLeft B left in it by a reader" else "") + "), asked over took ${"%.2f".format(asked.toDouble() / taken.coerceAtLeast(1))}"
}

// The asks the section prints and holds: none below the room its work took,
// and each close one at most `over` times it and ASK_SLACK more.
private fun holdAsks(close: List<Ask>, atLeast: List<Ask>, over: Double) {
    for (a in close + atLeast) println("RESULT ask-1b $a")
    val low = (close + atLeast).filter { it.asked < it.taken }
    val high = close.filter { it.asked > it.taken * over + ASK_SLACK }
    assertTrue("asks below the room their work took: ${low.joinToString("; ")}", low.isEmpty())
    assertTrue("asks over $over times the room their work took and $ASK_SLACK B: ${high.joinToString("; ")}", high.isEmpty())
}

// How far over the room its work took a close ask may be. With the long
// names (-PoctoProofLongTitles), which set the save constants: their margin
// and the log's room, which is asked whole while a save seldom fills it.
// With the proof library's own short names: as much again as long names
// take over short ones.
private const val ASK_OVER = 1.35
private const val ASK_OVER_SHORT_NAMES = 2.2
private const val ASK_SLACK = 2_000_000L
private val LONG_NAMES_RUN = System.getProperty("octo.proofLongTitles") == "true"

// Runs `copy`, a whole copy that saves through the world's merge, with
// another writer, holds and stable reads beside its save from its check
// on, and with `user`: the user's writes on old ids at the save's first
// park, every one shown at each sampled park and at its end, and the play
// totals equal to a recount at those parks (a window of songs each, every
// song at turns 3 and 400 and at the end). Holds what the section's success
// lines say of it.
private suspend fun saved(w: ProofWorld, hooks: SaveHooks, watch: SaveWatch, label: String, user: UserWrites? = null, copy: suspend () -> Unit): SaveResult {
    watch.reset()
    val stats = GroupStats()
    hooks.stats = stats
    hooks.atBuilt = null
    hooks.atFound = null
    hooks.atEnd = null
    hooks.atPeak = null
    hooks.changedBySave = emptyMap()
    hooks.asked = 0
    hooks.kind = null
    hooks.logAtMark = 0
    hooks.watch = watch
    val space = hooks.space!!
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val rival = Rival(w.db, w.merge)
    val probes = HoldProbes(w.db, w.merge)
    val problems = Collections.synchronizedList(ArrayList<String>())
    var parks = 0
    var totals = 0L
    suspend fun recountAll(at: String) {
        eachSongPage(w.db) { page ->
            totals += page.size
            problems += totalsOff(w.db, page, "proof").take(3).map { "$label, $at: $it" }
        }
    }
    hooks.started = {
        rival.start(scope)
        probes.start(scope)
    }
    hooks.rival = rival
    // A check that fails between groups is kept as a problem and ends the
    // checks: thrown there, it would stop the save, which then resumes.
    var failed = false
    hooks.between = between@{ turn ->
        if (turn <= 3) progress("between groups, turn $turn, the user's writes ${if (user == null) "none" else "next"}")
        if (user == null || failed) return@between
        try {
            if (turn == 1L) {
                user.write()
                progress("the user's writes written")
            }
            if (turn <= 3 || turn % 50 == 0L) {
                parks++
                problems += user.check("$label, turn $turn")
                val window = w.db.read("SELECT id FROM track ORDER BY rowid LIMIT 500 OFFSET ?", (turn * 997) % w.songs) { it.getString(0) } + user.songsNow()
                totals += window.size
                problems += totalsOff(w.db, window, "proof").take(3).map { "$label, turn $turn: $it" }
            }
            if (turn == 3L || turn == 400L) recountAll("turn $turn")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failed = true
            problems += "$label, turn $turn: $e"
            progress("$label, turn $turn: $e")
        }
    }
    space.start(scope)
    val at = System.nanoTime()
    try {
        copy()
    } finally {
        hooks.started = null
        hooks.between = null
        rival.stop()
        probes.stop()
        space.stop()
        scope.cancel()
    }
    val saveMs = (System.nanoTime() - at) / 1_000_000
    if (user != null) {
        problems += user.check("$label, after the save")
        recountAll("after the save")
        assertTrue("$label: $parks parks checked; ${problems.take(5)}", parks >= 2)
    }
    val waits = listOf(rival.waits.summary(watch)) + probes.summaries(watch)
    val taken = Ask("$label (${hooks.kind})", hooks.asked, space.mostInUseSinceMark - space.markInUse, space.mostLogSinceMark, hooks.logAtMark)
    val result = SaveResult(
        label, watch.costs(), stats.planned, stats.runCount(), stats.transactions, stats.longestRun(), waits, hooks.asked,
        space.mostInUse - hooks.atClaim, space.mostInUse - hooks.atCheck, space.mostLog, parks, totals, saveMs, hooks.kind, taken,
    )
    println("RESULT ask-1b $taken")
    println("RESULT save-1b $result")
    val points = listOf("at the build's end" to hooks.atBuilt, "after the find" to hooks.atFound, "the most during the groups" to hooks.atPeak, "at the save's end" to hooks.atEnd)
    for (line in spaceLines(label, points)) println("RESULT space-1b $line")
    println(
        "RESULT writes-1b $label: the merge's output ${hooks.placed} songs; rows the writes changed from the build's end to the save's end, by table: " +
            hooks.changedBySave.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" },
    )
    assertTrue("$label: ${problems.size} problems: ${problems.take(20).joinToString("\n")}", problems.isEmpty())
    val apply = watch.costOf(SAVE_APPLY)
    assertTrue("$label: no apply transaction measured", apply != null && apply.transactions > 1)
    for (c in listOfNotNull(apply, watch.costOf(SAVE_FIND))) assertEquals("$label: ${c.label} transactions over the bound ${watch.bound}: ${c.firstOver}", 0L, c.over)
    val (outside, outsideSql) = watch.outsideMerge()
    assertEquals("$label: the merge's writes outside a transaction (the last: $outsideSql)", 0L, outside)
    for (wait in waits) {
        assertTrue("$label: $wait", wait.during > 0)
        // A hold enters between groups: one save transaction at most. A
        // write waits out at most one once it is queued, and its lead-in to
        // Room's queue may add one.
        when {
            wait.name.startsWith("a stable read") -> {}
            wait.mostQueued >= 0 -> assertTrue("$label: $wait", wait.mostQueued <= 1 && wait.mostGroups <= 2)
            else -> assertTrue("$label: $wait", wait.mostGroups <= 1)
        }
    }
    if (user != null) assertTrue("$label: no run was parked at", stats.runPaged() && stats.transactions > 1)
    return result
}

// The user's hearts and stars, written through the app's stores on songs'
// ids as they are at the save's first park (old ids, the most of them not
// yet moved), and read back as the screens read them, through the old id
// and the id it is now.
private class UserWrites(private val w: ProofWorld) {
    private val ids = SongIds(w.db, w.merge)
    private val sync = w.listeningSync()
    private val likes = LikeStore(w.db.userDao(), w.db.dao(), sync, w.db, ids)
    private val ratings = RatingStore(w.db.userDao(), sync, ids)
    private val hearts = LinkedHashMap<String, Boolean>()
    private val stars = LinkedHashMap<String, Int>()
    private var round = 0

    // Ten liked songs unliked, ten songs of the Unknown album liked and
    // ten rated, each waited for until it shows.
    suspend fun write() {
        round++
        hearts.clear()
        stars.clear()
        // Likes kept on ids no song has now (a like outlives its song, to be
        // put back by its key when the song returns), counted for the
        // record, a few of them with the id each follows to now.
        val kept = w.db.readLong("SELECT COUNT(*) FROM liked_track l WHERE NOT EXISTS (SELECT 1 FROM track t WHERE t.id = l.trackId)")
        val keptSome = w.db.read("SELECT trackId FROM liked_track l WHERE NOT EXISTS (SELECT 1 FROM track t WHERE t.id = l.trackId) ORDER BY trackId LIMIT 3") { it.getString(0) }
            .map { "$it now ${w.db.userDao().currentId(RENAME_SONG, it)}" }
        println("RESULT likes-1b round $round: $kept likes on ids no song has now, of ${w.db.readLong("SELECT COUNT(*) FROM liked_track")}: $keptSome")
        // A few likes as they stand: the song's id, the like's relink key and
        // the song's, and the source rows the song is a copy of.
        val sample = w.db.read(
            "SELECT l.trackId, l.relinkKey, t.relinkKey, (SELECT group_concat(s.id || ' of ' || s.sourceId, ', ') FROM source_track s WHERE s.mergedId = l.trackId) " +
                "FROM liked_track l LEFT JOIN track t ON t.id = l.trackId ORDER BY l.trackId LIMIT 3",
        ) { "${it.getString(0)} like key '${it.getString(1)}' song key '${it.getString(2)}' copies [${it.getString(3)}]" }
        println("RESULT likes-1b round $round, a few likes: $sample")
        progress("round $round, a few likes: $sample")
        progress("round $round: $kept likes on ids no song has now: $keptSome")
        // Liked songs the library has now.
        val live = w.db.readLong("SELECT COUNT(*) FROM liked_track l JOIN track t ON t.id = l.trackId")
        val liked = w.db.read(
            "SELECT l.trackId FROM liked_track l JOIN track t ON t.id = l.trackId ORDER BY l.trackId LIMIT 10 OFFSET ?",
            (round * 37L) % (live - 10).coerceAtLeast(1),
        ) { it.getString(0) }
        // The Unknown album as it was before the save (a switch adds the new
        // server's beside it).
        val unknown = w.db.read("SELECT id FROM album WHERE title = 'Unknown album' ORDER BY rowid LIMIT 1") { it.getString(0) }.single()
        val notLiked = w.db.read(
            "SELECT t.id FROM track t WHERE t.albumId = ? AND NOT EXISTS (SELECT 1 FROM liked_track l WHERE l.trackId = t.id) ORDER BY t.id LIMIT 10 OFFSET ?",
            unknown, round * 53,
        ) { it.getString(0) }
        val rated = w.db.read("SELECT id FROM track ORDER BY rowid LIMIT 10 OFFSET ?", w.songs / 40 + round * 41) { it.getString(0) }
        val n = if (round % 2 == 1) 4 else 2
        liked.forEach { hearts[it] = false }
        notLiked.forEach { hearts[it] = true }
        rated.forEach { stars[it] = n }
        assertTrue("songs to write", liked.size == 10 && notLiked.size == 10 && rated.size == 10)
        hearts.keys.forEach { likes.toggle(it) }
        stars.forEach { (id, s) -> ratings.rate(id, s) }
        // Each write lands between groups: waited for, a few seconds at most.
        val end = System.nanoTime() + 30_000_000_000L
        while (check("written").isNotEmpty()) {
            assertTrue("the user's writes never showed: ${check("written")}", System.nanoTime() < end)
            kotlinx.coroutines.delay(10)
        }
    }

    // What does not show as written, through the old id and the id now.
    suspend fun check(where: String): List<String> {
        val user = w.db.userDao()
        val wrong = ArrayList<String>()
        for ((id, liked) in hearts) {
            val now = user.currentId(RENAME_SONG, id)
            if (user.isLiked(id) != liked || user.isLiked(now) != liked) wrong += "$where: $id (now $now) should show liked=$liked"
        }
        for ((id, s) in stars) {
            val now = user.currentId(RENAME_SONG, id)
            if (user.rating(id) != s || user.rating(now) != s) wrong += "$where: $id (now $now) should show $s stars, shows ${user.rating(id)} and ${user.rating(now)}"
        }
        return wrong
    }

    // The ids the songs written are now.
    suspend fun songsNow(): List<String> = (hearts.keys + stars.keys).map { w.db.userDao().currentId(RENAME_SONG, it) }
}

// The play routes the save section taps, each as its screen asks: a row of
// Songs by title, Shuffle of the Songs, the server's album, and its
// 100,000-song list first and shuffled. Each tap counts what ran from the
// tap to the start's hand-off (no write statement and no write BEGIN may be
// among it) and is timed to its start entry playing.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ParkedStarts(private val rig: ProofQueue, private val taps: CaseHooks, private val w: ProofWorld, private val hooks: SaveHooks, private val steps: Steps) {
    private val titles = ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false))
    private val row by lazy { rig.rowAt(w.songs * 3 / 4) }
    // A library list's start reads as a stable read, which waits out a
    // group inside its transaction; a server list's start waits for none.
    private fun libraryRoutes(): List<Pair<String, PlayRequest>> = listOf(
        "Songs, row ${w.songs * 3 / 4}" to PlayRequest(titles, row, shownOverWindow = true),
        "Songs, Shuffle" to PlayRequest(titles, null, shuffle = true, shownOverWindow = true),
    )

    private fun serverRoutes(): List<Pair<String, PlayRequest>> = listOf(
        "the server's album" to PlayRequest(ListSpec.Online(OnlineKind.Album, ONLINE_ALBUM_ID), null, shownOverWindow = true),
        "the server's ${w.songs / 2}-song list" to PlayRequest(ListSpec.Online(OnlineKind.List, FAMILY_ID), null, shownOverWindow = true),
        "the server's ${w.songs / 2}-song list, shuffled" to PlayRequest(ListSpec.Online(OnlineKind.List, FAMILY_ID), null, shuffle = true, shownOverWindow = true),
    )

    // Runs `save` off the test's thread while the main looper and the
    // queue's worker turn; parks it inside a page of its run once renames
    // are in force (holding that page's transaction and the merge lock) and
    // plays the server's routes there; then parks it after that page, between
    // groups, and plays every route; and lets it go.
    fun <T> during(save: suspend () -> T): T {
        row
        rig.run { rig.store.claimList(); rig.store.claimQueue() }
        val parked = CompletableDeferred<Long>()
        val go = CompletableDeferred<Unit>()
        val between = CompletableDeferred<Long>()
        val goOn = CompletableDeferred<Unit>()
        hooks.inside = inside@{ turn, group ->
            if (parked.isCompleted || !hooks.stats.isRun(group) || hooks.stats.pagesOf(group) < 2) return@inside
            // On the group's own connection: a Room query inside its
            // transaction's coroutine is refused.
            val renamed = w.db.openHelper.writableDatabase.query("SELECT EXISTS (SELECT 1 FROM save_rename WHERE kind = ?)", arrayOf<Any?>(RENAME_SONG)).use { it.moveToFirst() && it.getLong(0) == 1L }
            if (!renamed) return@inside
            // The writer beside the save rests while the starts are counted.
            hooks.rival?.paused = true
            parked.complete(turn)
            go.await()
        }
        hooks.parkBetween = park@{ turn ->
            if (!parked.isCompleted || between.isCompleted) return@park
            between.complete(turn)
            goOn.await()
        }
        var out: Result<T>? = null
        val job = CoroutineScope(Dispatchers.Default).launch { out = runCatching { save() } }
        // A save at proof size can take longer than a queue case's wait.
        fun until(what: String, done: () -> Boolean) {
            val end = System.nanoTime() + SAVE_MINUTES * 60_000_000_000L
            while (!done()) {
                rig.settle()
                if (System.nanoTime() > end) throw AssertionError("never: $what")
                Thread.sleep(20)
            }
        }
        try {
            until("the save parked inside a run") { parked.isCompleted || job.isCompleted }
            if (parked.isCompleted) {
                val turn = rig.run { parked.await() }
                for ((name, request) in serverRoutes()) tap("during the save, parked inside transaction $turn, a page of its run", name, request)
            }
        } finally {
            hooks.inside = null
            go.complete(Unit)
        }
        try {
            until("the save parked between groups") { between.isCompleted || job.isCompleted }
            if (between.isCompleted) {
                val turn = rig.run { between.await() }
                for ((name, request) in libraryRoutes() + serverRoutes()) {
                    // The last play's posted writes done first, as nothing
                    // holds the write lock here.
                    rig.settle()
                    tap("during the save, parked after transaction $turn of its run", name, request)
                }
            }
        } finally {
            hooks.parkBetween = null
            hooks.rival?.paused = false
            goOn.complete(Unit)
        }
        until("the save ended") { job.isCompleted }
        if (!parked.isCompleted || !between.isCompleted) out?.exceptionOrNull()?.let { throw it }
        assertTrue("the save never parked inside a run with renames in force, then after it", parked.isCompleted && between.isCompleted)
        return out!!.getOrThrow()
    }

    // Every route again with no save.
    fun free() {
        for ((name, request) in libraryRoutes() + serverRoutes()) {
            rig.settle()
            tap("no save", name, request)
        }
    }

    private fun tap(where: String, name: String, request: PlayRequest) {
        val log = w.db.counted
        val listId = rig.run { rig.store.claimList() }
        var handed: Long? = null
        rig.window.handing = { if (it == listId) handed = log.mark() }
        steps.keepMostRead()
        val from = log.mark()
        log.recording = true
        val at = System.nanoTime()
        var answer: PlayAnswer? = null
        rig.scope.launch { answer = rig.window.play(request.copy(token = listId)) }
        try {
            val end = System.nanoTime() + 60_000_000_000L
            while (!taps.startedAt.containsKey(listId) && answer?.started != false) {
                rig.idle()
                assertTrue("$where, $name: the play never started", System.nanoTime() < end)
                Thread.sleep(5)
            }
        } finally {
            log.recording = false
        }
        assertTrue("$where, $name: the play was refused: ${answer?.refusal}", taps.startedAt.containsKey(listId))
        val ms = (taps.startedAt.getValue(listId) - at) / 1_000_000
        val mark = handed ?: throw AssertionError("$where, $name: the start never handed over")
        // The start's own work: unlabeled (the window's) or the start's read's
        // label. The builds of the plays before it go on beside it under
        // labels of their own, as do the save and the writer beside it; those
        // are counted apart.
        fun own(label: String?) = label == null || label == QUEUE_START_WRITE
        val window = log.statements.filter { it.seq in (from + 1)..mark && !it.bookkeeping }
        val ran = window.filter { own(it.label) }
        val writes = ran.filter { START_WRITE.containsMatchIn(it.sql) }
        val besides = window.count { !own(it.label) && START_WRITE.containsMatchIn(it.sql) }
        val begins = log.begins.filter { it.beginSeq in (from + 1)..mark && (it.statements > 0 || it.rowsChanged > 0) && own(it.label) }
        val writeBegins = begins.filter { it.kind.asksForWriteLock }
        println(
            "RESULT start-1b $where, $name: ${writes.size} write statements, ${writeBegins.size} write BEGINs (${begins.size} begun), " +
                "${ran.size} statements, ${ran.sumOf { it.rowsRead.toLong() }} rows read before the hand-off ($besides writes of other jobs beside it); tap to sound $ms ms",
        )
        assertTrue("$where, $name wrote before the player had it: ${writes.map { "${it.label}: ${it.sql.take(80)}" }}", writes.isEmpty())
        assertTrue("$where, $name began a write before the player had it: $writeBegins", writeBegins.isEmpty())
    }
}

// How long the save section waits for one save at most.
private const val SAVE_MINUTES = 100L

// The least library the save section plays its parked starts beside the
// switch in (the dry runs' 8,000 songs on).
private const val PARKED_STARTS_FROM = 8_000

// A write, or a transaction begun by its text, which Android always begins
// as EXCLUSIVE (SQLiteSession.executeSpecial).
private val START_WRITE = Regex("""^\s*(INSERT|UPDATE|DELETE|REPLACE|BEGIN|SAVEPOINT)\b""", RegexOption.IGNORE_CASE)

// An unlimited live list over every song: its ask (as a build with no room
// says it) beside the room its first build took.
private suspend fun liveAsk(w: ProofWorld, space: SpaceProbe): Ask {
    val totals = w.totals()
    val picks = LivePicks(w.db, w.merge, totals, WorkOwners(), FreeSpace { Long.MAX_VALUE }, System::currentTimeMillis, LIVE_PAGE)
    val owner = WorkOwners.marked("live:every")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val songs = w.db.readLong("SELECT COUNT(*) FROM track")
    val query = MutableStateFlow(LibraryQuery(rules = listOf(QueryRule(QueryField.Duration, QueryOp.AtLeast, number = 0))))
    try {
        val left = space.mark()
        space.start(scope)
        val shown = MutableStateFlow<LiveShown>(LiveShown.Loading)
        scope.launch { picks.watch(owner, "every", query).collect { shown.value = it } }
        val list = shown.first { it !is LiveShown.Loading }
        space.stop()
        assertTrue("$list", list is LiveShown.Shown && list.count.toLong() == songs)
        val inUse = space.mostInUseSinceMark - space.markInUse
        val asked = try {
            LivePicks(w.db, w.merge, totals, WorkOwners(), FreeSpace { 0 }, System::currentTimeMillis, LIVE_PAGE).copy(null, query.value, WorkOwners.marked("live:asks"))
            error("a build with no room went on")
        } catch (e: NotEnoughSpace) {
            e.neededBytes!!
        }
        println("RESULT ask-1b live list of every song: $songs songs, ${inUse / songs} B a song in use")
        return Ask("live list of every song", asked, inUse, space.mostLogSinceMark, left)
    } finally {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        picks.clear(owner)
    }
}

// The 100,000-entry server list read into its owner's rows: the drained
// answer's ask (its entries guessed at SERVER_JSON_BYTES_LEAST bytes each,
// their rows' room by serverRoom) beside the room the rows took; the answer
// is on the disk already when its rows are asked for.
private suspend fun serverListAsk(w: ProofWorld, reads: ServerReads, space: SpaceProbe, drains: File): Ask {
    val owner = WorkOwners.marked("vm:asks")
    var answer = 0L
    // The answer drains to a file while its pages are written: its most,
    // noted at each page.
    reads.hooks = object : ServerHooks() {
        override suspend fun filled(owner: String, pages: Int) {
            answer = maxOf(answer, drains.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        }

        override suspend fun drained(owner: String) {
            answer = maxOf(answer, drains.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val left = space.mark()
        space.start(scope)
        // A list no play read before, so its answer is drained anew.
        assertTrue(reads.read(ServerListKind.List, ASKS_ID, owner) != null)
        space.stop()
        val entries = reads.facts(owner).count
        val ask = serverRoom(drainedEntries(answer), SERVER_PAGE)
        val rows = space.mostInUseSinceMark - space.markInUse
        println(
            "RESULT ask-1b server list of $entries entries: the answer $answer B (${if (entries > 0) answer / entries else 0} B an entry), " +
                "${if (entries > 0) rows / entries else 0} B an entry in use",
        )
        assertTrue("the answer was measured", answer > 0)
        return Ask("server list of $entries entries", ask, rows, space.mostLogSinceMark, left)
    } finally {
        scope.cancel()
        reads.hooks = ServerHooks()
        reads.clear(owner)
    }
}
