package app.winters.octo.desktop.ui

import app.winters.octo.design.IconSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// How the desktop draws its icons, read from its source: every icon goes
// through the painter that puts it on whole pixels (a brand's mark and an
// icon at a worked-out size through the exact one), and every icon is drawn
// at one of the icon sizes or says it is drawn exactly.
class CrispIconSourceTest {
    private val roots = listOf(File("src"), File("../desktop-design/src"))
    private val mainRoots = listOf(File("src/main/kotlin"), File("../desktop-design/src/main/kotlin"))

    // The icon sizes, by name, as IconSize holds them.
    private val tokens = setOf("Mark", "Inline", "Table", "Toolbar", "Transport", "Prominent", "Feature", "Lead")

    @Test
    fun theTokensAreTheFittedSizes() {
        assertEquals(tokens.size, IconSize.Fitted.size)
    }

    @Test
    fun onlyThePainterFileMakesVectorPainters() {
        // Spelled in two parts so this file does not count itself.
        val needle = "rememberVectorPainter" + "("
        val found = roots.flatMap { root -> root.walk().filter { it.extension == "kt" }.toList() }
            .flatMap { file -> file.readLines().mapIndexedNotNull { i, line -> if (needle in line && !line.trimStart().startsWith("//")) "${file.path}:${i + 1}" else null } }
        val painterFile = found.filter { it.replace('\\', '/').contains("design/CrispIcons.kt:") }
        assertEquals("anywhere else:\n" + (found - painterFile.toSet()).joinToString("\n"), painterFile.size, found.size)
        // There, only crispPainter (for the vector as written) and exactPainter.
        assertEquals(2, painterFile.size)
    }

    @Test
    fun theDesktopDrawsPauseFromItsIcon() {
        val needle = Regex("""(?<!fun )\bPauseGlyph\(""")
        val found = mainRoots.flatMap { root -> root.walk().filter { it.extension == "kt" }.toList() }
            .filter { file -> file.readLines().any { needle.containsMatchIn(it) && !it.trimStart().startsWith("//") } }
        assertTrue("drawn by hand in: $found", found.isEmpty())
    }

    @Test
    fun everyIconIsATokenSizeOrDrawnExactly() {
        val sources = Sources(mainRoots)
        val glyph = sources.function("Glyph")
        val action = sources.function("IconAction")
        val wrong = ArrayList<String>()
        for ((fn, sizeParam) in listOf(glyph to "size", action to "iconSize")) {
            for (call in sources.calls(fn.name)) {
                val exact = call.arg(fn, "exact")
                if (exact == "true") continue
                val size = call.arg(fn, sizeParam) ?: fn.param(sizeParam).default
                val exactParam = exact?.takeIf { Regex("""\w+""").matches(it) }
                if (!sources.tokenSized(size, call.file, call.at, exactParam, 0)) wrong += "${call.where()}: $sizeParam = $size"
            }
        }
        assertTrue("Icon sizes that are neither an IconSize token nor exact = true:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    private inner class Sources(roots: List<File>) {
        val files = roots.flatMap { root -> root.walk().filter { it.extension == "kt" }.toList() }.map { SourceFile(it.path, blank(it.readText())) }

        fun function(name: String): Fn = files.firstNotNullOf { file -> file.functions().firstOrNull { it.name == name } }

        fun calls(name: String): List<Call> = files.flatMap { file ->
            Regex("""(?<![\w.])$name\(""").findAll(file.code).filter { !file.code.substring(0, it.range.first).trimEnd().endsWith("fun") }
                .map { Call(file, it.range.first, splitArgs(file.code.substring(it.range.last + 1, closing(file.code, it.range.last)))) }.toList()
        }

        // Whether `expr` in `file` at `at` is always an icon size token:
        // a token, either branch of an if of tokens, a value set to one, or
        // a parameter every caller gives one (or, when `exactParam` is the
        // enclosing function's flag, a caller that passes it true).
        fun tokenSized(expr: String?, file: SourceFile, at: Int, exactParam: String?, depth: Int): Boolean {
            if (expr == null || depth > 6) return false
            val e = expr.trim()
            Regex("""IconSize\.(\w+)""").matchEntire(e)?.let { return it.groupValues[1] in tokens }
            Regex("""if \((.+)\) (.+) else (.+)""").matchEntire(e)?.let {
                return tokenSized(it.groupValues[2], file, at, exactParam, depth + 1) && tokenSized(it.groupValues[3], file, at, exactParam, depth + 1)
            }
            if (!Regex("""\w+""").matches(e)) return false
            val fn = file.functions().lastOrNull { it.at < at }
            val param = fn?.params?.firstOrNull { it.name == e }
            if (fn != null && param != null) {
                val callers = calls(fn.name)
                if (callers.isEmpty()) return tokenSized(param.default, file, fn.at, null, depth + 1)
                return callers.all { call ->
                    (exactParam != null && call.arg(fn, exactParam) == "true") ||
                        tokenSized(call.arg(fn, e) ?: param.default, call.file, call.at, null, depth + 1)
                }
            }
            val value = Regex("""\bval\s+$e\b\s*(?::\s*[\w.]+\s*)?=\s*([^\n]+)""").findAll(file.code).lastOrNull { it.range.first < at }
                ?: Regex("""\bval\s+$e\b\s*(?::\s*[\w.]+\s*)?=\s*([^\n]+)""").find(file.code)
                ?: return false
            return tokenSized(value.groupValues[1], file, value.range.first, null, depth + 1)
        }
    }

    private class SourceFile(val path: String, val code: String) {
        fun functions(): List<Fn> = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""").findAll(code).map { m ->
            val open = m.range.last
            val params = splitArgs(code.substring(open + 1, closing(code, open))).map { raw ->
                val p = raw.replace(Regex("""@\w+(\([^)]*\))?\s*"""), "").replace(Regex("""^(vararg|noinline|crossinline)\s+"""), "")
                val parts = Regex("""(\w+)\s*:\s*([^=]+?)(?:=\s*(.+))?""").matchEntire(p.trim())
                Param(parts?.groupValues?.get(1) ?: p.trim(), parts?.groupValues?.get(3)?.trim()?.ifEmpty { null })
            }
            Fn(m.groupValues[1], m.range.first, params)
        }.toList()
    }

    private class Param(val name: String, val default: String?)

    private class Fn(val name: String, val at: Int, val params: List<Param>) {
        fun param(name: String) = params.first { it.name == name }
    }

    private class Call(val file: SourceFile, val at: Int, val args: List<String>) {
        // The argument given for `param` of `fn`, by name or by place.
        fun arg(fn: Fn, param: String): String? {
            val named = Regex("""(\w+)\s*=(?!=)\s*(.*)""", RegexOption.DOT_MATCHES_ALL)
            args.firstNotNullOfOrNull { a -> named.matchEntire(a)?.takeIf { it.groupValues[1] == param }?.groupValues?.get(2)?.trim() }?.let { return it }
            val placed = args.takeWhile { named.matchEntire(it) == null }
            val index = fn.params.indexOfFirst { it.name == param }
            return placed.getOrNull(index)
        }

        fun where() = "${file.path}:${file.code.substring(0, at).count { it == '\n' } + 1}"
    }

    companion object {
        // The source with comments and the insides of strings turned to
        // spaces, so brackets and commas in them do not count.
        fun blank(text: String): String {
            val out = StringBuilder(text)
            var i = 0
            // Inside a string, each open ${ adds one; braces count within it.
            val braces = ArrayList<Int>()
            var inString = false
            var raw = false
            while (i < text.length) {
                val c = text[i]
                if (inString) {
                    when {
                        !raw && c == '\\' -> {
                            out[i] = ' '
                            if (i + 1 < text.length) out[i + 1] = ' '
                            i += 2
                            continue
                        }
                        c == '$' && text.getOrNull(i + 1) == '{' -> {
                            braces += 0
                            inString = false
                            i += 2
                            continue
                        }
                        raw && text.startsWith("\"\"\"", i) -> {
                            inString = false
                            i += 3
                            continue
                        }
                        !raw && c == '"' -> {
                            inString = false
                            i++
                            continue
                        }
                        else -> {
                            if (c != '\n') out[i] = ' '
                            i++
                            continue
                        }
                    }
                }
                when {
                    text.startsWith("//", i) -> while (i < text.length && text[i] != '\n') out[i++] = ' '
                    text.startsWith("/*", i) -> {
                        val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                        for (k in i until end) if (text[k] != '\n') out[k] = ' '
                        i = end
                    }
                    text.startsWith("\"\"\"", i) -> {
                        inString = true
                        raw = true
                        i += 3
                    }
                    c == '"' -> {
                        inString = true
                        raw = false
                        i++
                    }
                    c == '\'' -> {
                        val end = text.indexOf('\'', i + if (text.getOrNull(i + 1) == '\\') 3 else 2).let { if (it < 0) text.length - 1 else it }
                        for (k in i + 1 until end) out[k] = ' '
                        i = end + 1
                    }
                    c == '{' && braces.isNotEmpty() -> {
                        braces[braces.size - 1]++
                        i++
                    }
                    c == '}' && braces.isNotEmpty() -> {
                        if (braces.last() == 0) {
                            braces.removeAt(braces.size - 1)
                            inString = true
                        } else {
                            braces[braces.size - 1]--
                        }
                        i++
                    }
                    else -> i++
                }
            }
            return out.toString()
        }

        // The index of the bracket that closes the one at `open`.
        fun closing(code: String, open: Int): Int {
            var depth = 0
            for (i in open until code.length) {
                when (code[i]) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> if (--depth == 0) return i
                }
            }
            return code.length
        }

        // A bracket's insides cut at its own commas, each trimmed.
        fun splitArgs(inner: String): List<String> {
            val out = ArrayList<String>()
            var depth = 0
            var start = 0
            for (i in inner.indices) {
                when (inner[i]) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    ',' -> if (depth == 0) {
                        out += inner.substring(start, i).trim()
                        start = i + 1
                    }
                }
            }
            inner.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out += it }
            return out
        }
    }
}
