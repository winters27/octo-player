package app.winters.octo.sound

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

// The limiter's level comes all the way back to unity after a peak, so a
// song that needs no limiting afterwards passes bit for bit.
class LimiterTest {
    private val rate = 48_000

    // A burst at twice full scale, which turns the level down to about 0.45.
    private fun burst(limiter: Limiter) {
        val loud = FloatArray(4_800) { if (it % 2 == 0) 2f else -2f }
        limiter.process(loud, loud.size, FloatArray(loud.size))
    }

    private fun quiet(limiter: Limiter, frames: Int) {
        limiter.process(FloatArray(frames), frames, FloatArray(frames))
    }

    // Runs a sine through and checks every frame comes out exactly as it
    // went in, one delay later. Its peak is under the ceiling, so with the
    // limiter on it needs no limiting either.
    private fun assertPassesExactly(limiter: Limiter) {
        val song = FloatArray(9_600) { (0.85 * sin(2 * PI * 1_000 * it / rate)).toFloat() }
        val out = FloatArray(song.size)
        val written = limiter.process(song, song.size, out)
        val delay = limiter.latency
        for (i in 0 until written - delay) assertEquals("frame $i", song[i], out[i + delay], 0f)
    }

    @Test
    fun aSongAfterLimitingWithTheLimiterOffPassesBitForBit() {
        val limiter = Limiter(1, rate)
        burst(limiter)
        // Switched off, as the mix limiter is once a blend's window ends.
        limiter.enabled = false
        quiet(limiter, rate * 2)
        assertPassesExactly(limiter)
    }

    @Test
    fun aSongAfterLimitingWithTheLimiterOnPassesBitForBit() {
        val limiter = Limiter(1, rate)
        burst(limiter)
        quiet(limiter, rate * 2)
        assertPassesExactly(limiter)
    }

    @Test
    fun theLevelLandsOnUnityAt44100Too() {
        val limiter = Limiter(2, 44_100)
        val loud = FloatArray(4_410 * 2) { if ((it / 2) % 2 == 0) 3f else -3f }
        limiter.process(loud, 4_410, FloatArray(loud.size))
        limiter.enabled = false
        val frames = 44_100 * 2
        limiter.process(FloatArray(frames * 2), frames, FloatArray(frames * 2))
        val song = FloatArray(4_410 * 2) { (0.99 * sin(2 * PI * 440 * (it / 2) / 44_100.0)).toFloat() }
        val out = FloatArray(song.size)
        val written = limiter.process(song, 4_410, out)
        val delay = limiter.latency
        for (i in 0 until (written - delay) * 2) assertEquals("sample $i", song[i], out[i + delay * 2], 0f)
    }
}
