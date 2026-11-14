package app.winters.octo.ui.common

import app.winters.octo.design.OctoIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// A failure on a form draws a glyph of its own, never the info glyph a note
// draws.
class FormGlyphsTest {
    // Red when the icon set loses its error glyph or points it at the info
    // glyph, or a form error falls back to the info glyph.
    @Test
    fun anErrorDrawsTheErrorGlyph() {
        assertNotEquals(OctoIcons.Info, OctoIcons.Error)
        // Filled, a shape of its own beside the info ring. Red with the
        // outlined warning circle.
        assertEquals(app.winters.octo.design.R.drawable.sym_error_fill, OctoIcons.Error)
        val groups = File("src/main/java/app/winters/octo/ui/common/FormGroups.kt").readText().replace("\r\n", "\n")
        val error = groups.substringAfter("fun FormError(").substringBefore(") {")
        assertTrue(error, error.contains("@DrawableRes icon: Int = OctoIcons.Error,"))
        assertFalse(error, error.contains("OctoIcons.Info"))
    }
}
