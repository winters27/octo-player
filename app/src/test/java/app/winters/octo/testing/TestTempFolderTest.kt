package app.winters.octo.testing

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test temp files land in this test task's own folder, which the build empties
 * around every run (root build.gradle.kts), never loose in the system temp
 * folder, where every database a test left behind used to pile up.
 */
class TestTempFolderTest {
    private val scratch = File(System.getProperty("java.io.tmpdir")).canonicalFile

    @Test
    fun tempFolderIsTheTaskOwnFolder() {
        assertTrue(scratch.name, Regex("octo-t[0-9a-f]{1,8}").matches(scratch.name))
    }

    @Test
    fun newTempFoldersLandThere() {
        val made = createTempDirectory("catalog").toFile()
        try {
            assertEquals(scratch, made.canonicalFile.parentFile)
        } finally {
            made.deleteRecursively()
        }
    }

    @Test
    fun nativeCodeAndChildProcessesSeeTheSameFolder() {
        for (name in listOf("TMP", "TEMP", "TMPDIR")) {
            val value = System.getenv(name)
            assertTrue("$name is unset", value != null)
            assertEquals(name, scratch, File(value!!).canonicalFile)
        }
    }
}
