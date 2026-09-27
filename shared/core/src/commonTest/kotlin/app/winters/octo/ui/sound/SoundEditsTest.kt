package app.winters.octo.ui.sound

import app.winters.octo.sound.EqFilter
import app.winters.octo.sound.EqMode
import app.winters.octo.sound.EqPresets
import app.winters.octo.sound.FilterType
import app.winters.octo.sound.GRAPHIC_Q
import app.winters.octo.sound.GraphicBands
import app.winters.octo.sound.ResponseCurve
import app.winters.octo.sound.SoundSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundEditsTest {
    private val rock = EqPresets.first { it.name == "Rock" }

    @Test
    fun aPresetSetsTheBandsAndSwitchesToGraphic() {
        val before = SoundSettings(eqEnabled = false, mode = EqMode.Parametric, preset = null)
        val after = withPreset(before, rock)
        assertEquals(rock.gains, after.graphicGains)
        assertEquals(EqMode.Graphic, after.mode)
        assertEquals("Rock", after.preset)
        assertTrue(after.eqEnabled)
        assertEquals("Rock", presetLabel(after))
    }

    @Test
    fun movingABandByHandMakesTheCurveCustom() {
        val after = withBandGain(withPreset(SoundSettings(), rock), 3, 20f)
        assertEquals(12f, after.graphicGains[3])
        assertNull(after.preset)
        assertEquals(CUSTOM, presetLabel(after))
    }

    @Test
    fun gainsSnapToZeroAndToTenths() {
        assertEquals(0f, cleanGain(0.2f))
        assertEquals(0f, cleanGain(-0.24f))
        assertEquals(3.5f, cleanGain(3.46f))
        assertEquals(-12f, cleanGain(-30f))
    }

    @Test
    fun parametricStartsFromTheTenBands() {
        val graphic = withPreset(SoundSettings(), rock)
        val after = withMode(graphic, EqMode.Parametric)
        assertEquals(EqMode.Parametric, after.mode)
        assertEquals(GraphicBands.size, after.filters.size)
        after.filters.forEachIndexed { i, filter ->
            assertEquals(EqFilter(FilterType.Peak, GraphicBands[i], rock.gains[i], GRAPHIC_Q), filter)
        }
        // The sound does not change on the switch.
        val heard = ResponseCurve.curve(graphic.activeFilters())
        val same = ResponseCurve.curve(after.activeFilters())
        heard.zip(same).forEach { (a, b) -> assertEquals(a, b, 0.0001f) }
    }

    @Test
    fun flatBandsLeaveEarlierFiltersAlone() {
        val own = listOf(EqFilter(FilterType.Peak, 3_000f, -4f, 2f))
        val settings = SoundSettings(mode = EqMode.Graphic, filters = own)
        assertEquals(own, withMode(settings, EqMode.Parametric).filters)
    }

    @Test
    fun goingBackToGraphicKeepsTheBands() {
        val parametric = withMode(withPreset(SoundSettings(), rock), EqMode.Parametric)
        val back = withMode(parametric, EqMode.Graphic)
        assertEquals(rock.gains, back.graphicGains)
        assertEquals("Rock", presetLabel(back))
    }

    @Test
    fun newFiltersGoInTheWidestGapAndStopAtSixteen() {
        val none = withNewFilter(SoundSettings(mode = EqMode.Parametric))
        // Halfway between 20 Hz and 20 kHz on a log scale.
        assertEquals(630f, none.filters.single().frequency)
        assertEquals(0f, none.filters.single().gainDb)

        var full = SoundSettings(mode = EqMode.Parametric)
        repeat(20) { full = withNewFilter(full) }
        assertEquals(MAX_FILTERS, full.filters.size)
        assertSame(full, withNewFilter(full))
    }

    @Test
    fun filterEditsStayInRange() {
        val settings = SoundSettings(mode = EqMode.Parametric, filters = listOf(EqFilter(FilterType.Peak, 1_000f, 0f, 1f)))
        val after = withFilter(settings, 0, EqFilter(FilterType.HighShelf, 50_000f, 40f, 0f))
        assertEquals(EqFilter(FilterType.HighShelf, MAX_HZ, 12f, MIN_Q), after.filters[0])
        assertEquals(emptyList<EqFilter>(), withoutFilter(after, 0).filters)
    }
}
