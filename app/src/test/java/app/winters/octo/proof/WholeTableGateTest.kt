package app.winters.octo.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// DAO reads that answer a whole table (or a whole source) as one list.
private val WHOLE_TABLE_CALLS = listOf(
    Regex("""\bcatalog\.(tracks|albums|artists|allTrackIds|trackPlaces)\(\)"""),
    Regex("""\bdao\(\)\.(tracks|albums|artists|allTrackIds|trackPlaces)\(\)"""),
    Regex("""\.likedIds\(\)"""),
    Regex("""\buser\.ratings\(\)"""),
    Regex("""\bsources\.(tracks|albums|artists)\("""),
    Regex("""\.starred\(\)"""),
    Regex("""\breadLibrary\("""),
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
)

// Every other file still calling one, by path under src/main/java: rounds
// 1b, 2 and 3 take them off.
private val LATER_ROUNDS = setOf<String>(
    "app/winters/octo/backup/Backups.kt",
    "app/winters/octo/data/LibraryRepository.kt",
    "app/winters/octo/livelists/LiveListSongs.kt",
    "app/winters/octo/offline/OfflineDownloads.kt",
    "app/winters/octo/playback/CarLibrary.kt",
    "app/winters/octo/playback/CarVoice.kt",
    "app/winters/octo/playback/LibraryRadio.kt",
    "app/winters/octo/playback/PlaybackConnection.kt",
    "app/winters/octo/playback/Stores.kt",
    "app/winters/octo/playlists/PlaylistFiles.kt",
    "app/winters/octo/ui/folders/FoldersViewModel.kt",
    "app/winters/octo/ui/library/LibraryViewModel.kt",
    "app/winters/octo/ui/library/health/HealthScreens.kt",
    "app/winters/octo/widget/QuickPicks.kt",
)

class WholeTableGateTest {
    @Test
    fun noNewCallerOfAWholeTableRead() {
        val root = File(System.getProperty("octo.mainSources")!!)
        val callers = root.walkTopDown().filter { it.extension == "kt" }.filter { file ->
            val text = file.readText()
            WHOLE_TABLE_CALLS.any { it.containsMatchIn(text) }
        }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        callers.forEach { assertTrue("$it streams now and must not read a whole table", it !in STREAMING) }
        assertEquals("callers of whole-table reads beyond the list", emptySet<String>(), callers - LATER_ROUNDS)
    }
}
