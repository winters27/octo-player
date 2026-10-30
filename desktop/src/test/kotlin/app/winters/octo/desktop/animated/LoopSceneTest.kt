package app.winters.octo.desktop.animated

import app.winters.octo.audio.loopVideoAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopSceneTest {
    private val fixture = File("../audio-engine/tests/fixtures/loop-red-blue.mp4")

    @Test(timeout = 60_000)
    fun renderingBeforeTheDecoderOpensDoesNotSpendItsFade() {
        assumeTrue(fixture.isFile && runCatching { loopVideoAvailable() }.getOrDefault(false))
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val probe = LoopProbe(beforeOpen = {
            opening.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        })
        LoopScene(fixture, probe).use { scene ->
            try {
                scene.await("opening gate") { opening.count == 0L }
                // More than the old entire five-second budget on the scene's
                // clock. The native open is explicitly still waiting, not slow.
                repeat(320) { assertEquals(0xFF000000.toInt() to 0xFF000000.toInt(), scene.frame()) }
                assertEquals(0, probe.copies.get())
                release.countDown()
                scene.awaitPicture()
            } finally {
                release.countDown()
            }
        }
        assertEquals(1, probe.opens.get())
        assertEquals(1, probe.closes.get())
    }

    @Test(timeout = 60_000)
    fun disposalWaitsForTheScenesOwnDecoderCleanup() = runBlocking {
        assumeTrue(fixture.isFile && runCatching { loopVideoAvailable() }.getOrDefault(false))
        val stopping = CountDownLatch(1)
        val release = CountDownLatch(1)
        val probe = LoopProbe(beforeStop = {
            stopping.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        })
        val scene = LoopScene(fixture, probe)
        var closing = false
        try {
            scene.awaitPicture()
            val done = async(Dispatchers.Default) { scene.close() }
            closing = true
            try {
                assertTrue(stopping.await(5, TimeUnit.SECONDS), scene.diagnostics())
                assertFalse(done.isCompleted, "Disposal returned while its native close was gated")
                assertEquals(0, probe.closes.get())
            } finally {
                release.countDown()
                withTimeout(10_000) { done.await() }
            }
            assertEquals(1, probe.closes.get())
        } finally {
            release.countDown()
            if (!closing) scene.close()
        }
    }
}
