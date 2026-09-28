package app.winters.octo.desktop.player

import app.winters.octo.playback.NoSource
import app.winters.octo.playback.QueueSource
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// What every DesktopPlayer must do, whatever makes the sound. The silent
// placeholder passes these now; the audio engine's player must pass them
// too before it takes its place. A subclass gives a fresh player and a way
// to let playing time pass.
abstract class DesktopPlayerContract {
    abstract fun newPlayer(): DesktopPlayer

    // Lets `ms` of playing time pass, and the player notice songs ending.
    abstract fun elapse(player: DesktopPlayer, ms: Long)

    // Five songs of 100 seconds each.
    protected val songs = (1..5).map { Song("s$it", "Song $it", artist = "Artist", duration = 100) }
    private val extra = listOf(Song("x", "Extra X", duration = 100), Song("y", "Extra Y", duration = 100))

    private fun DesktopPlayer.now() = state.value.current?.song?.id
    private fun DesktopPlayer.coming() = state.value.upcoming.map { it.song.id }

    // Positions are allowed a little slack for a clock that really runs.
    private fun assertNear(expected: Long, actual: Long) = assertTrue("expected about $expected, was $actual", kotlin.math.abs(expected - actual) <= 150)

    @Test
    fun playStartsAtTheChosenSong() {
        val p = newPlayer()
        p.play(songs, 2)
        assertEquals("s3", p.now())
        assertTrue(p.state.value.playing)
        assertEquals(listOf("s4", "s5"), p.coming())
        assertEquals(100_000L, p.state.value.durationMs)
        assertNear(0, p.positionMs())
    }

    @Test
    fun aRestoredQueueWaitsWhereItWasLeft() {
        val p = newPlayer()
        p.restore(SavedQueue(songs, songs.indices.toList(), index = 1, positionMs = 30_000, repeat = RepeatMode.All))
        assertEquals("s2", p.now())
        assertFalse(p.state.value.playing)
        assertEquals(RepeatMode.All, p.state.value.repeat)
        assertEquals(listOf("s1"), p.state.value.played.map { it.song.id })
        assertEquals(listOf("s3", "s4", "s5"), p.coming())
        assertNear(30_000, p.positionMs())
    }

    @Test
    fun timeMovesOnlyWhilePlaying() {
        val p = newPlayer()
        p.play(songs)
        elapse(p, 1_500)
        assertNear(1_500, p.positionMs())
        p.pause()
        assertFalse(p.state.value.playing)
        elapse(p, 1_000)
        assertNear(1_500, p.positionMs())
        p.togglePlay()
        elapse(p, 500)
        assertNear(2_000, p.positionMs())
    }

    @Test
    fun seekingStaysInsideTheSong() {
        val p = newPlayer()
        p.play(songs)
        p.seekTo(-5)
        assertNear(0, p.positionMs())
        p.seekTo(10_000_000)
        assertNear(100_000, p.positionMs())
        p.seekTo(42_000)
        assertNear(42_000, p.positionMs())
    }

    @Test
    fun previousRestartsASongUnderWayAndGoesBackFromItsStart() {
        val p = newPlayer()
        p.play(songs, 2)
        p.next()
        assertEquals("s4", p.now())
        p.previous()
        assertEquals("s3", p.now())
        elapse(p, RESTART_AFTER_MS + 1_000)
        p.previous()
        assertEquals("s3", p.now())
        assertNear(0, p.positionMs())
    }

    @Test
    fun nextKeepsAPausedPlayerPaused() {
        val p = newPlayer()
        p.play(songs)
        p.pause()
        p.next()
        assertEquals("s2", p.now())
        assertFalse(p.state.value.playing)
    }

    @Test
    fun aSongThatEndsGivesWayToTheNext() {
        val p = newPlayer()
        p.play(songs)
        elapse(p, 100_000 + 700)
        assertEquals("s2", p.now())
        assertNear(700, p.positionMs())
    }

    @Test
    fun theEndOfTheQueueStops() {
        val p = newPlayer()
        p.play(songs, 4)
        elapse(p, 100_500)
        assertEquals("s5", p.now())
        assertFalse(p.state.value.playing)
        p.next()
        assertEquals("next at the end does nothing", "s5", p.now())
    }

    @Test
    fun repeatAllGoesRound() {
        val p = newPlayer()
        p.setRepeat(RepeatMode.All)
        p.play(songs, 4)
        elapse(p, 100_300)
        assertEquals("s1", p.now())
        assertTrue(p.state.value.playing)
        assertEquals(RepeatMode.All, p.state.value.repeat)
    }

    @Test
    fun repeatOnePlaysTheSongAgain() {
        val p = newPlayer()
        p.setRepeat(RepeatMode.One)
        p.play(songs, 1)
        elapse(p, 100_400)
        assertEquals("s2", p.now())
        assertNear(400, p.positionMs())
        p.next()
        assertEquals("next still moves on", "s3", p.now())
    }

    @Test
    fun playNextPutsSongsRightAfterTheCurrentOne() {
        val p = newPlayer()
        p.play(songs)
        p.playNext(extra)
        assertEquals(listOf("x", "y", "s2", "s3", "s4", "s5"), p.coming())
        assertEquals("s1", p.now())
    }

    @Test
    fun playNextPlaysNextWhenShuffledToo() {
        val p = newPlayer()
        p.play(songs, 0, shuffle = true)
        p.playNext(extra)
        assertEquals(listOf("x", "y"), p.coming().take(2))
        p.next()
        assertEquals("x", p.now())
    }

    @Test
    fun addToQueueGoesAfterEverythingStillToCome() {
        val p = newPlayer()
        p.play(songs, 0, shuffle = true)
        p.addToQueue(extra)
        assertEquals(listOf("x", "y"), p.coming().takeLast(2))
        assertEquals(6, p.coming().size)
    }

    @Test
    fun shufflePlaysTheChosenSongFirstAndEveryOtherOnce() {
        val p = newPlayer()
        p.play(songs, 3, shuffle = true)
        assertEquals("s4", p.now())
        assertTrue(p.state.value.shuffle)
        assertEquals(setOf("s1", "s2", "s3", "s5"), p.coming().toSet())
        assertEquals(4, p.coming().size)
    }

    @Test
    fun turningShuffleOffGoesBackToQueueOrder() {
        val p = newPlayer()
        p.play(songs, 1, shuffle = true)
        p.setShuffle(false)
        assertEquals("s2", p.now())
        assertEquals(listOf("s3", "s4", "s5"), p.coming())
        p.setShuffle(true)
        assertEquals("s2", p.now())
        assertEquals(4, p.coming().size)
    }

    @Test
    fun removingASongToComeLeavesTheRest() {
        val p = newPlayer()
        p.play(songs)
        p.remove(p.state.value.upcoming[1].key)
        assertEquals(listOf("s2", "s4", "s5"), p.coming())
        assertEquals("s1", p.now())
    }

    @Test
    fun removingTheCurrentSongMovesToTheNext() {
        val p = newPlayer()
        p.play(songs, 1)
        p.remove(p.state.value.current!!.key)
        assertEquals("s3", p.now())
        assertEquals(4, p.state.value.queue.size)
    }

    @Test
    fun songsToComeCanBeReordered() {
        val p = newPlayer()
        p.play(songs)
        p.moveUpcoming(3, 0)
        assertEquals(listOf("s5", "s2", "s3", "s4"), p.coming())
        p.moveUpcoming(0, 2)
        assertEquals(listOf("s2", "s3", "s5", "s4"), p.coming())
        assertEquals("s1", p.now())
    }

    @Test
    fun reorderingWhileShuffledChangesOnlyThePlayOrder() {
        val p = newPlayer()
        p.play(songs, 0, shuffle = true)
        val before = p.coming()
        p.moveUpcoming(0, 3)
        assertEquals(before.drop(1).take(3) + before.first(), p.coming())
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), p.state.value.queue.map { it.song.id })
    }

    @Test
    fun skippingToAnEntryPlaysIt() {
        val p = newPlayer()
        p.play(songs)
        p.pause()
        p.skipTo(p.state.value.upcoming[2].key)
        assertEquals("s4", p.now())
        assertTrue(p.state.value.playing)
    }

    @Test
    fun twoCopiesOfASongAreTwoEntries() {
        val p = newPlayer()
        p.play(listOf(songs[0], songs[0], songs[1]))
        val keys = p.state.value.queue.map { it.key }
        assertEquals(3, keys.toSet().size)
        p.remove(p.state.value.upcoming.first().key)
        assertEquals(listOf("s1", "s2"), p.state.value.queue.map { it.song.id })
    }

    @Test
    fun volumeStaysBetweenNothingAndFull() {
        val p = newPlayer()
        p.setVolume(1.7f)
        assertEquals(1f, p.state.value.volume)
        p.setVolume(-1f)
        assertEquals(0f, p.state.value.volume)
    }

    @Test
    fun clearingStopsEverything() {
        val p = newPlayer()
        p.play(songs)
        p.clear()
        assertNull(p.now())
        assertFalse(p.state.value.playing)
        assertTrue(p.state.value.queue.isEmpty())
    }

    @Test
    fun anEmptyPlayerIgnoresTheControls() {
        val p = newPlayer()
        p.resume()
        p.next()
        p.previous()
        p.seekTo(1_000)
        assertFalse(p.state.value.playing)
        assertNull(p.now())
    }

    @Test
    fun songsAddedToAnEmptyQueueWaitToBePlayed() {
        val p = newPlayer()
        p.addToQueue(extra)
        assertEquals("x", p.now())
        assertFalse(p.state.value.playing)
        p.resume()
        assertTrue(p.state.value.playing)
    }

    @Test
    fun thereIsAlwaysAnOutputToShow() {
        val p = newPlayer()
        assertNotEquals(null, p.state.value.output)
        assertTrue(p.state.value.outputs.isNotEmpty())
    }

    @Test
    fun theFadeLeavesTheVolumeAlone() {
        val p = newPlayer()
        p.setVolume(0.6f)
        p.play(songs)
        p.setFade(0.25f)
        assertEquals(0.6f, p.state.value.volume)
        assertEquals(0.25f, p.state.value.fade)
        p.setFade(7f)
        assertEquals(1f, p.state.value.fade)
        assertEquals(0.6f, p.state.value.volume)
    }

    @Test
    fun onlySongsThatPlayOutCountAsEnded() {
        val p = newPlayer()
        p.play(songs)
        p.next()
        assertEquals(0, p.state.value.ended)
        elapse(p, 100_300)
        assertEquals("s3", p.now())
        assertEquals(1, p.state.value.ended)
    }

    // ---- Where songs came from, and taking edits back ----

    private val album = QueueSource.Played("OK Computer")
    private val you = QueueSource.You

    private fun DesktopPlayer.keyOf(id: String) = state.value.queue.first { it.song.id == id }.key
    private fun DesktopPlayer.comingFrom() = state.value.upcoming.map { it.song.id to it.source }

    @Test
    fun songsKnowWhereTheyCameFrom() {
        val p = newPlayer()
        p.play(songs, 0, source = album)
        p.playNext(extra.take(1))
        p.addToQueue(extra.drop(1))
        assertEquals(album, p.state.value.current?.source)
        assertEquals(
            listOf("x" to you, "s2" to album, "s3" to album, "s4" to album, "s5" to album, "y" to you),
            p.comingFrom(),
        )
    }

    @Test
    fun songsARadioOrAutoplayAddAreNotTheListeners() {
        val p = newPlayer()
        val radio = QueueSource.Played("Song 1 radio")
        p.play(songs.take(1), source = radio)
        p.addToQueue(extra.take(1), radio)
        p.addToQueue(extra.drop(1), QueueSource.Autoplay)
        assertEquals(listOf("x" to radio, "y" to QueueSource.Autoplay), p.comingFrom())
        // Nothing the listener did to take back.
        assertFalse(p.state.value.canUndo)
        assertFalse(p.undo())
    }

    @Test
    fun aPlainPlayHasNoSourceToName() {
        val p = newPlayer()
        p.play(songs)
        assertEquals(NoSource, p.state.value.current?.source)
    }

    @Test
    fun takingSongsOutCanBeUndone() {
        val p = newPlayer()
        p.play(songs, source = album)
        p.remove(listOf(p.keyOf("s2"), p.keyOf("s4")))
        assertEquals(listOf("s3", "s5"), p.coming())
        assertTrue(p.state.value.canUndo)
        assertTrue(p.undo())
        assertEquals(listOf("s2", "s3", "s4", "s5"), p.coming())
        assertEquals(album, p.state.value.upcoming.first().source)
        assertFalse(p.state.value.canUndo)
        assertFalse("nothing more to take back", p.undo())
    }

    @Test
    fun clearingWhatIsToComeKeepsTheSongPlaying() {
        val p = newPlayer()
        p.play(songs, 1)
        p.clearUpcoming()
        assertEquals("s2", p.now())
        assertTrue(p.state.value.playing)
        assertEquals(emptyList<String>(), p.coming())
        assertEquals(listOf("s1"), p.state.value.played.map { it.song.id })
        assertTrue(p.undo())
        assertEquals("s2", p.now())
        assertEquals(listOf("s3", "s4", "s5"), p.coming())
    }

    @Test
    fun theSongsPlayedCanBeTakenOutAndPutBack() {
        val p = newPlayer()
        p.play(songs, 2)
        p.removePlayed()
        assertEquals(emptyList<QueueEntry>(), p.state.value.played)
        assertEquals("s3", p.now())
        assertTrue(p.undo())
        assertEquals(listOf("s1", "s2"), p.state.value.played.map { it.song.id })
        assertEquals("s3", p.now())
    }

    @Test
    fun editsComeBackMostRecentFirst() {
        val p = newPlayer()
        p.play(songs)
        p.playNext(extra.take(1))
        p.move(listOf(p.keyOf("s5")), before = p.keyOf("s2"))
        assertEquals(listOf("x", "s5", "s2", "s3", "s4"), p.coming())
        assertTrue(p.undo())
        assertEquals(listOf("x", "s2", "s3", "s4", "s5"), p.coming())
        assertTrue(p.undo())
        assertEquals(listOf("s2", "s3", "s4", "s5"), p.coming())
        assertFalse(p.undo())
        assertEquals("s1", p.now())
    }

    @Test
    fun anUndoNeverActsOnAQueueChangedSince() {
        val p = newPlayer()
        p.play(songs)
        p.remove(p.keyOf("s3"))
        p.setShuffle(true)
        assertFalse(p.state.value.canUndo)
        assertFalse(p.undo())
        assertEquals(3, p.coming().size)
        // A new queue starts with nothing to take back.
        p.remove(p.keyOf("s2"))
        p.play(songs)
        assertFalse(p.undo())
    }

    @Test
    fun takingOutTheSongPlayingAndUndoingKeepsTheNextPlaying() {
        val p = newPlayer()
        p.play(songs, 1)
        p.remove(p.state.value.current!!.key)
        assertEquals("s3", p.now())
        assertTrue(p.undo())
        assertEquals("the song playing now plays on", "s3", p.now())
        assertEquals(listOf("s1", "s2"), p.state.value.played.map { it.song.id })
        assertEquals(listOf("s4", "s5"), p.coming())
    }

    @Test
    fun aMovedSongJoinsTheRunItLandsIn() {
        val p = newPlayer()
        p.play(songs, source = album)
        p.playNext(extra)
        // Dragged in among the listener's own, it becomes theirs.
        p.move(listOf(p.keyOf("s4")), before = p.keyOf("y"))
        assertEquals(listOf("x" to you, "s4" to you, "y" to you, "s2" to album, "s3" to album, "s5" to album), p.comingFrom())
        // And one of theirs dragged in among the album's joins the album.
        p.move(listOf(p.keyOf("x")), before = p.keyOf("s3"))
        assertEquals(listOf("s4" to you, "y" to you, "s2" to album, "x" to album, "s3" to album, "s5" to album), p.comingFrom())
        // At the edge between the two, a song keeps its own.
        p.move(listOf(p.keyOf("s5")), before = p.keyOf("s2"))
        assertEquals("s5" to album, p.comingFrom()[2])
    }

    @Test
    fun pickedSongsMoveTogetherAndInTheirOrder() {
        val p = newPlayer()
        p.play(songs)
        p.move(listOf(p.keyOf("s5"), p.keyOf("s3")), before = null)
        assertEquals(listOf("s2", "s4", "s3", "s5"), p.coming())
        p.move(listOf(p.keyOf("s3"), p.keyOf("s5")), before = p.keyOf("s2"))
        assertEquals(listOf("s3", "s5", "s2", "s4"), p.coming())
        // The song playing never moves, and nothing goes before it.
        p.move(listOf(p.keyOf("s1")), before = null)
        assertEquals("s1", p.now())
        assertEquals(listOf("s3", "s5", "s2", "s4"), p.coming())
    }

    @Test
    fun aSongAlreadyPlayedCanComeBackNext() {
        val p = newPlayer()
        p.play(songs, 2)
        p.move(listOf(p.keyOf("s1")), before = p.state.value.upcoming.first().key)
        assertEquals("s3", p.now())
        assertEquals(listOf("s1", "s4", "s5"), p.coming())
        assertEquals(listOf("s2"), p.state.value.played.map { it.song.id })
    }

    @Test
    fun movesWhileShuffledChangeOnlyThePlayOrder() {
        val p = newPlayer()
        p.play(songs, 0, shuffle = true)
        val before = p.coming()
        p.move(listOf(p.keyOf(before.last())), before = p.keyOf(before.first()))
        assertEquals(listOf(before.last()) + before.dropLast(1), p.coming())
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), p.state.value.queue.map { it.song.id })
        assertTrue(p.undo())
        assertEquals(before, p.coming())
    }

    @Test
    fun songsDroppedInLandWhereTheyWereDroppedAsTheListeners() {
        val p = newPlayer()
        p.play(songs, source = album)
        p.insert(extra, before = p.keyOf("s4"))
        assertEquals(listOf("s2", "s3", "x", "y", "s4", "s5"), p.coming())
        assertEquals(you, p.comingFrom()[2].second)
        p.insert(listOf(Song("z", "Z", duration = 100)), before = null)
        assertEquals("z", p.coming().last())
        assertTrue(p.undo())
        assertTrue(p.undo())
        assertEquals(listOf("s2", "s3", "s4", "s5"), p.coming())
    }

    @Test
    fun songsDroppedInWhileShuffledPlayWhereTheyWereDropped() {
        val p = newPlayer()
        p.play(songs, 0, shuffle = true)
        val before = p.coming()
        p.insert(extra, before = p.keyOf(before[1]))
        assertEquals(listOf(before[0], "x", "y") + before.drop(1), p.coming())
    }

    @Test
    fun songsDroppedIntoAnEmptyQueueWaitToBePlayed() {
        val p = newPlayer()
        p.insert(extra, before = null)
        assertEquals("x", p.now())
        assertFalse(p.state.value.playing)
        // Taking that back empties the queue again.
        assertTrue(p.undo())
        assertNull(p.now())
    }

    @Test
    fun aSavedQueueKeepsWhereItsSongsCameFrom() {
        val p = newPlayer()
        p.restore(SavedQueue(songs, songs.indices.toList(), index = 0, sources = listOf(album, you, you, album, album)))
        assertEquals(listOf("s2" to you, "s3" to you, "s4" to album, "s5" to album), p.comingFrom())
    }
}
