package app.winters.octo.desktop.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.concurrent.thread

// Octo's own state files: a crash at any point of a save leaves the old
// text or the new, something else holding a file open never loses a save,
// and a save that fails says so once.
class SavedFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private class Crash : RuntimeException()

    private fun saved(name: String, text: String): File = File(temp.root, name).also { writeWhole(it, text) }

    @Test
    fun aCrashAtAnyPointLeavesTheOldTextOrTheNew() {
        for (at in SaveStep.entries) {
            val file = saved("crash-$at.json", "old")
            try {
                writeWhole(file, "new") { if (it == at) throw Crash() }
            } catch (e: Crash) {
                // Octo stopped here.
            }
            val found = readWhole(file)
            assertTrue("after $at: $found", found == "old" || found == "new")
            // Once the new text is kept, a crash does not lose it.
            if (at != SaveStep.Written) assertEquals("after $at", "new", found)
            // And the next save, after the restart, goes through.
            writeWhole(file, "newer")
            assertEquals("newer", readWhole(file))
            assertEquals("newer", file.readText())
        }
    }

    @Test
    fun aCrashWhileEmptyingLeavesTheOldTextOrNone() {
        for (at in SaveStep.entries) {
            val file = saved("empty-$at.txt", "old")
            try {
                writeWhole(file, null) { if (it == at) throw Crash() }
            } catch (e: Crash) {
                // Octo stopped here.
            }
            val found = readWhole(file)
            assertTrue("after $at: $found", found == "old" || found == null)
            if (at != SaveStep.Written) assertEquals("after $at", null, found)
        }
    }

    // A crash while the new text is still being written leaves a part of it
    // beside the file; that is never read.
    @Test
    fun aHalfWrittenSaveIsNeverRead() {
        val file = saved("queue.json", """{"songs":["a"]}""")
        File(temp.root, "queue.json.tmp").writeText("""{"songs":[""")
        assertEquals("""{"songs":["a"]}""", readWhole(file))
        writeWhole(file, """{"songs":["b"]}""")
        assertEquals("""{"songs":["b"]}""", readWhole(file))
    }

    // Held open past the wait: the save is kept beside the file and read
    // from there, and the next save once it is let go puts it in place.
    @Test
    fun aSaveWhileSomethingHoldsTheFileIsKept() {
        val file = saved("jump-list.json", "old")
        val open = FileInputStream(file)
        val closing = thread {
            Thread.sleep(2_500)
            open.close()
        }
        writeWhole(file, "new")
        assertEquals("new", readWhole(file))
        closing.join()
        writeWhole(file, "newer")
        assertEquals("newer", file.readText())
        assertFalse(File(temp.root, "jump-list.json.next").exists())
    }

    @Test
    fun whatAnOlderOctoLeftBesideAFileIsCleared() {
        val file = saved("pending.txt", "1 a\n")
        val left = File(temp.root, "pending.txt.new").apply { writeText("0 stale\n") }
        writeWhole(file, "2 b\n")
        assertFalse(left.exists())
        assertEquals("2 b\n", readWhole(file))
    }

    // A save that cannot be kept is logged once while it keeps failing, and
    // the caller carries on.
    @Test
    fun aFailedSaveIsLoggedOnceAndNotThrown() {
        val logged = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                synchronized(logged) { logged += record }
            }
            override fun flush() {}
            override fun close() {}
        }
        val logger = Logger.getLogger("octo.files")
        logger.addHandler(handler)
        try {
            val file = saved("live-lists.json", "[]")
            // A folder where the save writes first: nothing can be saved.
            val blocker = File(temp.root, "live-lists.json.tmp").apply { mkdirs() }
            assertFalse(saveWhole(file, "[1]"))
            assertFalse(saveWhole(file, "[2]"))
            assertEquals(1, logged.size)
            assertTrue(logged.single().message.contains("live-lists.json"))
            assertEquals("[]", readWhole(file))
            blocker.delete()
            assertTrue(saveWhole(file, "[3]"))
            assertEquals("[3]", readWhole(file))
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    fun aFileThatIsNotThereReadsAsNone() {
        assertEquals(null, readWhole(File(temp.root, "none.json")))
        assertEquals(null, readSaved(File(temp.root, "none.json")))
    }
}
