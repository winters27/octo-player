package app.winters.octo.design

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathSegment
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.toPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs

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

    // Each file's own note says which Phosphor weight and scale made it;
    // the packaged icon list says the same, so its stroke width is the
    // file's.
    @Test
    fun everyFileIsTheWeightTheIconListSays() {
        val files = drawables.listFiles { f -> f.name.startsWith("sym_") && f.name.endsWith(".xml") }.orEmpty()
        val note = Regex("""^<!-- Phosphor Icons [\d.]+, [\w-]+ \((\w+)(?: at ([\d.]+))?\)""")
        var listed = 0
        files.forEach { file ->
            val name = file.nameWithoutExtension
            val (weight, scale) = IconList.entries[name] ?: return@forEach assertEquals("$name has no stroke", null, loadIcon(name).stroke)
            val found = note.find(file.readText()) ?: error("${file.name} has no note")
            assertEquals(file.name, found.groupValues[1], weight)
            assertEquals(file.name, found.groupValues[2].ifEmpty { "1" }.toFloat(), scale, 1e-6f)
            listed++
        }
        assertEquals("every listed file is packaged", IconList.entries.size, listed)
    }

    @Test
    fun theStrokeIsTheWeightsTimesTheScale() {
        assertEquals(16f, loadIcon("sym_settings").stroke!!, 1e-4f)
        assertEquals(24f, loadIcon("sym_more_horiz").stroke!!, 1e-4f)
        assertEquals(12f, loadIcon("sym_add_light").stroke!!, 1e-4f)
        assertEquals(16f * 0.8f, loadIcon("sym_pause").stroke!!, 1e-4f)
        // Not in the list: drawn exactly, never fitted.
        assertEquals(null, loadIcon("sym_sign_out").stroke)
    }

    // The fitter reads every file as moves, lines, cubics and closes. Drawn
    // from those, each icon is the same as Compose draws its own path data.
    @Test
    fun theFittersOutlinesDrawEveryFileAsComposeDoes() {
        val files = drawables.listFiles { f -> f.name.startsWith("sym_") && f.name.endsWith(".xml") }.orEmpty()
        assertTrue(files.size > 50)
        files.forEach { file ->
            val icon = parseVectorXml(file.nameWithoutExtension, file.readText())
            val outlines = outlinesOf(icon)
            for (px in listOf(24, 48, 96)) {
                val ours = rasterize(outlines, icon.viewportWidth, px)
                val compose = composeRaster(icon, px)
                val worst = ours.indices.maxOf { abs((ours[it].toInt() and 0xFF) - (compose[it].toInt() and 0xFF)) }
                assertTrue("${file.name} at $px px differs by $worst", worst <= 2)
            }
        }
    }

    // Compose's own reading of the icon's path data, drawn as rasterize
    // draws. Compose keeps a quadratic curve as one, which Skia smooths a
    // little differently from the cubic it exactly equals, so each is
    // written as that cubic here; everything else is Compose's own.
    private fun composeRaster(icon: IconSource, px: Int): ByteArray {
        val info = ImageInfo.makeA8(px, px)
        val bitmap = Bitmap().apply { allocPixels(info) }
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            color = 0xFFFFFFFF.toInt()
        }
        canvas.scale(px / icon.viewportWidth, px / icon.viewportWidth)
        icon.paths.forEach { part ->
            val read = addPathNodes(part.data).toPath()
            val path = Path().apply { fillType = if (part.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero }
            for (segment in read) {
                val p = segment.points
                when (segment.type) {
                    PathSegment.Type.Move -> path.moveTo(p[0], p[1])
                    PathSegment.Type.Line -> path.lineTo(p[2], p[3])
                    PathSegment.Type.Quadratic -> path.cubicTo(
                        p[0] + 2f / 3 * (p[2] - p[0]), p[1] + 2f / 3 * (p[3] - p[1]),
                        p[4] + 2f / 3 * (p[2] - p[4]), p[5] + 2f / 3 * (p[3] - p[5]), p[4], p[5],
                    )
                    PathSegment.Type.Cubic -> path.cubicTo(p[2], p[3], p[4], p[5], p[6], p[7])
                    PathSegment.Type.Close -> path.close()
                    else -> error("${icon.name} has a ${segment.type} Compose did not write")
                }
            }
            canvas.drawPath(path.asSkiaPath(), paint)
        }
        return bitmap.readPixels(info, px, 0, 0)!!
    }

    // Every icon file is made from Phosphor by tools/icons, and its licence
    // travels with the icons.
    @Test
    fun everyIconIsPhosphorWithItsLicence() {
        val files = drawables.listFiles { f -> f.name.startsWith("sym_") && f.name.endsWith(".xml") }.orEmpty()
        files.forEach { file -> assertTrue(file.name, file.readText().startsWith("<!-- Phosphor Icons ")) }
        val licence = OctoIcons::class.java.getResourceAsStream("/octo-icons/Phosphor-MIT.txt")
        assertTrue("the licence is packaged", licence != null)
        assertTrue(licence!!.use { it.readBytes().decodeToString() }.contains("Phosphor Icons"))
    }
}
