package app.winters.octo.covers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverTextTest {
    private val setter = FakeTypesetter()
    private val look = CoverType(0f, 600, 0f, 1.08f, 1)

    @Test
    fun writingIsToldApart() {
        assertEquals(CoverScript.Latin, coverScript("Late night"))
        assertEquals(CoverScript.Latin, coverScript("Музыка для работы"))
        assertEquals(CoverScript.Wide, coverScript("夜のドライブ"))
        assertEquals(CoverScript.Tall, coverScript("أغاني الصيف"))
        assertEquals(CoverScript.Tall, coverScript("שירים לנסיעה"))
        assertEquals(CoverScript.Emoji, coverScript("🔥🔥🔥"))
        assertEquals(CoverScript.Latin, coverScript("2026"))
    }

    @Test
    fun rightToLeftGoesByTheFirstLetter() {
        assertTrue(isRightToLeft("أغاني الصيف"))
        assertTrue(isRightToLeft("2026 שירים"))
        assertFalse(isRightToLeft("Summer أغاني"))
        assertFalse(isRightToLeft("🔥"))
    }

    @Test
    fun theFirstCharacterKeepsWhatBelongsToIt() {
        assertEquals("é", firstGrapheme("école"))
        assertEquals("👍🏽", firstGrapheme("👍🏽 yes"))
        assertEquals("👩‍💻", firstGrapheme("👩‍💻 code"))
        assertEquals("🇯🇵", firstGrapheme("🇯🇵🇫🇷"))
        assertEquals("❤️", firstGrapheme("❤️ love"))
        assertEquals("夜", firstGrapheme("夜のドライブ"))
    }

    @Test
    fun aMonogramIsTheFirstLetterAsACapital() {
        assertEquals("L", monogram("late night"))
        assertEquals("1", monogram("  #1 hits"))
        assertEquals("夜", monogram("夜のドライブ"))
        assertEquals("🌙", monogram("🌙 Night moves"))
        assertEquals("أ", monogram("أغاني الصيف"))
    }

    @Test
    fun wideCharactersEachStandAlone() {
        assertEquals(listOf("Late", "night"), unbreakableRuns("Late  night"))
        assertEquals(listOf("夜", "の", "ド", "ラ", "イ", "ブ"), unbreakableRuns("夜のドライブ"))
        assertEquals(listOf("Tokyo", "夜"), unbreakableRuns("Tokyo 夜"))
    }

    @Test
    fun theLargestSizeThatFitsIsChosen() {
        val fit = fitCoverText("Late night", 200f, 1000f, 1, 10f, 80f, look, setter)
        // 10 characters at 0.55 of the size: 36 px fits 200, 37 does not.
        assertEquals(36f, fit.type.sizePx)
        assertFalse(fit.measured.cut)
    }

    @Test
    fun textAlwaysStaysInItsBox() {
        val names = listOf("Chill", "Late night drives", "Everything I have ever loved, in the order I found it", "東京の夜に聴きたい曲たち、雨の日のためのプレイリスト", "Supercalifragilisticexpialidocious", "🔥🔥🔥")
        for (side in listOf(32, 72, 96, 160, 300, 1000)) for (name in names) {
            val width = side * 0.84f
            val height = side * 0.6f
            val fit = fitCoverText(name, width, height, 3, 9f, side * 0.16f, look, setter)
            assertTrue("$name at $side is ${fit.measured.height} tall", fit.measured.height <= height || fit.type.sizePx == 9f)
            assertTrue(fit.measured.lines <= 3)
            if (!fit.measured.cut) {
                assertTrue(unbreakableRuns(name).all { setter.widthOf(it, fit.type) <= width })
            }
        }
    }

    @Test
    fun aWordTooLongIsCutOnOneLineNotBroken() {
        val fit = fitCoverText("Supercalifragilisticexpialidocious", 100f, 200f, 3, 12f, 30f, look, setter)
        assertEquals(1, fit.type.maxLines)
        assertTrue(fit.measured.cut)
    }

    @Test
    fun scriptsWithMarksGetRoomBetweenLines() {
        assertEquals(1.08f, coverLineHeight(CoverScript.Latin, 1.08f))
        assertEquals(1.4f, coverLineHeight(CoverScript.Tall, 1.08f))
        assertEquals(1.15f, coverLineHeight(CoverScript.Wide, 1.08f))
    }
}
