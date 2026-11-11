package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.SAVE_GROUP_ENTRIES
import app.winters.octo.catalog.SAVE_GROUP_FLOOR
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// No play waits for a library save on purpose: no code reads whether the
// library is saving to hold a play or a page, the words that said a play
// waits for the save are gone, and a server play started while a save is
// parked inside a page of a run, holding its transaction, reaches the
// player.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class NoSaveWaitTest {
    private fun sources(): List<File> = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun noCodeReadsTheSaveToHoldAPlayOrAPage() {
        val files = sources()
        assertTrue("the sources were found", files.size > 100)
        val readers = Regex("""\bmerge\.saving\b|\.saving\.(value|first|collect)|\bsaving\s*:\s*StateFlow<Boolean>""")
        val found = files.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) -> readers.containsMatchIn(line) }.map { (i, line) -> "${file.path}:${i + 1}: ${line.trim()}" }
        }
        assertEquals("code reading whether the library saves", emptyList<String>(), found)
        val waits = listOf("onSaveWait", "waitingOnSave", "WAITING_ON_SAVE_WORDS", "Starts when your library finishes saving")
        val waiting = files.flatMap { file -> waits.filter { it in file.readText() }.map { "${file.path}: $it" } }
        assertEquals("the save wait and its words", emptyList<String>(), waiting)
    }

    // An album whose first song a parked page's earlier pages renamed, at
    // the floor bound and the default (a whole start and a big one).
    @Test
    fun aPlayDuringAParkedSaveReachesThePlayer() {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            ParkedSave(bound).use { save ->
                val rig = save.rig
                val token = runBlocking { rig.store.claimList() }
                save.park()
                val (native, _, new) = save.renamedSong()
                rig.server.albums["A"] = "Views" to listOf(native) + (0 until save.songs).map { "s$it" }.filter { it != native }
                val answer = rig.playWhileParked(PlayRequest(ListSpec.Online(OnlineKind.Album, "A"), token = token))
                assertTrue("bound $bound: the album did not start while the save was parked: $answer", answer.started)
                assertEquals("bound $bound", new, rig.current())
                assertEquals(STARTING_WORDS, StartingPlay(1, null, null, null).words)
                val groups = save.release()
                rig.joined()
                assertTrue("bound $bound: $groups groups", groups >= 2)
                assertTrue("bound $bound: no run was parked", save.turn >= 2 && save.hooks.inRun(save.turn - 1))
                assertTrue("bound $bound: the album's rows never landed", rig.songs().isNotEmpty())
            }
        }
    }
}
