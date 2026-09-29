package app.winters.octo.desktop.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// JNA is pointed at the app's folder only when its libraries are there,
// so a build run from source keeps finding them on the class path.
class AppNativesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun theAppsOwnLibrariesAreUsedOnlyWhereTheyAre() {
        assertNull(appNativesFolder(null))
        assertNull(appNativesFolder(""))
        assertNull(appNativesFolder(folder.root.path))
        File(folder.root, System.mapLibraryName("jnidispatch")).writeText("")
        assertEquals(folder.root, appNativesFolder(folder.root.path))
    }
}
