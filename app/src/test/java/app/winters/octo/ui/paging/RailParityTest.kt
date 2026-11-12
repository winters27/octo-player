package app.winters.octo.ui.paging

import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.common.railStops
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import app.winters.octo.ui.common.today.indexLetter as todayIndexLetter
import app.winters.octo.ui.common.today.letterRuns as todayLetterRuns
import app.winters.octo.ui.common.today.railStops as todayRailStops

// A labeled pin: the letter rail a list counts by ranges of its index is
// today's (ui/common/today, as c2208eaa had it): every character files under
// today's letter, and the runs and rail stops of every list ordered by a
// name, both ways, equal today's over the names in today's order, both "#"
// runs, the dotless i and the long s included.
@RunWith(RobolectricTestRunner::class)
class RailParityTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun everyCharacterFilesUnderTodaysLetter() {
        for (point in 0..0xFFFF) {
            if (point in 0xD800..0xDFFF) continue
            val name = String(Character.toChars(point)) + "x"
            assertEquals("U+%04X".format(point), todayIndexLetter(name), LetterRanges.ranges[LetterRanges.indexOf(name)].letter)
        }
        for (name in listOf("", "𝄞 clef", "🎵", String(Character.toChars(0x10FFFF)))) {
            assertEquals(name, todayIndexLetter(name), LetterRanges.ranges[LetterRanges.indexOf(name)].letter)
        }
        assertEquals("ranges", 59, LetterRanges.ranges.size)
    }

    @Test
    fun runsAndStopsEqualTodaysForEveryNameOrder() = runBlocking {
        db.edgeSongs(900)
        val lists = SongLists(db.listsDao())
        for (sort in listOf(SongSort.Title, SongSort.Artist, SongSort.Album)) {
            for (down in listOf(false, true)) {
                val name = "$sort ${if (down) "desc" else "asc"}"
                val headings = db.todaySongs(sort, down, SongScope.All, select = "HEADING")
                val today = todayLetterRuns(headings)
                val survey = lists.loader(SongScope.All, SortOrder(sort, down)).survey(null)
                assertEquals(name, today, survey.runs)
                assertEquals("$name count", headings.size, survey.count)
                assertEquals("$name stops", todayRailStops(today, headed = true, before = 2), railStops(survey.runs!!, headed = true, before = 2))
                assertTrue("$name has both # runs", today.count { it.letter == '#' } >= 2)
            }
        }
    }
}
