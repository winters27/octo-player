package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.allIcons
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

// Every icon at the sizes the desktop draws them (inline, table, toolbar,
// transport, and 24), white and in the quiet accent, at 1x and 2x: for
// looking at the whole set after it is regenerated (tools/icons). Only
// when asked: OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*IconSheetShotsTest*'.
// Saved as build/shots/icons-1x.png and icons-2x.png, with the title bar's
// window buttons as the frame draws them (46 wide, a 16 icon, quiet) in
// icons-titlebar-1x.png and -2x.png; the shots draw no frame of their own.
class IconSheetShotsTest {
    @Test
    fun theWholeSet() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val sizes = listOf(IconSize.Inline, IconSize.Table, IconSize.Toolbar, IconSize.Transport, 24.dp)
        val label = TextStyle(color = OctoColors.TextSecondary, fontSize = 11.sp)
        val columns = allIcons.chunked((allIcons.size + 1) / 2)
        for (density in listOf(1f, 2f)) {
            val width = 860
            val height = columns.first().size * 32 + 24
            val scene = ImageComposeScene((width * density).toInt(), (height * density).toInt(), Density(density)) {
                Row(Modifier.background(OctoColors.Background).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    columns.forEach { column ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            column.forEach { (name, icon) ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    BasicText(name, Modifier.width(110.dp), style = label)
                                    sizes.forEach { Glyph(icon(), size = it, tint = OctoColors.TextPrimary) }
                                    sizes.forEach { Glyph(icon(), size = it, tint = OctoColors.TextSecondary) }
                                }
                            }
                        }
                    }
                }
            }
            save(scene, "icons-${density.toInt()}x")
            val bar = ImageComposeScene((46 * 5 * density).toInt(), (32 * density).toInt(), Density(density)) {
                Row(Modifier.background(OctoColors.Background)) {
                    listOf(OctoIcons.Minimize, OctoIcons.Maximize, OctoIcons.Restore, OctoIcons.Close, OctoIcons.Settings).forEach { icon ->
                        Box(Modifier.size(46.dp, 32.dp), contentAlignment = Alignment.Center) { Glyph(icon, size = 16.dp, tint = OctoColors.TextSecondary) }
                    }
                }
            }
            save(bar, "icons-titlebar-${density.toInt()}x")
        }
    }

    private fun save(scene: ImageComposeScene, name: String) {
        val image = scene.render()
        File("build/shots/$name.png").apply { parentFile.mkdirs() }.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        image.close()
        scene.close()
    }
}
