package app.winters.octo.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Every write of the download table goes through OfflineDownloads' book,
// which moves the download counters after each, so no write can forget
// them: only the download store, its walks of the table (OfflineRules)
// and the animated art keeper hold the download DAO, the art keeper and
// the walks write nothing themselves, and inside the store every write
// call stands in the book.
class DownloadWritesGateTest {
    private val root = File(System.getProperty("octo.mainSources")!!)
    private val sources = root.walkTopDown().filter { it.extension == "kt" }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

    private val holders = setOf(
        "app/winters/octo/catalog/CatalogDatabase.kt",
        "app/winters/octo/di/CatalogModule.kt",
        "app/winters/octo/offline/DownloadDao.kt",
        "app/winters/octo/offline/LoopKeeper.kt",
        "app/winters/octo/offline/OfflineDownloads.kt",
        "app/winters/octo/offline/OfflineRules.kt",
    )

    private val writes = Regex(
        """\bdao\.(upsert|setReason|setState|markDownloading|setProgress|finish|noteFileSize|requeueInterrupted|delete|relink|requeueGone)\(""",
    )

    @Test
    fun onlyTheStoreWritesDownloadsAndOnlyThroughItsBook() {
        val holding = sources.filter { (_, text) -> "DownloadDao" in text || "downloadDao()" in text }.keys
        assertEquals("files holding the download DAO", holders, holding)
        assertTrue("the art keeper writes downloads", !writes.containsMatchIn(sources.getValue("app/winters/octo/offline/LoopKeeper.kt")))
        assertTrue("the walks write downloads past the book", !writes.containsMatchIn(sources.getValue("app/winters/octo/offline/OfflineRules.kt")))
        val store = sources.getValue("app/winters/octo/offline/OfflineDownloads.kt")
        val book = store.indexOf("internal class DownloadBook(")
        assertTrue("the book is missing", book >= 0)
        val outside = writes.findAll(store).filter { it.range.first < book }.map { it.value }.toList()
        assertEquals("writes outside the book", emptyList<String>(), outside)
        assertTrue("the book writes", writes.containsMatchIn(store.substring(book)))
    }
}
