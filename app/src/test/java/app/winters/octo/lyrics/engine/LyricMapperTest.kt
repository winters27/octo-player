package app.winters.octo.lyrics.engine

import app.winters.octo.lyrics.LyricLine
import app.winters.octo.lyrics.LyricWord
import app.winters.octo.lyrics.Lyrics
import app.winters.octo.lyrics.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricMapperTest {
    private fun synced(vararg lines: LyricLine) = Lyrics(synced = true, lines = lines.toList(), source = LyricsSource.Server)

    // A line whose words are its space-separated parts, each `each` ms long.
    private fun worded(start: Long, text: String, each: Long = 500): LyricLine {
        var at = 0
        val words = text.split(' ').mapIndexed { index, word ->
            val from = text.indexOf(word, at)
            at = from + word.length
            LyricWord(start + index * each, start + (index + 1) * each, word, from, from + word.length)
        }
        return LyricLine(startMs = start, endMs = start + words.size * each, text = text, words = words)
    }

    @Test
    fun directionComesFromTheFirstLetterWithOne() {
        assertTrue(isRtl("שלום עולם"))
        assertTrue(isRtl("مرحبا"))
        assertTrue(isRtl("ܫܠܡܐ"))
        assertTrue(isRtl("ހެލޯ"))
        assertTrue(isRtl("ߊߟߎ"))
        // Numbers and brackets have no direction of their own.
        assertTrue(isRtl("123 (שלום) hello"))
        assertFalse(isRtl("hello שלום"))
        assertFalse(isRtl("..."))
    }

    @Test
    fun rightToLeftAndDuetDecideTheSide() {
        fun side(rtl: Boolean, duet: Boolean) = SyncLine(0, "x", emptyList(), 0.0, 1.0, rtl = rtl, isDuet = duet).alignRight
        assertFalse(side(rtl = false, duet = false))
        assertTrue(side(rtl = false, duet = true))
        assertTrue(side(rtl = true, duet = false))
        assertFalse(side(rtl = true, duet = true))
    }

    @Test
    fun theMappedLinesKeepTheirDirectionAndDuet() {
        val lines = LyricMapper.map(
            synced(
                LyricLine(startMs = 0, text = "שלום"),
                LyricLine(startMs = 1_000, text = "hello", duet = true),
                LyricLine(startMs = 2_000, text = "שלום", duet = true),
            ),
        )
        assertEquals(listOf(true, true, false), lines.map { it.alignRight })
    }

    @Test
    fun aTranslationOrRomanizationTheSameAsTheLineIsDropped() {
        val lines = LyricMapper.map(
            synced(
                LyricLine(startMs = 0, text = "Hello world", translation = "hello  WORLD", romanization = "HelloWorld"),
                LyricLine(startMs = 1_000, text = "こんにちは", translation = "Hello", romanization = "konnichiwa"),
            ),
        )
        assertNull(lines[0].translation)
        assertNull(lines[0].romanization)
        assertEquals("Hello", lines[1].translation)
        assertEquals("konnichiwa", lines[1].romanization)
    }

    @Test
    fun syllablesOfOneWordStayTogether() {
        val text = "Hello world"
        val line = LyricLine(
            startMs = 0,
            endMs = 1_500,
            text = text,
            words = listOf(
                LyricWord(0, 300, "Hel", 0, 3),
                LyricWord(300, 700, "lo", 3, 5),
                LyricWord(800, 1_500, "world", 6, 11),
            ),
        )
        val words = LyricMapper.map(synced(line)).single().words
        assertEquals(listOf("Hel", "lo", "world"), words.map { it.text })
        assertEquals(listOf(false, true, true), words.map { it.trailingSpace })
        assertEquals(0.3, words[0].end, 1e-12)
        assertEquals(0.8, words[2].start, 1e-12)
    }

    @Test
    fun wordsCarryingTheirOwnSpacesEndGroups() {
        // Pieces the way timed LRC gives them, spaces inside the pieces.
        val text = "so long"
        val line = LyricLine(
            startMs = 0,
            text = text,
            words = listOf(LyricWord(0, 400, "so ", 0, 3), LyricWord(400, 900, "long", 3, 7)),
        )
        val words = LyricMapper.map(synced(line)).single().words
        assertEquals(listOf("so", "long"), words.map { it.text })
        assertEquals(listOf(true, true), words.map { it.trailingSpace })
    }

    @Test
    fun aLineTimedByLineGetsOneWordAcrossIt() {
        val lines = LyricMapper.map(synced(LyricLine(startMs = 1_000, text = "no word timing"), LyricLine(startMs = 3_000, text = "next")))
        val first = lines.first()
        assertTrue(first.isLineTimed)
        assertEquals(1, first.words.size)
        assertEquals("no word timing", first.words.single().text)
        assertEquals(1.0, first.words.single().start, 0.0)
        assertEquals(3.0, first.words.single().end, 0.0)
    }

    @Test
    fun wordsThatDoNotCoverTheLineFallBackToTheWholeLine() {
        val line = LyricLine(startMs = 0, endMs = 1_000, text = "two words", words = listOf(LyricWord(0, 500, "two", 0, 3)))
        assertTrue(LyricMapper.map(synced(line)).single().isLineTimed)
    }

    @Test
    fun aLongWaitBetweenLinesGetsAnInterlude() {
        val lines = LyricMapper.map(synced(worded(0, "one two"), worded(11_000, "three four")))
        assertEquals(3, lines.size)
        val gap = lines[1]
        assertTrue(gap.isInterlude)
        assertEquals(1.0, gap.start, 0.0)
        assertEquals(11.0, gap.end, 0.0)
        assertTrue(gap.words.isEmpty())
        assertEquals(listOf(0, 1, 2), lines.map { it.index })
    }

    @Test
    fun aShortWaitStretchesTheLineInstead() {
        val lines = LyricMapper.map(synced(worded(0, "one two"), worded(3_000, "three")))
        assertEquals(2, lines.size)
        assertFalse(lines.any { it.isInterlude })
        assertEquals(3.0, lines[0].end, 0.0)
    }

    @Test
    fun aLateFirstLineGetsAnInterludeBeforeIt() {
        val lines = LyricMapper.map(synced(worded(8_000, "late start")))
        assertTrue(lines[0].isInterlude)
        assertEquals(0.0, lines[0].start, 0.0)
        assertEquals(8.0, lines[0].end, 0.0)
        val early = LyricMapper.map(synced(worded(2_000, "early start")))
        assertFalse(early[0].isInterlude)
    }

    @Test
    fun anEmptySourceLineIsAnInterludeWhenThereIsRoom() {
        val lines = LyricMapper.map(
            synced(
                LyricLine(startMs = 0, text = "verse"),
                LyricLine(startMs = 4_000, text = ""),
                LyricLine(startMs = 7_000, text = "chorus"),
                LyricLine(startMs = 9_000, text = ""),
                LyricLine(startMs = 10_000, text = "tag"),
            ),
        )
        assertEquals(listOf("verse", "", "chorus", "tag"), lines.map { it.text })
        assertTrue(lines[1].isInterlude)
        assertEquals(4.0, lines[1].start, 0.0)
        assertEquals(7.0, lines[1].end, 0.0)
        // The one-second gap was too short for dots; the line before it holds on.
        assertEquals(10.0, lines[2].end, 0.0)
    }

    @Test
    fun backingVocalsFollowTheirLineWithoutBrackets() {
        val lead = worded(0, "hold on")
        val line = lead.copy(
            backingText = "(ooh yeah)",
            backing = listOf(LyricWord(200, 600, "(ooh", 0, 4), LyricWord(600, 1_000, "yeah)", 5, 10)),
            duet = true,
        )
        val lines = LyricMapper.map(synced(line))
        assertEquals(2, lines.size)
        val backing = lines[1]
        assertTrue(backing.isBackground)
        assertEquals("ooh yeah", backing.text)
        assertEquals(listOf("ooh", "yeah"), backing.words.map { it.text })
        assertEquals(0.2, backing.start, 1e-12)
        assertEquals(1.0, backing.end, 1e-12)
        assertTrue(backing.isDuet)
    }

    @Test
    fun aLineOnlyBackingVoicesSingIsABackgroundLine() {
        val lines = LyricMapper.map(synced(LyricLine(startMs = 0, text = "(la la)", background = true)))
        assertTrue(lines.single().isBackground)
        assertEquals("la la", lines.single().text)
    }

    @Test
    fun aCreditLineOnlyWhenWritersAreKnown() {
        val lyrics = synced(worded(0, "one"), worded(1_000, "two"))
        assertFalse(LyricMapper.map(lyrics).any { it.isCredit })
        val credited = LyricMapper.map(lyrics, writers = listOf("A", "B", "C"))
        val credit = credited.last()
        assertTrue(credit.isCredit)
        assertEquals("Written by A, B & C", credit.text)
        assertEquals(1.5, credit.start, 0.0)
        assertEquals(Double.POSITIVE_INFINITY, credit.end, 0.0)
    }

    @Test
    fun creditLinesFromTheSourceAreSkipped() {
        val lines = LyricMapper.map(
            synced(LyricLine(startMs = 0, text = "作词 : Someone"), LyricLine(startMs = 1_000, text = "Written by: Someone"), LyricLine(startMs = 2_000, text = "the song")),
        )
        assertEquals(listOf("the song"), lines.filterNot { it.isInterlude }.map { it.text })
    }

    @Test
    fun plainLyricsGiveNothing() {
        assertTrue(LyricMapper.map(Lyrics(synced = false, lines = listOf(LyricLine(text = "a")), source = LyricsSource.Online)).isEmpty())
    }
}
