package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

// With shuffle on, a tapped row plays first at every size, small lists
// played whole and big ones written behind it alike: then every other song
// once, in the order of the keys the play's seed gives them, the tapped
// song's own key kept for when shuffle turns on at another song.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class TappedRowShuffleTest {
    private val titles = ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false))

    // The order a play of `n` songs tapped at `t` plays in with shuffle on:
    // the tapped song, then every other by its key from the play's seed.
    private fun model(n: Int, t: Int, seed: Long): List<String> =
        listOf("s$t") + (0 until n).filter { it != t }.sortedBy { shuffledKey(seed, playKey(playNumber(it, t))) }.map { "s$it" }

    private fun tapShuffled(n: Int, t: Int) {
        val rig = QueueRig(random = Random(31L * n + t))
        try {
            rig.db.songs(n)
            val answer = rig.play(PlayRequest(titles, ListStart.Row("s$t", null), shuffle = true, shownOverWindow = n > QUEUE_WINDOW))
            assertEquals("n=$n t=$t", true, answer.started)
            assertEquals("n=$n t=$t: the tapped song plays", "s$t", rig.current())
            rig.joined()
            rig.settle()
            val seed = rig.lists().single().seed
            val order = rig.songs(QueueOrder.Shuffle)
            assertEquals("n=$n t=$t", model(n, t, seed), order)
            assertEquals("n=$n t=$t: every song once", n, order.toSet().size)
            // Next plays the song after it in that order.
            rig.next()
            assertEquals("n=$n t=$t", order[1], rig.current())
            // The tapped song's own key waits for a toggle elsewhere.
            val tapped = rig.rows(QueueOrder.Shuffle).first()
            assertEquals(SK_FIRST, tapped.sk)
            assertEquals(shuffledKey(seed, playKey(0)), tapped.skBefore)
            rig.rows().filter { it.entry != tapped.entry }.forEach { assertNull(it.skBefore) }
        } finally {
            rig.close()
        }
    }

    @Test
    fun aTappedRowPlaysFirstAtEverySize() {
        for (n in listOf(12, 500, 501, 1_200)) {
            for (t in listOf(0, n / 2, n - 1)) tapShuffled(n, t)
        }
    }

    // With shuffle off a tapped row keeps the order of the list.
    @Test
    fun withShuffleOffTheListPlaysInOrderFromTheTap() {
        val rig = QueueRig(random = Random(4))
        try {
            rig.db.songs(12)
            rig.play(PlayRequest(titles, ListStart.Row("s5", null)))
            rig.settle()
            assertEquals("s5", rig.current())
            rig.next()
            assertEquals("s6", rig.current())
            assertNull(rig.rows().firstOrNull { it.skBefore != null })
        } finally {
            rig.close()
        }
    }
}
