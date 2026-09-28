package app.winters.octo.desktop.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

// A running Octo in its own process, for the test below: it claims the
// folder, says so, prints the first launch handed to it, and ends.
object RunningOcto {
    @JvmStatic
    fun main(args: Array<String>) {
        val claim = SingleInstance.claim(File(args[0]), emptyList())
        if (claim !is SingleInstance.Claim.First) {
            println("NOT FIRST $claim")
            return
        }
        val done = java.util.concurrent.CountDownLatch(1)
        claim.instance.onLaunch { launched ->
            println("LAUNCH " + launched.joinToString("|"))
            done.countDown()
        }
        println("READY")
        done.await(20, TimeUnit.SECONDS)
        claim.instance.close()
    }
}

// The handover between two real processes, as between two launches.
class SingleInstanceAcrossProcessesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun aSecondProcessHandsItsFilesToTheFirst() {
        val dir = folder.newFolder("octo")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val running = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), RunningOcto::class.java.name, dir.path)
            .redirectErrorStream(true)
            .start()
        try {
            val output = running.inputStream.bufferedReader()
            assertEquals("READY", output.readLine())
            val claim = SingleInstance.claim(dir, listOf("C:\\Music\\a song.flac", "octo://album/7"))
            assertEquals(SingleInstance.Claim.HandedOver, claim)
            assertEquals("LAUNCH C:\\Music\\a song.flac|octo://album/7", output.readLine())
            assertTrue(running.waitFor(10, TimeUnit.SECONDS))
        } finally {
            running.destroyForcibly()
        }
    }
}
