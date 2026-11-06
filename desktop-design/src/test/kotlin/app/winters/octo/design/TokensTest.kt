package app.winters.octo.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.FixedAccents
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.base
import app.winters.octo.theme.contrastOn
import app.winters.octo.theme.resolvePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Colours keep each channel to 1/255.
private const val Byte = 1f / 255f + 1e-4f

class TokensTest {
    // Every token duration, read from the object so a new one is covered too.
    private val durations: List<Int> = OctoDuration::class.java.declaredFields
        .filter { it.type == Int::class.javaPrimitiveType && java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.name.startsWith("$") }
        .map { it.getInt(null) }

    @Test
    fun theDurationsAreTheSpecsSet() {
        assertTrue(durations.containsAll(listOf(80, 140, 160, 180, 200, 300)))
    }

    @Test
    fun reducedMotionScalesEveryDuration() {
        assertTrue(durations.size >= 6)
        for (base in durations) {
            assertEquals(base, MotionScale.Full.ms(base))
            assertEquals(Math.round(base * 0.5f), MotionScale.Reduced.ms(base))
            assertTrue("$base should be shorter when reduced", MotionScale.Reduced.ms(base) < base)
        }
    }

    @Test
    fun aDurationMultiplierScalesEveryDurationByItself() {
        val slow = MotionScale(duration = 2f)
        for (base in durations) assertEquals(base * 2, slow.ms(base))
    }

    @Test
    fun reducedMotionStopsEveryTravelAndGrowth() {
        val reduced = MotionScale.Reduced
        assertTrue(reduced.still)
        assertEquals(0.dp, reduced.travel(OctoPress.LiftMedium))
        assertEquals(1f, reduced.scale(OctoPress.Pop), 0f)
        assertEquals(1f, reduced.scale(OctoPress.Scale), 0f)
        assertEquals(OctoPress.Pop, MotionScale.Full.scale(OctoPress.Pop), 1e-6f)
        assertEquals(1.dp, MotionScale.Full.travel(OctoPress.LiftSmall))
    }

    @Test
    fun theSystemAnimatorScaleOnlyCountsWhenOff() {
        // Compose already stretches animations by a non-zero animator scale.
        assertEquals(MotionScale.Full, MotionScale.of(systemAnimatorScale = 1f, reduce = false))
        assertEquals(MotionScale.Full, MotionScale.of(systemAnimatorScale = 1.5f, reduce = false))
        assertEquals(MotionScale.Reduced, MotionScale.of(systemAnimatorScale = 0f, reduce = false))
        assertEquals(MotionScale.Reduced, MotionScale.of(systemAnimatorScale = 1f, reduce = true))
    }

    @Test
    fun theCurvesStartAtZeroAndEndAtOne() {
        val curves = listOf(
            OctoEasing.Spring, OctoEasing.Overshoot, OctoEasing.Bounce,
            OctoEasing.Smooth, OctoEasing.Snappy, OctoEasing.EaseIn, OctoEasing.Sweep,
        )
        for (curve in curves) {
            assertEquals(0f, curve.transform(0f), 1e-3f)
            assertEquals(1f, curve.transform(1f), 1e-3f)
        }
        // The overshoot goes past the end before it settles.
        assertTrue((1..99).any { OctoEasing.Overshoot.transform(it / 100f) > 1f })
    }

    @Test
    fun wordsAreBlackOnALightFillAndWhiteOnADarkOne() {
        assertEquals(Color.Black, contentColorFor(Color.White))
        assertEquals(Color.White, contentColorFor(Color.Black))
        // Octo's accent is light enough for black words; the accent button's
        // darker fill and the tonal fill take white.
        val octo = OctoDefaultPalette.accents
        assertEquals(Color.Black, contentColorFor(Color(octo.accent)))
        assertEquals(Color.White, contentColorFor(Color(octo.fill)))
        assertEquals(Color.White, contentColorFor(Color(octo.tonal)))
    }

    // A sample cover color for the accent taken from the album art.
    private val sampleArt = 0xFF3A8FB7.toInt()

    @Test
    fun secondaryWordsAreTheThemesForEveryAccent() {
        for (theme in ThemeChoice.entries) {
            for (choice in AccentChoice.entries) {
                for (tint in listOf(false, true)) {
                    val p = resolvePalette(theme, choice, sampleArt, tint)
                    assertEquals("${theme.label} ${choice.label}", Color(theme.base.secondary), surfacesOf(p).secondary)
                }
            }
        }
    }

    @Test
    fun everyFixedAccentKeepsOctosWordsOnItsFamily() {
        for (choice in FixedAccents) {
            val family = resolvePalette(ThemeChoice.Octo, choice, null, false).accents
            assertEquals(choice.label, Color(choice.argb!!), Color(family.accent))
            assertEquals(choice.label, Color.Black, contentColorFor(Color(family.accent)))
            assertEquals(choice.label, Color.White, contentColorFor(Color(family.fill)))
            assertEquals(choice.label, Color.White, contentColorFor(Color(family.tonal)))
            assertEquals(choice.label, Color.White, contentColorFor(Color(family.selected)))
        }
        assertEquals(Color(0xFFADC4CC), Color(OctoDefaultPalette.accents.hover))
    }

    @Test
    fun theSwitchFromWhiteToBlackWordsIsAtAbout58Percent() {
        // Greys either side of the threshold.
        val below = (0..255).map { Color(it, it, it) }.last { perceivedLightness(it) <= LightFillThreshold }
        val above = (0..255).map { Color(it, it, it) }.first { perceivedLightness(it) > LightFillThreshold }
        assertEquals(Color.White, contentColorFor(below))
        assertEquals(Color.Black, contentColorFor(above))
        assertEquals(0.58f, perceivedLightness(above), 0.01f)
        // Mid grey is about 53.6% light to the eye.
        assertEquals(0.536f, perceivedLightness(Color(128, 128, 128)), 0.005f)
    }

    @Test
    fun theAccentFamilyIsDerivedFromTheAccent() {
        val family = OctoDefaultPalette.accents
        val accent = Color(family.accent)
        val selected = Color(family.selected)
        // Selected row: 40% of the accent out of black.
        assertEquals(accent.red * 0.4f, selected.red, Byte)
        assertEquals(accent.green * 0.4f, selected.green, Byte)
        assertEquals(accent.blue * 0.4f, selected.blue, Byte)
        // Tonal: a tenth of the accent in rgb(30 30 30).
        val grey = 30f / 255f
        val tonal = Color(family.tonal)
        assertEquals(grey + (accent.red - grey) * 0.1f, tonal.red, Byte)
        assertEquals(grey + (accent.blue - grey) * 0.1f, tonal.blue, Byte)
        // The ring is the accent at 70%, the menu press the selected row at 55%.
        val ring = Color(family.focusRing)
        assertEquals(accent.red, ring.red, Byte)
        assertEquals(0.7f, ring.alpha, 0.003f)
        val press = Color(family.menuPress)
        assertEquals(selected.red, press.red, Byte)
        assertEquals(0.55f, press.alpha, 0.003f)
        // The destructive words are the signal red, softened.
        assertEquals(OctoColors.SignalRed.copy(alpha = 0.85f), OctoColors.Destructive)
    }

    @Test
    fun octosMutedWordsAreItsSecondaryAtEightyPercent() {
        val octo = surfacesOf(OctoDefaultPalette)
        assertEquals(octo.secondary.red, octo.muted.red, Byte)
        assertEquals(octo.secondary.green, octo.muted.green, Byte)
        assertEquals(octo.secondary.blue, octo.muted.blue, Byte)
        assertEquals(0.8f, octo.muted.alpha, 0.003f)
    }

    @Test
    fun theTextHierarchyIsOctosWhiteAtFallingOpacitiesAndEveryThemesReads() {
        val octo = ThemeChoice.Octo
        val p = resolvePalette(octo, AccentChoice.Octo, null, false)
        // Colours keep their opacity to 1/255.
        val alphas = listOf(p.inkPrimary, p.inkSecondary, p.inkTertiary, p.inkQuaternary, p.inkQuinary).map { it ushr 24 }
        assertEquals(listOf(217, 140, 64, 26, 13), alphas)
        assertTrue(listOf(p.inkPrimary, p.inkSecondary, p.inkTertiary, p.inkQuaternary, p.inkQuinary).all { it and 0xFFFFFF == 0xFFFFFF })
        for (theme in ThemeChoice.entries) {
            val t = resolvePalette(theme, AccentChoice.Octo, null, false)
            for (layer in listOf(t.page, t.card)) {
                assertTrue("${theme.label} primary on ${layer.toString(16)}", contrastOn(t.inkPrimary, layer) >= 7.0)
                assertTrue("${theme.label} secondary on ${layer.toString(16)}", contrastOn(t.inkSecondary, layer) >= 4.5)
            }
        }
    }

    @Test
    fun theRadiiAreTheSpecs() {
        assertEquals(listOf(10.dp, 6.dp, 12.dp), listOf(OctoRadius.Chrome, OctoRadius.ChromeSmall, OctoRadius.Row))
        assertEquals(listOf(3.dp, 6.dp, 8.dp, 12.dp), listOf(OctoRadius.ArtXs, OctoRadius.ArtS, OctoRadius.ArtM, OctoRadius.ArtL))
        assertEquals(12.dp, OctoRadius.Dialog)
    }

    @Test
    fun anIconGesturePeaksAt45PercentAndRestsAtEitherEnd() {
        assertEquals(0f, accentStrength(0f), 0f)
        assertEquals(0f, accentStrength(1f), 0f)
        assertEquals(1f, accentStrength(AccentPeak), 1e-3f)
        assertTrue(accentStrength(0.2f) < 1f && accentStrength(0.8f) < 1f)
        assertEquals(1.2f, accentPose(IconAccent.Pulse, AccentPeak).scale, 1e-3f)
        assertEquals(4f, accentPose(IconAccent.SlideForward, AccentPeak).dx, 1e-3f)
        assertEquals(-4f, accentPose(IconAccent.SlideBack, AccentPeak).dx, 1e-3f)
        // Reduced motion keeps every icon still.
        for (accent in IconAccent.entries) assertEquals(AccentPose.Rest, accentPose(accent, AccentPeak, distance = 0f))
    }

    @Test
    fun theTabsThePillPassesFlashAndTheOneItLandsOnDoesNot() {
        // Moving from tab 0 to tab 3, halfway across tab 1.
        assertEquals(GearShiftTint, gearShiftTint(1, pill = 1f, selected = 3), 1e-6f)
        assertEquals(GearShiftTint / 2f, gearShiftTint(2, pill = 1.5f, selected = 3), 1e-6f)
        assertEquals(0f, gearShiftTint(3, pill = 3f, selected = 3), 0f)
        assertEquals(0f, gearShiftTint(0, pill = 2f, selected = 3), 0f)
    }

    @Test
    fun aSliderSnapsToItsSteps() {
        assertEquals(0.25f, snapToSteps(0.3f, 4), 1e-6f)
        assertEquals(0.5f, snapToSteps(0.4f, 4), 1e-6f)
        assertEquals(0.3f, snapToSteps(0.3f, 0), 1e-6f)
        assertEquals(1f, snapToSteps(1.4f, 0), 1e-6f)
        assertFalse(snapToSteps(-1f, 3) < 0f)
    }
}
