package app.winters.octo.proof

import org.junit.Assume.assumeTrue
import org.junit.Test

// Serves the proof library on this computer's network, port 4533, for the
// phone run: `-PoctoProofServer` keeps it up until the run is stopped.
class ProofServer {
    @Test
    fun serve() {
        assumeTrue(System.getProperty("octo.proofServer") == "true")
        val fake = LazySubsonic(LazyLibrary(songs = 200_000, albums = 20_000, artists = 5_000), starredSongs = 50_000, port = 4533, anyAddress = true)
        println("PROOF SERVER on port 4533")
        Thread.currentThread().join()
        fake.close()
    }
}
