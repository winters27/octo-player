package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

class CarArtTest {
    @get:Rule val folder = TemporaryFolder()

    private val side = 200
    private val gray = 0xFF808080.toInt()

    private fun cover() = IntArray(side * side) { gray }

    // Where the mark's disc sits, worked out as the mark does.
    private fun disc(round: Boolean, side: Int = this.side): Triple<Float, Float, Float> {
        val mark = side * MARK_SHARE
        val margin = maxOf(side * MARK_MARGIN, 3f)
        val inset = if (round) {
            val radius = side / 2f
            radius - (radius - margin - mark / 2) / kotlin.math.sqrt(2f) - mark / 2
        } else {
            margin
        }
        val r = mark / 2
        return Triple(side - inset - r, side - inset - r, r)
    }

    @Test
    fun theMarkChangesOnlyItsDiscAndShowsItsPlus() {
        listOf(false, true).forEach { round ->
            val pixels = cover()
            drawOutsideMark(pixels, side, side, round)
            val (cx, cy, r) = disc(round)
            var changed = 0
            for (y in 0 until side) {
                for (x in 0 until side) {
                    if (pixels[y * side + x] == gray) continue
                    changed++
                    assertTrue("pixel $x,$y outside the disc", hypot(x + 0.5f - cx, y + 0.5f - cy) <= r + 1f)
                }
            }
            assertTrue(changed > 0)
            // The middle of the plus is light, the film beside it dark.
            val middle = pixels[cy.toInt() * side + cx.toInt()] and 0xFF
            val film = pixels[(cy + r * 0.6f).toInt() * side + (cx + r * 0.6f).toInt()] and 0xFF
            assertTrue("plus $middle over film $film", middle > 0x80 && film < 0x80)
        }
    }

    @Test
    fun aRoundPictureHasItsMarkFurtherIn() {
        assertTrue(disc(round = true).first < disc(round = false).first)
    }

    @Test
    fun theMarkStillShowsItsPlusOnceACarShrinksTheCoverToARow() {
        // A car is sent 512 px and shows a row's cover at 56 px.
        val big = 512
        val row = 56
        val pixels = IntArray(big * big) { gray }
        drawOutsideMark(pixels, big, big, round = false)
        val shrunk = shrink(pixels, big, row)
        val (cx, cy, r) = disc(round = false, side = big)
        val scale = row.toFloat() / big
        val middle = shrunk[(cy * scale).toInt() * row + (cx * scale).toInt()] and 0xFF
        val film = shrunk[((cy + r * 0.6f) * scale).toInt() * row + ((cx + r * 0.6f) * scale).toInt()] and 0xFF
        assertTrue("disc only ${2 * r * scale} px across", 2 * r * scale >= 10f)
        assertTrue("plus $middle over film $film", middle - film >= 0x40)
    }

    // The blue channel of `pixels` (`from` square) averaged down to `to` square.
    private fun shrink(pixels: IntArray, from: Int, to: Int): IntArray = IntArray(to * to) { i ->
        val x0 = (i % to) * from / to
        val y0 = (i / to) * from / to
        val x1 = ((i % to) + 1) * from / to
        val y1 = ((i / to) + 1) * from / to
        var sum = 0
        for (y in y0 until y1) for (x in x0 until x1) sum += pixels[y * from + x] and 0xFF
        sum / ((x1 - x0) * (y1 - y0))
    }

    @Test
    fun aHandleStillResolvesAfterTheAppRestarts() {
        val dir = File(folder.root, "refs")
        val ref = "station-art:" + "x".repeat(400)
        val handle = CarArtRefs(dir).handleFor(ref)
        assertTrue(isHandle(handle))
        assertEquals(ref, CarArtRefs(dir).refFor(handle))
        assertNull(CarArtRefs(dir).refFor("../../etc"))
        assertNull(CarArtRefs(dir).refFor("0".repeat(40)))
    }

    @Test
    fun manyWritersOfOneReferenceLeaveItWhole() {
        val dir = File(folder.root, "refs")
        val ref = "station-art:" + "y".repeat(5_000)
        val pool = Executors.newFixedThreadPool(8)
        repeat(32) { pool.execute { CarArtRefs(dir).handleFor(ref) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        val files = dir.listFiles()!!
        assertEquals(listOf("${carSha1(ref)}.ref"), files.map { it.name })
        assertEquals(ref, files.single().readText())
    }

    @Test
    fun manyWritersOfOnePictureLeaveOneOfThemWhole() {
        val dir = File(folder.root, "art").apply { mkdirs() }
        val file = File(dir, "cover.jpg")
        val pictures = List(8) { n -> ByteArray(200_000) { (n + 1).toByte() } }
        val pool = Executors.newFixedThreadPool(8)
        repeat(32) { i -> pool.execute { writeWhole(file, pictures[i % 8]) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        val got = file.readBytes()
        assertTrue(pictures.any { it.contentEquals(got) })
        assertEquals(listOf("cover.jpg"), dir.listFiles()!!.map { it.name })
    }

    @Test
    fun theLeastRecentlyHandedOutGoFirst() {
        val dir = File(folder.root, "refs")
        val refs = CarArtRefs(dir, kept = 3)
        val handles = (1..3).map { refs.handleFor("ref-$it-" + "z".repeat(300)) }
        File(dir, "${handles[0]}.ref").setLastModified(1_000)
        File(dir, "${handles[1]}.ref").setLastModified(2_000)
        refs.handleFor("ref-4-" + "z".repeat(300))
        val left = dir.listFiles()!!.map { it.name.removeSuffix(".ref") }.toSet()
        assertEquals(3, left.size)
        assertFalse(handles[0] in left)
    }
}
