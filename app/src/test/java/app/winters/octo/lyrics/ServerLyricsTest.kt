package app.winters.octo.lyrics

import app.winters.octo.subsonic.Cue
import app.winters.octo.subsonic.CueLine
import app.winters.octo.subsonic.LyricsAgent
import app.winters.octo.subsonic.LyricsLine
import app.winters.octo.subsonic.StructuredLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLyricsTest {
    private val text = "Café naïve 日本"

    // Bytes: "Café " is 0..5 (é is two), "naïve " 6..12 (ï is two), 日 13..15
    // and 本 16..18 (three each), both ends included.
    private val cues = listOf(
        Cue(1_000, 1_500, "Café ", 0, 5),
        Cue(1_500, 2_500, "naïve ", 6, 12),
        Cue(2_500, 3_000, "日", 13, 15),
        Cue(3_000, 3_500, "本", 16, 18),
    )

    @Test
    fun byteOffsetsBecomeCharacterRanges() {
        val words = cueWords(CueLine(index = 0, start = 1_000, end = 3_500, value = text, cue = cues))
        assertEquals(listOf("Café ", "naïve ", "日", "本"), words.map { text.substring(it.from, it.to) })
        assertEquals(listOf(0 to 5, 5 to 11, 11 to 12, 12 to 13), words.map { it.from to it.to })
        assertEquals(words.map { it.text }, cues.map { it.value })
    }

    @Test
    fun lettersBuiltFromTwoCharsKeepBothChars() {
        // The note is four bytes and two Java chars.
        val line = "🎵 la"
        val words = cueWords(
            CueLine(value = line, cue = listOf(Cue(0, 500, "note", 0, 3), Cue(500, 900, " la", 4, 6))),
        )
        assertEquals(listOf(0 to 2, 2 to 5), words.map { it.from to it.to })
    }

    @Test
    fun anEndInsideALetterTakesTheWholeLetter() {
        val range = Utf8Positions("日本").charRange(1, 3)
        assertEquals(0 until 2, range)
    }

    @Test
    fun cuesOutsideTheTextAreLeftOut() {
        val words = cueWords(CueLine(value = "ab", cue = listOf(Cue(0, 1, "a", 0, 0), Cue(1, 2, "?", 1, 9), Cue(2, 3, "?", 1, 0))))
        assertEquals(1, words.size)
    }

    @Test
    fun missingEndsComeFromTheNextCue() {
        val words = cueWords(CueLine(value = "a b", end = 900, cue = listOf(Cue(0, null, "a ", 0, 1), Cue(400, null, "b", 2, 2))))
        assertEquals(listOf(400L, 900L), words.map { it.endMs })
    }

    private fun enhanced() = listOf(
        StructuredLyrics(
            kind = "main",
            lang = "fra",
            synced = true,
            offset = -100.0,
            line = listOf(LyricsLine(1_000, "$text (écho)"), LyricsLine(4_000, ""), LyricsLine(9_000, "Fin")),
            agents = listOf(LyricsAgent("lead", "main", "Lead"), LyricsAgent("backing", "bg")),
            cueLine = listOf(
                CueLine(0, "lead", 1_000, 3_500, text, cues),
                CueLine(0, "backing", 2_000, 3_500, "(écho)", listOf(Cue(2_000, 3_500, "(écho)", 0, 6))),
            ),
        ),
        StructuredLyrics(
            kind = "translation",
            lang = "eng",
            synced = true,
            line = listOf(LyricsLine(1_000, "Naive coffee Japan"), LyricsLine(4_000, ""), LyricsLine(9_000, "End")),
        ),
        StructuredLyrics(lang = "und", synced = false, line = listOf(LyricsLine(value = text))),
    )

    @Test
    fun mapsTheSyncedMainLyricsWithWordsBackingAndTranslation() {
        val lyrics = requireNotNull(serverLyrics(enhanced(), language = "en"))
        assertTrue(lyrics.synced)
        assertEquals(LyricsSource.Server, lyrics.source)
        val first = lyrics.lines.first()
        // The offset of -100 makes every time later by 100.
        assertEquals(1_100L, first.startMs)
        assertEquals(text, first.text)
        assertEquals(1_100L, first.words.first().startMs)
        assertEquals("(écho)", first.backingText)
        assertEquals(0 to 6, first.backing.single().let { it.from to it.to })
        assertEquals("Lead", first.agent)
        assertEquals("Naive coffee Japan", first.translation)
        assertEquals(3_600L, first.endMs)
        assertTrue(lyrics.lines[1].isGap)
        assertEquals("End", lyrics.lines[2].translation)
    }

    @Test
    fun noTranslationWhenThePhoneReadsTheSongsLanguage() {
        val lyrics = requireNotNull(serverLyrics(enhanced(), language = "fr"))
        assertNull(lyrics.lines.first().translation)
    }

    @Test
    fun aLineSungOnlyByBackingVoicesIsMarked() {
        val lyrics = requireNotNull(
            serverLyrics(
                listOf(
                    StructuredLyrics(
                        synced = true,
                        line = listOf(LyricsLine(0, "ooh")),
                        agents = listOf(LyricsAgent("lead", "main"), LyricsAgent("choir", "bg")),
                        cueLine = listOf(CueLine(0, "choir", 0, 800, "ooh", listOf(Cue(0, 800, "ooh", 0, 2)))),
                    ),
                ),
                language = "en",
            ),
        )
        assertTrue(lyrics.lines.single().background)
        assertEquals("ooh", lyrics.lines.single().text)
    }

    @Test
    fun plainServerLyricsStayPlain() {
        val lyrics = requireNotNull(serverLyrics(listOf(enhanced().last()), language = "en"))
        assertFalse(lyrics.synced)
        assertEquals(text, lyrics.lines.single().text)
    }

    @Test
    fun nothingUsableIsNull() {
        assertNull(serverLyrics(emptyList(), "en"))
        assertNull(serverLyrics(listOf(StructuredLyrics(kind = "translation", synced = true, line = listOf(LyricsLine(0, "x")))), "en"))
    }

    @Test
    fun languagesMatchInTwoOrThreeLetters() {
        assertTrue(sameLanguage("en", "eng"))
        assertTrue(sameLanguage("fra", "fr"))
        assertFalse(sameLanguage("ko", "en"))
    }
}
