package app.winters.octo.testing

import java.io.File

// A plan rule: a song's copies are looked up by the song. Several indexes
// on source_track start with the source, and both planners take one of
// them for a statement that names a source and a song, walking every copy
// the source has for each song asked about. So wherever a statement
// compares a source_track row's mergedId with a song (a bound value, a list
// or subquery of them, or, from inside a subquery, a column of the rows
// around it), the plan's line for that row names index_source_track_mergedId
// (or the row's own key). The statement is read as one text, whatever lines
// its conditions sit on.
object SongCopyRule {
    private val KEYWORDS = setOf(
        "WHERE", "ON", "JOIN", "CROSS", "INNER", "LEFT", "OUTER", "NATURAL", "INDEXED", "NOT", "SET", "GROUP", "ORDER", "LIMIT",
        "USING", "AND", "OR", "WHEN", "THEN", "ELSE", "END", "VALUES", "SELECT", "UNION", "EXCEPT", "INTERSECT", "AS", "HAVING",
        "WINDOW", "DEFAULT",
    )

    private val COPY = Regex("""\bsource_track\b(?!\.)(?:\s+(?:AS\s+)?([A-Za-z_]\w*))?""", RegexOption.IGNORE_CASE)
    // A comparison that names songs: equal to one, or in a list of them. A
    // range of merged ids is a walk in their order, as a folder's pages are.
    private val COMPARED = Regex("""(?:\b([A-Za-z_]\w*)\.)?\bmergedId\b\s*(=|\bIN\b)\s*""", RegexOption.IGNORE_CASE)
    private val COLUMN = Regex("""^(?:[A-Za-z_]\w*\.)?[A-Za-z_]\w*\b(?!\s*\()""")
    private val SET_BEFORE = Regex("""\bSET\s*$""", RegexOption.IGNORE_CASE)
    private val LITERAL_START = Regex("""^('|\d|NULL\b|TRUE\b|FALSE\b|CASE\b)""", RegexOption.IGNORE_CASE)

    // The statement as one line, without backticks.
    fun flat(sql: String): String = sql.replace('`', ' ').replace(Regex("""\s+"""), " ").trim()

    // Whether the text at `at` lies inside a subquery (an open parenthesis
    // whose text starts with SELECT), quoted text skipped.
    private fun inSubquery(sql: String, at: Int): Boolean {
        val open = ArrayDeque<Boolean>()
        var i = 0
        while (i < at) {
            when (sql[i]) {
                '\'' -> {
                    i = sql.indexOf('\'', i + 1).let { if (it < 0) at else it }
                }
                '(' -> open.addLast(sql.substring(i + 1).trimStart().startsWith("SELECT", ignoreCase = true))
                ')' -> open.removeLastOrNull()
            }
            i++
        }
        return open.any { it }
    }

    // Each name the statement reads source_track under (the table's own for
    // none), with whether that read is inside a subquery.
    fun copies(sql: String): Map<String, Boolean> {
        val text = flat(sql)
        val found = LinkedHashMap<String, Boolean>()
        for (m in COPY.findAll(text)) {
            val word = m.groupValues[1]
            val name = if (word.isEmpty() || word.uppercase() in KEYWORDS) "source_track" else word
            found[name] = (found[name] ?: false) || inSubquery(text, m.range.first)
        }
        return found
    }

    // The names of source_track whose mergedId the statement compares with
    // a song.
    fun lookups(sql: String): Set<String> {
        val text = flat(sql)
        val copies = copies(text)
        val out = LinkedHashSet<String>()
        for (m in COMPARED.findAll(text)) {
            if (SET_BEFORE.containsMatchIn(text.substring(0, m.range.first))) continue
            val name = m.groupValues[1].ifEmpty { "source_track" }
            val inside = copies[name] ?: continue
            val rest = text.substring(m.range.last + 1)
            val op = m.groupValues[2].uppercase()
            val given = rest.startsWith("?") || rest.startsWith(":") || (op == "IN" && rest.startsWith("("))
            val column = !given && !LITERAL_START.containsMatchIn(rest) && COLUMN.containsMatchIn(rest)
            if (given || (column && inside)) out += name
        }
        return out
    }

    // The plan's lines that read source_track under `name`, on 3.21 and on
    // a current SQLite.
    private fun linesOf(name: String, lines: List<String>): List<String> {
        val pattern = if (name == "source_track") {
            Regex("""^(SEARCH|SCAN) (TABLE )?source_track( USING| \(|$)""")
        } else {
            Regex("""^(SEARCH|SCAN) (TABLE source_track AS ${Regex.escape(name)}|${Regex.escape(name)})( |$)""")
        }
        return lines.filter { pattern.containsMatchIn(it) }
    }

    // What breaks the rule in a statement's plan lines.
    fun problems(sql: String, lines: List<String>): List<String> =
        lookups(sql).flatMap { name ->
            linesOf(name, lines).filterNot { "index_source_track_mergedId" in it || "PRIMARY KEY" in it || "sqlite_autoindex_source_track_1" in it }
                .map { "$name: $it" }
        }

    // Every statement text in a Kotlin file that reads source_track and
    // names mergedId: each string literal, plain or raw, joined with those
    // `+` adds after it, a template kept as `${}` (or `$` and a name).
    fun statementsIn(file: File): List<String> {
        val text = file.readText()
        val found = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                text[i] == '\'' -> i = text.indexOf('\'', i + if (text[i + 1] == '\\') 3 else 2) + 1
                text[i] == '"' -> {
                    val pieces = StringBuilder()
                    i = literal(text, i, pieces)
                    while (true) {
                        var k = i
                        while (k < text.length && text[k].isWhitespace()) k++
                        if (k >= text.length || text[k] != '+') break
                        k++
                        while (k < text.length && text[k].isWhitespace()) k++
                        if (k >= text.length || text[k] != '"') break
                        i = literal(text, k, pieces)
                    }
                    val sql = pieces.toString()
                    if ("source_track" in sql && "mergedId" in sql) found += sql
                }
                else -> i++
            }
        }
        return found
    }

    // A literal from its opening quote into `out`, templates as `${}`;
    // answers where it ends.
    private fun literal(text: String, start: Int, out: StringBuilder): Int {
        val raw = text.startsWith("\"\"\"", start)
        var i = start + if (raw) 3 else 1
        while (true) {
            val c = text[i]
            when {
                raw && text.startsWith("\"\"\"", i) -> {
                    var end = i + 3
                    while (end < text.length && text[end] == '"') end++
                    out.append(text, i, end - 3)
                    return end
                }
                !raw && c == '"' -> return i + 1
                !raw && c == '\\' -> {
                    out.append(if (text[i + 1] == 'n' || text[i + 1] == 't') ' ' else text[i + 1])
                    i += if (text[i + 1] == 'u') 6 else 2
                }
                c == '$' && text[i + 1] == '{' -> {
                    var depth = 0
                    var k = i + 1
                    while (true) {
                        when (text[k]) {
                            '{' -> depth++
                            '}' -> if (--depth == 0) break
                        }
                        k++
                    }
                    out.append(if (text.substring(i, k).contains("marks(")) "?, ?, ?" else "\${}")
                    i = k + 1
                }
                c == '$' && (text[i + 1].isLetter() || text[i + 1] == '_') -> {
                    var k = i + 1
                    while (k < text.length && (text[k].isLetterOrDigit() || text[k] == '_')) k++
                    out.append("\${}")
                    i = k
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
    }
}
