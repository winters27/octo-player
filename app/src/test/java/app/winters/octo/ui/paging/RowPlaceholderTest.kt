package app.winters.octo.ui.paging

import androidx.compose.ui.unit.dp
import app.winters.octo.ui.common.ListRowSide
import app.winters.octo.ui.common.SongRowCover
import app.winters.octo.ui.common.SongRowCoverCorner
import app.winters.octo.ui.common.SongRowGap
import app.winters.octo.ui.common.SongRowMinHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RowPlaceholderTest {
    // A row not read yet keeps exactly a song row's height, so nothing moves
    // when it arrives.
    @Test
    fun aPlaceholderIsASongRowTall() {
        assertEquals(56.dp, SongRowMinHeight)
        assertEquals(SongRowMinHeight, SongRowHeight)
    }

    // Its cover square sits where a song row's cover does, at its size and corners.
    @Test
    fun itsCoverMatchesTheSongRows() {
        assertEquals(12.dp, ListRowSide)
        assertEquals(44.dp, SongRowCover)
        assertEquals(6.dp, SongRowCoverCorner)
        assertEquals(12.dp, SongRowGap)
        // The cover and the row's 4dp above and below fit the row.
        assertTrue(SongRowCover + 8.dp <= SongRowHeight)
    }

    // The two bars stand where the title and subtitle lines do: inside the
    // cover's height, the subtitle's shorter and thinner.
    @Test
    fun itsBarsSitWhereTheWordsDo() {
        assertTrue(PlaceholderTitleBar + PlaceholderBarGap + PlaceholderSubtitleBar <= SongRowCover)
        assertTrue(PlaceholderSubtitleBar < PlaceholderTitleBar)
        assertTrue(PlaceholderSubtitleWidth < PlaceholderTitleWidth)
        assertTrue(PlaceholderTitleWidth < 1f)
    }
}
