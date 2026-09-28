package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueSourcesTest {
    private val album = QueueSource.Played("OK Computer")
    private val you = QueueSource.You

    @Test
    fun headingsSayWhereSongsCameFrom() {
        assertEquals("Next from you", queueSourceTitle(you))
        assertEquals("Next from OK Computer", queueSourceTitle(album))
        assertEquals("Up next", queueSourceTitle(NoSource))
        assertEquals("Next from Autoplay", queueSourceTitle(QueueSource.Autoplay))
    }

    @Test
    fun songsToComeAreCutIntoRunsInPlayOrder() {
        val songs = listOf("x" to you, "y" to you, "a" to album, "b" to album, "z" to you)
        val runs = sourceRuns(songs) { it.second }.map { (source, items) -> source to items.map { it.first } }
        assertEquals(listOf(you to listOf("x", "y"), album to listOf("a", "b"), you to listOf("z")), runs)
        assertEquals(emptyList<Any>(), sourceRuns(emptyList<String>()) { you })
    }

    @Test
    fun aMovedSongJoinsTheRunItLandsIn() {
        // In among the listener's own, it becomes theirs; among the album's, the album's.
        assertEquals(you, movedSource(album, you, you))
        assertEquals(album, movedSource(you, album, album))
        // At the top or the bottom, it joins its one neighbour.
        assertEquals(you, movedSource(album, null, you))
        assertEquals(album, movedSource(you, album, null))
        // At the edge between two runs, it keeps its own.
        assertEquals(album, movedSource(album, you, album))
        assertEquals(you, movedSource(you, you, album))
        assertEquals(album, movedSource(album, null, null))
    }

    @Test
    fun aSongNeverBecomesAutoplaysByMoving() {
        assertEquals(you, movedSource(you, QueueSource.Autoplay, null))
        assertEquals(album, movedSource(album, QueueSource.Autoplay, QueueSource.Autoplay))
        // Autoplay's own song dragged in among the listener's becomes theirs.
        assertEquals(you, movedSource(QueueSource.Autoplay, you, you))
    }

    @Test
    fun sourcesSurviveBeingSaved() {
        listOf(you, album, NoSource, QueueSource.Autoplay, QueueSource.Played("Radio: a:b")).forEach {
            assertEquals(it, queueSourceOf(it.encoded()))
        }
        assertEquals(NoSource, queueSourceOf(null))
        assertEquals(NoSource, queueSourceOf("something new"))
    }
}
