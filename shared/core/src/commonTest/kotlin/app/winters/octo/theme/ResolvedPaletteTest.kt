package app.winters.octo.theme

import app.winters.octo.covers.toLch
import app.winters.octo.player.immersive.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedPaletteTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private val artSeeds: List<Int?> = listOf(
        0xFFE01010.toInt(), 0xFF1030C0.toInt(), 0xFFFFE000.toInt(), 0xFF0A4010.toInt(), 0xFF808080.toInt(), null,
    )

    // Every accent choice with each art seed that applies to it.
    private fun cases(): List<Triple<ThemeChoice, AccentChoice, Int?>> = buildList {
        for (theme in ThemeChoice.entries) {
            for (choice in AccentChoice.entries) {
                if (choice == AccentChoice.FromArt) artSeeds.forEach { add(Triple(theme, choice, it)) } else add(Triple(theme, choice, null))
            }
        }
    }

    // The scrim behind a sheet is black at 45%, over black or over white.
    private val scrims = listOf(overArgb(alphaArgb(black, 450), black), overArgb(alphaArgb(black, 450), white))

    private fun layers(p: ResolvedPalette): List<Int> =
        listOf(p.page, p.card, p.raised) + scrims.map { overArgb(p.sheet, it) }

    private fun describe(theme: ThemeChoice, choice: AccentChoice, art: Int?, glass: Boolean) =
        "$theme/$choice/${art?.let { "%08X".format(it) }}/glass-$glass"

    private fun check(what: String, measured: Double, min: Double) =
        assertTrue("$what measured $measured, needs $min", measured >= min)

    @Test
    fun everyRoleReadsEverywhere() {
        for ((theme, choice, art) in cases()) {
            for (glass in listOf(false, true)) {
                val p = resolvePalette(theme, choice, art, glass)
                val w = describe(theme, choice, art, glass)
                val all = layers(p)
                val three = listOf(p.page, p.card, p.raised)
                val a = p.accents
                for (layer in all) check("$w text on %08X".format(layer), contrastOn(p.text, layer), 7.0)
                val secondaryLayers = if (theme == ThemeChoice.Octo) listOf(p.page, p.card) else all
                for (layer in secondaryLayers) check("$w secondary on %08X".format(layer), contrastOn(p.secondary, layer), 7.0)
                for (layer in all) {
                    check("$w muted on %08X".format(layer), contrastOn(p.muted, layer), 4.5)
                    check("$w inkSecondary on %08X".format(layer), contrastOn(p.inkSecondary, layer), 4.5)
                }
                for (layer in three) {
                    check("$w accent on %08X".format(layer), contrastOn(a.accent, layer), 4.5)
                    check("$w complement on %08X".format(layer), contrastOn(p.complement, layer), 4.5)
                    check("$w third on %08X".format(layer), contrastOn(p.third, layer), 4.5)
                }
                check("$w onFill on fill", contrastOn(a.onFill, a.fill), 4.5)
                check("$w onAccent on accent", contrastOn(a.onAccent, a.accent), 4.5)
                check("$w text on selected", contrastOn(p.text, a.selected), 7.0)
                check("$w selectedWords on selected", contrastOn(a.selectedWords, a.selected), 7.0)
                check("$w text on menuPress", contrastOn(p.text, overArgb(a.menuPress, p.card)), 7.0)
                check("$w text on tonal", contrastOn(p.text, a.tonal), 7.0)
                if (theme.light) {
                    check("$w onFill on hover", contrastOn(a.onFill, a.hover), 4.5)
                } else {
                    check("$w page on hover", contrastOn(p.page, a.hover), 4.5)
                }
                check("$w focusRing on page", contrastOn(a.focusRing, p.page), 3.0)
                check("$w focusRing on card", contrastOn(a.focusRing, p.card), 3.0)
                val track = overArgb(p.offTrack, p.card)
                check("$w offTrack on card", contrastRatio(track, p.card), 3.0)
                check("$w white on offTrack", contrastRatio(white, track), 3.0)
            }
        }
    }

    @Test
    fun layersStepApart() {
        for (theme in ThemeChoice.entries) {
            val p = resolvePalette(theme, AccentChoice.Octo, null, false)
            val pageToCard = toLch(p.card).l - toLch(p.page).l
            val cardToRaised = toLch(p.raised).l - toLch(p.card).l
            val minPageToCard = if (theme.light) 0.050 else 0.035
            val minCardToRaised = if (theme.light) 0.025 else 0.035
            // Light themes darken toward the page, so the step is read as a distance.
            check("$theme page to card", kotlin.math.abs(pageToCard), minPageToCard)
            check("$theme card to raised", kotlin.math.abs(cardToRaised), minCardToRaised)
            if (theme.light) check("$theme edge on card", contrastRatio(p.edge, p.card), 1.3)
        }
    }

    @Test
    fun selectedRowsStandOut() {
        for ((theme, choice, art) in cases()) {
            for (glass in listOf(false, true)) {
                val p = resolvePalette(theme, choice, art, glass)
                val w = describe(theme, choice, art, glass)
                check("$w selected vs page", contrastRatio(p.accents.selected, p.page), 1.12)
                check("$w selected vs card", contrastRatio(p.accents.selected, p.card), 1.12)
            }
        }
    }

    @Test
    fun octoKeepsTodaysColors() {
        val p = resolvePalette(ThemeChoice.Octo, AccentChoice.Octo, null, false)
        assertEquals(0xFF0C0C0D.toInt(), p.page)
        assertEquals(0xFF1A1A1B.toInt(), p.card)
        assertEquals(0xFF222324.toInt(), p.raised)
        assertEquals(alphaArgb(mixArgb(p.page, 0xFF97B1B9.toInt(), 50), 970), p.sheet)
        assertEquals(247, p.sheet ushr 24)
        assertEquals(0xFF3C474A.toInt(), p.accents.selected)
        assertEquals(0xFF2A2D2E.toInt(), p.accents.tonal)
        assertEquals(0xFFADC4CC.toInt(), p.accents.hover)
        assertEquals(mixArgb(0xFF97B1B9.toInt(), black, 450), p.accents.fill)
        assertEquals(listOf(217, 140, 64, 26, 13), listOf(p.inkPrimary, p.inkSecondary, p.inkTertiary, p.inkQuaternary, p.inkQuinary).map { it ushr 24 })
        listOf(p.inkPrimary, p.inkSecondary, p.inkTertiary, p.inkQuaternary, p.inkQuinary).forEach { assertEquals(0xFFFFFF, it and 0xFFFFFF) }
        assertEquals(0xFF98B2BA.toInt(), p.secondary)
        assertEquals(87, p.offTrack ushr 24)
        assertEquals(217, p.accents.selectedWords ushr 24)
    }

    @Test
    fun overArtIsDarkInEveryTheme() {
        val octo = ThemeChoice.Octo.base
        for ((theme, choice, art) in cases()) {
            for (glass in listOf(false, true)) {
                val p = resolvePalette(theme, choice, art, glass)
                val w = describe(theme, choice, art, glass)
                check("$w overArt accent on Octo page", contrastRatio(p.overArt.accent, octo.page), 4.5)
                check("$w overArt accent on Octo card", contrastRatio(p.overArt.accent, octo.card), 4.5)
            }
        }
    }

    private val glowSeeds = artSeeds.filterNotNull() + listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF00A000.toInt(), 0xFF0000FF.toInt())

    // Every word role, and the accent drawn now, over `glow` at `alpha`.
    private fun checkGlow(w: String, p: ResolvedPalette, glow: Int, alpha: Int, accent: Int) {
        val under = overArgb(alphaArgb(glow, alpha), p.page)
        check("$w text", contrastRatio(p.text, under), 7.0)
        check("$w secondary", contrastRatio(p.secondary, under), 7.0)
        check("$w muted", contrastOn(p.muted, under), 4.5)
        check("$w theme accent", contrastRatio(p.accents.accent, under), 4.5)
        check("$w complement", contrastRatio(p.complement, under), 4.5)
        check("$w third", contrastRatio(p.third, under), 4.5)
        check("$w accent drawn", contrastRatio(accent, under), 4.5)
    }

    @Test
    fun glowKeepsWordsReadable() {
        for (theme in ThemeChoice.entries) {
            val p = resolvePalette(theme, AccentChoice.Octo, null, false)
            for (choice in AccentChoice.entries) {
                for (seed in glowSeeds) {
                    val accent = themedAccent(theme, choice, seed)
                    for (alpha in listOf(140, 240)) {
                        val glow = glowForCover(theme, seed, alpha, accent)
                        checkGlow("$theme/${choice.label}/%08X/glow %08X/alpha $alpha".format(seed, glow), p, glow, alpha, accent)
                    }
                }
            }
        }
    }

    // A glow mesh, the theme's own colors at rest or a cover's, reads where
    // any two of its colors blend half and half too, for every accent.
    @Test
    fun aGlowMeshReadsAtEveryBlendOfTwoOfItsColors() {
        val covers = listOf(
            listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFE01010.toInt(), 0xFF00A000.toInt()),
            listOf(0xFFFFE000.toInt(), 0xFF0000FF.toInt(), 0xFF3A8FB7.toInt(), 0xFFFFFFFF.toInt()),
        )
        for (theme in ThemeChoice.entries) {
            val p = resolvePalette(theme, AccentChoice.Octo, null, false)
            val meshes = covers + if (p.glow.size >= 3) listOf(listOf(p.glow[0], p.glow[1], p.glow[2], p.glow[0])) else emptyList()
            for (choice in AccentChoice.entries) {
                for ((m, colors) in meshes.withIndex()) {
                    val accent = themedAccent(theme, choice, colors[0])
                    for (alpha in listOf(140, 240)) {
                        val mesh = glowMesh(theme, colors, alpha, accent)
                        assertEquals(colors.size, mesh.size)
                        for (i in mesh.indices) for (j in mesh.indices) {
                            val blend = mixArgb(mesh[i], mesh[j], 500)
                            checkGlow("$theme/${choice.label}/mesh $m/$i-$j %08X/alpha $alpha".format(blend), p, blend, alpha, accent)
                        }
                    }
                }
            }
        }
    }

    // A mesh that already reads at every blend is left as glowForCover
    // makes each of its colors.
    @Test
    fun aMeshThatReadsIsLeftAsItIs() {
        val dark = listOf(0xFF102030.toInt(), 0xFF201030.toInt(), 0xFF103020.toInt())
        assertEquals(dark.map { glowForCover(ThemeChoice.Octo, it, 140) }, glowMesh(ThemeChoice.Octo, dark, 140))
    }

    @Test
    fun keyColorReadsOnLight() {
        for (theme in listOf(ThemeChoice.Daylight, ThemeChoice.Paper)) {
            val p = resolvePalette(theme, AccentChoice.Octo, null, false)
            val key = keyForTheme(theme, 0xFFFFDD33.toInt())
            for (layer in listOf(p.page, p.card, p.raised, p.sheet)) {
                check("$theme key %08X on %08X".format(key, layer), contrastRatio(key, layer), 3.0)
            }
        }
    }
}
