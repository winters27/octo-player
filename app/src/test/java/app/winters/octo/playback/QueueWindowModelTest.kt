package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

// The queue in Room follows the player through a long run of mixed edits on
// a queue bigger than the window, against a plain list that does the same:
// Next and Previous, Play next of a new song and taking out a song coming
// up. After each step the song that is on matches, and at the end Room's
// order is the list's.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class QueueWindowModelTest {
    private val rig = QueueRig()

    @After
    fun close() = rig.close()

    @Test
    fun roomFollowsAMixOfEdits() {
        rig.db.songs(150)
        rig.db.songs(60, album = "x", prefix = "x")
        rig.play(PlayRequest(ListSpec.Album("al", withFinds = false), null, shownOverWindow = true))
        rig.joined()
        val model = (0 until 150).map { "s$it" }.toMutableList()
        var at = 0
        var added = 0
        val r = Random(11)
        repeat(120) { step ->
            when (r.nextInt(4)) {
                0 -> if (at < model.size - 1) {
                    rig.next()
                    at++
                }
                1 -> if (at > 0) {
                    rig.player.seekToPreviousMediaItem()
                    rig.until("Previous moved") { rig.current() == model[at - 1] }
                    at--
                }
                2 -> if (added < 60) {
                    val id = "x${added++}"
                    rig.run { rig.window.addList(ListSpec.Ids(listOf(id)), next = true, source = null) }
                    rig.settle()
                    model.add(at + 1, id)
                }
                else -> {
                    // A song coming up inside the window goes.
                    val held = rig.held()
                    val i = held.indexOf(model[at])
                    val target = held.getOrNull(i + 1 + r.nextInt(3))
                    if (target != null) {
                        val key = rig.player.getMediaItemAt(i + 1 + held.drop(i + 1).indexOf(target)).entryId!!
                        rig.run { rig.window.takeOut(listOf(entryOfKey(key)!!)) }
                        rig.settle()
                        model.remove(target)
                    }
                }
            }
            assertEquals("step $step", model[at], rig.current())
        }
        rig.until("Room caught up") { rig.songs() == model }
        assertTrue(rig.held().size <= rig.sizes.window)
    }
}
