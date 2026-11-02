package app.winters.octo.ui.imports

import app.winters.octo.subsonic.ImportListSummary
import app.winters.octo.subsonic.ImportOverview
import app.winters.octo.subsonic.ImportTrack
import app.winters.octo.subsonic.ImportTrackState
import app.winters.octo.subsonic.TrickleStatus
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// What both apps say about an import.
class ImportWordsTest {
    // Expected words with "~" where a no-break space goes.
    private fun nb(text: String) = text.replace('~', Char(0xA0))

    @Test
    fun aListSaysWhereItIsFromAndWhatTheLibraryHas() {
        val list = ImportListSummary(source = "link", by = "Spotify", total = 10, have = 6, missing = 2, queued = 1, downloading = 1, notFound = 1)
        assertEquals("Link · by Spotify", list.originLine())
        assertEquals(nb("6~of~10 · 2~missing · 2~coming · 1~not~found"), list.countsLine())
        assertEquals("File", ImportListSummary(source = "file").originLine())
        assertEquals("", ImportListSummary(source = "textList").originLine())
        assertEquals("Fetched", ImportTrackState.Done.label())
        assertEquals("Waiting", ImportTrackState.Queued.label())
    }

    @Test
    fun aListFromASpotifySignInSaysItIsNotUpdating_AndIsNotReadAgain() {
        val playlist = ImportListSummary(source = "spotifyPlaylist", by = "Spotify", canRefresh = true)
        assertEquals("Spotify · by Spotify · not updating", playlist.originLine())
        assertFalse(playlist.readsAgain)
        val liked = ImportListSummary(source = "spotifyLiked", canRefresh = true)
        assertEquals("Spotify · not updating", liked.originLine())
        assertFalse(liked.readsAgain)
        assertTrue(ImportListSummary(source = "link", canRefresh = true).readsAgain)
        assertFalse(ImportListSummary(source = "link", canRefresh = false).readsAgain)
    }

    @Test
    fun removingAListSaysWhatGoesAndWhatStays() {
        assertEquals("Octo forgets Road trip. Songs already in your library stay.", ImportListSummary(name = "Road trip").removeLine())
        assertEquals(
            "Octo forgets Road trip and stops getting its missing songs. Songs already in your library stay, and so does its playlist.",
            ImportListSummary(name = "Road trip", getMissing = true, playlistId = "pl-1").removeLine(),
        )
    }

    @Test
    fun theTrickleSaysWhatItWaitsFor() {
        val running = TrickleStatus(state = "running", perHour = 20, queued = 3, nextUtc = "2026-10-04T14:05:00Z", done = 2)
        assertEquals("Waiting for the next turn", running.title())
        assertEquals(nb("Up to 20~songs an hour. Next song at 14:05. 3~waiting,~2~fetched."), running.line(ZoneOffset.UTC))
        assertEquals("Waiting for Soulseek", TrickleStatus(state = "waitingForSoulseek").title())
        val held = TrickleStatus(state = "waitingForMusicDatabase", perHour = 20, queued = 3)
        assertEquals("Waiting for MusicBrainz", held.title())
        assertEquals(nb("It goes on once MusicBrainz is back. Up to 20~songs an hour. 3~waiting."), held.line(ZoneOffset.UTC))
        assertEquals("Songs an hour is 0 on the server, so nothing starts.", TrickleStatus(state = "off").line(ZoneOffset.UTC))
        assertEquals("Nothing to fetch", TrickleStatus(state = "idle").title())
        assertEquals(nb("Someone's own downloads come first. Up to 20~songs an hour. 1~waiting."), TrickleStatus(state = "yielding", perHour = 20, queued = 1).line(ZoneOffset.UTC))
        assertEquals(nb("It goes on once Soulseek is back. Up to 20~songs an hour. 1~waiting."), TrickleStatus(state = "waitingForSoulseek", perHour = 20, queued = 1).line(ZoneOffset.UTC))
    }

    @Test
    fun theTrickleSaysItWaitsForMornyesCheck() {
        val held = TrickleStatus(state = "waitingForMornyeCheck", perHour = 20, queued = 3)
        assertEquals("Waiting for Mornye's check", held.title())
        assertEquals(nb("It goes on when Mornye may try its check again, or once it is passed on the dashboard. Up to 20~songs an hour. 3~waiting."), held.line(ZoneOffset.UTC))
    }

    @Test
    fun theTrickleSaysItWaitsForAMornyeThatDoesNotAnswer() {
        val held = TrickleStatus(state = "waitingForMornye", perHour = 20, queued = 3)
        assertEquals("Waiting for Mornye", held.title())
        assertEquals(nb("It goes on once Mornye can download. Up to 20~songs an hour. 3~waiting."), held.line(ZoneOffset.UTC))
    }

    @Test
    fun aStateThisAppDoesNotKnowNeverSaysNothingToFetch() {
        assertEquals("Working on it", TrickleStatus(state = "somethingNew", queued = 2).title())
        assertEquals("Working on it", TrickleStatus(state = "somethingNew", downloading = 1).title())
        assertEquals("Tidying up", TrickleStatus(state = "somethingNew", label = "Tidying up", queued = 2).title())
        assertEquals("Tidying up", TrickleStatus(state = "somethingNew", label = "Tidying up").title())
        assertEquals("Working on it", TrickleStatus(state = "somethingNew", label = " ", queued = 2).title())
        assertEquals("Nothing to fetch", TrickleStatus(state = "somethingNew").title())
        assertEquals(nb("Up to 1~song an hour. 1~not~found."), TrickleStatus(state = "running", perHour = 1, notFound = 1).line(ZoneOffset.UTC))
        assertEquals(nb("Up to 20~songs an hour. 2~waiting."), TrickleStatus(state = "somethingNew", perHour = 20, queued = 2).line(ZoneOffset.UTC))
    }

    @Test
    fun songsOnTheirWayMeanAskingAgainSoon() {
        assertFalse(ImportOverview().moving())
        assertTrue(ImportOverview(trickle = TrickleStatus(queued = 1)).moving())
        assertTrue(ImportOverview(trickle = TrickleStatus(downloading = 1)).moving())
    }

    @Test
    fun heldRowsLeaveTheHeadingsWordsOut() {
        val roads = ImportTrack(key = "s:3", title = "Roads", artist = "Portishead", state = "queued", detail = "Waiting for MusicBrainz to answer")
        assertEquals(listOf(null), TrickleStatus(state = "waitingForMusicDatabase", next = listOf(roads)).shownNext().map { it.detail })
        assertEquals(
            "some held while others fetch: the row says which",
            listOf("Waiting for MusicBrainz to answer"),
            TrickleStatus(state = "running", next = listOf(roads)).shownNext().map { it.detail },
        )
    }
}
