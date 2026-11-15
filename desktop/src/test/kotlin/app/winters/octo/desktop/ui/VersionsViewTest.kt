package app.winters.octo.desktop.ui

import app.winters.octo.subsonic.StreamVersion
import app.winters.octo.subsonic.StreamVersionPlaying
import app.winters.octo.subsonic.StreamVersions
import app.winters.octo.subsonic.STREAM_VERSION_AUTO
import app.winters.octo.versions.StreamVersionWords
import app.winters.octo.versions.StreamVersionsEvent
import app.winters.octo.versions.StreamVersionsSheetState
import app.winters.octo.versions.VersionsSong
import app.winters.octo.versions.on
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// The dialog as the shared states draw it: looking, the list with the pick
// playing and the way back, one pick at a time and only for the song
// playing, could not reach with Try again, a refused way back with the
// server's words, a download mode with no rows, and Octo's own pick with
// nothing to undo.
class VersionsViewTest {
    private val song = VersionsSong(entryId = "7", songId = "X", title = "Song", artist = "Artist")

    private val answer = StreamVersions(
        id = "X",
        playsFrom = "stream",
        playing = StreamVersionPlaying(videoId = "v1", title = "Song", channel = "Artist - Topic", duration = 215, kind = "release", picked = "person", mine = true),
        heardVideoId = "v0",
        version = listOf(StreamVersion("v2", "Song (Live)", "Artist", 240, "video"), StreamVersion("v3", "Song (Remix)", "DJ", 250, "video")),
        missed = listOf("youtubeMusic"),
        ids = listOf("X", "Y"),
    )

    private val start: StreamVersionsSheetState = StreamVersionsSheetState.Closed.on(StreamVersionsEvent.Open(song))

    private fun shown(of: StreamVersions = answer) = start.on(StreamVersionsEvent.Listed(of))

    @Test
    fun lookingThenTheListWithThePickToUndo() {
        assertEquals(VersionsView(line = StreamVersionWords.LOOKING, busy = true), viewOf(start))
        val view = viewOf(shown())
        assertEquals("v1", view.playing?.videoId)
        assertEquals(StreamVersionWords.playingNote(answer.playing!!, "v0"), view.playingNote)
        assertEquals(listOf("v2", "v3"), view.versions.map { it.videoId })
        assertTrue(view.listed)
        assertTrue(view.undo)
        assertEquals(StreamVersionWords.notice(answer), view.line)
        assertNull(view.empty)
    }

    // Red when: the dialog draws a pick the state refused, or picks for a
    // song no longer playing.
    @Test
    fun onePickAtATimeAndOnlyForTheSongPlaying() {
        val listed = shown()
        assertSame(listed, listed.on(StreamVersionsEvent.Pick("v2", "8", "X")))
        val checking = listed.on(StreamVersionsEvent.Pick("v2", "7", "X"))
        assertEquals("v2", viewOf(checking).checking)
        assertSame(checking, checking.on(StreamVersionsEvent.Pick("v3", "7", "X")))
    }

    @Test
    fun couldNotReachOffersTryAgain() {
        val view = viewOf(start.on(StreamVersionsEvent.ListFailed(null)))
        assertEquals(StreamVersionWords.SERVER_UNREACHABLE, view.line)
        assertTrue(view.retry)
    }

    @Test
    fun aRefusedWayBackSaysTheServersWords() {
        val refused = shown().on(StreamVersionsEvent.Undo("7", "X")).on(StreamVersionsEvent.PickFailed("Could not reach YouTube to find Octo's pick."))
        val view = viewOf(refused)
        assertEquals(STREAM_VERSION_AUTO, view.refused)
        assertEquals("Could not reach YouTube to find Octo's pick.", view.refusal)
        assertNull(view.checking)
    }

    @Test
    fun aDownloadModeShowsItsNoticeAndNoRows() {
        val view = viewOf(shown(answer.copy(playsFrom = "download", playing = null, version = emptyList(), missed = emptyList())))
        assertEquals(StreamVersionWords.PLAYS_FROM_DOWNLOAD_WORDS, view.line)
        assertFalse(view.listed)
        assertTrue(view.versions.isEmpty())
        assertFalse(view.undo)
    }

    @Test
    fun octosOwnPickHasNothingToUndo() {
        val octo = answer.copy(playing = answer.playing!!.copy(picked = "octo", mine = false), heardVideoId = null, missed = emptyList(), version = emptyList())
        val view = viewOf(shown(octo))
        assertFalse(view.undo)
        assertEquals(StreamVersionWords.OCTOS_PICK, view.playingNote)
        assertEquals(StreamVersionWords.NOTHING, view.empty)
        assertNull(view.line)
    }
}
