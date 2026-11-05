package app.winters.octo.proof

import org.junit.Assume.assumeTrue
import org.junit.Test

// Serves the proof library on this computer's network, port 4533, for the
// phone run: `-PoctoProofServer` keeps it up until the run is stopped.
// `-PoctoProofServer=20000` serves that many songs (200,000 by default);
// `-PoctoProofDelay=60` holds each page back that many milliseconds.
class ProofServer {
    @Test
    fun serve() {
        val asked = System.getProperty("octo.proofServer")
        assumeTrue(asked != null)
        val songs = asked?.toIntOrNull() ?: 200_000
        val delay = System.getProperty("octo.proofDelay")?.toLongOrNull() ?: 0
        val library = LazyLibrary(songs = songs, albums = maxOf(1, songs / 10), artists = maxOf(1, songs / 40))
        val fake = LazySubsonic(library, starredSongs = songs / 4, port = 4533, anyAddress = true, pageDelayMs = delay)
        println("PROOF SERVER on port 4533, $songs songs, $delay ms a page")
        Thread.currentThread().join()
        fake.close()
    }
}
