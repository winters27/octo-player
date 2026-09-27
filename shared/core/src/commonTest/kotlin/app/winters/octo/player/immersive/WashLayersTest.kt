package app.winters.octo.player.immersive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class WashLayersTest {
    // The spec's table, written out as its formulas: offset x, offset y and
    // turn at time t and rotation R.
    private class Row(
        val name: String,
        val opacity: Float,
        val zoom: Float,
        val breathes: Boolean,
        val x: (Float) -> Float,
        val y: (Float) -> Float,
        val turn: (Float, Float) -> Float,
    )

    private val spec = listOf(
        Row("Base", 1.0f, 3.0f, false, { 0f }, { 0f }, { _, _ -> 0f }),
        Row("Parallax 1", 0.60f, 3.0f, true, { t -> 0.10f + 0.08f * sin(0.25f * t) }, { t -> 0.10f + 0.06f * cos(0.25f * t) }, { _, r -> 0.5f * r }),
        Row(
            "Parallax 2", 0.30f, 3.8f, true,
            { t -> 0.25f + 0.08f * sin(0.25f * t + 2.4f) }, { t -> 0.25f + 0.06f * cos(0.25f * t + 2.4f) },
            { _, r -> 0.5f * r + 0.15f },
        ),
        Row(
            "Parallax 3", 0.20f, 4.6f, true,
            { t -> 0.40f + 0.08f * sin(0.25f * t + 4.8f) }, { t -> 0.40f + 0.06f * cos(0.25f * t + 4.8f) },
            { _, r -> 0.5f * r + 0.30f },
        ),
        Row(
            "Primary", 0.85f, 2.4f, true,
            { t -> 0.05f * sin(0.5f * t) }, { t -> 0.04f * cos(0.5f * t) },
            { t, r -> r + 0.02f * sin(0.5f * t) },
        ),
        Row(
            "Secondary", 0.70f, 2.0f, true,
            { t -> 0.30f + 0.06f * sin(0.5f * t + 1f) }, { t -> 0.30f + 0.05f * cos(0.5f * t + 1f) },
            { t, r -> -0.5f * r + 0.02f * sin(0.7f * t + 1f) },
        ),
        Row(
            "Tertiary", 0.55f, 1.8f, true,
            { t -> -0.20f + 0.07f * sin(0.4f * t) }, { t -> 0.40f + 0.05f * cos(0.3f * t) },
            { t, r -> 0.8f * r + 0.02f * sin(0.8f * t + 2f) },
        ),
        Row(
            "Fourth", 0.40f, 2.2f, true,
            { t -> 0.40f + 0.08f * sin(0.35f * t + 3f) }, { t -> -0.10f + 0.06f * cos(0.25f * t + 3f) },
            { t, r -> 1.5f * r + 0.02f * sin(0.6f * t + 3f) },
        ),
    )

    @Test
    fun theCopiesMatchTheSpecsTable() {
        assertEquals(spec.map { it.name }, WashCopies.map { it.name })
        spec.zip(WashCopies).forEach { (row, copy) ->
            assertEquals(row.name, row.opacity, copy.opacity)
            assertEquals(row.name, row.zoom, copy.zoom)
            assertEquals(row.name, row.breathes, copy.breathes)
            for (t in listOf(0f, 1.3f, 7.7f, 42f, 100f)) {
                for (r in listOf(-5f, 0f, 0.6f, 6f)) {
                    // The table rounds the golden-angle phases to 2.4 and 4.8.
                    assertEquals("${row.name} x at $t", row.x(t), copy.offsetX(t), 1e-3f)
                    assertEquals("${row.name} y at $t", row.y(t), copy.offsetY(t), 1e-3f)
                    assertEquals("${row.name} turn at $t, $r", row.turn(t, r), copy.angle(t, r), 1e-5f)
                }
            }
        }
    }

    @Test
    fun theParallaxPhasesStepByTheGoldenAngle() {
        val phases = WashCopies.filter { it.name.startsWith("Parallax") }.map { it.xPhase }
        assertEquals(listOf(0f, 2.39996f, 4.79992f), phases)
    }

    @Test
    fun breathingMagnifiesEveryCopyButTheBase() {
        assertEquals(3f, WashCopies[0].magnification(1.1f))
        assertEquals(2.4f * 1.1f, WashCopies[4].magnification(1.1f), 1e-6f)
    }

    @Test
    fun offsetComesBeforeZoomAndTurnAboutTheCentre() {
        val base = WashCopies[0]
        // The centre samples the cover's centre; a third of the way out
        // samples a ninth, magnified three times.
        val (u, v) = copySample(base, 0f, 0f, 1f, 0.5f, 0.5f)
        assertEquals(0.5f, u, 1e-6f)
        assertEquals(0.5f, v, 1e-6f)
        val (u2, _) = copySample(base, 0f, 0f, 1f, 0.8f, 0.5f)
        assertEquals(0.6f, u2, 1e-6f)
        // Parallax 1 at t = 0: offset (0.1, 0.16), zoom 3, turned by 0.5R.
        val p1 = WashCopies[1]
        val r = 0.4f
        val (u3, v3) = copySample(p1, 0f, r, 1f, 0.5f, 0.5f)
        val a = 0.5f * r
        assertEquals(0.5f + (cos(a) * 0.1f - sin(a) * 0.16f) / 3f, u3, 1e-5f)
        assertEquals(0.5f + (sin(a) * 0.1f + cos(a) * 0.16f) / 3f, v3, 1e-5f)
    }

    @Test
    fun theWarpNumbersAreTheSpecs() {
        assertEquals(0.17578f, WarpAmplitude, 1e-5f)
        assertArrayEquals(floatArrayOf(0.0175f, 0.0105f, 0.021f), WarpScales, 1e-6f)
        assertEquals(listOf(0.25f, 0.5f, 0.75f), WarpRest.toList())
    }

    @Test
    fun dividersAtRestLeaveThePictureAlone() {
        val rest = floatArrayOf(0.25f, 0.5f, 0.75f)
        for (i in 0..100) {
            val x = i / 100f
            assertEquals(x, warpX(x, rest), 1e-6f)
        }
    }

    @Test
    fun theDividersStayInOrderWithRoomBetween() {
        var tightest = 1f
        for (y in 0 until 512 step 7) {
            for (r in listOf(-6.2f, -2f, 0f, 0.7f, 3f, 6.2f)) {
                for (p in 0 until 63) {
                    val d = warpDividers(y.toFloat(), r, p / 10f)
                    val gaps = listOf(d[0], d[1] - d[0], d[2] - d[1], 1f - d[2])
                    gaps.forEach { assertTrue("gap $it at $y, $r, $p", it >= WarpMinBand - 1e-6f) }
                    tightest = minOf(tightest, gaps.min())
                }
            }
        }
        // The dividers do swing far enough to meet, so the minimum band is
        // what holds them apart.
        assertEquals(WarpMinBand, tightest, 1e-4f)
    }

    @Test
    fun theRemapIsMonotonicAndCoversTheWidth() {
        for (y in listOf(0f, 100f, 311f, 511f)) {
            for (p in listOf(0f, 1f, 2.5f, 4f, 5.5f)) {
                val d = warpDividers(y, 5f, p)
                assertEquals(0f, warpX(0f, d), 1e-6f)
                assertEquals(1f, warpX(1f, d), 1e-5f)
                var last = -1f
                for (i in 0..1000) {
                    val sx = warpX(i / 1000f, d)
                    assertTrue(sx >= last - 1e-6f)
                    last = sx
                }
                // Each divider lands on its quarter.
                assertEquals(0.25f, warpX(d[0], d), 1e-5f)
                assertEquals(0.5f, warpX(d[1], d), 1e-5f)
                assertEquals(0.75f, warpX(d[2], d), 1e-5f)
            }
        }
    }
}
