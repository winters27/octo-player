package app.winters.octo.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun plain(text: String) = listOf(Span(text))

    @Test
    fun headingsOneToThree() {
        val blocks = parseMarkdown("# One\n## Two\n### Three\n#### Four")
        assertEquals(
            listOf(
                Block.Heading(1, plain("One")),
                Block.Heading(2, plain("Two")),
                Block.Heading(3, plain("Three")),
                // Deeper headings read as the smallest.
                Block.Heading(3, plain("Four")),
            ),
            blocks,
        )
    }

    @Test
    fun aHashWithoutASpaceIsNotAHeading() {
        assertEquals(listOf(Block.Paragraph(plain("#1 hits"))), parseMarkdown("#1 hits"))
        assertEquals(listOf(Block.Heading(2, plain("C#"))), parseMarkdown("## C#"))
    }

    @Test
    fun linesTogetherMakeOneParagraphAndABlankLineStartsAnother() {
        val blocks = parseMarkdown("First line\nsame paragraph\n\nSecond")
        assertEquals(listOf(Block.Paragraph(plain("First line same paragraph")), Block.Paragraph(plain("Second"))), blocks)
    }

    @Test
    fun bulletsWithEveryMark() {
        val blocks = parseMarkdown("- one\n* two\n+ three")
        assertEquals(listOf(Block.Bullets(listOf(Bullet(plain("one")), Bullet(plain("two")), Bullet(plain("three"))))), blocks)
    }

    @Test
    fun anIndentedBulletSitsUnderTheOneAbove() {
        val blocks = parseMarkdown("- parent\n  - child\n  - second child\n      - deeper\n- next")
        assertEquals(
            listOf(
                Block.Bullets(
                    listOf(
                        Bullet(plain("parent"), listOf(Bullet(plain("child")), Bullet(plain("second child")), Bullet(plain("deeper")))),
                        Bullet(plain("next")),
                    ),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun anIndentedLineCarriesItsBulletOn() {
        val blocks = parseMarkdown("- a long line\n  that wraps\nAfter")
        assertEquals(listOf(Block.Bullets(listOf(Bullet(plain("a long line that wraps")))), Block.Paragraph(plain("After"))), blocks)
    }

    @Test
    fun rules() {
        assertEquals(listOf(Block.Paragraph(plain("a")), Block.Rule, Block.Paragraph(plain("b"))), parseMarkdown("a\n\n---\n\nb"))
        assertEquals(listOf(Block.Rule, Block.Rule), parseMarkdown("***\n_ _ _"))
    }

    @Test
    fun boldAndItalic() {
        assertEquals(listOf(Span("a "), Span("bold", bold = true), Span(" word")), parseInline("a **bold** word"))
        assertEquals(listOf(Span("a "), Span("bold", bold = true)), parseInline("a __bold__"))
        assertEquals(listOf(Span("an "), Span("italic", italic = true), Span(" word")), parseInline("an *italic* word"))
        assertEquals(listOf(Span("an "), Span("italic", italic = true)), parseInline("an _italic_"))
    }

    @Test
    fun italicInsideBold() {
        assertEquals(
            listOf(Span("very ", bold = true), Span("much", bold = true, italic = true), Span(" so", bold = true)),
            parseInline("**very *much* so**"),
        )
    }

    @Test
    fun inlineCodeKeepsItsMarks() {
        assertEquals(listOf(Span("run "), Span("a *b* c", code = true)), parseInline("run `a *b* c`"))
    }

    @Test
    fun links() {
        assertEquals(
            listOf(Span("see "), Span("the site", link = "https://example.org"), Span(".")),
            parseInline("see [the site](https://example.org)."),
        )
        assertEquals(listOf(Span("big", bold = true, link = "https://x.y")), parseInline("[**big**](https://x.y)"))
    }

    @Test
    fun bracketsThatAreNotLinksStayAsTheyAre() {
        assertEquals(plain("Song [Remix] (Live)"), parseInline("Song [Remix] (Live)"))
        assertEquals(plain("[] and [a](b c)"), parseInline("[] and [a](b c)"))
    }

    @Test
    fun songTitlesWithMarksReadAsWritten() {
        listOf(
            "\$UICIDE",
            "\$uicideboy\$",
            "F*ck It",
            "S*M*A*R*T",
            "*NSYNC",
            "5 * 3 = 15",
            "Hello*",
            "snake_case_title",
            "2 ** 8",
            "_underscore at the start",
            "\$100 Bill",
        ).forEach { title ->
            assertEquals(title, plain(title), parseInline(title))
        }
    }

    @Test
    fun aTitleWithMarksInsideAListItem() {
        val blocks = parseMarkdown("- \$UICIDE by *\$uicideboy\$*")
        assertEquals(
            listOf(Block.Bullets(listOf(Bullet(listOf(Span("\$UICIDE by "), Span("\$uicideboy\$", italic = true)))))),
            blocks,
        )
    }

    @Test
    fun aBackslashKeepsAMarkLiteral() {
        assertEquals(plain("*not italic*"), parseInline("\\*not italic\\*"))
    }

    @Test
    fun anUnclosedMarkIsPlainText() {
        assertEquals(plain("**half bold"), parseInline("**half bold"))
        assertEquals(plain("`half code"), parseInline("`half code"))
    }

    @Test
    fun emptyTextHasNoBlocks() {
        assertTrue(parseMarkdown("").isEmpty())
        assertTrue(parseMarkdown("\n\n  \n").isEmpty())
    }
}
