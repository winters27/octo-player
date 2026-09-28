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

    @Test
    fun onlyTheInstalledLauncherCountsAsInstalled() {
        assertEquals("C:/Users/b/AppData/Local/Octo/Octo.exe", installedProgram(null, "C:/Users/b/AppData/Local/Octo/Octo.exe"))
        assertEquals("/opt/octo/bin/Octo", installedProgram(null, "/opt/octo/bin/Octo"))
        assertEquals("/opt/octo/bin/Octo", installedProgram("/opt/octo/bin/Octo", "/usr/lib/jvm/bin/java"))
        // A build runs on Java: nothing is registered for it.
        assertNull(installedProgram(null, "C:/jdks/17/bin/java.exe"))
        assertNull(installedProgram(null, null))
    }

    @Test
    fun linuxGetsAHiddenEntryForLinksOnlyOnce() {
        val folder = kotlin.io.path.createTempDirectory("octo-apps").toFile()
        try {
            assertTrue(registerLinksOnLinux("/opt/octo/bin/Octo", folder, makeDefault = false))
            val entry = java.io.File(folder, "octo-links.desktop").readText()
            assertTrue("Exec=\"/opt/octo/bin/Octo\" %u" in entry)
            assertTrue("MimeType=x-scheme-handler/octo;" in entry)
            assertTrue("NoDisplay=true" in entry)
            // The same program again changes nothing.
            val written = java.io.File(folder, "octo-links.desktop").lastModified()
            assertTrue(registerLinksOnLinux("/opt/octo/bin/Octo", folder, makeDefault = false))
            assertEquals(written, java.io.File(folder, "octo-links.desktop").lastModified())
        } finally {
            folder.deleteRecursively()
        }
        assertEquals(java.io.File("/x/data", "applications"), linuxApplicationsFolder(mapOf("XDG_DATA_HOME" to "/x/data")::get, "/home/b"))
        assertEquals(java.io.File("/home/b/.local/share", "applications"), linuxApplicationsFolder({ null }, "/home/b"))
    }
}
