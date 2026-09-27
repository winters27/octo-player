package app.winters.octo.playback

import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.discovery.AcquisitionHost
import app.winters.octo.discovery.ProgressWatch
import app.winters.octo.discovery.adoptionsOf
import app.winters.octo.subsonic.Acquisition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_000_000_000_000L
private const val SIX_MINUTES = 6 * 60_000L

// The server's library ids the two songs went in under.
private const val UICIDE_ON_SERVER = "dAti9HX34pPuxG3bYPqDPJ"
private const val HUNTIN_ON_SERVER = "cEi8RizCv3u1jOCzd6jCJL"

// The library songs the phone has for them.
private const val UICIDE_SONG = "server:octo:$UICIDE_ON_SERVER"
private const val HUNTIN_SONG = "server:octo:$HUNTIN_ON_SERVER"

private fun find(id: String, title: String, artist: String, requestedAt: Long) = OnlineSongEntity(
    id = "find:$id", sourceId = "server:octo", nativeId = id, title = title, artist = artist, album = title,
    albumId = null, artistId = null, durationMs = 0, coverId = null, mimeType = null, bitrate = null, seenAt = NOW,
    requestedAt = requestedAt,
)

private fun done(id: String, title: String, artist: String, libraryId: String, startedAt: String) =
    Acquisition(id = id, artist = artist, title = title, state = "done", progress = 1f, error = null, libraryId = libraryId, startedAt = startedAt)

// Stands in for the server and the library, with both songs already taken
// in by the server.
private class TwoSongsHost : AcquisitionHost {
    val finds = mutableListOf<OnlineSongEntity>()
    var listed = emptyList<Acquisition>()

    override suspend fun waiting() = finds.filter { it.adoptedId.isEmpty() }

    override suspend fun acquisitions(): List<Acquisition> = listed

    override suspend fun takeIn(libraryId: String): String? = when (libraryId) {
        UICIDE_ON_SERVER -> UICIDE_SONG
        HUNTIN_ON_SERVER -> HUNTIN_SONG
        else -> null
    }

    override suspend fun adopt(findId: String, trackId: String) {
        val at = finds.indexOfFirst { it.id == findId }
        if (finds[at].adoptedId.isEmpty()) finds[at] = finds[at].copy(adoptedId = trackId)
    }

    override suspend fun syncAndWait() = Unit
}

// A find added to the library, then tapped once its row has turned into the
// library song, plays that song and no other.
@OptIn(ExperimentalCoroutinesApi::class)
class TappedFindTest {
    private fun TestScope.watchOf(host: TwoSongsHost) = ProgressWatch(host) { NOW + testScheduler.currentTime }

    @Test
    fun theSecondSongAddedPlaysItselfWhenTapped() = runTest {
        val host = TwoSongsHost()
        val watch = watchOf(host)
        val uicide = find("uicide", "\$UICIDE", "\$uicideboy\$", NOW)
        val huntin = find("huntin", "Huntin' Wabbitz", "J. Cole", NOW + SIX_MINUTES)

        // "$UICIDE" is added and arrives.
        host.finds += uicide
        host.listed = listOf(done("uicide", uicide.title, uicide.artist, UICIDE_ON_SERVER, "2026-09-27T12:00:00Z"))
        assertTrue(watch.run())

        // Six minutes on, "Huntin' Wabbitz" is added and arrives; the server
        // still lists the first one too.
        advanceTimeBy(SIX_MINUTES)
        host.finds += huntin
        host.listed = host.listed + done("huntin", huntin.title, huntin.artist, HUNTIN_ON_SERVER, "2026-09-27T12:06:00Z")
        assertTrue(watch.run())

        // Each find is linked to its own library song.
        assertEquals(mapOf(uicide.id to UICIDE_SONG, huntin.id to HUNTIN_SONG), adoptionsOf(host.finds))

        // The results list both finds, the first added on top. Tapping the
        // second plays the list from it, by the finds' ids.
        val asked = listOf(uicide.id, huntin.id)
        val tapped = asked.indexOf(huntin.id)
        val library = setOf(UICIDE_SONG, HUNTIN_SONG)
        val stored = host.finds.associateBy { it.id }
        val played = asked.map { id -> stored[id]?.let { playsAs(it, library) } }
        assertEquals(listOf(UICIDE_SONG, HUNTIN_SONG), played)

        val start = chosenStart(played, tapped)
        assertEquals(HUNTIN_SONG, played.filterNotNull()[start])
    }

    @Test
    fun theChosenSongIsFoundByItsPlace() {
        // A song listed twice starts at the copy tapped.
        assertEquals(2, chosenStart(listOf("a", "b", "a"), 2))
        // Songs gone before it move it up.
        assertEquals(1, chosenStart(listOf(null, "a", null, "b"), 3))
        // The chosen one gone, or nothing chosen: the first.
        assertEquals(0, chosenStart(listOf("a", null, "b"), 1))
        assertEquals(0, chosenStart(listOf("a", "b"), 5))
    }
}
