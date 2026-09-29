package app.winters.octo.covers

// A text engine for tests: every character a fixed share of the size wide
// (wide characters a whole size), words wrapped greedily at spaces and
// between wide characters, lines `lineHeight` apart.
class FakeTypesetter : CoverTypesetter {
    private fun charWidth(cp: Int, size: Float) =
        if (Character.UnicodeScript.of(cp) in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA) || isEmoji(cp)) size else size * 0.55f

    private fun width(text: String, size: Float): Float {
        var w = 0f
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            w += charWidth(cp, size)
            i += Character.charCount(cp)
        }
        return w
    }

    override fun widthOf(text: String, type: CoverType): Float = width(text, type.sizePx)

    override fun measure(text: String, type: CoverType, width: Float): Measured {
        val space = width(" ", type.sizePx)
        val lines = mutableListOf<Float>()
        var line = 0f
        for (run in unbreakableRuns(text)) {
            val w = width(run, type.sizePx)
            if (line > 0f && line + space + w > width) {
                lines += line
                line = w
            } else {
                line = if (line == 0f) w else line + space + w
            }
        }
        lines += line
        val shown = minOf(lines.size, type.maxLines)
        val widest = lines.take(shown).maxOrNull()?.coerceAtMost(width) ?: 0f
        return Measured(shown, widest, shown * type.sizePx * type.lineHeight, lines.size > type.maxLines || lines.any { it > width })
    }
}
