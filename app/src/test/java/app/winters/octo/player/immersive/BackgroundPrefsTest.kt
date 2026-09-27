package app.winters.octo.player.immersive

import androidx.compose.ui.graphics.Color
import app.winters.octo.player.PlayerColors
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundPrefsTest {
    private val yellow = PlayerColors.Quiet.copy(dominant = Color(0xFFFFFF00))

    @Test
    fun theDefaultsAreTheSpecs() {
        val prefs = BackgroundPrefs()
        assertEquals(BackgroundMode.Default, prefs.mode)
        assertEquals(50, prefs.brightnessCap)
        assertEquals(180, prefs.saturation)
        assertEquals(1.3f, prefs.contrast)
        assertEquals(true, prefs.useBpm)
        assertEquals(60, prefs.fps)
        assertEquals(WashTuning(1.3f, 1.8f, 0.5f), prefs.tuning)
    }

    @Test
    fun oddSavedValuesAreHeldToTheRanges() {
        val odd = BackgroundPrefs(brightnessCap = 5, saturation = 900, contrast = 9f, fps = 45).sane()
        assertEquals(20, odd.brightnessCap)
        assertEquals(300, odd.saturation)
        assertEquals(2f, odd.contrast)
        assertEquals(60, odd.fps)
    }

    @Test
    fun theWordsFollowTheBackground() {
        assertEquals(DarkInk, yellow.over(BackgroundPrefs()).content)
        assertEquals(DarkInk, yellow.over(BackgroundPrefs(mode = BackgroundMode.Artwork)).content)
        assertEquals(DarkInk, yellow.over(BackgroundPrefs(mode = BackgroundMode.Colour)).content)
        // The classic mesh keeps white words.
        assertEquals(Color.White, yellow.over(BackgroundPrefs(mode = BackgroundMode.Classic)).content)
        // A low cap keeps even yellow dim enough for white.
        assertEquals(Color.White, yellow.over(BackgroundPrefs(brightnessCap = 30)).content)
        // No artwork: white.
        assertEquals(Color.White, PlayerColors.Quiet.over(BackgroundPrefs()).content)
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
