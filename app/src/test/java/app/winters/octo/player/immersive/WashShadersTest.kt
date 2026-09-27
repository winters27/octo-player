package app.winters.octo.player.immersive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WashShadersTest {
    @Test
    fun theBlurTapsAreTheSpecs() {
        assertEquals(9, BlurTaps.size)
        assertEquals(0.9f, BlurStepAcross, 1e-6f)
        assertEquals(1.2f, BlurStepDown, 1e-6f)
        // The weights add to a little under one, dimming each pass.
        assertEquals(0.8743f, BlurPassGain, 1e-4f)
    }

    @Test
    fun thePlatformBlurMatchesTheTapsSpread() {
        // The taps follow a bell curve about 3.44 taps wide.
        assertEquals(3.44f, BlurSigmaTaps, 0.01f)
        // Across: 3.44 x 0.9 texels a pass, two passes: about 4.38 texels.
        assertEquals(4.38f, blurSigmaTexels(BlurStepAcross), 0.01f)
        assertEquals(5.84f, blurSigmaTexels(BlurStepDown), 0.01f)
        // The platform turns a radius r into a spread of 0.57735 r + 0.5.
        val across = blurRadiusFor(blurSigmaTexels(BlurStepAcross))
        val down = blurRadiusFor(blurSigmaTexels(BlurStepDown))
        assertEquals(6.72f, across, 0.02f)
        assertEquals(9.25f, down, 0.02f)
        assertEquals(blurSigmaTexels(BlurStepAcross), 0.57735f * across + 0.5f, 1e-4f)
        assertEquals(0f, blurRadiusFor(0.2f))
    }

    @Test
    fun theFinalGainCarriesTheBlursDimming() {
        val four = BlurPassGain * BlurPassGain * BlurPassGain * BlurPassGain
        assertEquals(1.5f * four, FinalGain, 1e-6f)
        assertEquals(0.876f, FinalGain, 0.001f)
    }

    @Test
    fun theCompositeShaderIsWrittenFromTheTable() {
        val lines = CompositeShader.lines()
        val draws = lines.filter { it.trim().startsWith("c = mix(c, copy(uv, m") }
        assertEquals(WashCopies.size, draws.size)
        draws.zip(WashCopies).forEachIndexed { i, (line, copy) ->
            assertTrue(line, line.trim() == "c = mix(c, copy(uv, m$i, o$i), ${String.format(Locale.ROOT, "%.5f", copy.opacity)});")
            assertTrue("uniform float4 m$i;" in lines)
            assertTrue("uniform float2 o$i;" in lines)
        }
        // Numbers are written with a point, whatever the phone's language.
        assertFalse(Regex("""\d,\d""").containsMatchIn(CompositeShader))
        assertTrue("uniform shader oldCover;" in lines)
        assertTrue("uniform shader newCover;" in lines)
    }

    @Test
    fun theFinalShaderDithersByOneStepEitherWay() {
        assertTrue("n / 255.0" in FinalShader)
        assertTrue("uniform shader wash;" in FinalShader)
    }
}
