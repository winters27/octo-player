package app.winters.octo.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundModelTest {
    @Test
    fun aPeakFilterBoostsItsFrequencyByItsGain() {
        val bump = listOf(EqFilter(FilterType.Peak, 1_000f, 6f, 1.41f))
        assertEquals(6f, ResponseCurve.gainDb(bump, 1_000f), 0.05f)
        assertEquals(0f, ResponseCurve.gainDb(bump, 20f), 0.1f)
        assertEquals(0f, ResponseCurve.gainDb(bump, 18_000f), 0.2f)
    }

    @Test
    fun shelvesLiftEverythingPastTheirCorner() {
        val low = listOf(EqFilter(FilterType.LowShelf, 200f, 6f, 0.707f))
        assertEquals(6f, ResponseCurve.gainDb(low, 20f), 0.2f)
        assertEquals(0f, ResponseCurve.gainDb(low, 10_000f), 0.1f)
        val high = listOf(EqFilter(FilterType.HighShelf, 4_000f, -6f, 0.707f))
        assertEquals(-6f, ResponseCurve.gainDb(high, 18_000f), 0.3f)
        assertEquals(0f, ResponseCurve.gainDb(high, 100f), 0.1f)
    }

    @Test
    fun theAutomaticPreampTakesBackTheHighestBoost() {
        val settings = SoundSettings(eqEnabled = true, graphicGains = EqPresets.first { it.name == "Bass Boost" }.gains)
        val preamp = settings.effectivePreampDb()
        assertTrue(preamp < -5f)
        assertEquals(-ResponseCurve.peakDb(settings.activeFilters()), preamp, 0.001f)
    }

    @Test
    fun anEqualizerThatIsOffChangesNothing() {
        val settings = SoundSettings(eqEnabled = false, graphicGains = List(10) { 6f })
        assertTrue(settings.activeFilters().isEmpty())
        assertEquals(0f, settings.effectivePreampDb())
    }

    @Test
    fun aCorrectionRunsBeforeTheOwnCurve() {
        val fix = HeadphoneCorrection("HD 650", "oratory1990", -6f, listOf(EqFilter(FilterType.LowShelf, 105f, 6.4f, 0.7f)))
        val settings = SoundSettings(eqEnabled = true, correction = fix, graphicGains = List(10) { if (it == 5) 3f else 0f })
        val filters = settings.activeFilters()
        assertEquals(FilterType.LowShelf, filters.first().type)
        assertEquals(2, filters.size)
    }

    @Test
    fun everyPresetHasTenBands() {
        EqPresets.forEach { assertEquals(it.name, GraphicBands.size, it.gains.size) }
    }
}
