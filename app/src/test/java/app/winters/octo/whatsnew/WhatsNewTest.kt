package app.winters.octo.whatsnew

import app.winters.octo.markdown.Block
import app.winters.octo.markdown.parseMarkdown
import app.winters.octo.markdown.plainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WhatsNewTest {
    // Tests run from the app module's folder.
    private val notes = File("src/main/assets/$WHATS_NEW_FILE").readText()

    @Test
    fun aFreshInstallHasNoNews() {
        assertFalse(whatsNewDue(lastSeen = null, current = 3, freshInstall = true))
    }

    @Test
    fun theFirstUpdateSinceTheCardExistedShowsIt() {
        assertTrue(whatsNewDue(lastSeen = null, current = 3, freshInstall = false))
    }

    @Test
    fun itShowsOncePerVersion() {
        assertTrue(whatsNewDue(lastSeen = 2, current = 3, freshInstall = false))
        assertFalse(whatsNewDue(lastSeen = 3, current = 3, freshInstall = false))
    }

    @Test
    fun theNotesArePlainWords() {
        // The long dashes, by their codes, so this file does not hold one.
        val dashes = listOf(Char(0x2014), Char(0x2013))
        (listOf(WhatsNewSummary) + notes.lines()).forEach { line ->
            assertFalse(line, dashes.any { it in line })
        }
    }

    @Test
    fun everyPartOfTheAppHasAHeadingAndItsLines() {
        val blocks = parseMarkdown(notes)
        val headings = blocks.filterIsInstance<Block.Heading>().map { it.text.plainText() }
        assertEquals(
            listOf("Sound", "Library", "Playback", "Server", "Lyrics", "Offline", "Everyday touches", "Settings"),
            headings,
        )
        // Each heading is followed by its list.
        blocks.forEachIndexed { index, block ->
            if (block is Block.Heading) {
                val next = blocks.getOrNull(index + 1)
                assertTrue(block.text.plainText(), next is Block.Bullets && next.items.isNotEmpty())
            }
        }
        // Nothing is left as a stray mark.
        val words = blocks.flatMap { block ->
            when (block) {
                is Block.Heading -> listOf(block.text.plainText())
                is Block.Paragraph -> listOf(block.text.plainText())
                is Block.Bullets -> block.items.map { it.text.plainText() }
                Block.Rule -> emptyList()
            }
        }
        words.forEach { assertFalse(it, "**" in it || "`" in it) }
    }

    @Test
    fun theHomeCardLineIsShort() {
        assertTrue(WhatsNewSummary.length <= 70)
    }
}
