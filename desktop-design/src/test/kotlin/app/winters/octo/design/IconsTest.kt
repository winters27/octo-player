package app.winters.octo.design

import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

// The desktop draws the phone app's own icon files, so every one of them
// has to read, and every icon the desktop names has to build.
class IconsTest {
    private val drawables = File("../design/src/main/res/drawable")

    @Test
    fun everyPhoneIconFileReads() {
        val files = drawables.listFiles { f -> f.name.startsWith("sym_") && f.name.endsWith(".xml") }.orEmpty()
        assertTrue("found the phone app's icons", files.size > 50)
        files.forEach { file ->
            val icon = parseVectorXml(file.nameWithoutExtension, file.readText())
            assertTrue(file.name, icon.viewportWidth > 0f && icon.viewportHeight > 0f)
            assertTrue(file.name, icon.paths.all { it.data.isNotBlank() })
        }
    }

    @Test
    fun everyPackagedIconBuilds() {
        allIcons.forEach { (name, icon) ->
            val vector = icon()
            val group = vector.root.first() as VectorGroup
            assertTrue(name, group.any { it is VectorPath })
        }
    }

    @Test
    fun aFileWithGroupsIsRefused() {
        val xml = """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp"
            android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
            <group android:rotation="90"><path android:pathData="M0,0L1,1Z"/></group></vector>"""
        try {
            parseVectorXml("grouped", xml)
            fail("a grouped icon was read")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("groups"))
        }
    }

    @Test
    fun theCogIsOnePathWithAHole() {
        val path = cogPath()
        assertEquals(2, path.count { it == 'Z' })
        assertTrue(path.startsWith("M"))
    }
}
