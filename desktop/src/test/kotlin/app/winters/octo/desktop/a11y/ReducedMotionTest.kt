package app.winters.octo.desktop.a11y

import app.winters.octo.desktop.nav.Page
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// With motion reduced (Calm motion, or the system's setting) the playing
// row's bars rest, still lit; with it full they move. The same frames are
// drawn at two moments of the scene's clock and compared.
class ReducedMotionTest {
    @get:Rule val folder = TemporaryFolder()

    // The number column of the playing row, drawn at two moments.
    private fun barsMove(reduce: Boolean): Boolean = A11yScene(folder.newFolder(), reduceMotion = reduce).use { s ->
        s.onUi {
            s.app.play(s.app.library!!.index!!.songs, 0)
            s.app.navigator.go(Page.Songs)
        }
        s.render(6)
        val row = s.nodes().first { it.name().startsWith("Airbag") && it.name().contains("playing") }.boundsInRoot
        fun crop(image: Image): IntArray {
            val bitmap = Bitmap.makeFromImage(image)
            val out = ArrayList<Int>()
            for (y in row.top.toInt() until row.bottom.toInt()) for (x in row.left.toInt() until row.left.toInt() + 60) out += bitmap.getColor(x, y)
            return out.toIntArray()
        }
        val base = System.nanoTime()
        val first = crop(s.frameAt(base))
        val later = crop(s.frameAt(base + 700_000_000L))
        !first.contentEquals(later)
    }

    @Test(timeout = 120_000)
    fun thePlayingBarsMoveWithFullMotion() {
        assertTrue("the bars move while a song plays", barsMove(reduce = false))
    }

    @Test(timeout = 120_000)
    fun thePlayingBarsRestWithMotionReduced() {
        assertFalse("the bars hold still with motion reduced", barsMove(reduce = true))
    }
}
