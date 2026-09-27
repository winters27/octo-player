package app.winters.octo.desktop.player

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

// The placeholder player against the contract, on a clock the test moves.
class SilentPlayerTest : DesktopPlayerContract() {
    private var now = 0L

    override fun newPlayer(): DesktopPlayer = SilentPlayer(clock = { now }, random = Random(7))

    override fun elapse(player: DesktopPlayer, ms: Long) {
        now += ms
        (player as SilentPlayer).tick()
    }

    @Test
    fun aLongGapPlaysThroughSeveralSongs() {
        val p = newPlayer()
        p.play(songs)
        elapse(p, 250_000)
        assertEquals("s3", p.state.value.current?.song?.id)
        assertEquals(50_000L, p.positionMs())
    }
}
