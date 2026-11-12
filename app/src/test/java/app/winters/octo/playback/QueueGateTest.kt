package app.winters.octo.playback

import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// No route hands the player a whole list: the old whole-list plays and
// adds are gone, only the queue's window sets the player's items (beside
// casting and the crossfade's spare deck, which move a window), and a list
// named by its songs is refused past QUEUE_WINDOW with words, never thrown.
@RunWith(RobolectricTestRunner::class)
class QueueGateTest {
    private val root = File(System.getProperty("octo.mainSources")!!)
    private val sources = root.walkTopDown().filter { it.extension == "kt" }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

    private val gone = listOf(
        Regex("""\bplayback\.(playAlbum|loadQueue|playLast|playNextUndoable|playLastUndoable|playNext)\("""),
        Regex("""\bremoveInserted\("""),
        Regex("""\bPLAY_NEXT\b"""),
        Regex("""\bqueueItems\(\)|\bqueueState\(\)|\bsaveQueue\(items"""),
        Regex("""\bpicks\.everything\(\)\.let\b|\bpicks\.songsFor\(|\bsuspend fun songsFor\(pick"""),
    )

    @Test
    fun theWholeListRoutesAreGone() {
        val found = sources.flatMap { (path, text) -> gone.filter { it.containsMatchIn(text) }.map { "$path: ${it.pattern}" } }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun onlyTheWindowSetsThePlayersItems() {
        val allowed = setOf(
            "app/winters/octo/playback/QueueWindow.kt",
            "app/winters/octo/output/Casting.kt",
            "app/winters/octo/playback/Crossfader.kt",
        )
        val setters = sources.filter { (_, text) -> Regex("""(?<!fun |handle)\bsetMediaItems\(""").containsMatchIn(text) }.keys
        assertEquals(emptySet<String>(), setters - allowed)
    }

    @Test
    fun idsPastTheWindowAreRefusedWithWords() = runBlocking {
        val db = openTestCatalog()
        try {
            val starts = QueueStarts(db, { error("no loaders") }, { error("no live") }, { error("no health") }, app.winters.octo.catalog.WorkOwners())
            val read = starts.read(ListSpec.Ids((0..QUEUE_WINDOW).map { "s$it" }), null, false, false, null)
            assertEquals(StartRead.Refused(COULD_NOT_PLAY_LIST), read)
            val fits = starts.read(ListSpec.Ids((0 until QUEUE_WINDOW).map { "s$it" }), null, false, false, null)
            assertTrue(fits is StartRead.Whole)
        } finally {
            db.close()
        }
    }
}
