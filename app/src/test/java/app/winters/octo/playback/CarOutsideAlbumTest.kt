package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.today.todayResolve
import app.winters.octo.listening.timed
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// An album beyond the library, played from the car, plays as the car shows
// it: with Library songs only, only the library's songs it holds, named one
// by one in the album's order (a song chosen among them plays them from
// it), as the phone's pages leave finds out; with the setting off, the
// album itself, finds and all.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class CarOutsideAlbumTest {
    private val songs: Map<String, Song> = (0 until 40).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist") } +
        (0 until 10).associate { "f$it" to Song("f$it", title = "Find $it", artist = "Elsewhere", isExternal = true) }
    private val server = ListServer(songs)
    private val rig = CarRig(server = server)

    @After
    fun close() = rig.close()

    @Test
    fun libraryOnlyPlaysTheLibrarySongsTheCarShows() = timed(60) {
        rig.db.putLibrary((0 until 40 step 2).map { libraryTrack("t$it", "Title $it") }) { "srv${it.id.removePrefix("t")}" }
        val listing = (0 until 40).flatMap { i -> listOfNotNull("srv$i", if (i % 4 == 1) "f${i / 4}" else null) }
        server.albums["oa"] = "Elsewhere" to listing
        val node = CarNode.OnlineAlbum("oa")
        val library = todayResolve(rig.db, SERVER_SOURCE, listing.map { songs.getValue(it) }).map { it.id }.filterNot { it.startsWith("find:") }
        assertTrue(library.isNotEmpty())

        rig.libraryOnly = true
        val shown = runBlocking { rig.answer(node, fresh = true) }
        assertEquals("the car shows the library's songs alone", library.size, shown.size)
        val play = runBlocking { rig.pages.onlineAlbumPlay(node, fresh = false) }
        assertEquals(ListSpec.Ids(library), play.spec)
        assertEquals(QueueSource.Played("Elsewhere"), play.source)
        // A song chosen in it plays that list from the song.
        val chosen = CarPlays({ n, fresh -> if (n == node) rig.pages.onlineAlbumPlay(node, fresh) else null }, { true }) {}
        val answer = runBlocking { chosen.playFor(carId(CarNode.Song(library[3], node))) } as CarPlayAnswer.Play
        assertEquals(ListSpec.Ids(library), answer.play.spec)
        assertEquals(ListStart.Track(library[3]), answer.play.start)

        rig.libraryOnly = false
        val whole = runBlocking { rig.pages.onlineAlbumPlay(node, fresh = false) }
        assertEquals(ListSpec.Online(OnlineKind.Album, "oa"), whole.spec)
        assertTrue(whole.network)
    }
}
