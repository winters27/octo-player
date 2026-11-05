package app.winters.octo.design

import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// The fits' store: one worker works fits out and drops the oldest past its
// limit while drawing threads read it all the time.
class IconFitCacheTest {
    // How long the readers and askers keep at it.
    private val stressMillis = 2_000L

    @Test
    fun readersAndTheWorkerNeverTrip() {
        val busy = AtomicInteger()
        val most = AtomicInteger()
        val decided = AtomicInteger()
        val fits = IconFits(cap = 64) { _, px ->
            val now = busy.incrementAndGet()
            most.accumulateAndGet(now, ::maxOf)
            decided.incrementAndGet()
            busy.decrementAndGet()
            if (px % 2 == 0) FittedIcon(emptyList()) else null
        }
        val vectors = allIcons.map { it.second() }.filter { IconRecipes.of(it)?.fits == true }
        assertTrue(vectors.size > 50)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val until = System.currentTimeMillis() + stressMillis
        val threads = (0 until 6).map { n ->
            thread(name = "reader $n") {
                try {
                    var i = n
                    while (System.currentTimeMillis() < until) {
                        val vector = vectors[i % vectors.size]
                        val px = 8 + (i * 7) % 40
                        fits.fitted(vector, px)
                        fits.known(vector, px)
                        fits.ask(vector, IconRecipes.of(vector)!!, px)
                        fits.forDrawing(vector, Size(px.toFloat(), px.toFloat()), 1f)
                        i++
                    }
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }
        threads.forEach { it.join() }
        assertTrue("readers failed: ${failures.firstOrNull()}", failures.isEmpty())
        // The worker is alone, and still at work after it all.
        assertEquals(1, most.get())
        assertTrue(decided.get() > 64)
        val last = vectors.first()
        runBlocking { fits.ask(last, IconRecipes.of(last)!!, 1000)?.join() }
        assertTrue(fits.known(last, 1000))
        assertTrue("held ${fits.size}, past its limit", fits.size <= 64)
    }

    @Test
    fun aMissingFitIsDrawnAsWrittenUntilTheWorkerHasIt() {
        val fits = IconFits { _, _ -> FittedIcon(emptyList()) }
        val vector = OctoIcons.Settings
        val size = Size(18f, 18f)
        val before = fits.landed.value
        assertNull(fits.forDrawing(vector, size, 1f))
        // The draw asked for it; once it lands the icon is drawn fitted.
        runBlocking { withTimeout(5_000) { while (fits.landed.value == before) delay(2) } }
        assertTrue(fits.known(vector, 18))
        assertTrue(fits.forDrawing(vector, size, 1f) != null)
        // Not an icon size, or at 2x: never fitted.
        assertNull(fits.forDrawing(vector, Size(19f, 19f), 1f))
        assertNull(fits.forDrawing(vector, Size(36f, 36f), 2f))
    }
}
