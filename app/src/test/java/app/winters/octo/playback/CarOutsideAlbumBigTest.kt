package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.today.todayResolve
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// With Library songs only, an album beyond the library that holds more
// than QUEUE_WINDOW library songs plays every one of them from the car, in
// the album's order, through the queue's big list: 700 library songs among
// finds, played whole and from a song chosen among them.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarOutsideAlbumBigTest {
    private val songs: Map<String, Song> = (0 until 900).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist") } +
        (0 until 100).associate { "f$it" to Song("f$it", title = "Find $it", artist = "Elsewhere", isExternal = true) }

    @Test
    fun libraryOnlyPlaysEveryLibrarySongOfABigAlbum() {
        NetworkQueueRig(songs).use { net ->
            val car = CarRig(db = net.db, server = net.server)
            try {
                net.db.putLibrary((0 until 700).map { libraryTrack("t$it", "Title $it") }) { "srv${it.id.removePrefix("t")}" }
                val listing = (0 until 900).flatMap { i -> listOfNotNull("srv$i", if (i % 9 == 4) "f${i / 9}" else null) }
                net.server.albums["oa"] = "Elsewhere" to listing
                val node = CarNode.OnlineAlbum("oa")
                val library = todayResolve(net.db, SERVER_SOURCE, listing.map { songs.getValue(it) }).map { it.id }.filterNot { it.startsWith("find:") }
                assertEquals(700, library.size)

                car.libraryOnly = true
                val play = runBlocking { car.pages.onlineAlbumPlay(node, fresh = true) }
                assertEquals(ListSpec.Online(OnlineKind.Album, "oa", libraryOnly = true), play.spec)
                assertTrue(play.network && play.shownOverWindow)
                assertTrue(net.play(PlayRequest(play.spec, play.start, shownOverWindow = play.shownOverWindow)).started)
                net.joined()
                assertEquals("every library song, in the album's order", library, net.songs())
                assertEquals(library.first(), net.current())

                // A song chosen among them plays them all from it.
                val chosen = CarPlays({ n, fresh -> if (n == node) car.pages.onlineAlbumPlay(node, fresh) else null }, { true }) {}
                val answer = runBlocking { chosen.playFor(carId(CarNode.Song(library[600], node))) } as CarPlayAnswer.Play
                assertEquals(ListStart.Track(library[600]), answer.play.start)
                assertTrue(net.play(PlayRequest(answer.play.spec, answer.play.start, shownOverWindow = answer.play.shownOverWindow)).started)
                net.joined()
                assertEquals(library, net.songs())
                assertEquals(library[600], net.current())
            } finally {
                // The car's rig shares the queue rig's database and server,
                // which the queue rig closes.
                runBlocking { car.scope.coroutineContext[Job]!!.cancelAndJoin() }
                car.drains.deleteRecursively()
            }
        }
    }
}
