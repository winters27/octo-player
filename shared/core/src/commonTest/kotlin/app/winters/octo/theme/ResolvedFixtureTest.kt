package app.winters.octo.theme

import java.io.File
import java.util.SortedMap
import java.util.TreeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ResolvedFixtureTest {
    private val artSeeds: List<Int?> = listOf(
        0xFFE01010.toInt(), 0xFF1030C0.toInt(), 0xFFFFE000.toInt(), 0xFF0A4010.toInt(), 0xFF808080.toInt(), null,
    )

    private fun hex(argb: Int) = "#" + "%08X".format(argb)

    private fun familyEntries(prefix: String, f: AccentFamily): Map<String, String> = mapOf(
        "$prefix.accent" to hex(f.accent),
        "$prefix.hover" to hex(f.hover),
        "$prefix.tonal" to hex(f.tonal),
        "$prefix.selected" to hex(f.selected),
        "$prefix.selectedWords" to hex(f.selectedWords),
        "$prefix.menuPress" to hex(f.menuPress),
        "$prefix.fill" to hex(f.fill),
        "$prefix.onFill" to hex(f.onFill),
        "$prefix.onAccent" to hex(f.onAccent),
        "$prefix.focusRing" to hex(f.focusRing),
        "$prefix.glassAccent" to hex(f.glassAccent),
    )

    // Every resolved role for every theme, accent, cover seed and glass setting,
    // keyed "<theme>/<accent>[/<art>]/glass-<on|off>/<role>", sorted by key.
    private fun fixtureOf(): SortedMap<String, String> {
        val out = TreeMap<String, String>()
        for (theme in ThemeChoice.entries) {
            for (choice in AccentChoice.entries) {
                val seeds: List<Int?> = if (choice == AccentChoice.FromArt) artSeeds else listOf(null)
                for (art in seeds) {
                    for (glass in listOf(false, true)) {
                        val p = resolvePalette(theme, choice, art, glass)
                        val artPart = if (choice == AccentChoice.FromArt) "/" + (art?.let { "%08X".format(it) } ?: "none") else ""
                        val head = "${theme.name}/${choice.name}$artPart/glass-${if (glass) "on" else "off"}"
                        val roles = linkedMapOf(
                            "theme" to p.theme.name,
                            "light" to p.light.toString(),
                            "page" to hex(p.page),
                            "card" to hex(p.card),
                            "raised" to hex(p.raised),
                            "sheet" to hex(p.sheet),
                            "edge" to hex(p.edge),
                            "backgroundSecondary" to hex(p.backgroundSecondary),
                            "text" to hex(p.text),
                            "secondary" to hex(p.secondary),
                            "muted" to hex(p.muted),
                            "inkPrimary" to hex(p.inkPrimary),
                            "inkSecondary" to hex(p.inkSecondary),
                            "inkTertiary" to hex(p.inkTertiary),
                            "inkQuaternary" to hex(p.inkQuaternary),
                            "inkQuinary" to hex(p.inkQuinary),
                            "ink" to hex(p.ink),
                            "offTrack" to hex(p.offTrack),
                            "complement" to hex(p.complement),
                            "third" to hex(p.third),
                            "shadow" to p.shadow.toString(),
                        )
                        p.glow.forEachIndexed { i, c -> roles["glow.$i"] = hex(c) }
                        roles.putAll(familyEntries("accents", p.accents))
                        roles.putAll(familyEntries("overArt", p.overArt))
                        for ((role, value) in roles) out["$head/$role"] = value
                    }
                }
            }
        }
        return out
    }

    private fun render(map: Map<String, String>) = map.entries.joinToString("") { "${it.key}=${it.value}\n" }

    @Test
    fun resolvedColorsMatchTheFixture() {
        val file = File("src/commonTest/resources/themes/resolved.txt")
        val actual = render(fixtureOf())
        if (System.getProperty("octo.writeThemeFixture") != null) {
            file.parentFile.mkdirs()
            file.writeText(actual)
            fail("fixture written to ${file.absolutePath}; run the test again without octo.writeThemeFixture")
        }
        // A Windows checkout may hold the file with CRLF line ends.
        assertEquals(file.readText().replace("\r\n", "\n"), actual)
    }
}
