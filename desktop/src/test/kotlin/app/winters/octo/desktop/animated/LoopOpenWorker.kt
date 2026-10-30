package app.winters.octo.desktop.animated

import app.winters.octo.audio.LoopVideo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

// Only this process's explicitly owned decoders can contribute to its counts.
object LoopOpenWorker {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        assertEquals(emptySet(), VideoThreads.ids(), "isolated worker must start empty")
        println("pid=${ProcessHandle.current().pid()} initial decoder IDs=[]")
        println("READY")
        check(readln() == "continue")
        val omitClose = args[1] == "omit-close"
        val rounds = if (omitClose) 1 else 1_000
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val used = AtomicInteger()
        // Keep an omitted close alive so GC cannot turn the negative control green.
        val owned = mutableListOf<LoopVideo>()
        try {
            repeat(rounds) { round ->
                val allocated = CompletableDeferred<Result<Unit>>()
                val returnFromOpen = CountDownLatch(1)
                val job = launch(Dispatchers.Default) {
                    openAndUse(
                        open = {
                            try {
                                val video = LoopVideo.open(args[0], 64u, 64u)
                                owned += video
                                opened.incrementAndGet()
                                allocated.complete(Result.success(Unit))
                                check(returnFromOpen.await(10, TimeUnit.SECONDS)) { "cancellation gate timed out" }
                                video
                            } catch (error: Throwable) {
                                allocated.complete(Result.failure(error))
                                throw error
                            }
                        },
                        close = { video ->
                            if (!omitClose) { video.stop(); video.destroy() }
                            closed.incrementAndGet()
                        },
                    ) { used.incrementAndGet(); awaitCancellation() }
                }
                try {
                    withTimeout(15_000) { allocated.await().getOrThrow() }
                    // Observe a real held decoder before cancelling across the
                    // allocation/return boundary. No scheduler-yield lottery.
                    if (round == 0) {
                        val alive = VideoThreads.ids()
                        assertEquals(1, alive.size, "counter must see the owned live decoder: $alive")
                        println("live decoder IDs=$alive")
                    }
                } finally {
                    job.cancel()
                    returnFromOpen.countDown()
                    withTimeout(15_000) { job.join() }
                }
            }
            println("opened=${opened.get()} closed=${closed.get()} used=${used.get()}")
            assertEquals(rounds, opened.get(), "every cancellation reached native allocation")
            assertEquals(opened.get(), closed.get(), "every loop opened was closed")
            assertEquals(0, used.get(), "cancelled opens must not enter use")
            val remaining = VideoThreads.ids()
            println("remaining decoder IDs=$remaining")
            assertEquals(emptySet(), remaining, "octo-video threads left")
        } finally {
            // Also cleans up a failing/mutated test; the assertion is made first.
            owned.forEach { video ->
                if (!video.uniffiIsDestroyed) { video.stop(); video.destroy() }
            }
        }
    }
}
