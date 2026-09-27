package app.winters.octo.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteQueueTest {
    // The phone's queue at the moment casting starts: shuffled, on "c",
    // 83 seconds in, playing, repeating the whole queue.
    private val phone = QueueState(
        items = listOf("a", "b", "c", "d", "e"),
        index = 2,
        positionMs = 83_000,
        playing = true,
        repeatMode = REPEAT_ALL,
        shuffle = true,
        order = listOf(4, 2, 0, 3, 1),
    )

    @Test
    fun everythingCarriesOverToTheDevice() {
        val remote = RemoteQueue(phone)
        assertEquals(phone.items, remote.items)
        assertEquals(2, remote.index)
        assertEquals("c", remote.current)
        assertEquals(REPEAT_ALL, remote.repeatMode)
        assertEquals(true, remote.shuffle)
        assertEquals(phone.order, remote.order)
        // And back to the phone unchanged, at the device's position.
        assertEquals(phone.copy(positionMs = 91_500, playing = false), remote.state(91_500, playing = false))
    }

    @Test
    fun theDevicePlaysInTheSameOrderAsThePhoneWould() {
        val remote = RemoteQueue(phone)
        // c is second in the play order 4, 2, 0, 3, 1.
        assertEquals(0, remote.next())
        assertEquals(4, remote.previous())
        assertEquals(listOf(0, 3, 1), remote.upcoming())
        // Repeating the whole queue, the last one leads back to the first.
        assertEquals(4, remote.next(from = 1))
        assertNull(remote.next(from = 1, repeat = REPEAT_OFF))
        // Repeating one song, it plays again.
        assertEquals(2, remote.next(repeat = REPEAT_ONE))
        remote.shuffle = false
        assertEquals(3, remote.next())
    }

    @Test
    fun songsAddedWhileCastingLandWhereThePhoneWouldPutThem() {
        val remote = RemoteQueue(phone.copy(shuffle = true))
        // Play next: straight after the current song, in play order too.
        remote.add(3, listOf("x"), playNext = true)
        assertEquals(listOf("a", "b", "c", "x", "d", "e"), remote.items)
        assertEquals(3, remote.next())
        // The current song keeps its place.
        assertEquals("c", remote.current)
    }

    @Test
    fun listenerSongsGoBeforeAutoplaySongs() {
        val queue = QueueState(listOf("a", "b", "auto1", "auto2"), index = 1, positionMs = 0, playing = true)
        val remote = RemoteQueue(queue) { it.startsWith("auto") }
        remote.add(4, listOf("mine"))
        assertEquals(listOf("a", "b", "mine", "auto1", "auto2"), remote.items)
    }

    @Test
    fun takingOutTheCurrentSongMovesOnToTheNext() {
        val remote = RemoteQueue(QueueState(listOf("a", "b", "c", "d"), index = 1, positionMs = 0, playing = true))
        assertEquals(Removal.MovedOn, remote.remove(1, 2))
        assertEquals("c", remote.current)
        assertEquals(Removal.Kept, remote.remove(0, 1))
        assertEquals("c", remote.current)
        assertEquals(0, remote.index)
        // The last song goes while on: nothing is left after it.
        remote.moveTo(1)
        assertEquals(Removal.Ended, remote.remove(1, 2))
        assertEquals(listOf("c"), remote.items)
    }

    @Test
    fun movingSongsKeepsTheCurrentOneOn() {
        val remote = RemoteQueue(QueueState(listOf("a", "b", "c", "d", "e"), index = 2, positionMs = 0, playing = true))
        remote.move(0, 1, 4)
        assertEquals(listOf("b", "c", "d", "e", "a"), remote.items)
        assertEquals("c", remote.current)
        remote.move(1, 2, 0)
        assertEquals(listOf("c", "b", "d", "e", "a"), remote.items)
        assertEquals("c", remote.current)
        remote.move(3, 5, 1)
        assertEquals(listOf("c", "e", "a", "b", "d"), remote.items)
        assertEquals("c", remote.current)
    }

    @Test
    fun aNewQueueWhileCastingStartsWhereAsked() {
        val remote = RemoteQueue(phone.copy(shuffle = false))
        remote.replaceAll(listOf("x", "y", "z"), start = 1) { n -> (n - 1 downTo 0).toList() }
        assertEquals("y", remote.current)
        remote.shuffle = true
        remote.replaceAll(listOf("x", "y", "z"), start = null) { n -> (n - 1 downTo 0).toList() }
        // With no start given, the first song in play order.
        assertEquals("z", remote.current)
    }

    @Test
    fun aSavedOrderThatDoesNotFitIsNotUsed() {
        val remote = RemoteQueue(phone.copy(order = listOf(0, 1)))
        assertEquals(listOf(0, 1, 2, 3, 4), remote.order)
    }

    @Test
    fun theWayBackFollowsTheSettingOnlyWhenCastingEndsByItself() {
        // Picking the phone carries on as things were.
        assertEquals(true, playsOnReturn(wasPlaying = true, chosen = true, keepPlaying = false))
        assertEquals(false, playsOnReturn(wasPlaying = false, chosen = true, keepPlaying = true))
        // The device went away: paused, unless the setting says carry on.
        assertEquals(false, playsOnReturn(wasPlaying = true, chosen = false, keepPlaying = false))
        assertEquals(true, playsOnReturn(wasPlaying = true, chosen = false, keepPlaying = true))
        assertEquals(false, playsOnReturn(wasPlaying = false, chosen = false, keepPlaying = true))
    }

    @Test
    fun anUndoPutsSongsBackExactlyWithTheirOrder() {
        val remote = RemoteQueue(QueueState(listOf("a", "c", "e"), index = 1, positionMs = 0, playing = true, shuffle = true, order = listOf(2, 0, 1)))
        remote.insertAt(1, listOf("b"))
        remote.insertAt(3, listOf("d"))
        remote.setOrder(listOf(4, 0, 1, 2, 3))
        assertEquals(listOf("a", "b", "c", "d", "e"), remote.items)
        assertEquals("c", remote.current)
        assertEquals(listOf(4, 0, 1, 2, 3), remote.order)
    }
}
