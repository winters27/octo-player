package app.winters.octo.desktop.system

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // A run with a folder of its own touches nothing of the system's.
        assertNull(installedProgram("C:/Users/b/AppData/Local/Octo/Octo.exe", "C:/Users/b/AppData/Local/Octo/Octo.exe", separate = true))
    }

    @Test
    fun linuxGetsTheAppsEntryWithFilesAndLinksOnlyOnce() {
        val folder = kotlin.io.path.createTempDirectory("octo-apps").toFile()
        try {
            assertTrue(registerWithLinuxDesktop("/opt/octo/bin/Octo", folder, makeDefault = false))
            // Named as the package names its own, so it is read in its place.
            val file = java.io.File(folder, "octo-Octo.desktop")
            val entry = file.readText()
            assertTrue("Exec=\"/opt/octo/bin/Octo\" %U" in entry)
            assertTrue("Icon=/opt/octo/lib/Octo.png" in entry)
            assertTrue("audio/flac;" in entry)
            assertTrue("x-scheme-handler/octo;" in entry)
            // The same program again changes nothing.
            val written = file.lastModified()
            assertTrue(registerWithLinuxDesktop("/opt/octo/bin/Octo", folder, makeDefault = false))
            assertEquals(written, file.lastModified())
        } finally {
            folder.deleteRecursively()
        }
        assertEquals(java.io.File("/x/data", "applications"), linuxApplicationsFolder(mapOf("XDG_DATA_HOME" to "/x/data")::get, "/home/b"))
        assertEquals(java.io.File("/home/b/.local/share", "applications"), linuxApplicationsFolder({ null }, "/home/b"))
    }

    // Inside a Flatpak nothing is written: the Flatpak's own entry starts
    // Octo in its sandbox. The media controls and notices name that entry.
    @Test
    fun aFlatpakWritesNoEntryAndNamesItsOwn() {
        val folder = kotlin.io.path.createTempDirectory("octo-apps").toFile()
        val flatpak = mapOf("FLATPAK_ID" to "app.winters.octo")::get
        try {
            assertTrue(registerWithLinuxDesktop("/app/octo/bin/Octo", folder, makeDefault = false, env = flatpak))
            assertTrue(folder.listFiles().isNullOrEmpty())
        } finally {
            folder.deleteRecursively()
        }
        assertTrue(inFlatpak(flatpak))
        assertEquals("app.winters.octo", linuxDesktopId(flatpak))
        assertEquals("octo-Octo", linuxDesktopId { null })
    }

    // An AppImage's files go when it quits: the entry starts the AppImage
    // file (quoted, spaces and all) and shows an icon copied out of it.
    @Test
    fun anAppImagesEntryOutlivesItsMountedFiles() {
        val root = kotlin.io.path.createTempDirectory("octo-image").toFile()
        try {
            val mount = java.io.File(root, ".mount_OctoAb12").apply { java.io.File(this, "usr/lib").mkdirs() }
            java.io.File(mount, "usr/lib/Octo.png").writeBytes(byteArrayOf(1, 2, 3))
            val image = java.io.File(root, "My Apps/Octo.AppImage").apply { parentFile.mkdirs(); writeText("image") }
            val applications = java.io.File(root, "data/applications")
            val env = mapOf("APPDIR" to mount.path, "APPIMAGE" to image.path)::get
            val program = "${mount.path}/usr/bin/Octo"
            assertTrue(registerWithLinuxDesktop(program, applications, makeDefault = false, env = env))
            mount.deleteRecursively()
            val entry = java.io.File(applications, "octo-Octo.desktop").readText()
            assertTrue("Exec=\"${image.absoluteFile.path}\" %U" in entry, entry)
            assertFalse(mount.name in entry, "nothing points into the mounted files")
            val icon = java.io.File(entry.lineSequence().first { it.startsWith("Icon=") }.substringAfter('='))
            assertTrue(icon.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
            assertEquals(java.io.File(root, "data/icons/hicolor/512x512/apps/app.winters.octo.png"), icon)
        } finally {
            root.deleteRecursively()
        }
    }

    // Files unpacked from an AppImage by hand have no AppImage to start
    // again, so nothing is written for them.
    @Test
    fun anUnpackedAppImageWritesNoEntry() {
        val root = kotlin.io.path.createTempDirectory("octo-unpacked").toFile()
        try {
            val folder = java.io.File(root, "squashfs-root")
            val applications = java.io.File(root, "applications")
            val env = mapOf("APPDIR" to folder.path)::get
            assertTrue(inUnpackedAppImageFolder("${folder.path}/usr/bin/Octo", env))
            assertTrue(registerWithLinuxDesktop("${folder.path}/usr/bin/Octo", applications, makeDefault = false, env = env))
            assertFalse(applications.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    // APPDIR and APPIMAGE count only when Octo's program is inside APPDIR:
    // an AppImage that started Octo (a terminal, say) passes its own on.
    @Test
    fun anotherAppImagesVariablesAreNeverOctos() {
        val terminal = mapOf("APPDIR" to "/tmp/.mount_TermX1", "APPIMAGE" to "/home/b/Apps/Terminal.AppImage")::get
        assertNull(runningAppImage("/opt/octo/bin/Octo", terminal))
        assertFalse(inUnpackedAppImageFolder("/opt/octo/bin/Octo", mapOf("APPDIR" to "/home/b/squashfs-root")::get))
        assertNull(runningAppImage(null, terminal))
        val octo = mapOf("APPDIR" to "/tmp/.mount_OctoQ7", "APPIMAGE" to "/home/b/Apps/Octo.AppImage")::get
        val running = runningAppImage("/tmp/.mount_OctoQ7/usr/bin/Octo", octo)!!
        assertEquals(java.io.File("/home/b/Apps/Octo.AppImage").absoluteFile, running.file)
        assertFalse(running.unpacked)
        val unpacked = mapOf("APPDIR" to "/tmp/appimage_extracted_5f3a", "APPIMAGE" to "/home/b/Apps/Octo.AppImage")::get
        assertTrue(runningAppImage("/tmp/appimage_extracted_5f3a/usr/bin/Octo", unpacked)!!.unpacked)
        // A folder that only starts with the same letters is not APPDIR.
        assertNull(runningAppImage("/tmp/.mount_OctoQ7-other/usr/bin/Octo", octo))
    }
}
