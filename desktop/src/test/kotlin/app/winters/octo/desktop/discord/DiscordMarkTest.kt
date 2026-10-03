package app.winters.octo.desktop.discord

import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordMarkTest {
    // Simple Icons' own path, with its packed arc flags, drew arcs that swung
    // far outside the 24 by 24 box and across the mark. Written out, the mark
    // keeps inside it, as Discord's does.
    @Test
    fun theMarkStaysInsideItsBox() {
        val paths = DiscordMark.root.filterIsInstance<VectorPath>()
        assertTrue(paths.isNotEmpty())
        for (path in paths) {
            val bounds = path.pathData.toPath().getBounds()
            assertTrue("drawn at $bounds", bounds.left >= -0.5f && bounds.top >= -0.5f && bounds.right <= 24.5f && bounds.bottom <= 24.5f)
            // And it fills most of the box, so nothing was lost either.
            assertTrue("drawn at $bounds", bounds.width > 22f && bounds.height > 16f)
        }
    }
}
