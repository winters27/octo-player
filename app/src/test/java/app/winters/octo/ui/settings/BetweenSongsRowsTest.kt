package app.winters.octo.ui.settings

import app.winters.octo.playback.BetweenSongs
import org.junit.Assert.assertEquals
import org.junit.Test

// The Playback page under Between songs: Crossfade shows its length and
// curve, Automix its Filter sweeps and Match tempo, Gapless nothing more;
// a search result for a row the choice hides lights Between songs.
class BetweenSongsRowsTest {
    @Test
    fun eachChoiceShowsItsOwnRows() {
        assertEquals(emptyList<SettingEntry>(), rowsUnder(BetweenSongs.Gapless).map { it.entry })
        assertEquals(listOf(SettingsIndex.CrossfadeLength, SettingsIndex.CrossfadeCurve), rowsUnder(BetweenSongs.Crossfade).map { it.entry })
        assertEquals(listOf(SettingsIndex.FilterSweeps, SettingsIndex.MatchTempo), rowsUnder(BetweenSongs.Automix).map { it.entry })
    }

    @Test
    fun aResultForAHiddenRowLightsBetweenSongs() {
        val between = SettingsIndex.BetweenSongs.id
        val rows = mapOf(
            BetweenSongs.Gapless to emptyList(),
            BetweenSongs.Crossfade to listOf("crossfade_length", "crossfade_curve"),
            BetweenSongs.Automix to listOf("filter_sweeps", "match_tempo"),
        )
        for ((choice, shown) in rows) {
            for (id in listOf("crossfade_length", "crossfade_curve", "filter_sweeps", "match_tempo")) {
                assertEquals("$id with $choice", if (id in shown) id else between, playbackHighlight(id, choice))
            }
            // Between songs itself, the other rows and no result stay as they are.
            assertEquals(between, playbackHighlight(between, choice))
            assertEquals("speed", playbackHighlight("speed", choice))
            assertEquals(null, playbackHighlight(null, choice))
        }
    }
}
