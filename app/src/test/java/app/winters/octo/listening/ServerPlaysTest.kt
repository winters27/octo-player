package app.winters.octo.listening

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.PlayedAlbum
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.ServerPlayedTrack
import app.winters.octo.catalog.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPlaysTest {
    private fun track(id: String) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    private fun album(id: String) = AlbumEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", year = null, songCount = 1, durationMs = 0, addedAt = 0, artwork = null,
    )

    private fun local(id: String, plays: Int, at: Long) = PlayedTrack(track(id), plays, at)

    private fun server(id: String, plays: Int, at: Long?, serverId: String = "s$id") =
        ServerPlayedTrack(track(id), serverId, plays, at)

    private fun List<PlayedTrack>.of(id: String) = single { it.track.id == id }

    @Test
    fun countsAddUpAndTheLaterPlayWins() {
        val combined = withServerPlays(listOf(local("a", 3, 500)), listOf(server("a", 4, 900)), emptyMap())
        assertEquals(7, combined.of("a").plays)
        assertEquals(900, combined.of("a").lastPlayedAt)
    }

    @Test
    fun aLaterPlayOnThePhoneWins() {
        val combined = withServerPlays(listOf(local("a", 1, 2_000)), listOf(server("a", 1, 900)), emptyMap())
        assertEquals(2_000, combined.of("a").lastPlayedAt)
    }

    @Test
    fun songsPlayedOnlyOnTheServerJoin() {
        val combined = withServerPlays(listOf(local("a", 1, 100)), listOf(server("b", 5, 300)), emptyMap())
        assertEquals(listOf("a", "b"), combined.map { it.track.id })
        assertEquals(5, combined.of("b").plays)
    }

    @Test
    fun songsPlayedOnlyOnThePhoneStayAsTheyAre() {
        val combined = withServerPlays(listOf(local("a", 2, 100)), emptyList(), emptyMap())
        assertEquals(listOf(local("a", 2, 100)), combined)
    }

    @Test
    fun octosOwnPlaysAreNotCountedTwice() {
        // Played twice on the phone and sent both; the server counts those two
        // and three from elsewhere.
        val sent = emptyMap<String, SentPlays>().plusSent("sa", 1_000).plusSent("sa", 2_000)
        val combined = withServerPlays(listOf(local("a", 2, 2_000)), listOf(server("a", 5, 2_000)), sent)
        assertEquals(5, combined.of("a").plays)
    }

    @Test
    fun aPlaySentAfterTheServerWasReadIsNotTakenOff() {
        // The server's record was read before the play at 3000 reached it.
        val sent = emptyMap<String, SentPlays>().plusSent("sa", 1_000).plusSent("sa", 3_000)
        val combined = withServerPlays(listOf(local("a", 2, 3_000)), listOf(server("a", 4, 1_000)), sent)
        // Two here, and three of the server's four are from elsewhere.
        assertEquals(5, combined.of("a").plays)
    }

    @Test
    fun serverTimesInWholeSecondsStillMatch() {
        val sent = emptyMap<String, SentPlays>().plusSent("sa", 1_000_450)
        val combined = withServerPlays(listOf(local("a", 1, 1_000_450)), listOf(server("a", 1, 1_000_000)), sent)
        assertEquals(1, combined.of("a").plays)
    }

    @Test
    fun aServerThatIgnoredOctosPlaysLosesNothing() {
        // Sent, but the server never counted it, so its last play stayed older.
        val sent = emptyMap<String, SentPlays>().plusSent("sa", 5_000)
        val combined = withServerPlays(listOf(local("a", 1, 5_000)), listOf(server("a", 3, 1_000)), sent)
        assertEquals(4, combined.of("a").plays)
    }

    @Test
    fun theServersCountNeverGoesBelowZero() {
        val sent = mapOf("sa" to SentPlays(folded = 9))
        val combined = withServerPlays(listOf(local("a", 2, 100)), listOf(server("a", 3, 100)), sent)
        assertEquals(2, combined.of("a").plays)
    }

    @Test
    fun aSongWhosePlaysAreAllOctosOwnAndGoneLocallyDropsOut() {
        val sent = mapOf("sb" to SentPlays(folded = 2))
        val combined = withServerPlays(emptyList(), listOf(server("b", 2, 100)), sent)
        assertTrue(combined.isEmpty())
    }

    @Test
    fun twoServerCopiesOfOneSongBothCount() {
        val combined = withServerPlays(
            listOf(local("a", 1, 100)),
            listOf(server("a", 2, 300, serverId = "sa1"), server("a", 3, 200, serverId = "sa2")),
            emptyMap(),
        )
        assertEquals(6, combined.of("a").plays)
        assertEquals(300, combined.of("a").lastPlayedAt)
    }

    @Test
    fun aServerPlayWithoutATimeStillCounts() {
        val combined = withServerPlays(emptyList(), listOf(server("a", 2, null)), emptyMap())
        assertEquals(2, combined.of("a").plays)
        assertEquals(0, combined.of("a").lastPlayedAt)
    }

    @Test
    fun foldingKeepsTheSameCountAndForgetsGoneSongs() {
        val sent = emptyMap<String, SentPlays>()
            .plusSent("sa", 1_000).plusSent("sa", 3_000)
            .plusSent("gone", 1_000)
        val folded = sent.foldedInto(mapOf("sa" to 2_000L))
        assertEquals(mapOf("sa" to SentPlays(folded = 1, recent = listOf(3_000))), folded)
        assertEquals(sent["sa"].heldBy(2_000), folded["sa"].heldBy(2_000))
        // Once the server has the newer play too, it is counted as held.
        assertEquals(2, folded["sa"].heldBy(3_000))
    }

    @Test
    fun aSongWithNoServerPlayTimeFoldsNothing() {
        val sent = emptyMap<String, SentPlays>().plusSent("sa", 1_000)
        assertEquals(SentPlays(0, listOf(1_000)), sent.foldedInto(mapOf("sa" to null))["sa"])
    }

    @Test
    fun sentPlaysRoundTrip() {
        val sent = mapOf(
            "sa" to SentPlays(3, listOf(1_000, 2_000)),
            "sb" to SentPlays(0, listOf(5)),
            "odd id with spaces" to SentPlays(7, emptyList()),
        )
        assertEquals(sent, decodeSent(encodeSent(sent)))
    }

    @Test
    fun brokenSentLinesAreSkipped() {
        assertEquals(emptyMap<String, SentPlays>(), decodeSent(setOf("", "x 1 sa", "2 1")))
    }

    @Test
    fun albumsTakeTheLaterLastPlay() {
        val combined = withServerAlbumPlays(
            local = listOf(PlayedAlbum(album("x"), 500), PlayedAlbum(album("y"), 100)),
            server = listOf(PlayedAlbum(album("x"), 900), PlayedAlbum(album("z"), 300)),
        )
        assertEquals(mapOf("x" to 900L, "y" to 100L, "z" to 300L), combined.associate { it.album.id to it.lastPlayedAt })
    }
}
