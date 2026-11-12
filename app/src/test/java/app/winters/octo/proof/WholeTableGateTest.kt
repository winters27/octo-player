package app.winters.octo.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Reads that answer a whole table, a whole source or every song of a kind
// as one list. A declaration of one (`fun likedTracks()`) is not a call.
private val WHOLE_TABLE_CALLS = listOf(
    Regex("""\bcatalog\.(tracks|albums|artists|allTrackIds|trackPlaces)\(\)"""),
    Regex("""\bdao\(\)\.(tracks|albums|artists|allTrackIds|trackPlaces)\(\)"""),
    Regex("""\.likedIds\(\)"""),
    Regex("""\buser\.ratings\(\)"""),
    Regex("""\bsources\.(tracks|albums|artists)\("""),
    Regex("""\.starred\(\)"""),
    Regex("""\breadLibrary\("""),
    // Round 1b: plays, likes, playlists, downloads and the phone's tags.
    Regex("""(?<!fun )\bplayedTracks\(\)"""),
    Regex("""(?<!fun )\bserverPlayedTracks\(\)"""),
    Regex("""(?<!fun )\bplayedAlbums\(\)"""),
    Regex("""(?<!fun )\bserverPlayedAlbums\(\)"""),
    Regex("""\beveryTrack\b"""),
    Regex("""\beveryAlbum\b"""),
    Regex("""(?<!fun )\blikedTracks\(\)"""),
    Regex("""(?<!fun )\bplaylistEntries\(\)"""),
    Regex("""(?<!fun )\ballFlow\(\)"""),
    Regex("""\bdao\.all\(\)"""),
    Regex("""(?<!fun )\bfileTags\(\)"""),
    Regex("""(?<!fun )\breplaceSource\("""),
    Regex("""(?<!fun )\bplaylistItems\("""),
    Regex("""(?<!fun )\bplaylistTrackIds\("""),
    Regex("""\b(catalog|dao)\.albums\(\)"""),
    Regex("""\.byTrack\b"""),
    Regex("""\.done\.value\b"""),
    // Every downloaded song and every liked song, each as one set.
    Regex("""\b(downloads|offline)\.done\b"""),
    Regex("""\blikes\.liked\b"""),
    Regex("""\b(store|listening)\.sent\b"""),
    // A server playlist read whole.
    Regex("""\bclient(\(\))?\.playlist\("""),
    // Every played song or album, every sorted song and every download row,
    // each as one list.
    Regex("""\bhistory\.(tracks|albums)\b"""),
    Regex("""\bsorted\.songs\("""),
    Regex("""\boffline\.rows\b"""),
    // A live list's songs, or the server's saved queue, taken whole.
    Regex("""\bliveSongs\.now\("""),
    Regex("""\bserverQueue\.take\("""),
    // A server folder's level decoded whole.
    Regex("""\.indexes\(\)"""),
    Regex("""\.musicDirectory\("""),
)

// Song reads of one album, one artist or one playlist: song-sized when one
// album holds the library ("Unknown album"). A file may call one only where
// GUARDED_CALLS lists it, inside a function that compares the album's size
// against WHOLE_ALBUM_MAX; anywhere else it is a whole read.
private val PER_LIST_CALLS = listOf(
    Regex("""(?<!fun )\balbumTracks\("""),
    Regex("""(?<!fun )\balbumTrackIds\("""),
    Regex("""(?<!fun )\bartistTracks\("""),
    Regex("""(?<!fun )\bplaylistTracks\("""),
    // The server's listing of a library album, decoded whole.
    Regex("""\bdiscovery\.libraryAlbum\("""),
)

// Where a per-list read is allowed, as (file, the call), each behind its
// size check: the album page's own small path.
private val GUARDED_CALLS = listOf(
    "app/winters/octo/ui/album/AlbumScreen.kt" to Regex("""(?<!fun )\balbumTracks\("""),
    "app/winters/octo/ui/album/AlbumScreen.kt" to Regex("""\bdiscovery\.libraryAlbum\("""),
)

// Files round 1a made streaming: none of them may call one.
private val STREAMING = setOf(
    "app/winters/octo/catalog/CatalogMerge.kt",
    "app/winters/octo/catalog/CatalogMerger.kt",
    "app/winters/octo/server/ServerSync.kt",
    "app/winters/octo/server/StagedCopy.kt",
    "app/winters/octo/listening/ListeningSync.kt",
    "app/winters/octo/listening/FavouriteSync.kt",
    "app/winters/octo/listening/ListeningStore.kt",
    // Round 1b, Part A: the play totals and their readers.
    "app/winters/octo/catalog/StableRead.kt",
    "app/winters/octo/listening/History.kt",
    "app/winters/octo/listening/PlayShelves.kt",
    "app/winters/octo/listening/PlayTotals.kt",
    "app/winters/octo/listening/PlayTotalsDao.kt",
    "app/winters/octo/listening/PlayWalk.kt",
    "app/winters/octo/listening/TotalsHolds.kt",
    "app/winters/octo/ui/history/HistoryScreen.kt",
    "app/winters/octo/ui/home/HomeScreen.kt",
    "app/winters/octo/ui/home/HomeViewModel.kt",
    // Parts B and C: likes, download marks and lookups, playlist summaries.
    "app/winters/octo/favourites/PinStore.kt",
    "app/winters/octo/offline/OfflineMarks.kt",
    "app/winters/octo/playback/PlaylistStore.kt",
    "app/winters/octo/playback/PlaylistSummaries.kt",
    "app/winters/octo/player/LivePlayerViewModel.kt",
    "app/winters/octo/ui/common/Items.kt",
    "app/winters/octo/ui/common/Marks.kt",
    "app/winters/octo/ui/menu/SongInfo.kt",
    // Part D: the rules walk, and every walk of the download table.
    "app/winters/octo/offline/OfflineRules.kt",
    // Part E: the phone scan.
    "app/winters/octo/device/DeviceLibrary.kt",
    "app/winters/octo/device/PhoneScan.kt",
    "app/winters/octo/device/PhoneScanDao.kt",
    // Part F: playlist sync and the playlists' item sets.
    "app/winters/octo/catalog/PlaylistSets.kt",
    "app/winters/octo/catalog/PlaylistWorkDao.kt",
    "app/winters/octo/data/Drains.kt",
    "app/winters/octo/playlists/PlaylistReads.kt",
    "app/winters/octo/playlists/PlaylistSteps.kt",
    "app/winters/octo/playlists/PlaylistSync.kt",
    "app/winters/octo/playlists/PlaylistWalks.kt",
    // G2: every paged list, its loaders, selection and the album routes.
    "app/winters/octo/catalog/ListsDao.kt",
    "app/winters/octo/catalog/PlaylistEdits.kt",
    "app/winters/octo/catalog/UserDao.kt",
    "app/winters/octo/data/Upgrades.kt",
    "app/winters/octo/discovery/AlbumHighlight.kt",
    "app/winters/octo/server/ServerControls.kt",
    "app/winters/octo/sort/SortedLibrary.kt",
    "app/winters/octo/ui/album/AlbumCopies.kt",
    "app/winters/octo/ui/album/AlbumDiscs.kt",
    "app/winters/octo/ui/album/AlbumDownload.kt",
    "app/winters/octo/ui/album/AlbumScreen.kt",
    "app/winters/octo/ui/album/AlbumShare.kt",
    "app/winters/octo/ui/artist/ArtistScreen.kt",
    "app/winters/octo/ui/common/Selection.kt",
    "app/winters/octo/ui/common/SelectionBar.kt",
    "app/winters/octo/ui/favourites/FavouritesScreen.kt",
    "app/winters/octo/ui/folders/FoldersScreen.kt",
    "app/winters/octo/ui/folders/FoldersViewModel.kt",
    "app/winters/octo/ui/genre/GenreScreen.kt",
    "app/winters/octo/ui/library/LibraryScreen.kt",
    "app/winters/octo/ui/library/LibraryViewModel.kt",
    "app/winters/octo/ui/menu/CollectionMenuPopup.kt",
    "app/winters/octo/ui/offline/DownloadsScreen.kt",
    "app/winters/octo/ui/paging/AlbumLoaders.kt",
    "app/winters/octo/ui/paging/ArtistLoaders.kt",
    "app/winters/octo/ui/paging/DownloadsLoader.kt",
    "app/winters/octo/ui/paging/FilteredLoader.kt",
    "app/winters/octo/ui/paging/FolderLoaders.kt",
    "app/winters/octo/ui/paging/HiddenRows.kt",
    "app/winters/octo/ui/paging/LetterRanges.kt",
    "app/winters/octo/ui/paging/ListHandle.kt",
    "app/winters/octo/ui/paging/ListLoader.kt",
    "app/winters/octo/ui/paging/ListLoaders.kt",
    "app/winters/octo/ui/paging/ListPaging.kt",
    "app/winters/octo/ui/paging/PagedRows.kt",
    "app/winters/octo/ui/paging/Parts.kt",
    "app/winters/octo/ui/paging/PlaylistLoader.kt",
    "app/winters/octo/ui/paging/SearchLoader.kt",
    "app/winters/octo/ui/paging/SongLoaders.kt",
    "app/winters/octo/ui/paging/SortCopy.kt",
    "app/winters/octo/ui/playlist/AddSources.kt",
    "app/winters/octo/ui/playlist/KeepDownloaded.kt",
    "app/winters/octo/ui/playlist/PlaylistScreen.kt",
    "app/winters/octo/ui/playlist/PlaylistSheets.kt",
    "app/winters/octo/ui/search/SearchViewModel.kt",
    // G4: the radio's pool and seeds, live lists, Library health and the
    // matching of files.
    "app/winters/octo/playback/LibraryRadio.kt",
    "app/winters/octo/playback/RadioSeeds.kt",
    "app/winters/octo/catalog/ReaderSql.kt",
    "app/winters/octo/catalog/ReaderDao.kt",
    "app/winters/octo/livelists/LivePicks.kt",
    "app/winters/octo/livelists/LiveLoader.kt",
    "app/winters/octo/livelists/LiveOrderKey.kt",
    "app/winters/octo/ui/library/health/HealthBuild.kt",
    "app/winters/octo/ui/library/health/HealthLoader.kt",
    "app/winters/octo/ui/library/health/HealthRecheck.kt",
    "app/winters/octo/playlists/MatchJob.kt",
    // The queue: Room holds it whole and the player a window of it.
    "app/winters/octo/catalog/QueueDao.kt",
    "app/winters/octo/output/OutputSwitch.kt",
    "app/winters/octo/playback/AlbumListLoader.kt",
    "app/winters/octo/playback/OctoPlaybackService.kt",
    "app/winters/octo/playback/PlaybackConnection.kt",
    "app/winters/octo/playback/QueueBuild.kt",
    "app/winters/octo/playback/QueueCommands.kt",
    "app/winters/octo/playback/QueueEdits.kt",
    "app/winters/octo/playback/QueueEditor.kt",
    "app/winters/octo/playback/QueueFill.kt",
    "app/winters/octo/playback/QueueKeys.kt",
    "app/winters/octo/playback/QueueStarts.kt",
    "app/winters/octo/playback/QueueStore.kt",
    "app/winters/octo/playback/QueueWindow.kt",
    "app/winters/octo/playback/StartingPlays.kt",
    "app/winters/octo/player/PlayerViewModel.kt",
    "app/winters/octo/ui/menu/CollectionMenu.kt",
    "app/winters/octo/ui/menu/SongMenu.kt",
    "app/winters/octo/widget/QuickPicks.kt",
    "app/winters/octo/widget/WidgetRemote.kt",
    // G4's pages: Library health's and live lists' screens on the paged
    // lists, Fix all planned page by page.
    "app/winters/octo/data/FixRuns.kt",
    "app/winters/octo/livelists/LiveListSongs.kt",
    "app/winters/octo/ui/library/health/FixAllPlans.kt",
    "app/winters/octo/ui/library/health/HealthLines.kt",
    "app/winters/octo/ui/library/health/HealthScreens.kt",
    "app/winters/octo/ui/livelists/LiveListEditScreen.kt",
    "app/winters/octo/ui/livelists/LiveListScreen.kt",
    "app/winters/octo/ui/playlist/PlaylistsScreen.kt",
    // G4's server half: server lists streamed into their owners' rows, the
    // network starts, the play queue's offer and take, backups and M3U.
    "app/winters/octo/backup/BackupSongs.kt",
    "app/winters/octo/backup/BackupStreams.kt",
    "app/winters/octo/backup/Backups.kt",
    "app/winters/octo/catalog/ServerListSql.kt",
    "app/winters/octo/data/Exports.kt",
    "app/winters/octo/discovery/ServerLists.kt",
    "app/winters/octo/playback/Stores.kt",
    "app/winters/octo/playlists/PlaylistFiles.kt",
    "app/winters/octo/server/QueueSync.kt",
    "app/winters/octo/ui/home/ResumeCard.kt",
    "app/winters/octo/ui/online/OnlineAlbumScreen.kt",
    "app/winters/octo/ui/online/ServerListScreen.kt",
    "app/winters/octo/ui/online/ServerPages.kt",
    "app/winters/octo/ui/server/RadioStationsScreen.kt",
    "app/winters/octo/ui/settings/BackupPage.kt",
    // The car, Autoplay and voice: every car list a page at a time, every
    // play a list by what it is, Autoplay's picks walked into samples.
    "app/winters/octo/discovery/Discovery.kt",
    "app/winters/octo/listening/PlayHistory.kt",
    "app/winters/octo/offline/OfflineDownloads.kt",
    "app/winters/octo/playback/Autoplay.kt",
    "app/winters/octo/playback/CarLibrary.kt",
    "app/winters/octo/playback/CarLists.kt",
    "app/winters/octo/playback/CarPaged.kt",
    "app/winters/octo/playback/CarPlays.kt",
    "app/winters/octo/playback/CarVoice.kt",
    // Server folder levels a page at a time, and the car's long lists
    // apart from the rest of the car.
    "app/winters/octo/folders/ServerFolders.kt",
    "app/winters/octo/ui/folders/ServerLevelPages.kt",
    "app/winters/octo/playback/CarPages.kt",
)

// Every other file still calling one, by path under src/main/java: the
// parts of round 1b took each off this list as they made it stream, and
// the list is empty.
private val LATER_ROUNDS = setOf<String>()

// The lines of `text` a guarded call may not stand on: each match whose
// text from the nearest `fun ` line above it to the match never compares
// against WHOLE_ALBUM_MAX. Answers their line numbers.
internal fun unguarded(text: String, call: Regex): List<Int> {
    val lines = text.lines()
    return lines.indices.filter { i -> call.containsMatchIn(lines[i]) }.filterNot { i ->
        val from = (i downTo 0).firstOrNull { "fun " in lines[it] } ?: return@filterNot false
        val upToCall = lines.subList(from, i).joinToString("\n") + "\n" + lines[i].substring(0, call.find(lines[i])!!.range.first)
        "WHOLE_ALBUM_MAX" in upToCall
    }.map { it + 1 }
}

class WholeTableGateTest {
    private val root = File(System.getProperty("octo.mainSources")!!)
    private val sources = root.walkTopDown().filter { it.extension == "kt" }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

    @Test
    fun noNewCallerOfAWholeTableRead() {
        val guarded = GUARDED_CALLS.groupBy({ it.first }, { it.second })
        val callers = sources.filter { (path, text) ->
            WHOLE_TABLE_CALLS.any { it.containsMatchIn(text) } ||
                PER_LIST_CALLS.any { call -> call.containsMatchIn(text) && guarded[path].orEmpty().none { it.pattern == call.pattern } }
        }.keys
        callers.forEach { assertTrue("$it streams now and must not read a whole table", it !in STREAMING) }
        assertEquals("callers of whole-table reads beyond the list", emptySet<String>(), callers - LATER_ROUNDS)
        assertEquals("files on the list that no longer read a whole table", emptySet<String>(), LATER_ROUNDS - callers - guarded.keys)
    }

    // A listed per-list read stands only inside a function that checks the
    // album's size first; a file still on LATER_ROUNDS has yet to add it.
    @Test
    fun guardedCallsCheckTheAlbumsSize() {
        GUARDED_CALLS.filter { (path, _) -> path !in LATER_ROUNDS }.forEach { (path, call) ->
            val text = sources[path] ?: return@forEach
            assertEquals("$path reads a whole album without WHOLE_ALBUM_MAX on lines", emptyList<Int>(), unguarded(text, call))
        }
    }

    // The rule itself: a call after the size check in its function passes;
    // one with no check, or a check in another function, does not; a
    // declaration is no call.
    @Test
    fun theGuardRuleReadsTheEnclosingFunction() {
        val call = GUARDED_CALLS.first().second
        val text = """
            fun small(id: String, count: Int) = if (count <= WHOLE_ALBUM_MAX) dao.albumTracks(id) else paged(id)
            fun big(id: String) {
                val all = dao.albumTracks(id)
            }
            fun checked(count: Int) = count <= WHOLE_ALBUM_MAX
            fun after(id: String) = dao.albumTracks(id)
            suspend fun albumTracks(id: String): List<String>
        """.trimIndent()
        assertEquals(listOf(3, 6), unguarded(text, call))
        assertTrue(WHOLE_TABLE_CALLS.none { it.containsMatchIn("    suspend fun likedTracks(): Flow<List<TrackEntity>>") })
        assertTrue(WHOLE_TABLE_CALLS.any { it.containsMatchIn("user.likedTracks().first()") })
    }

    // The server's lists stream: the library's whole-answer reads are gone,
    // and so are a station's and an album's whole reads and the saved
    // queue's whole take, with nothing calling them.
    @Test
    fun serverListsAreReadIntoTheirOwnRows() {
        assertTrue("LibraryRepository read whole server answers", "app/winters/octo/data/LibraryRepository.kt" !in sources)
        val whole = listOf(Regex("""\bdiscovery\.stationSongs\("""), Regex("""\bdiscovery\.album\("""), Regex("""\.take\(offer\)"""))
        val callers = sources.filter { (_, text) -> whole.any { it.containsMatchIn(text) } }.keys
        assertEquals("whole server lists read", emptySet<String>(), callers)
        val gone = listOf(
            "app/winters/octo/discovery/Discovery.kt" to Regex("""\bfun (stationSongs\(id: String\)|album\(id: String\))"""),
            "app/winters/octo/server/QueueSync.kt" to Regex("""\bfun take\(|\bclass ResumeQueue\b"""),
            "app/winters/octo/livelists/LiveListSongs.kt" to Regex("""\bfun now\("""),
        )
        gone.forEach { (path, declared) -> assertTrue("$path still declares a whole read", !declared.containsMatchIn(sources.getValue(path))) }
    }

    // The car and voice name every list they play by what it is: no car
    // or voice file builds a play from a whole list of songs.
    @Test
    fun carAndVoicePlayListsByWhatTheyAre() {
        val whole = listOf(Regex("""\bCarList\(\s*[a-zA-Z_.]*\.map\b"""), Regex("""\bVoicePlay\(\s*source\."""), Regex("""\ballTrackIds\(\)"""))
        for (path in listOf("app/winters/octo/playback/CarLibrary.kt", "app/winters/octo/playback/CarVoice.kt", "app/winters/octo/playback/CarPlays.kt")) {
            val text = sources.getValue(path)
            whole.forEach { assertTrue("$path plays a whole list: ${it.pattern}", !it.containsMatchIn(text)) }
        }
    }

    // An export's last try walks a whole playlist or file, so it is a Walk
    // hold, never a Read.
    @Test
    fun exportsHoldAWalk() {
        for (path in listOf("app/winters/octo/backup/BackupSongs.kt", "app/winters/octo/playlists/PlaylistFiles.kt")) {
            val text = sources.getValue(path)
            val calls = Regex("""\bstableRead\(merge, HoldKind\.(\w+)""").findAll(text).map { it.groupValues[1] }.toList()
            assertTrue("$path has no stable read", calls.isNotEmpty())
            assertEquals("$path's stable reads", calls.map { "Walk" }, calls)
        }
    }
}
