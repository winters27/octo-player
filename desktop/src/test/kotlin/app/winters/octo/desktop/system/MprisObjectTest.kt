package app.winters.octo.desktop.system

import app.winters.octo.desktop.player.PlayerState
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.subsonic.Song
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.types.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The MPRIS object answers D-Bus calls from what the app last showed. It is
// tried here without a bus: the calls are plain methods.
class MprisObjectTest {
    private val heard = ArrayList<SystemEvent>()
    private var position = 12_000L
    private val mpris = MprisObject({ position }, { heard += it })
    private val song = Song("s1", "Title", artist = "Artist", duration = 200)
    private val now = nowPlayingOf(PlayerState(queue = listOf(QueueEntry(5, song)), current = QueueEntry(5, song), playing = true, durationMs = 200_000))!!

    @Test
    fun methodsBecomeEvents() {
        mpris.PlayPause()
        mpris.Next()
        mpris.Previous()
        mpris.Play()
        mpris.Pause()
        mpris.Stop()
        mpris.Raise()
        mpris.Quit()
        mpris.OpenUri("file:///music/a.flac")
        assertEquals(
            listOf(
                SystemEvent.Toggle, SystemEvent.Next, SystemEvent.Previous, SystemEvent.Play, SystemEvent.Pause,
                SystemEvent.Stop, SystemEvent.Raise, SystemEvent.Quit, SystemEvent.OpenUri("file:///music/a.flac"),
            ),
            heard,
        )
    }

    @Test
    fun seekingIsInMicroseconds() {
        mpris.Seek(-5_000_000)
        assertEquals(SystemEvent.SeekBy(-5_000), heard.single())
    }

    @Test
    fun setPositionOnlyAppliesToTheSongPlaying() {
        mpris.update(now)
        mpris.SetPosition(DBusPath("/app/winters/octo/track/e4"), 1_000_000)
        mpris.SetPosition(DBusPath(Mpris.trackPath(now)), 300_000_000)
        assertTrue("another track, or past the end, is ignored", heard.isEmpty())
        mpris.SetPosition(DBusPath(Mpris.trackPath(now)), 90_000_000)
        assertEquals(SystemEvent.SeekTo(90_000), heard.single())
    }

    @Test
    fun propertiesComeFromWhatWasShown() {
        mpris.update(now, "file:///cache/cover.img")
        val player = mpris.GetAll(Mpris.PLAYER)
        assertEquals("Playing", player["PlaybackStatus"]?.value)
        assertEquals("the position is read when asked", 12_000_000L, player["Position"]?.value)
        @Suppress("UNCHECKED_CAST")
        val meta = player["Metadata"]?.value as Map<String, Variant<*>>
        assertEquals("Title", meta["xesam:title"]?.value)
        assertEquals("file:///cache/cover.img", meta["mpris:artUrl"]?.value)
        position = 30_000
        assertEquals(30_000_000L, mpris.Get<Variant<*>>(Mpris.PLAYER, "Position").value)
        assertEquals("Octo", mpris.GetAll(Mpris.ROOT)["Identity"]?.value)
        assertNull(mpris.Get<Variant<*>?>(Mpris.PLAYER, "NoSuchThing"))
    }

    @Test
    fun theVolumeCanBeSet() {
        mpris.Set(Mpris.PLAYER, "Volume", 0.25)
        mpris.Set(Mpris.PLAYER, "Volume", Variant(3.0))
        mpris.Set(Mpris.PLAYER, "Rate", 2.0)
        assertEquals(listOf(SystemEvent.SetVolume(0.25f), SystemEvent.SetVolume(1f)), heard)
    }
}
