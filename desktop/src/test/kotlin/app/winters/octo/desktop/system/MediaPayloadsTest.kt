package app.winters.octo.desktop.system

import app.winters.octo.desktop.player.PlayerState
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.desktop.player.RepeatMode
import app.winters.octo.subsonic.ArtistRef
import app.winters.octo.subsonic.Song
import org.freedesktop.dbus.DBusPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The song as each system's player is given it, from the player's state.
class MediaPayloadsTest {
    private val song = Song(
        id = "s1",
        title = "Weightless",
        album = "Ambient Works",
        artist = "Marconi",
        displayArtist = "Marconi Union",
        displayAlbumArtist = "Various",
        track = 3,
        discNumber = 1,
        duration = 480,
        coverArt = "al-9",
        genres = listOf("Ambient", "Electronic"),
    )
    private val entry = QueueEntry(41, song)
    private val later = QueueEntry(42, Song("s2", "Next one", duration = 100))

    private fun state(playing: Boolean = true, upcoming: List<QueueEntry> = listOf(later), repeat: RepeatMode = RepeatMode.Off) =
        PlayerState(queue = listOf(entry) + upcoming, current = entry, upcoming = upcoming, playing = playing, repeat = repeat, durationMs = 480_000)

    @Test
    fun theSongIsTakenFromTheCurrentEntry() {
        val now = nowPlayingOf(state())!!
        assertEquals(41L, now.entryKey)
        assertEquals("Weightless", now.title)
        assertEquals("the credited name wins over the plain one", "Marconi Union", now.artist)
        assertEquals("Ambient Works", now.album)
        assertEquals("Various", now.albumArtist)
        assertEquals(480_000L, now.durationMs)
        assertEquals("al-9", now.coverId)
        assertTrue(now.playing)
        assertTrue(now.canNext)
        assertTrue("previous always starts over or goes back", now.canPrevious)
    }

    @Test
    fun nothingPlayingIsNothingToShow() {
        assertNull(nowPlayingOf(PlayerState()))
    }

    @Test
    fun nextWorksAtTheEndOnlyWhenTheQueueRepeats() {
        assertFalse(nowPlayingOf(state(upcoming = emptyList()))!!.canNext)
        assertTrue(nowPlayingOf(state(upcoming = emptyList(), repeat = RepeatMode.All))!!.canNext)
    }

    @Test
    fun albumArtistFallsBackToTheCreditedList() {
        val plain = song.copy(displayAlbumArtist = null, albumArtists = listOf(ArtistRef("a1", "One"), ArtistRef("a2", "Two")))
        val now = nowPlayingOf(PlayerState(current = QueueEntry(1, plain), queue = listOf(QueueEntry(1, plain))))!!
        assertEquals("One, Two", now.albumArtist)
    }

    @Test
    fun anOpenedFileWithNoTitleIsNamedAfterItsFile() {
        val file = Song(id = openedFileId("file:///C:/Music/Some%20Song.flac"), title = "")
        val now = nowPlayingOf(PlayerState(current = QueueEntry(1, file), queue = listOf(QueueEntry(1, file))))!!
        assertEquals("Some Song", now.title)
    }

    // Windows (SMTC) and macOS (Now Playing) get the same fields from the
    // library's calls; the library turns them into ticks or seconds.
    @Test
    fun smtcAndNowPlayingGetTheSongAndItsState() {
        val now = nowPlayingOf(state())!!
        assertEquals(NativeTrack("Weightless", "Marconi Union", "Ambient Works", "Various", 480_000), nativeTrackOf(now))
        assertEquals(NativePlayback(NativeStatus.Playing, 61_000, canPrevious = true, canNext = true, rate = 1.0), nativePlaybackOf(now, 61_000))
        val paused = nowPlayingOf(state(playing = false))!!
        assertEquals(NativeStatus.Paused, nativePlaybackOf(paused, 0).status)
        assertEquals(NativeStatus.Stopped, nativePlaybackOf(null, 0).status)
    }

    @Test
    fun theTimeMovesOnAtTheSongsSpeedAndStandsStillWhileWaiting() {
        assertEquals(1.5, nativePlaybackOf(nowPlayingOf(state().copy(speed = 1.5f)), 0).rate, 0.0)
        assertEquals("paused", 0.0, nativePlaybackOf(nowPlayingOf(state(playing = false).copy(speed = 1.5f)), 0).rate, 0.0)
        assertEquals("waiting for sound", 0.0, nativePlaybackOf(nowPlayingOf(state().copy(buffering = true)), 0).rate, 0.0)
        assertEquals("nothing playing", 0.0, nativePlaybackOf(null, 0).rate, 0.0)
        assertEquals("a speed that makes no sense is taken as 1", 1f, nowPlayingOf(state().copy(speed = Float.NaN))!!.speed)
    }

    @Test
    fun mprisShowsTheSpeedShuffleAndRepeat() {
        val fast = Mpris.playerProperties(nowPlayingOf(state().copy(speed = 1.5f)), 1.0, shuffle = true, repeat = RepeatMode.All)
        assertEquals(1.5, fast["Rate"])
        assertEquals(true, fast["Shuffle"])
        assertEquals("Playlist", fast["LoopStatus"])
        assertTrue(fast["MinimumRate"] as Double <= 1.0 && fast["MaximumRate"] as Double >= 1.5)
        assertEquals("paused keeps its rate; the status says it is paused", 1.5, Mpris.playerProperties(nowPlayingOf(state(playing = false).copy(speed = 1.5f)), 1.0)["Rate"])
        assertEquals(listOf("None", "Playlist", "Track"), RepeatMode.entries.map(Mpris::loopStatus))
        RepeatMode.entries.forEach { assertEquals(it, Mpris.repeatOf(Mpris.loopStatus(it))) }
        assertNull(Mpris.repeatOf("Sometimes"))
    }

    @Test
    fun positionsStayInsideTheSong() {
        val now = nowPlayingOf(state())!!
        assertEquals(480_000L, nativePlaybackOf(now, 999_999).positionMs)
        assertEquals(0L, nativePlaybackOf(now, -5).positionMs)
        assertEquals(90_000L, clampPosition(90_000, 0))
    }

    @Test
    fun statusNumbersMatchTheLibrary() {
        assertEquals(listOf(1, 2, 3), listOf(NativeStatus.Stopped, NativeStatus.Playing, NativeStatus.Paused).map { it.code })
    }

    @Test
    fun mprisMetadataFollowsTheSpecification() {
        val now = nowPlayingOf(state())!!
        val meta = Mpris.metadata(now, "file:///home/b/.cache/octo/now-playing/abc.img")
        assertEquals(ObjectPath("/app/winters/octo/track/e41"), meta["mpris:trackid"])
        assertEquals("microseconds", 480_000_000L, meta["mpris:length"])
        assertEquals("Weightless", meta["xesam:title"])
        assertEquals(listOf("Marconi Union"), meta["xesam:artist"])
        assertEquals("Ambient Works", meta["xesam:album"])
        assertEquals(listOf("Various"), meta["xesam:albumArtist"])
        assertEquals(3, meta["xesam:trackNumber"])
        assertEquals(1, meta["xesam:discNumber"])
        assertEquals(listOf("Ambient", "Electronic"), meta["xesam:genre"])
        assertEquals("file:///home/b/.cache/octo/now-playing/abc.img", meta["mpris:artUrl"])
    }

    @Test
    fun mprisLeavesOutWhatIsNotKnown() {
        val bare = Song("s9", "Only a title")
        val now = nowPlayingOf(PlayerState(current = QueueEntry(7, bare), queue = listOf(QueueEntry(7, bare))))!!
        val meta = Mpris.metadata(now, null)
        assertEquals(setOf("mpris:trackid", "xesam:title"), meta.keys)
        assertEquals(mapOf("mpris:trackid" to ObjectPath(Mpris.NO_TRACK)), Mpris.metadata(null, null))
    }

    @Test
    fun mprisTrackIdsAreValidObjectPaths() {
        val path = Mpris.trackPath(nowPlayingOf(state())!!)
        assertTrue(Regex("^(/[A-Za-z0-9_]+)+$").matches(path))
        assertFalse(path.startsWith("/org/mpris"))
    }

    @Test
    fun mprisPlayerPropertiesFollowThePlayer() {
        val playing = Mpris.playerProperties(nowPlayingOf(state()), 0.4)
        assertEquals("Playing", playing["PlaybackStatus"])
        assertEquals(true, playing["CanGoNext"])
        assertEquals(true, playing["CanSeek"])
        assertEquals(0.4, playing["Volume"])
        val empty = Mpris.playerProperties(null, 2.0)
        assertEquals("Stopped", empty["PlaybackStatus"])
        assertEquals(false, empty["CanPlay"])
        assertEquals("volume is kept between 0 and 1", 1.0, empty["Volume"])
        assertEquals("Paused", Mpris.status(nowPlayingOf(state(playing = false))))
    }

    @Test
    fun valuesBecomeVariantsOfTheRightDBusType() {
        assertEquals("s", variantOf("x").sig)
        assertEquals("b", variantOf(true).sig)
        assertEquals("d", variantOf(0.5).sig)
        assertEquals("x", variantOf(5L).sig)
        assertEquals("i", variantOf(5).sig)
        assertEquals("as", variantOf(listOf("a", "b")).sig)
        assertEquals("o", variantOf(ObjectPath("/a/b")).sig)
        assertEquals(DBusPath("/a/b"), variantOf(ObjectPath("/a/b")).value)
        val meta = variantOf(Mpris.metadata(nowPlayingOf(state()), null))
        assertEquals("a{sv}", meta.sig)
    }
}
