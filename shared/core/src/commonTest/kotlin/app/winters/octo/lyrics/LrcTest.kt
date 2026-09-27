package app.winters.octo.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {
    private fun parse(text: String) = requireNotNull(parseLyricsText(text, LyricsSource.LyricsFile))

    @Test
    fun readsStampsInEveryWrittenForm() {
        val lyrics = parse(
            """
            [00:01.5]half
            [00:02.25]quarter
            [00:03.125]eighth
            [00:04]whole
            [01:05:50]colon
            """.trimIndent(),
        )
        assertTrue(lyrics.synced)
        assertEquals(listOf(1_500L, 2_250L, 3_125L, 4_000L, 65_500L), lyrics.lines.map { it.startMs })
        assertEquals("colon", lyrics.lines.last().text)
    }

    @Test
    fun aLineWithSeveralStampsIsSungAtEach() {
        val lyrics = parse(
            """
            [00:10.00][00:40.00]Chorus line
            [00:20.00]Verse line
            """.trimIndent(),
        )
        assertEquals(listOf(10_000L, 20_000L, 40_000L), lyrics.lines.map { it.startMs })
        assertEquals(listOf("Chorus line", "Verse line", "Chorus line"), lyrics.lines.map { it.text })
    }

    @Test
    fun tagsAreSkippedAndTheOffsetMovesEveryLine() {
        val lyrics = parse(
            """
            [ar:Someone]
            [ti:Something]
            [length: 03:20]
            [offset:+500]
            [00:10.00]Sooner
            [00:00.20]Never before the start
            """.trimIndent(),
        )
        assertEquals(listOf(0L, 9_500L), lyrics.lines.map { it.startMs })
        assertEquals("Sooner", lyrics.lines[1].text)

        val later = parse("[offset:-250]\n[00:10.00]Later")
        assertEquals(10_250L, later.lines.single().startMs)
    }

    @Test
    fun emptyTimedLinesAreGaps() {
        val lyrics = parse("[00:01.00]One\n[00:05.00]\n[00:20.00]Two")
        assertEquals(3, lyrics.lines.size)
        assertTrue(lyrics.lines[1].isGap)
        assertFalse(lyrics.lines[0].isGap)
    }

    @Test
    fun wordStampsTimeEachWord() {
        val lyrics = parse("[00:12.00]<00:12.00>Hello <00:12.50>naïve <00:13.20>world<00:14.00>")
        val line = lyrics.lines.single()
        assertEquals("Hello naïve world", line.text)
        assertEquals(listOf(12_000L, 12_500L, 13_200L), line.words.map { it.startMs })
        assertEquals(listOf(12_500L, 13_200L, 14_000L), line.words.map { it.endMs })
        assertEquals(listOf("Hello ", "naïve ", "world"), line.words.map { line.text.substring(it.from, it.to.coerceAtMost(line.text.length)) })
        assertEquals(14_000L, line.endMs)
    }

    @Test
    fun textBeforeTheFirstWordStampStartsWithTheLine() {
        val line = parse("[00:05.00]Oh <00:06.00>yes").lines.single()
        assertEquals("Oh yes", line.text)
        assertEquals(5_000L, line.words.first().startMs)
        assertEquals(6_000L, line.words.first().endMs)
    }

    @Test
    fun wordStampsMoveWithTheOffset() {
        val line = parse("[offset:1000]\n[00:05.00]<00:05.00>a <00:06.00>b").lines.single()
        assertEquals(4_000L, line.startMs)
        assertEquals(listOf(4_000L, 5_000L), line.words.map { it.startMs })
    }

    @Test
    fun backingLinesJoinTheLineBefore() {
        val line = parse("[00:05.00]Lead line\n[bg: <00:06.00>ooh <00:07.00>aah]").lines.single()
        assertEquals("ooh aah", line.backingText)
        assertEquals(2, line.backing.size)
    }

    @Test
    fun linesComeOutInTimeOrder() {
        val lyrics = parse("[00:30.00]Third\n[00:10.00]First\n[00:20.00]Second")
        assertEquals(listOf("First", "Second", "Third"), lyrics.lines.map { it.text })
    }

    @Test
    fun textWithoutStampsIsPlain() {
        val lyrics = parse("[ar:Someone]\nFirst line\n\n\n\nSecond verse\n\n")
        assertFalse(lyrics.synced)
        assertEquals(listOf("First line", "", "Second verse"), lyrics.lines.map { it.text })
    }

    @Test
    fun windowsLineEndsAndAByteOrderMarkAreFine() {
        val lyrics = parse(Char(0xFEFF) + "[00:01.00]One\r\n[00:02.00]Two\r\n")
        assertEquals(listOf("One", "Two"), lyrics.lines.map { it.text })
    }

    @Test
    fun nothingToShowIsNull() {
        assertNull(parseLyricsText("", LyricsSource.SongFile))
        assertNull(parseLyricsText("[ar:Someone]\n[ti:Nothing]", LyricsSource.SongFile))
        assertNull(parseLyricsText("[00:01.00]\n[00:02.00]", LyricsSource.SongFile))
    }
}
