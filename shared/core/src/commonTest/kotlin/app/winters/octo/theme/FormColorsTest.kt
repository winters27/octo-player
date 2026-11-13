package app.winters.octo.theme

import app.winters.octo.player.immersive.contrastRatio
import app.winters.octo.player.immersive.relativeLuminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The sign-in form's word colors against every theme, accent, cover seed
// and glass setting in src/commonTest/resources/themes/resolved.txt.
class FormColorsTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    // One resolved palette: its key ("<theme>/<accent>[/<art>]/glass-<on|off>")
    // and its roles.
    private class Row(val head: String, val roles: Map<String, String>) {
        val light: Boolean get() = roles.getValue("light") == "true"

        fun color(role: String): Int = roles.getValue(role).removePrefix("#").toLong(16).toInt()
    }

    private val rows: List<Row> = File("src/commonTest/resources/themes/resolved.txt").readLines()
        .filter { it.isNotBlank() }
        .map { line -> line.substringBefore('=') to line.substringAfter('=') }
        .groupBy({ it.first.substringBeforeLast('/') }, { it.first.substringAfterLast('/') to it.second })
        .map { (head, roles) -> Row(head, roles.toMap()) }

    // The palette a row was resolved from.
    private fun paletteOf(head: String): ResolvedPalette {
        val parts = head.split('/')
        val choice = AccentChoice.valueOf(parts[1])
        val art = if (choice == AccentChoice.FromArt) parts[2].takeIf { it != "none" }?.toLong(16)?.toInt() else null
        return resolvePalette(ThemeChoice.valueOf(parts[0]), choice, art, parts.last() == "glass-on")
    }

    // The scrim behind a sheet: black at 45%, over black or over white.
    private val scrims = listOf(overArgb(alphaArgb(black, 450), black), overArgb(alphaArgb(black, 450), white))

    private fun check(what: String, measured: Double, min: Double) =
        assertTrue("$what measured $measured, needs $min", measured >= min)

    @Test
    fun theFixtureHoldsEveryThemeAccentCoverAndGlass() {
        assertEquals(150, rows.size)
        assertEquals(5, rows.map { it.roles.getValue("theme") }.toSet().size)
    }

    @Test
    fun theRedsAreTheChosenOnes() {
        assertEquals(0xFFB42318.toInt(), ERROR_WORDS_LIGHT)
        assertEquals(0xFFF97C7C.toInt(), ERROR_WORDS_DARK)
    }

    // Red when an error red falls under 4.5:1 on any page, card, raised or
    // sheet surface of any theme, accent, cover or glass setting.
    @Test
    fun errorWordsReadOnEveryPageCardRaisedAndSheet() {
        for (row in rows) {
            val words = errorWordsFor(row.light)
            assertEquals(row.head, words, paletteOf(row.head).errorWords)
            val page = row.color("page")
            val sheet = row.color("sheet")
            val surfaces = mapOf(
                "page" to page,
                "card" to row.color("card"),
                "raised" to row.color("raised"),
                "sheet over page" to overArgb(sheet, page),
            ) + scrims.mapIndexed { i, scrim -> "sheet over scrim $i" to overArgb(sheet, scrim) }
            for ((name, surface) in surfaces) check("${row.head} error words on $name", contrastRatio(words, surface), 4.5)
        }
    }

    // The darkest a glow may leave a light theme's page, or the lightest it
    // may leave a dark one's, as luminance: where text or secondary words
    // would fall to 7:1, or the theme's accent, complement or third to 4.5:1,
    // whichever comes first.
    private fun glowBound(row: Row): Double {
        val needs = listOf("text" to 7.0, "secondary" to 7.0, "accents.accent" to 4.5, "complement" to 4.5, "third" to 4.5)
            .map { (role, ratio) -> relativeLuminance(row.color(role)) to ratio }
        return if (row.light) {
            needs.maxOf { (luminance, ratio) -> (luminance + 0.05) * ratio } - 0.05
        } else {
            needs.minOf { (luminance, ratio) -> (luminance + 0.05) / ratio } - 0.05
        }
    }

    // Red when an error red falls under 4.5:1 over the light glow floor or
    // the dark glow ceiling of any theme.
    @Test
    fun errorWordsReadOverTheLightGlowFloorAndTheDarkGlowCeiling() {
        val themes = rows.filter { it.head.endsWith("/Octo/glass-off") }
        assertEquals(5, themes.size)
        for (row in themes) {
            val bound = glowBound(row)
            val words = relativeLuminance(errorWordsFor(row.light))
            val ratio = if (row.light) (bound + 0.05) / (words + 0.05) else (words + 0.05) / (bound + 0.05)
            check("${row.head} error words over the glow ${if (row.light) "floor" else "ceiling"}", ratio, 4.5)
        }
    }

    // Red when the Optional tag's words fall under 4.5:1 on its fill, laid on
    // a raised row, for any theme, accent, cover or glass setting.
    @Test
    fun theOptionalTagReadsOverRaisedEverywhere() {
        for (row in rows) {
            val palette = paletteOf(row.head)
            assertEquals(row.head, row.color("accents.accent"), palette.tagWords)
            assertEquals(row.head, row.color("accents.tonal"), palette.tagFill)
            val fill = overArgb(row.color("accents.tonal"), row.color("raised"))
            check("${row.head} tag", contrastRatio(overArgb(row.color("accents.accent"), fill), fill), 4.5)
        }
    }

    // Red when an entry's words or placeholder fall under the themes table on
    // the page color, the entry well's fill.
    @Test
    fun entryWordsReadInTheWell() {
        for (row in rows) {
            val page = row.color("page")
            check("${row.head} entry words", contrastRatio(row.color("text"), page), 7.0)
            check("${row.head} placeholder", contrastOn(row.color("muted"), page), 4.5)
        }
    }
}
