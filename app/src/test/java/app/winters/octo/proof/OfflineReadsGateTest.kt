package app.winters.octo.proof

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// The download store reads nothing whole for its rules, its after-sync or
// its album and playlist downloads: every download, every liked song,
// every kept playlist's songs, an album's or a playlist's songs, and the
// finished downloads after a sync are all read a page at a time by its
// walks. The one whole read left in it, the map of every download, stays
// for the screens still reading it.
class OfflineReadsGateTest {
    private val root = File(System.getProperty("octo.mainSources")!!)

    private val wholeReads = listOf(
        Regex("""\bdao\.all\(\)"""),
        Regex("""\.likedIds\(\)"""),
        Regex("""\bplaylistMembers\("""),
        Regex("""\bplaylistTrackIds\("""),
        Regex("""\balbumTrackIds\("""),
        Regex("""\bdoneFiles\("""),
        Regex("""\bheldByTrack\("""),
    )

    @Test
    fun theStoreWalksInPages() {
        for (path in listOf("app/winters/octo/offline/OfflineDownloads.kt", "app/winters/octo/offline/OfflineRules.kt")) {
            val file = File(root, path).takeIf { it.exists() } ?: continue
            val code = file.readLines().joinToString("\n") { it.substringBefore("//") }
            assertEquals("$path reads whole", emptyList<String>(), wholeReads.filter { it.containsMatchIn(code) }.map { it.pattern })
        }
    }
}
