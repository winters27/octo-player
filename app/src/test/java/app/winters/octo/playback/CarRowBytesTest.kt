package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.timed
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Labeled guard: a car row is sized as the item it is sent as, built when
// its page is read. Every text field, texts cut to what a car shows, emoji,
// the explicit mark, short cover references and long ones (which a car is
// sent by a handle, given out when the item is built) are counted as they
// go out, so an answer holds as many rows as their items allow and never
// passes the budget: rows whose long references would pass the budget many
// times over as written still fit one answer by their handles.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class CarRowBytesTest {
    private val rig = CarRig(budget = 160_000)

    @After
    fun close() = rig.close()

    private val longRef = "online:server:fake|" + "c".repeat(6_000)

    private fun song(i: Int): TrackEntity {
        val title = when (i % 4) {
            0 -> "Song %03d".format(i)
            1 -> "Ünïcode 😀 %03d ".format(i) + "word ".repeat(40)
            2 -> "%03d ".format(i) + "x".repeat(300)
            else -> "Short $i"
        }
        return TrackEntity(
            id = "s%03d".format(i), sourceId = "device", nativeId = "s$i", title = title, searchKey = title.lowercase(),
            sortKey = "%03d".format(i), artist = if (i % 3 == 0) "Ärtist 😀" else "Band", artistId = "ar", album = "Album $i",
            albumId = "al", trackNo = null, discNo = null, year = null, durationMs = 1_000, addedAt = 0, mimeType = null,
            sizeBytes = null, artwork = if (i % 2 == 0) "$longRef$i" else "server:fake|cover$i", uri = null,
            explicit = i % 5 == 0,
        )
    }

    @Test
    fun rowsAreSizedAsTheItemsTheyAreSentAs() = timed(60) {
        val songs = (0 until 60).map(::song)
        runBlocking { rig.db.dao().insertTracks(songs) }
        val sent = runBlocking { rig.answer(CarNode.Songs) }
        // Written out, the long references alone would pass the budget many
        // times over: by their handles, the whole list fits one answer.
        assertTrue(songs.count { it.artwork!!.length > longRef.length } * 4 * longRef.length > 4 * rig.budget)
        assertEquals(60, sent.size)
        assertTrue("the answer stays in budget", sent.sumOf(::carBytes) <= rig.budget)
        sent.forEachIndexed { i, item ->
            val address = item.mediaMetadata.artworkUri.toString()
            assertTrue("a long reference goes by its handle: $address", address.length < 200)
            if (i % 2 == 0) assertTrue(address, "/$HANDLE_FLAG/" in address)
            // The size counted is the item's as built again: one handle for one reference.
            val again = rig.look.song(songs[i], CarNode.Songs, outside = false, downloaded = false)
            assertEquals(carBytes(again), carBytes(item))
            assertTrue((item.mediaMetadata.title?.length ?: 0) <= CAR_TEXT_MAX)
        }
    }
}
