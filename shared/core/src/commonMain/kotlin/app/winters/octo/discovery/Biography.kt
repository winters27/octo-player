package app.winters.octo.discovery

// An artist's biography as a server sends it (HTML from Last.fm, as a
// rule), made plain for both apps' artist pages.

private val MoreLink = Regex("""<a\b[^>]*>\s*read more\b[^<]*</a>\s*\.?\s*$""", RegexOption.IGNORE_CASE)
private val Breaks = Regex("""<br\s*/?>|</p\s*>""", RegexOption.IGNORE_CASE)
private val Tags = Regex("""<[^>]*>""")
private val Spaces = Regex("""[ \t ]+""")
private val Gaps = Regex("""\s*\n\s*(\n\s*)*""")
private val Numbered = Regex("""&#(x[0-9a-fA-F]+|[0-9]+);""")
private val Named = mapOf("&quot;" to "\"", "&apos;" to "'", "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ")

// A biography as plain text: the "Read more" link servers add at the end is
// dropped, then every tag, and the rest is written out as characters with
// tidy spacing. Nothing when no words are left.
fun cleanBiography(html: String): String? {
    var text = html.trim().replace(MoreLink, "")
    text = text.replace(Breaks, "\n").replace(Tags, "")
    text = Numbered.replace(text) { match ->
        val code = match.groupValues[1]
        val value = if (code.startsWith("x")) code.drop(1).toIntOrNull(16) else code.toIntOrNull()
        value?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: match.value
    }
    Named.forEach { (name, character) -> text = text.replace(name, character, ignoreCase = true) }
    text = text.replace("&amp;", "&", ignoreCase = true)
    // Paragraphs keep one empty line between them; other runs of space become one.
    text = text.replace(Spaces, " ")
    text = Gaps.replace(text) { if (it.value.count { c -> c == '\n' } > 1) "\n\n" else "\n" }
    return text.trim().takeIf { it.isNotEmpty() }
}
