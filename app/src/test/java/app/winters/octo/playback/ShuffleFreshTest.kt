package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Every Shuffle is a new shuffle: each play takes a fresh seed from the
// window's random source (SecureRandom in the app), so tapping Shuffle on
// the same list twice gives two orders, small lists and big ones alike, and
// no order repeats.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class ShuffleFreshTest {
    private val rig = QueueRig()
    private val titles = SortOrder(SongSort.Title, false)

    @After
    fun close() = rig.close()

    private fun shuffle(big: Boolean): Pair<Long, List<String>> {
        rig.play(PlayRequest(ListSpec.Songs(SongScope.All, titles), null, shuffle = true, shownOverWindow = big))
        rig.joined()
        return rig.lists().single().seed to rig.songs(QueueOrder.Shuffle)
    }

    @Test
    fun twoShufflesOfOneListDiffer() {
        rig.db.songs(600)
        for (big in listOf(false, true)) {
            val (seedA, orderA) = shuffle(big)
            val (seedB, orderB) = shuffle(big)
            assertNotEquals(seedA, seedB)
            assertNotEquals(orderA, orderB)
            assertEquals(orderA.toSet(), orderB.toSet())
        }
    }

    @Test
    fun fiftyShufflesOfTwelveNeverRepeat() {
        rig.db.songs(12)
        val seen = HashSet<List<String>>()
        repeat(50) { assertTrue("an order repeated", seen.add(shuffle(big = false).second)) }
    }

    @Test
    fun twoHundredShufflesOfTwentyStartAcrossTheList() {
        rig.db.songs(20)
        val firsts = (1..200).map { shuffle(big = false).second.first() }
        assertTrue("the first song covered ${firsts.toSet().size} of 20", firsts.toSet().size >= 15)
    }

    // The window's own source is SecureRandom unless a test hands in one.
    @Test
    fun theSeedsComeFromSecureRandom() {
        val field = QueueWindow::class.java.getDeclaredField("random").apply { isAccessible = true }
        assertEquals(java.security.SecureRandom::class.java, field.get(rig.window)!!.javaClass)
    }
}
