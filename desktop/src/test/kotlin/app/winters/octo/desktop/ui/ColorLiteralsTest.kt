package app.winters.octo.desktop.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Every fixed color written into the apps' code, read from their source with
// comments and strings blanked out: each file's colors must be exactly the
// ones its module's list names, each with why it stays fixed in every theme.
// A new fixed color on a page that follows the theme fails here, naming the
// file and line.
class ColorLiteralsTest {
    private val root = File("..")

    // Each module's code and the list of the fixed colors it keeps.
    private val modules = listOf(
        "app" to "app/color-literals.txt",
        "desktop" to "desktop/color-literals.txt",
        "design" to "design/color-literals.txt",
        "desktop-design" to "desktop-design/color-literals.txt",
    )

    private val reasons = setOf("over-art", "signal", "scanner", "shadow", "self-contained pill", "mask", "scrim", "token")

    // A fixed color: an ARGB value, Compose's black or white, or one made
    // from channels.
    private val literal = Regex("""(?<![A-Za-z0-9_])(Color\(0x|Color\.White|Color\.Black|Color\(\s*red\s*=)""")

    private data class Found(val path: String, val line: Int, val text: String)

    @Test
    fun blankingKeepsCodeAndDropsCommentsAndStrings() {
        val source = "val a = Color.White // Color.Black\n/* Color(0xFF000000) /* nested */ Color.Black */ val b = \"Color.White\"\nval c = \"\"\"\nColor.Black\n\"\"\" + Color(0xFF112233)"
        val found = literals("x.kt", source)
        assertTrue(found.toString(), found == listOf(Found("x.kt", 1, "Color.White"), Found("x.kt", 5, "Color(0xFF112233)")))
    }

    @Test
    fun everyFixedColorIsOnItsModulesList() {
        val problems = mutableListOf<String>()
        for ((module, listPath) in modules) {
            val listFile = File(root, listPath)
            val allowed = mutableMapOf<String, MutableList<Pair<String, Int>>>()
            if (listFile.exists()) {
                listFile.readLines().forEachIndexed { i, raw ->
                    if (raw.isBlank()) return@forEachIndexed
                    val parts = raw.split(" | ")
                    if (parts.size != 3) {
                        problems += "$listPath:${i + 1}: not `path | literal | reason`: $raw"
                        return@forEachIndexed
                    }
                    val (path, text, reason) = parts
                    if (reason !in reasons) problems += "$listPath:${i + 1}: reason '$reason' is not one of $reasons"
                    if (!path.startsWith("$module/src/main/")) problems += "$listPath:${i + 1}: $path is not in $module/src/main"
                    allowed.getOrPut(path) { mutableListOf() } += text to i + 1
                }
            } else {
                problems += "$listPath is missing"
            }
            val found = File(root, "$module/src/main").walk()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .filter { file -> file.invariantSeparatorsPath.split('/').none { it == "build" || it == "generated" } }
                .flatMap { file -> literals(file.relativeTo(root).invariantSeparatorsPath, file.readText()) }
                .groupBy { it.path }
            for (path in (found.keys + allowed.keys).sorted()) {
                val inCode = found[path].orEmpty().toMutableList()
                val listed = allowed[path].orEmpty().toMutableList()
                val unmatched = mutableListOf<Found>()
                for (f in inCode) {
                    val at = listed.indexOfFirst { it.first == f.text }
                    if (at >= 0) listed.removeAt(at) else unmatched += f
                }
                unmatched.forEach { problems += "${it.path}:${it.line}: ${it.text} is not on $listPath" }
                listed.forEach { problems += "$listPath:${it.second}: $path has no ${it.first}" }
            }
        }
        assertTrue("fixed colors and their lists disagree:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    // The fixed colors in one file, with the lines they start on.
    private fun literals(path: String, source: String): List<Found> {
        val code = blank(source)
        return literal.findAll(code).map { match ->
            val start = match.range.first
            val end = if (match.value.startsWith("Color(")) closing(code, start + "Color".length) else match.range.last + 1
            val text = code.substring(start, end).split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
            Found(path, code.substring(0, start).count { it == '\n' } + 1, text)
        }.toList()
    }

    // Just past the parenthesis that closes the one at `open`.
    private fun closing(code: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i + 1
            }
            i++
        }
        return code.length
    }

    // The source with comments, strings and characters turned to spaces, and
    // line breaks kept so lines still count true.
    private fun blank(s: String): String {
        val out = StringBuilder(s.length)
        fun keep(c: Char) = out.append(if (c == '\n') '\n' else ' ')
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') keep(s[i++])
                s.startsWith("/*", i) -> {
                    var depth = 0
                    while (i < s.length) {
                        if (s.startsWith("/*", i)) {
                            depth++
                            keep(s[i++]); keep(s[i++])
                        } else if (s.startsWith("*/", i)) {
                            depth--
                            keep(s[i++]); keep(s[i++])
                            if (depth == 0) break
                        } else {
                            keep(s[i++])
                        }
                    }
                }
                s.startsWith("\"\"\"", i) -> {
                    repeat(3) { keep(s[i++]) }
                    while (i < s.length && !s.startsWith("\"\"\"", i)) keep(s[i++])
                    while (i < s.length && s[i] == '"') keep(s[i++])
                }
                s[i] == '"' || s[i] == '\'' -> {
                    val quote = s[i]
                    keep(s[i++])
                    while (i < s.length && s[i] != quote && s[i] != '\n') {
                        if (s[i] == '\\' && i + 1 < s.length) keep(s[i++])
                        keep(s[i++])
                    }
                    if (i < s.length && s[i] == quote) keep(s[i++])
                }
                else -> out.append(s[i++])
            }
        }
        return out.toString()
    }
}
