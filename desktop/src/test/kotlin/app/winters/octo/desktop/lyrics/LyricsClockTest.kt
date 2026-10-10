package app.winters.octo.desktop.lyrics

import app.winters.octo.audio.EngineEvent
import app.winters.octo.audio.TrackInfo
import app.winters.octo.desktop.audio.EnginePlayer
import app.winters.octo.desktop.audio.FakeEngine
import app.winters.octo.desktop.audio.Heard
import app.winters.octo.desktop.audio.SongAddress
import app.winters.octo.desktop.audio.itemId
import app.winters.octo.lyrics.LyricLine
import app.winters.octo.lyrics.Lyrics
import app.winters.octo.lyrics.LyricsSource
import app.winters.octo.lyrics.engine.LyricEngine
import app.winters.octo.lyrics.engine.LyricMapper
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Test

// The word-by-word lyrics keep time by the engine's audio clock, read
// through the player, with the song's and the output's timing and the
// screen's lead on top, as on the phone.
class LyricsClockTest {
    private val lyrics = Lyrics(
        synced = true,
        source = LyricsSource.Server,
        lines = listOf(LyricLine(0, text = "One"), LyricLine(4_000, text = "Two"), LyricLine(8_000, text = "Three"), LyricLine(12_000, text = "Four")),
    )

    private class Rig {
        val engine = FakeEngine()
        var now = 0L
        val player = EnginePlayer(engine, { SongAddress("tone.wav") }, clock = { now })

        init {
            player.play(listOf(Song("s", "S", duration = 200)))
            // The engine is heard at the start.
            hear(0.0)
            player.positionMs()
        }

        val key get() = player.state.value.current!!.key

        // Where the engine says the listener is.
        fun hear(ms: Double) {
            engine.heard = Heard(itemId(key), ms, 200_000)
        }
    }

    // One frame of the lyrics engine at `nanos`, from the player.
    private fun LyricEngine.frameAt(rig: Rig, nanos: Long, offsetMs: Long = 0, outputOffsetMs: Long = 0) =
        frame(nanos, rig.player.positionMs(), rig.player.state.value.playing, rig.player.state.value.speed.toDouble(), offsetMs, outputOffsetMs)

    private fun engineFor(): LyricEngine = LyricEngine(LyricMapper.map(lyrics)).apply {
        setGeometry(DoubleArray(LyricMapper.map(lyrics).size) { 40.0 }, 10.0, 40.0, 600.0)
    }

    @Test
    fun theLinesFollowTheAudioClock() {
        val rig = Rig()
        val view = engineFor()
        rig.hear(4_200.0)
        view.frameAt(rig, 1_000_000_000)
        assertEquals(4.2, view.lyricTime, 0.001)
        assertEquals("Two", view.lines[view.focus].text)
        rig.hear(8_050.0)
        view.frameAt(rig, 1_016_000_000)
        assertEquals("Three", view.lines[view.focus].text)
    }

    @Test
    fun theSongsTimingMovesTheWordsLater() {
        val rig = Rig()
        val view = engineFor()
        rig.hear(4_200.0)
        // Heard half a second later than written: at 4.2 s the song is
        // still on the first line.
        view.frameAt(rig, 1_000_000_000, offsetMs = 500)
        assertEquals(3.7, view.lyricTime, 0.001)
        assertEquals("One", view.lines[view.focus].text)
    }

    @Test
    fun theOutputsTimingAndTheScreensLeadComeOffTheClock() {
        val rig = Rig()
        val view = engineFor()
        rig.hear(7_980.0)
        val lead = screenLeadMs(60f)
        assertEquals(-33L, lead)
        assertEquals(-17L, screenLeadMs(120f))
        assertEquals(-33L, screenLeadMs(Float.NaN))
        // The screen's lead shows the words 33 ms early: at 7.98 s the third
        // line is already up.
        view.frameAt(rig, 1_000_000_000, outputOffsetMs = lead)
        assertEquals(8.013, view.lyricTime, 0.001)
        assertEquals("Three", view.lines[view.focus].text)
    }

    @Test
    fun aClickOnALineShowsItsTimeUntilTheEngineGetsThere() {
        val rig = Rig()
        val view = engineFor()
        rig.hear(1_000.0)
        view.frameAt(rig, 1_000_000_000)
        // Clicking "Three": the player is asked to seek, and shows the
        // target until the engine is heard there.
        rig.player.seekTo(heardAt(8_000, 0))
        view.frameAt(rig, 1_016_000_000)
        assertEquals("Three", view.lines[view.focus].text)
        rig.now += 30
        rig.hear(8_010.0)
        view.frameAt(rig, 1_032_000_000)
        assertEquals(8.01, view.lyricTime, 0.02)
    }

    // The song's length changing under the lyrics (a wrong listing found
    // out once the song opened) moves no line: they follow the place in
    // the song, never a share of its length.
    @Test
    fun aCorrectedLengthMovesNoLine() {
        val (kept, corrected) = Rig() to Rig()
        val (keptView, correctedView) = engineFor() to engineFor()
        for (rig in listOf(kept, corrected)) rig.hear(8_050.0)
        keptView.frameAt(kept, 1_000_000_000)
        correctedView.frameAt(corrected, 1_000_000_000)
        corrected.engine.emit(EngineEvent.TrackStarted(itemId(corrected.key), 0u, TrackInfo("aac", false, 44_100u, 2u, null, 190_000uL, null)))
        assertEquals(190_000, corrected.player.state.value.durationMs)
        assertEquals(200_000, kept.player.state.value.durationMs)
        keptView.frameAt(kept, 1_016_000_000)
        correctedView.frameAt(corrected, 1_016_000_000)
        assertEquals(keptView.lyricTime, correctedView.lyricTime, 0.0)
        assertEquals("Three", correctedView.lines[correctedView.focus].text)
    }

    @Test
    fun timingStepsStayInsideTheirLimits() {
        assertEquals(50L, stepTiming(0, 1))
        assertEquals(TIMING_LIMIT_MS, stepTiming(TIMING_LIMIT_MS, 3))
        assertEquals(-OUTPUT_TIMING_LIMIT_MS, stepTiming(0, -100, OUTPUT_TIMING_LIMIT_MS))
        assertEquals("+0.75 s", signedTiming(750))
        assertEquals("-1.5 s", signedTiming(-1_500))
        assertEquals("0 s", signedTiming(0))
    }
}
