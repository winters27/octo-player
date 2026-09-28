package app.winters.octo.player.immersive

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundPrefsTest {
    @Test
    fun theDefaultsAreTheSpecs() {
        val prefs = BackgroundPrefs()
        assertEquals(BackgroundMode.Default, prefs.mode)
        assertEquals(50, prefs.brightnessCap)
        assertEquals(180, prefs.saturation)
        assertEquals(1.3f, prefs.contrast)
        assertEquals(true, prefs.useBpm)
        assertEquals(60, prefs.fps)
        // Slower than the full pace, so it reads as an ambience.
        assertEquals(25, prefs.speed)
        assertEquals(WashTuning(1.3f, 1.8f, 0.5f), prefs.tuning)
    }

    @Test
    fun oddSavedValuesAreHeldToTheRanges() {
        val odd = BackgroundPrefs(brightnessCap = 5, saturation = 900, contrast = 9f, fps = 45, speed = 0).sane()
        assertEquals(20, odd.brightnessCap)
        assertEquals(300, odd.saturation)
        assertEquals(2f, odd.contrast)
        assertEquals(60, odd.fps)
        assertEquals(5, odd.speed)
    }

    @Test
    fun olderPhonesShowTheStillArtworkForTheWash() {
        assertEquals(BackgroundMode.Default, drawnMode(BackgroundMode.Default, 33))
        assertEquals(BackgroundMode.Artwork, drawnMode(BackgroundMode.Default, 32))
        assertEquals(BackgroundMode.Artwork, drawnMode(BackgroundMode.Default, 29))
        assertEquals(BackgroundMode.Classic, drawnMode(BackgroundMode.Classic, 29))
        assertEquals(BackgroundMode.Colour, drawnMode(BackgroundMode.Colour, 29))
    }

    @Test
    fun theDollyRunsFromNinetyPercentToFull() {
        assertEquals(0.9f, dollyScale(0f), 1e-6f)
        assertEquals(1f, dollyScale(1f), 1e-6f)
    }
}
