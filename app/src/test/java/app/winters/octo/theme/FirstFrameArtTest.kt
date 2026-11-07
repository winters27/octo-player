package app.winters.octo.theme

import app.winters.octo.ui.settings.AccentSeed
import org.junit.Assert.assertEquals
import org.junit.Test

// With From the album art, the first frame draws the accent of the cover
// seen last time until this run's cover is worked out, and never Octo's
// own accent in between.
class FirstFrameArtTest {
    private val seen = 0xFF3A8FB7.toInt()
    private val now = 0xFFE01010.toInt()

    @Test
    fun theCoverSeenLastTimeStandsInUntilThisRunsIsKnown() {
        // Nothing read yet.
        assertEquals(seen, firstFrameArt(null, AccentSeed(null, seedKnown = false), seen))
        // The accent is read, the cover not yet worked out.
        assertEquals(seen, firstFrameArt(AccentChoice.FromArt, AccentSeed(null, seedKnown = false), seen))
        // This run's cover is known.
        assertEquals(now, firstFrameArt(AccentChoice.FromArt, AccentSeed(now, seedKnown = true), seen))
        // Known to have no cover: none.
        assertEquals(null, firstFrameArt(AccentChoice.FromArt, AccentSeed(null, seedKnown = true), seen))
    }

    @Test
    fun theFirstFrameDrawsTheSeenCoversAccentNotOctos() {
        for (theme in ThemeChoice.entries) {
            val art = firstFrameArt(AccentChoice.FromArt, AccentSeed(null, seedKnown = false), seen)
            val first = resolvePalette(theme, AccentChoice.FromArt, art, false).accents.accent
            val last = resolvePalette(theme, AccentChoice.FromArt, seen, false).accents.accent
            assertEquals("${theme.label}: the first frame's accent", last, first)
        }
    }
}
