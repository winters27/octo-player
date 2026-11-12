package app.winters.octo.proof

import org.junit.Assume.assumeTrue
import org.junit.Test

// Serves the proof library on this computer's network, port 4533, for the
// phone run: `-PoctoProofServer` keeps it up until the run is stopped.
// `-PoctoProofServer=20000` serves that many songs (200,000 by default);
// `-PoctoProofDelay=60` holds each page back that many milliseconds.
// Besides the songs: a quarter of them starred, half of them in "Unknown
// album" (the other half five to an album), a playlist as long as the
// library with every kind of entry, a
// family playlist of 1,000, a saved queue of 20,000 and one album found
// online (search for "Found online"). `-PoctoProofServerChange` serves the
// library as it is after the server read its folders again (LazyLibrary's
// change: a later scan time, 2,000 titles changed, 100 songs in 10 new
// albums that sort newest, 100 songs gone).
class ProofServer {
    @Test
    fun serve() {
        val asked = System.getProperty("octo.proofServer")
        assumeTrue(asked != null)
        val songs = asked?.toIntOrNull() ?: 200_000
        val delay = System.getProperty("octo.proofDelay")?.toLongOrNull() ?: 0
        // A tenth as many albums as songs besides the Unknown album, so the
        // other half of the songs fill them evenly.
        val library = LazyLibrary(songs = songs, albums = maxOf(1, songs / 10) + 1, artists = maxOf(1, songs / 40), unknownAlbumSongs = songs / 2)
        library.changed = System.getProperty("octo.proofServerChange") == "true"
        val fake = LazySubsonic(
            library,
            starredSongs = songs / 4,
            port = 4533,
            anyAddress = true,
            pageDelayMs = delay,
            playlists = listOf(
                LazyPlaylist.mixed(library, "pl-big", "Everything, mixed", songs),
                LazyPlaylist.family(library, "pl-family", "Family mix"),
            ),
            playQueue = LazyPlayQueue.of(library, size = minOf(20_000, songs), current = minOf(12_000, songs - 1)),
            onlineAlbumSongs = 600,
        )
        println("PROOF SERVER on port 4533, $songs songs, $delay ms a page${if (library.changed) ", changed" else ""}")
        Thread.currentThread().join()
        fake.close()
    }
}
