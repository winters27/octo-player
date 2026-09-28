package app.winters.octo.desktop.system

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkRegistrationTest {
    @Test
    fun theLinkCommandQuotesTheProgramAndTheLink() {
        // A program under a folder with spaces still gets the whole link.
        assertEquals("\"C:/Users/A B/Octo/Octo.exe\" \"%1\"", linkCommand("C:/Users/A B/Octo/Octo.exe"))
    }

    @Test
    fun windowsKeepsTheRegistrationExactly() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        // A scheme of the test's own, never octo itself, removed after.
        val key = "Software\\Classes\\octo-test-" + System.nanoTime()
        val program = "C:\\Program Files\\Octo\\Octo.exe"
        try {
            assertNull(linkProgramOnWindows(key))
            assertTrue(registerLinksOnWindows(program, key))
            assertEquals("\"$program\" \"%1\"", linkProgramOnWindows(key))
            // Asked again from the same place, nothing needs writing.
            assertTrue(registerLinksOnWindows(program, key))
        } finally {
            forgetLinksOnWindows(key)
        }
        assertNull(linkProgramOnWindows(key))
    }
}
