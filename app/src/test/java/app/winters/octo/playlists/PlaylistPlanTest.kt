package app.winters.octo.playlists

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.discovery.id
import app.winters.octo.discovery.resolveSongs
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.Song
import app.winters.octo.ui.playlist.phoneOnlyNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistPlanTest {
    private fun server(id: String, name: String = id, stamp: String = "t1|3") = ServerPlaylist(id, name, stamp)

    private fun linked(
        serverId: String,
        name: String = serverId,
        edited: Boolean = false,
        syncedName: String = name,
        syncedStamp: String = "t1|3",
    ) = LinkedPlaylist("local-$serverId", serverId, name, edited, syncedName, syncedStamp)

    private fun plan(
        server: List<ServerPlaylist> = emptyList(),
        linked: List<LinkedPlaylist> = emptyList(),
        others: Set<String> = emptySet(),
        waiting: List<String> = emptyList(),
        deleted: Set<String> = emptySet(),
    ) = planPlaylists(server, others, linked, waiting, deleted)

    // Planning

    @Test
    fun agreeingSidesNeedNothing() {
        assertEquals(emptyList<PlaylistStep>(), plan(listOf(server("a")), listOf(linked("a"))))
    }

    @Test
    fun aNewServerPlaylistIsImported() {
        assertEquals(listOf(PlaylistStep.Import("a", "t1|3")), plan(listOf(server("a"))))
    }

    @Test
    fun aServerChangeIsTakenWithItsName() {
        val steps = plan(listOf(server("a", name = "Renamed", stamp = "t2|4")), listOf(linked("a")))
        assertEquals(listOf(PlaylistStep.Pull("local-a", "a", "t2|4", keepName = false)), steps)
    }

    @Test
    fun aPhoneChangeIsSent() {
        val steps = plan(listOf(server("a")), listOf(linked("a", edited = true)))
        assertEquals(listOf(PlaylistStep.Push("local-a", "a")), steps)
    }

    @Test
    fun whenBothChangedTheServerWinsForSongs() {
        // Songs edited on both sides, the name left alone here: the server's
        // songs and name are taken.
        val steps = plan(listOf(server("a", name = "Server name", stamp = "t2|5")), listOf(linked("a", edited = true)))
        assertEquals(listOf(PlaylistStep.Pull("local-a", "a", "t2|5", keepName = false)), steps)
    }

    @Test
    fun whenBothChangedANameChangedHereWins() {
        val here = linked("a", name = "Phone name", edited = true, syncedName = "a")
        val steps = plan(listOf(server("a", name = "Server name", stamp = "t2|5")), listOf(here))
        assertEquals(listOf(PlaylistStep.Pull("local-a", "a", "t2|5", keepName = true)), steps)
    }

    @Test
    fun theSameNewNameOnBothSidesNeedsNoRename() {
        val here = linked("a", name = "Same", edited = true, syncedName = "a")
        val steps = plan(listOf(server("a", name = "Same", stamp = "t2|5")), listOf(here))
        assertEquals(listOf(PlaylistStep.Pull("local-a", "a", "t2|5", keepName = false)), steps)
    }

    @Test
    fun aSongCountChangeAloneCountsAsAChange() {
        val steps = plan(listOf(server("a", stamp = "t1|4")), listOf(linked("a")))
        assertEquals(listOf(PlaylistStep.Pull("local-a", "a", "t1|4", keepName = false)), steps)
    }

    @Test
    fun deletedOnTheServerGoesFromThePhone() {
        assertEquals(listOf(PlaylistStep.Remove("local-a")), plan(linked = listOf(linked("a"))))
    }

    @Test
    fun deletedOnTheServerButEditedHereStaysOnThePhone() {
        assertEquals(listOf(PlaylistStep.Unlink("local-a")), plan(linked = listOf(linked("a", edited = true))))
    }

    @Test
    fun aPlaylistNoLongerKeptInStepStaysOnThePhone() {
        // Still on the server, but now read-only (or a station): never deleted here.
        assertEquals(listOf(PlaylistStep.Unlink("local-a")), plan(linked = listOf(linked("a")), others = setOf("a")))
    }

    @Test
    fun deletedHereIsDeletedThereAndNotImportedBack() {
        val steps = plan(listOf(server("a"), server("b")), deleted = setOf("a", "gone"))
        assertEquals(
            setOf(PlaylistStep.DeleteOnServer("a"), PlaylistStep.ForgetDeletion("gone"), PlaylistStep.Import("b", "t1|3")),
            steps.toSet(),
        )
    }

    @Test
    fun playlistsWaitingForTheServerAreMade() {
        assertEquals(listOf(PlaylistStep.Create("p1")), plan(waiting = listOf("p1")))
    }

    @Test
    fun aPushThatFindsTheServerChangedSettlesLikeASync() {
        val here = linked("a", edited = true)
        assertEquals(PlaylistStep.Push("local-a", "a"), planLinked(server("a"), here))
        assertEquals(PlaylistStep.Pull("local-a", "a", "t9|1", keepName = false), planLinked(server("a", stamp = "t9|1"), here))
        assertEquals(PlaylistStep.Unlink("local-a"), planLinked(null, here))
        assertNull(planLinked(server("a"), linked("a")))
    }

    // Which server playlists come over

    private fun listed(id: String, owner: String? = "winters", readonly: Boolean = false) =
        Playlist(id = id, name = id, owner = owner, readonly = readonly)

    @Test
    fun readOnlyStationsAndOtherPeoplesPlaylistsAreSkipped() {
        val all = listOf(
            listed("mine"),
            listed("or7", readonly = true),
            listed("orStation"),
            listed("smart", readonly = true),
            listed("theirs", owner = "someone"),
            listed("noOwner", owner = null),
            listed("orchestra"),
        )
        val kept = importable(all, stationIds = setOf("orStation"), username = "Winters").map { it.id }
        assertEquals(listOf("mine", "noOwner", "orchestra"), kept)
    }

    @Test
    fun onlyUnmarkedPlaylistsThatLookLikeStationsAreSuspects() {
        val all = listOf(listed("or1", readonly = true), listed("or2"), listed("abc"))
        assertEquals(listOf("or2"), stationSuspects(all).map { it.id })
    }

    @Test
    fun anUnknownUserOwnsEverything() {
        assertEquals(1, importable(listOf(listed("x", owner = "someone")), emptySet(), username = "").size)
    }

    @Test
    fun theStampFollowsTimeAndCount() {
        assertEquals("2026-09-26T10:00:00Z|12", stampOf("2026-09-26T10:00:00Z", 12))
        assertEquals("|0", stampOf(null, 0))
    }

    // Entries

    private fun track(id: String, title: String, artist: String) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = artist, artistId = "a", album = "Album", albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = 200_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    private fun song(id: String, title: String, artist: String) = Song(id = id, title = title, artist = artist, duration = 200)

    @Test
    fun serverEntriesBecomeLibrarySongsOrFindsInOrderWithRepeats() {
        val songs = listOf(
            song("s1", "One More Time", "Daft Punk"),
            song("e1", "Genesis", "Justice"),
            song("s1", "One More Time", "Daft Punk"),
            song("s2", "Digital Love", "Daft Punk"),
        )
        val phone = track("p2", "Digital Love", "Daft Punk")
        val ids = resolveSongs(songs, "server:x", mapOf("s1" to "lib1"), listOf(phone), 0).map { it.id }
        val rows = serverRows(songs.map { it.id }, ids)
        assertEquals(
            listOf(Row("lib1", "s1"), Row("find:e1", "e1"), Row("lib1", "s1"), Row("p2", "s2")),
            rows,
        )
    }

    @Test
    fun eachRowGoesBackAsTheRightServerSong() {
        // The copy it came from, while the song still has it.
        assertEquals("s2", serverSongFor(Row("lib", "s2"), listOf("s1", "s2")))
        // Otherwise the song's copy, the same one every time.
        assertEquals("s1", serverSongFor(Row("lib"), listOf("s3", "s1")))
        assertEquals("s1", serverSongFor(Row("lib", "gone"), listOf("s1")))
        // A find is a server song itself.
        assertEquals("e1", serverSongFor(Row("find:e1"), emptyList()))
        // A phone song that came from the server goes back as that song.
        assertEquals("s9", serverSongFor(Row("p9", "s9"), emptyList()))
        // Only on the phone: left out of the server's copy.
        assertNull(serverSongFor(Row("p1"), emptyList()))
    }

    @Test
    fun songsOnlyOnThePhoneKeepTheirPlaceAfterAPull() {
        val phoneOnly = setOf("p1", "p2", "p3")
        val local = listOf(Row("p1"), Row("a"), Row("p2"), Row("b"), Row("c"), Row("p3"))
        val server = listOf(Row("c", "sc"), Row("a", "sa"), Row("d", "sd"))
        val merged = withPhoneOnly(server, local) { it.trackId in phoneOnly }
        assertEquals(listOf("p1", "c", "p3", "a", "p2", "d"), merged.map { it.trackId })
    }

    @Test
    fun aPhoneSongAfterOneTheServerDroppedFollowsTheOneBefore() {
        val local = listOf(Row("a"), Row("b"), Row("p1"))
        val server = listOf(Row("a", "sa"))
        val merged = withPhoneOnly(server, local) { it.trackId == "p1" }
        assertEquals(listOf("a", "p1"), merged.map { it.trackId })
    }

    @Test
    fun withNothingOnlyOnThePhoneTheServersListIsTakenAsIs() {
        val server = listOf(Row("b", "sb"), Row("a", "sa"), Row("b", "sb"))
        assertTrue(withPhoneOnly(server, listOf(Row("a"), Row("b"))) { false } == server)
    }

    @Test
    fun thePhoneOnlyLineCountsSongs() {
        assertEquals("1 song is only on this phone and is not in the server's copy", phoneOnlyNote(1))
        assertEquals("3 songs are only on this phone and are not in the server's copy", phoneOnlyNote(3))
    }
}
