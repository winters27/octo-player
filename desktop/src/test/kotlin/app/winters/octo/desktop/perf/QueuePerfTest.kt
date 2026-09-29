package app.winters.octo.desktop.perf

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.audio.EnginePlayer
import app.winters.octo.desktop.audio.LocalOrServer
import app.winters.octo.desktop.audio.NativeAudioEngine
import app.winters.octo.desktop.audio.ServerSongs
import app.winters.octo.desktop.audio.queueItem
import app.winters.octo.desktop.audio.writeSine
import app.winters.octo.desktop.player.QueueEntry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.swing.SwingUtilities

// Times handing the real engine a whole library as the queue, as a double
// click on a Songs row does, on the window's thread: every song's address
// is signed and the queue crosses into the engine. The engine plays on its
// silent device. Only when asked:
// OCTO_PERF=1 ./gradlew :desktop:test --tests '*QueuePerfTest*'.
class QueuePerfTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun measureQueueingTheLibrary() {
        assumeTrue(System.getenv("OCTO_PERF") == "1")
        FakeServer().use { server ->
            val tone = File(folder.root, "tone.wav").also { writeSine(it, seconds = 2) }
            server.file("stream", tone.readBytes())
            val client = server.client()
            val player = EnginePlayer(NativeAudioEngine.open(silent = true), LocalOrServer(ServerSongs { client }))
            val lines = mutableListOf("Octo queue performance (the engine player, silent device)")
            for (size in listOf(2_500, 20_000)) {
                val songs = madeUpLibrary(size).songs
                val times = List(7) {
                    var took = 0.0
                    SwingUtilities.invokeAndWait {
                        val start = System.nanoTime()
                        player.play(songs, size / 2)
                        took = (System.nanoTime() - start) / 1e6
                        player.pause()
                    }
                    took
                }.drop(2).sorted()
                val sources = LocalOrServer(ServerSongs { client })
                val signing = List(7) {
                    val start = System.nanoTime()
                    songs.forEachIndexed { i, song -> queueItem(QueueEntry(i.toLong(), song), sources) }
                    (System.nanoTime() - start) / 1e6
                }.drop(2).sorted()
                lines += "  of which making each song's queue item (its signed address): ${"%.1f".format(signing[signing.size / 2])} ms"
                lines += "Play ${"%,d".format(size)} songs from the middle: median ${"%.1f".format(times[times.size / 2])} ms on the window's thread (runs ${times.joinToString { "%.0f".format(it) }})"
            }
            player.close()
            lines.forEach(::println)
            File("build/perf").mkdirs()
            File("build/perf/queue.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }
}
